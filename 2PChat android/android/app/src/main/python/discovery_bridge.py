import asyncio
import sys
import threading
import json
import traceback
import uuid
import re
import time
import tempfile
import hashlib
import base64
import os
import socket
import struct
import urllib.parse
from pathlib import Path
from datetime import datetime, timezone

from messenger.core.discovery_manager import get_discovery_provider
from messenger.core.tracker_catalog import BASE_TRACKERS, TrackerSpec, get_tracker_by_name
from messenger.core.discovery_base import PeerEndpoint
from messenger.core.session import Session
from messenger.core.identity import load_or_create_identity, load_or_create_signing_identity, TrustStore, fingerprint
from messenger.core.transport_manager import listen as transport_listen, connect as transport_connect
from messenger.utils.logger import setup_logger

# Configure logging for the bridge
logger = setup_logger("discovery_bridge")

def print(*args, **kwargs):
    sep = kwargs.get('sep', ' ')
    msg = sep.join(str(arg) for arg in args)
    known_names = set(globals().get("peer_fingerprint_to_name", {}).values())
    local_name = globals().get("local_identity_nickname", "")
    if local_name:
        known_names.add(local_name)
    for name in sorted((str(value) for value in known_names if value), key=len, reverse=True):
        alias = hashlib.sha256(name.encode("utf-8")).hexdigest()[:8]
        msg = re.sub(rf"(?<!\w){re.escape(name)}(?!\w)", f"<peer:{alias}>", msg)
    logger.info(msg)

active_sessions = {}
peer_operation_locks = {}
peer_fingerprint_to_name = {}
incoming_files = {}
incoming_file_starts = {}
MOBILE_ACK_TIMEOUT = 3.0
MOBILE_MAX_RETRIES = 1
MAX_INCOMING_FILES = 16
MAX_INCOMING_FILES_PER_PEER = 4
MAX_INCOMING_FILE_BYTES = 100 * 1024 * 1024
MAX_INCOMING_FILE_CHUNKS = 2048
INCOMING_FILE_RATE_WINDOW_SECONDS = 60
MAX_INCOMING_FILE_STARTS_PER_WINDOW = 8
INCOMING_FILE_TTL_SECONDS = 120
MAX_ENCRYPTED_CHUNK_SIZE = 1024 * 1024
MAX_CONCURRENT_HANDSHAKES = 10
tracker_diagnostics = {}
public_address_observations = set()
local_identity_nickname = ""
local_identity_about_me = ""
local_identity_fingerprint = ""
local_yggdrasil_available = False
local_announced_ips = set()

rejected_fingerprints = {}

def is_fingerprint_rejected(peer_name: str = "", fingerprint: str = "") -> bool:
    now = time.time()
    for key in (fingerprint, (peer_name, fingerprint)):
        if not key:
            continue
        exp = rejected_fingerprints.get(key)
        if exp:
            if now < exp:
                return True
            else:
                rejected_fingerprints.pop(key, None)
    return False

def record_rejected_fingerprint(peer_name: str, fingerprint: str, cooldown_seconds: float = 300.0):
    now = time.time()
    if fingerprint:
        rejected_fingerprints[fingerprint] = now + cooldown_seconds
    if peer_name and fingerprint:
        rejected_fingerprints[(peer_name, fingerprint)] = now + cooldown_seconds

def clear_rejected_fingerprint(peer_name: str = "", fingerprint: str = ""):
    if fingerprint:
        rejected_fingerprints.pop(fingerprint, None)
    if peer_name:
        for k in list(rejected_fingerprints.keys()):
            if k == peer_name or (isinstance(k, tuple) and k[0] == peer_name):
                rejected_fingerprints.pop(k, None)

# Kotlin notification callbacks
message_listener_callback = None
session_listener_callback = None
loop = None
_listener_thread = None
_listener_task = None
_listener_stopped = None
_runtime_shutdown_requested = False
_runtime_lock = threading.RLock()

def _setup_socket_keepalive(writer) -> None:
    try:
        sock = writer.get_extra_info('socket')
        if sock is not None:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            if hasattr(socket, "TCP_KEEPIDLE"):
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 30)
            if hasattr(socket, "TCP_KEEPINTVL"):
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 10)
            if hasattr(socket, "TCP_KEEPCNT"):
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 3)
    except Exception as e:
        print(f"Failed to setup socket keep-alive: {e}")

# Track which Yggdrasil listener is running
_ygg_listener_running = False
listener_port = 50001
ipv4_enabled = True
CLEARNET_TRACKERS = (
    "OpenTrackr HTTP",
    "Dler HTTP",
    "Qu.Ax HTTP",
    "Yemekyedim HTTPS",
    "Nyacat HTTPS",
    "Torrent.eu.org UDP",
    "Open Stealth UDP",
    "Exodus UDP",
)
YGG_TRACKERS = (
    "Yggdrasil-only HTTP",
    "Yggdrasil-only UDP",
)
MAINLINE_DHT = "Mainline DHT (BEP 5)"
TRACKER_PROTOCOLS = frozenset({"http", "https", "udp"})
MAX_CUSTOM_TRACKERS = 32
_tracker_config_lock = threading.RLock()
_enabled_tracker_protocols = set(TRACKER_PROTOCOLS)
_disabled_builtin_trackers = set()
_custom_trackers = {}
_dht_enabled = True
_announce_enabled = True


def _tracker_spec_from_custom(item):
    if not isinstance(item, dict):
        raise ValueError("custom tracker entry must be an object")
    tracker_id = str(item.get("id", "")).strip()
    name = str(item.get("name", "")).strip()
    url = str(item.get("url", "")).strip()
    enabled = item.get("enabled", True)
    if not tracker_id or len(tracker_id) > 80 or not re.fullmatch(r"[A-Za-z0-9_-]+", tracker_id):
        raise ValueError("custom tracker id is invalid")
    if not name or len(name) > 60 or any(ord(char) < 32 for char in name):
        raise ValueError("custom tracker name is invalid")
    if not isinstance(enabled, bool):
        raise ValueError("custom tracker enabled flag must be boolean")

    parsed = urllib.parse.urlparse(url)
    protocol = parsed.scheme.lower()
    if protocol not in TRACKER_PROTOCOLS or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("custom tracker must use http, https, or udp with a valid host")
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError("custom tracker port is invalid") from exc
    if protocol == "udp" and port is None:
        raise ValueError("custom UDP tracker must specify a port")
    discovery_scheme = "udp-tracker" if protocol == "udp" else "http-tracker"
    key = f"custom:{tracker_id}"
    return key, TrackerSpec(
        name=f"Custom: {name}",
        announce_url=url,
        discovery_scheme=discovery_scheme,
        protocol=protocol,
        notes="user supplied",
    ), enabled


def configure_trackers(config_json: str) -> bool:
    """Atomically apply validated tracker settings supplied by the Android UI."""
    try:
        config = json.loads(config_json)
        if not isinstance(config, dict):
            raise ValueError("tracker configuration must be an object")
        protocols = config.get("enabled_protocols", list(TRACKER_PROTOCOLS))
        if not isinstance(protocols, list):
            raise ValueError("enabled_protocols must be a list")
        protocol_set = {str(value).lower() for value in protocols}
        if not protocol_set.issubset(TRACKER_PROTOCOLS):
            raise ValueError("unsupported tracker protocol")
        disabled = config.get("disabled_builtin_trackers", [])
        if not isinstance(disabled, list):
            raise ValueError("disabled_builtin_trackers must be a list")
        builtin_names = {spec.name for spec in BASE_TRACKERS}
        disabled_set = {str(value) for value in disabled}
        if not disabled_set.issubset(builtin_names):
            raise ValueError("unknown disabled built-in tracker")
        custom_items = config.get("custom_trackers", [])
        if not isinstance(custom_items, list) or len(custom_items) > MAX_CUSTOM_TRACKERS:
            raise ValueError("too many custom trackers")
        custom = {}
        for item in custom_items:
            key, spec, enabled = _tracker_spec_from_custom(item)
            if key in custom:
                raise ValueError("duplicate custom tracker id")
            custom[key] = (spec, enabled)
        dht_enabled = config.get("dht_enabled", True)
        announce_enabled = config.get("announce_enabled", True)
        if not isinstance(dht_enabled, bool) or not isinstance(announce_enabled, bool):
            raise ValueError("DHT and announce flags must be boolean")
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        print(f"Rejected tracker configuration: {exc}")
        return False

    global _enabled_tracker_protocols, _disabled_builtin_trackers
    global _custom_trackers, _dht_enabled, _announce_enabled
    with _tracker_config_lock:
        _enabled_tracker_protocols = protocol_set
        _disabled_builtin_trackers = disabled_set
        _custom_trackers = custom
        _dht_enabled = dht_enabled
        _announce_enabled = announce_enabled
    return True


def _configured_tracker(name: str):
    with _tracker_config_lock:
        custom = _custom_trackers.get(name)
    if custom is not None:
        return custom[0]
    return get_tracker_by_name(name)


def _filter_enabled_trackers(names):
    result = []
    with _tracker_config_lock:
        protocols = set(_enabled_tracker_protocols)
        disabled = set(_disabled_builtin_trackers)
        custom = dict(_custom_trackers)
    for name in names:
        if not name:
            continue
        if name in result or name in disabled:
            continue
        custom_item = custom.get(name)
        if custom_item is not None:
            spec, enabled = custom_item
            if not enabled:
                continue
        else:
            try:
                spec = get_tracker_by_name(name)
            except ValueError:
                # Preserve testability for injected tracker presets.
                spec = _configured_tracker(name)
        protocol = getattr(spec, "protocol", None)
        if protocol is None:
            protocol = urllib.parse.urlparse(spec.announce_url).scheme.lower()
        if protocol in protocols:
            result.append(name)
    return result


def _session_for_peer(peer_name: str, expected_fingerprint: str | None = None):
    """Resolve a live session through authenticated identity, not just a name key."""
    if expected_fingerprint:
        session = active_sessions.get(expected_fingerprint)
        if session and getattr(session, "is_online", False):
            return session

    matches = [
        active_sessions.get(fp)
        for fp, name in peer_fingerprint_to_name.items()
        if name == peer_name and active_sessions.get(fp) is not None
    ]
    online_matches = [s for s in matches if s and getattr(s, "is_online", False)]
    if len(online_matches) == 1:
        return online_matches[0]

    online_sessions = [s for s in active_sessions.values() if s and getattr(s, "is_online", False)]
    for s in online_sessions:
        if getattr(s, "peer_label", None) == peer_name:
            return s

    if len(online_sessions) == 1:
        return online_sessions[0]
    return None


async def _register_authenticated_session(session, peer_fp: str, *, initiator: bool):
    """Choose one stable connection when both peers dial at the same time.

    The peer with the lexicographically smaller fingerprint owns the outgoing
    side. Both devices therefore select opposite ends of the same TCP stream.
    """
    local_fp = local_identity_fingerprint
    if not local_fp:
        local_fp = fingerprint(load_or_create_identity().public_key)
    session._bridge_initiator = initiator
    existing = active_sessions.get(peer_fp)
    if not existing or not getattr(existing, "is_online", False):
        active_sessions[peer_fp] = session
        return session

    prefer_initiator = local_fp < peer_fp
    existing_preferred = getattr(existing, "_bridge_initiator", False) == prefer_initiator
    new_preferred = initiator == prefer_initiator
    if existing_preferred or not new_preferred:
        await session.close()
        return existing

    active_sessions[peer_fp] = session
    await existing.close()
    return session


def _format_endpoint(host: str, port: int) -> str:
    if ":" in host:
        return f"[{host}]:{port}"
    return f"{host}:{port}"


def _transport_for_endpoint(endpoint: str) -> str:
    host = endpoint.rsplit(":", 1)[0].strip("[]") if endpoint else ""
    return "Yggdrasil" if ":" in host else "Direct P2P"


def _is_ipv4_endpoint(endpoint: str) -> bool:
    host = endpoint.rsplit(":", 1)[0].strip("[]") if endpoint else ""
    return bool(host) and ":" not in host


def _prune_incoming_files(now: float | None = None):
    cutoff = (now if now is not None else time.monotonic()) - INCOMING_FILE_TTL_SECONDS
    for file_id, state in list(incoming_files.items()):
        if state.get("updated_at", 0) < cutoff:
            _discard_incoming_file(file_id)


def _allow_incoming_file_start(peer_fingerprint: str, now: float | None = None) -> bool:
    current = now if now is not None else time.monotonic()
    cutoff = current - INCOMING_FILE_RATE_WINDOW_SECONDS
    for peer, peer_starts in list(incoming_file_starts.items()):
        recent = [stamp for stamp in peer_starts if stamp >= cutoff]
        if recent:
            incoming_file_starts[peer] = recent
        else:
            incoming_file_starts.pop(peer, None)
    starts = [stamp for stamp in incoming_file_starts.get(peer_fingerprint, ()) if stamp >= cutoff]
    if len(starts) >= MAX_INCOMING_FILE_STARTS_PER_WINDOW:
        incoming_file_starts[peer_fingerprint] = starts
        return False
    starts.append(current)
    incoming_file_starts[peer_fingerprint] = starts
    return True


def _discard_incoming_file(key):
    state = incoming_files.pop(key, None)
    if not state:
        return
    handle = state.get("handle")
    if handle:
        try:
            handle.close()
        except OSError:
            pass
    path = state.get("temp_path")
    if path:
        try:
            Path(path).unlink(missing_ok=True)
        except OSError:
            pass


def set_ipv4_enabled(enabled: bool):
    """Apply the user's IPv4 policy to new and currently active sessions."""
    global ipv4_enabled
    ipv4_enabled = bool(enabled)
    print(f"IPv4 transport {'enabled' if ipv4_enabled else 'disabled'}")
    if ipv4_enabled or not loop or not loop.is_running():
        return

    async def _close_ipv4_sessions():
        seen = set()
        for session in list(active_sessions.values()):
            if id(session) in seen:
                continue
            seen.add(id(session))
            peername = session.writer.get_extra_info("peername") if hasattr(session, "writer") else None
            if peername and ":" not in str(peername[0]):
                await session.close()

    asyncio.run_coroutine_threadsafe(_close_ipv4_sessions(), loop)


def _endpoint_sort_key(endpoint_str: str) -> tuple[int, str]:
    # Prefer Yggdrasil IPv6 first (starts with "[") for reliability over NAT/firewalls, then fallback to IPv4.
    return (0 if endpoint_str.startswith("[") else 1, endpoint_str)


def _parse_endpoint_hosts(addresses, port: int):
    endpoints = []
    seen = set()
    for host in addresses:
        clean_host = str(host).strip()
        if not clean_host:
            continue
        key = (clean_host, port)
        if key in seen:
            continue
        seen.add(key)
        endpoints.append(PeerEndpoint(host=clean_host, port=port))
    return endpoints


def _has_ipv6_endpoint(endpoints: list[PeerEndpoint]) -> bool:
    return any(":" in endpoint.host for endpoint in endpoints)


def _resolve_tracker_names(primary_tracker: str | None = None) -> list[str]:
    with _tracker_config_lock:
        custom_names = tuple(_custom_trackers)
    return _filter_enabled_trackers(
        (primary_tracker, *CLEARNET_TRACKERS, *YGG_TRACKERS, *custom_names)
    )


def _announce_tracker_names(endpoints: list[PeerEndpoint]) -> list[str]:
    names = list(CLEARNET_TRACKERS)
    if _has_ipv6_endpoint(endpoints):
        names.extend(YGG_TRACKERS)
    with _tracker_config_lock:
        names.extend(_custom_trackers)
    return _filter_enabled_trackers(names)


def _set_tracker_diagnostic(tracker_name: str, operation: str, status: str) -> None:
    tracker_diagnostics.setdefault(tracker_name, {})[operation] = status


def _record_public_addresses(provider) -> None:
    for address in getattr(provider, "observed_addresses", ()):
        candidate = str(address).strip()
        if candidate and candidate not in public_address_observations:
            public_address_observations.add(candidate)
            print(f"Discovery observed our public address as {candidate}")


def _discover_public_ipv4_stun(timeout: float = 2.5) -> str | None:
    """Return the NAT-mapped IPv4 address from an RFC 5389 binding response."""
    cookie = 0x2112A442
    transaction_id = os.urandom(12)
    request = struct.pack(">HHI12s", 0x0001, 0, cookie, transaction_id)
    servers = (
        ("stun.cloudflare.com", 3478),
        ("stun.l.google.com", 19302),
    )
    for host, port in servers:
        try:
            addresses = socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_DGRAM)
        except OSError:
            continue
        for _family, _kind, _proto, _name, target in addresses:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(timeout)
            try:
                sock.sendto(request, target)
                response, _source = sock.recvfrom(2048)
                if len(response) < 20 or response[8:20] != transaction_id:
                    continue
                message_length = struct.unpack(">H", response[2:4])[0]
                offset = 20
                limit = min(len(response), 20 + message_length)
                while offset + 4 <= limit:
                    attr_type, attr_length = struct.unpack(">HH", response[offset : offset + 4])
                    value = response[offset + 4 : offset + 4 + attr_length]
                    offset += 4 + ((attr_length + 3) & ~3)
                    if attr_type not in {0x0001, 0x0020} or len(value) < 8 or value[1] != 0x01:
                        continue
                    packed_ip = value[4:8]
                    if attr_type == 0x0020:
                        mask = struct.pack(">I", cookie)
                        packed_ip = bytes(left ^ right for left, right in zip(packed_ip, mask))
                    return socket.inet_ntop(socket.AF_INET, packed_ip)
            except (OSError, struct.error):
                continue
            finally:
                sock.close()
    return None


def _same_nickname(left: str, right: str) -> bool:
    """Match display names without making case or repeated spaces significant."""
    return " ".join(left.strip().casefold().split()) == " ".join(right.strip().casefold().split())


def configure_local_identity(nickname: str, claimed_fingerprint: str = "", about_me: str = "") -> bool:
    """Set application identity independently from tracker availability."""
    global local_identity_nickname, local_identity_about_me, local_identity_fingerprint
    local_name = str(nickname or "").strip()
    actual_fingerprint = fingerprint(load_or_create_identity().public_key)
    if not local_name:
        return False
    if claimed_fingerprint and str(claimed_fingerprint).strip() != actual_fingerprint:
        raise ValueError("configured local fingerprint does not match the identity key")
    local_identity_nickname = local_name
    local_identity_about_me = str(about_me or "").strip()
    local_identity_fingerprint = actual_fingerprint
    return True


async def _send_local_identity_info(session) -> bool:
    """Send the authenticated local name before application traffic."""
    if not local_identity_nickname:
        return False
    actual_fingerprint = fingerprint(load_or_create_identity().public_key)
    if local_identity_fingerprint and local_identity_fingerprint != actual_fingerprint:
        print("Ignoring stale configured local fingerprint")
    await session.send_reliable({
        "type": "identity_info",
        "nickname": local_identity_nickname,
        "about_me": local_identity_about_me,
        "fingerprint": actual_fingerprint,
        "listen_port": listener_port,
    })
    print(f"Sent authenticated identity_info as '{local_identity_nickname}'")
    return True


def _canonical_expected_fingerprint(value: str | None) -> str | None:
    if value is None or not str(value).strip():
        return None
    candidate = str(value).strip()
    try:
        decoded = base64.b64decode(candidate, validate=True)
    except Exception as exc:
        raise ValueError("invite fingerprint is not valid Base64") from exc
    if len(decoded) != 32:
        raise ValueError("invite fingerprint must encode exactly 32 bytes")
    canonical = base64.b64encode(decoded).decode("ascii")
    if candidate != canonical:
        raise ValueError("invite fingerprint is not in canonical Base64 form")
    return canonical


def _parse_numeric_endpoint(endpoint: str) -> tuple[str, int] | None:
    """Accept only numeric IPv4 or IPv6 endpoints supplied by local discovery."""
    ipv6_match = re.fullmatch(r"\[([0-9a-fA-F:]+)\]:(\d{1,5})", endpoint)
    if ipv6_match:
        host, raw_port = ipv6_match.groups()
        family = socket.AF_INET6
    else:
        ipv4_match = re.fullmatch(r"([0-9.]+):(\d{1,5})", endpoint)
        if not ipv4_match:
            return None
        host, raw_port = ipv4_match.groups()
        family = socket.AF_INET
    port = int(raw_port)
    if port not in range(1, 65536):
        return None
    try:
        socket.inet_pton(family, host)
    except OSError:
        return None
    return host, port


async def _verify_live_endpoint(
    endpoint: str,
    nickname: str,
    expected_fingerprint: str | None = None,
) -> dict:
    """Require an authenticated live peer to confirm the name before showing it.

    Tracker announces are soft state and can outlive a disconnected phone.  A
    successful TCP connection alone is also insufficient: it might be a stale
    address now owned by a different service.  The lightweight probe therefore
    completes the encrypted 2PChat handshake and asks the peer to return its
    identity information.
    """
    identity_priv = load_or_create_identity()
    signing_key = load_or_create_signing_identity()
    session = None
    try:
        # Deliberately do not update the TOFU store while merely searching.
        session = await _dial_endpoint(
            endpoint, identity_priv, signing_key, None, expected_fingerprint
        )
        await session.send_reliable({"type": "identity_probe"})
        while True:
            message = await asyncio.wait_for(session.receive_message(), timeout=7.0)
            if message.get("type") != "identity_info":
                continue
            announced_name = str(message.get("nickname", ""))
            announced_fp = str(message.get("fingerprint", ""))
            # The name is accepted only when it was sent by the authenticated
            # session identity, not by an arbitrary payload claiming another key.
            if (
                announced_fp == session.peer_fingerprint
                and _same_nickname(announced_name, nickname)
            ):
                return {
                    "nickname": announced_name.strip(),
                    "fingerprint": session.peer_fingerprint,
                    "endpoint": endpoint,
                    "verified": True,
                    "ownership_verified": expected_fingerprint is not None,
                    "verification_reason": "authenticated live response",
                }
            print(
                "Live identity mismatch for "
                f"{endpoint}: requested={nickname!r}, announced={announced_name!r}, "
                f"payload_fp={announced_fp!r}, session_fp={session.peer_fingerprint!r}"
            )
            return {
                "endpoint": endpoint,
                "verified": False,
                "verification_reason": "live identity did not match the requested name",
            }
    except Exception as exc:
        print(f"Live peer verification failed for {endpoint}: {exc!r}")
        reason = str(exc).strip() or f"{type(exc).__name__} (no detail)"
        return {
            "endpoint": endpoint,
            "verified": False,
            "verification_reason": reason,
            "is_self": "refusing self connection" in reason.lower(),
        }
    finally:
        if session is not None:
            await session.close()


def verify_live_endpoints(
    endpoints_json: str,
    expected_live_name: str,
    expected_live_fingerprint: str | None = None,
):
    """Try local-network and connected Yggdrasil candidates before trackers.

    Yggdrasil's public trackers are useful for arbitrary remote peers, but a
    directly connected mesh neighbour is already a trustworthy route
    candidate.  The endpoint is still accepted only after the normal encrypted
    handshake proves that its authenticated identity advertises the requested
    name.  Return as soon as one candidate succeeds so dead public mesh peers
    cannot add their individual timeouts to local discovery latency.
    """
    expected_live_fingerprint = _canonical_expected_fingerprint(expected_live_fingerprint)
    try:
        decoded = json.loads(endpoints_json)
    except (TypeError, ValueError):
        return []
    if not isinstance(decoded, list):
        return []

    candidates = []
    seen = set()
    for value in decoded:
        endpoint = str(value).strip()
        if _parse_numeric_endpoint(endpoint) is None or endpoint in seen:
            continue
        seen.add(endpoint)
        candidates.append(endpoint)
        if len(candidates) >= 12:
            break
    if not candidates or not expected_live_name.strip():
        return []

    # Opening the first chat may already have established the one preferred
    # bidirectional session.  Dialling a second search probe is then correctly
    # rejected by duplicate-session arbitration, so reuse the authenticated
    # session when its peer address is one of these direct neighbours.
    for peer_fp, session in list(active_sessions.items()):
        if not session or not getattr(session, "is_online", False):
            continue
        known_name = peer_fingerprint_to_name.get(peer_fp) or getattr(session, "peer_label", "")
        if not known_name or not _same_nickname(str(known_name), expected_live_name):
            continue
        if expected_live_fingerprint is not None and peer_fp != expected_live_fingerprint:
            continue
        writer = getattr(session, "writer", None)
        peername = writer.get_extra_info("peername") if writer is not None else None
        peer_host = str(peername[0]).split("%", 1)[0] if peername else ""
        matching_endpoint = next(
            (
                endpoint for endpoint in candidates
                if _parse_numeric_endpoint(endpoint)[0] == peer_host
            ),
            None,
        )
        if matching_endpoint:
            return [{
                "nickname": str(known_name),
                "fingerprint": peer_fp,
                "transport": "direct",
                "endpoints": [matching_endpoint],
                "verified": True,
                "ownership_verified": expected_live_fingerprint is not None,
                "verification_status": "verified",
                "verification_reason": "authenticated active direct session",
            }]

    async def _first_verified():
        tasks = [
            asyncio.create_task(
                _verify_live_endpoint(endpoint, expected_live_name, expected_live_fingerprint)
            )
            for endpoint in candidates
        ]
        try:
            for completed in asyncio.as_completed(tasks):
                result = await completed
                if isinstance(result, dict) and result.get("verified"):
                    return result
            return None
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    verify_loop = asyncio.new_event_loop()
    try:
        asyncio.set_event_loop(verify_loop)
        result = verify_loop.run_until_complete(_first_verified())
    finally:
        verify_loop.close()
    if not result:
        return []
    return [{
        "nickname": result["nickname"],
        "fingerprint": result["fingerprint"],
        "transport": "direct",
        "endpoints": [result["endpoint"]],
        "verified": True,
        "ownership_verified": (
            expected_live_fingerprint is not None
            and result["fingerprint"] == expected_live_fingerprint
        ),
        "verification_status": "verified",
        "verification_reason": "authenticated direct discovery peer",
    }]

def resolve_peers(
    nickname: str,
    shared_code: str,
    tracker_name: str = "Yemekyedim HTTPS",
    expected_live_name: str | None = None,
    expected_live_fingerprint: str | None = None,
):
    """
    Resolve peers from multiple trackers to maximise endpoint coverage.
    Queries the specified HTTP tracker and the Torrent.eu.org UDP tracker
    (which carries IPv6/Yggdrasil endpoints) and deduplicates results.
    Returns a list of dicts with nickname, fingerprint, and endpoints.
    """
    import urllib.error

    expected_live_fingerprint = _canonical_expected_fingerprint(expected_live_fingerprint)

    async def _query_async(t_name):
        started = time.monotonic()
        try:
            tracker = _configured_tracker(t_name)
            provider = get_discovery_provider(
                tracker.discovery_scheme,
                tracker_url=tracker.announce_url,
                peer_port=listener_port,
                transport="direct"
            )
            result = await provider.resolve(nickname, shared_code)
            _record_public_addresses(provider)
            endpoint_count = sum(len(getattr(item, "endpoints", ())) for item in result)
            _set_tracker_diagnostic(t_name, "resolve", f"OK ({endpoint_count} endpoints)")
            _set_tracker_diagnostic(t_name, "resolve_rtt_ms", str(round((time.monotonic() - started) * 1000)))
            return result
        except (urllib.error.URLError, OSError) as e:
            _set_tracker_diagnostic(t_name, "resolve", f"FAIL ({e})")
            _set_tracker_diagnostic(t_name, "resolve_rtt_ms", str(round((time.monotonic() - started) * 1000)))
            print(f"Network error resolving peers from {t_name}: {e}")
            return []
        except Exception as e:
            _set_tracker_diagnostic(t_name, "resolve", f"FAIL ({e})")
            _set_tracker_diagnostic(
                t_name,
                "resolve_rtt_ms",
                str(round((time.monotonic() - started) * 1000)),
            )
            print(f"Error resolving peers from {t_name}: {e}")
            return []

    async def _query_dht():
        if not _dht_enabled:
            _set_tracker_diagnostic(MAINLINE_DHT, "resolve", "DISABLED BY USER")
            return []
        started = time.monotonic()
        try:
            provider = get_discovery_provider(
                "mainline-dht", peer_port=listener_port, transport="direct"
            )
            result = await provider.resolve(nickname, shared_code)
            _record_public_addresses(provider)
            endpoint_count = sum(len(item.endpoints) for item in result)
            _set_tracker_diagnostic(MAINLINE_DHT, "resolve", f"OK ({endpoint_count} endpoints)")
            _set_tracker_diagnostic(
                MAINLINE_DHT,
                "resolve_rtt_ms",
                str(round((time.monotonic() - started) * 1000)),
            )
            return result
        except Exception as exc:
            _set_tracker_diagnostic(MAINLINE_DHT, "resolve", f"FAIL ({exc})")
            print(f"Error resolving peers from {MAINLINE_DHT}: {exc}")
            return []

    async def _resolve_all():
        tasks = []
        for t_name in _resolve_tracker_names(tracker_name):
            tasks.append(_query_async(t_name))
        tasks.append(_query_dht())
        results = await asyncio.gather(*tasks, return_exceptions=True)
        
        flat_results = []
        for r in results:
            if isinstance(r, list):
                flat_results.extend(r)
            elif isinstance(r, Exception):
                if isinstance(r, (urllib.error.URLError, OSError)):
                    print(f"Network error resolving peers: {r}")
                else:
                    print(f"Unexpected error resolving peers: {r}")
                    traceback.print_exception(type(r), r, r.__traceback__)
        return flat_results

    loop = asyncio.new_event_loop()
    try:
        asyncio.set_event_loop(loop)
        descriptors = loop.run_until_complete(_resolve_all())
    except Exception as e:
        if isinstance(e, (urllib.error.URLError, OSError)):
            print(f"Network error in resolve_peers loop: {e}")
        else:
            print("Error in resolve_peers loop:", e)
            traceback.print_exc()
        descriptors = []
    finally:
        try:
            loop.close()
        except Exception:
            pass

    all_endpoints = []
    seen_ep = set()

    for d in descriptors:
        for ep in d.endpoints:
            if ep.host in local_announced_ips or ep.host in {"127.0.0.1", "::1", "localhost"}:
                continue
            ep_str = _format_endpoint(ep.host, ep.port)
            key = ep_str
            if key not in seen_ep:
                seen_ep.add(key)
                all_endpoints.append(ep_str)

    all_endpoints.sort(key=_endpoint_sort_key)
    print(f"Resolved {len(all_endpoints)} discovery endpoints for nickname '{nickname}': {all_endpoints}")
    if not ipv4_enabled:
        all_endpoints = [endpoint for endpoint in all_endpoints if not _is_ipv4_endpoint(endpoint)]
    if not all_endpoints:
        return []

    async def _verify_all():
        # A bounded fan-out prevents one malicious tracker reply from causing
        # an unbounded number of connection attempts on a mobile device.
        candidates = all_endpoints[:12]
        return await asyncio.gather(
            *[
                _verify_live_endpoint(
                    endpoint,
                    expected_live_name or nickname,
                    expected_live_fingerprint,
                )
                for endpoint in candidates
            ],
            return_exceptions=True,
        )

    verify_loop = asyncio.new_event_loop()
    try:
        asyncio.set_event_loop(verify_loop)
        verified = verify_loop.run_until_complete(_verify_all())
    finally:
        verify_loop.close()

    verified_by_identity = {}
    for result in verified:
        if isinstance(result, dict) and result.get("verified"):
            key = (result["nickname"], result["fingerprint"])
            verified_by_identity.setdefault(key, []).append(result["endpoint"])

    if verified_by_identity:
        print(f"Verified {sum(len(v) for v in verified_by_identity.values())} live endpoints for '{nickname}'")
        return [
            {
                "nickname": name,
                "fingerprint": peer_fingerprint,
                "transport": "direct",
                "endpoints": sorted(endpoints, key=_endpoint_sort_key),
                "verified": True,
                "ownership_verified": expected_live_fingerprint is not None and peer_fingerprint == expected_live_fingerprint,
                "verification_status": "verified",
                "verification_reason": "authenticated live response",
            }
            for (name, peer_fingerprint), endpoints in verified_by_identity.items()
        ]
    # Keep tracker hits visible without presenting them as authenticated. A
    # stale registration is useful evidence that the name existed recently,
    # but it must never be treated as a verified identity. Self-connections are
    # excluded explicitly.
    failed = [item for item in verified if isinstance(item, dict) and not item.get("is_self")]
    evidenced_descriptors = [
        descriptor for descriptor in descriptors
        if str(getattr(descriptor, "identity_fingerprint", "") or "").strip()
        and _same_nickname(str(getattr(descriptor, "nickname", "") or ""), nickname)
    ]
    if failed and evidenced_descriptors:
        candidate_fingerprint = str(evidenced_descriptors[0].identity_fingerprint).strip()
        evidenced_endpoints = {
            _format_endpoint(endpoint.host, endpoint.port)
            for descriptor in evidenced_descriptors
            for endpoint in descriptor.endpoints
        }
        failed = [item for item in failed if item.get("endpoint") in evidenced_endpoints]
        reasons = {item.get("endpoint", ""): item.get("verification_reason", "verification failed") for item in failed}
    if failed and evidenced_descriptors:
        print(f"Tracker found '{nickname}', but no endpoint passed live verification")
        return [{
            "nickname": nickname.strip(),
            "fingerprint": candidate_fingerprint,
            "transport": "direct",
            "endpoints": [item["endpoint"] for item in failed],
            "verified": False,
            "verification_status": "unverified",
            "verification_reason": "; ".join(f"{ep}: {reason}" for ep, reason in reasons.items()),
        }]
    if failed:
        print(
            f"Trackers returned {len(failed)} untrusted endpoints for '{nickname}', "
            "but supplied no identity metadata; refusing to label them with the searched name"
        )
    else:
        print(f"No external live endpoint confirmed nickname '{nickname}'")
    return []


def resolve_peer_endpoints(peer_fingerprint: str) -> list[str]:
    """Resolve fresh endpoints for a previously verified identity."""
    if not isinstance(peer_fingerprint, str) or len(peer_fingerprint.strip()) < 40:
        return []
    results = resolve_peers(peer_fingerprint.strip(), peer_fingerprint.strip())
    return list(dict.fromkeys(
        str(endpoint) for result in results for endpoint in result.get("endpoints", [])
    ))


async def _resolve_peer_endpoints_async(peer_fingerprint: str) -> list[str]:
    """Resolve routes on the existing asyncio loop; identity is verified when dialing."""
    if not isinstance(peer_fingerprint, str) or len(peer_fingerprint.strip()) < 40:
        return []
    expected = peer_fingerprint.strip()

    async def _query(tracker_name: str):
        try:
            tracker = _configured_tracker(tracker_name)
            provider = get_discovery_provider(
                tracker.discovery_scheme,
                tracker_url=tracker.announce_url,
                peer_port=listener_port,
                transport="direct",
            )
            return await provider.resolve(expected, expected)
        except Exception as exc:
            print(f"[RECONNECT] Endpoint resolve failed on {tracker_name}: {exc}")
            return []

    async def _query_dht():
        if not _dht_enabled:
            return []
        try:
            provider = get_discovery_provider(
                "mainline-dht", peer_port=listener_port, transport="direct"
            )
            return await provider.resolve(expected, expected)
        except Exception as exc:
            print(f"[RECONNECT] Endpoint resolve failed on {MAINLINE_DHT}: {exc}")
            return []

    batches = await asyncio.gather(
        *[_query(name) for name in _resolve_tracker_names("Yemekyedim HTTPS")],
        _query_dht(),
        return_exceptions=True,
    )
    endpoints = []
    for batch in batches:
        if not isinstance(batch, list):
            continue
        for descriptor in batch:
            descriptor_fp = str(getattr(descriptor, "identity_fingerprint", "") or "")
            if descriptor_fp and descriptor_fp != expected:
                continue
            for endpoint in getattr(descriptor, "endpoints", ()):
                host = endpoint.host
                if host in local_announced_ips or host in {"127.0.0.1", "::1", "localhost"}:
                    continue
                endpoints.append(_format_endpoint(host, endpoint.port))
    result = sorted(dict.fromkeys(endpoints), key=_endpoint_sort_key)
    return result if ipv4_enabled else [ep for ep in result if not _is_ipv4_endpoint(ep)]


def announce_peer_endpoints(
    nickname: str,
    fingerprint: str,
    endpoints_json: str,
    port: int,
    discovery_code: str = "",
) -> bool:
    """
    Announce all current IPv4 and Yggdrasil/global IPv6 endpoints across the tracker set.
    """
    import urllib.error
    global local_identity_nickname, local_identity_fingerprint, local_yggdrasil_available, local_announced_ips

    if not _announce_enabled:
        for tracker_name in _resolve_tracker_names():
            _set_tracker_diagnostic(tracker_name, "announce", "DISABLED BY USER")
        _set_tracker_diagnostic(MAINLINE_DHT, "announce", "DISABLED BY USER")
        return True

    try:
        addresses = json.loads(endpoints_json)
        local_announced_ips = set(addresses)
    except Exception as exc:
        print(f"Invalid endpoints_json passed to announce_peer_endpoints: {exc}")
        return False

    endpoints = _parse_endpoint_hosts(addresses, port)
    if not endpoints:
        print("No usable endpoints supplied for tracker announce.")
        return False

    local_identity_nickname = (nickname or "").strip()
    local_identity_fingerprint = (fingerprint or "").strip()
    local_yggdrasil_available = _has_ipv6_endpoint(endpoints)
    if not local_yggdrasil_available:
        for tracker_name in YGG_TRACKERS:
            tracker_diagnostics[tracker_name] = {
                "announce": "SKIPPED (Yggdrasil unavailable)",
                "resolve": "SKIPPED (Yggdrasil unavailable)",
            }

    loop = asyncio.new_event_loop()
    try:
        asyncio.set_event_loop(loop)
        variants = []
        if discovery_code.strip():
            variants.append((nickname, discovery_code.strip()))
        if fingerprint and len(fingerprint) > 10:
            variants.append((nickname, fingerprint))
            variants.append((fingerprint, fingerprint))

        async def _announce_tracker(tracker_name: str):
            started = time.monotonic()
            tracker = _configured_tracker(tracker_name)
            provider = get_discovery_provider(
                tracker.discovery_scheme,
                tracker_url=tracker.announce_url,
                peer_port=port,
                transport="direct",
            )
            tasks = [
                provider.announce(nick, shared_code, transport="direct", endpoints=endpoints)
                for nick, shared_code in variants
            ]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            _record_public_addresses(provider)
            _set_tracker_diagnostic(
                tracker_name, "announce_rtt_ms", str(round((time.monotonic() - started) * 1000))
            )
            success_count = 0
            for idx, res in enumerate(results, start=1):
                if isinstance(res, Exception):
                    if isinstance(res, (urllib.error.URLError, OSError)):
                        _set_tracker_diagnostic(tracker_name, "announce", f"FAIL ({res})")
                        print(f"Network error in announce {idx} on {tracker_name}: {res}")
                    else:
                        _set_tracker_diagnostic(tracker_name, "announce", f"FAIL ({res})")
                        print(f"Unexpected error in announce {idx} on {tracker_name}: {res}")
                        traceback.print_exception(type(res), res, res.__traceback__)
                else:
                    success_count += 1
            if success_count > 0:
                _set_tracker_diagnostic(tracker_name, "announce", f"OK ({success_count})")
            return success_count

        async def _announce_all():
            tracker_names = _announce_tracker_names(endpoints)

            async def _announce_dht():
                if not _dht_enabled:
                    _set_tracker_diagnostic(MAINLINE_DHT, "announce", "DISABLED BY USER")
                    return 0
                dht_started = time.monotonic()
                try:
                    dht = get_discovery_provider(
                        "mainline-dht", peer_port=port, transport="direct"
                    )
                    dht_results = await asyncio.gather(
                        *[
                            dht.announce(
                                nick,
                                shared_code,
                                transport="direct",
                                endpoints=endpoints,
                            )
                            for nick, shared_code in variants
                        ],
                        return_exceptions=True,
                    )
                    _record_public_addresses(dht)
                    dht_success = sum(
                        not isinstance(result, Exception) for result in dht_results
                    )
                    dht_errors = [
                        str(result)
                        for result in dht_results
                        if isinstance(result, Exception)
                    ]
                    status = (
                        f"OK ({dht_success})"
                        if dht_success
                        else f"FAIL ({'; '.join(dht_errors)})"
                    )
                    _set_tracker_diagnostic(MAINLINE_DHT, "announce", status)
                    print(f"{MAINLINE_DHT} accepted {dht_success} announce registrations.")
                    return dht_success
                except Exception as exc:
                    _set_tracker_diagnostic(MAINLINE_DHT, "announce", f"FAIL ({exc})")
                    print(f"Mainline DHT announce failed: {exc}")
                    return 0
                finally:
                    _set_tracker_diagnostic(
                        MAINLINE_DHT,
                        "announce_rtt_ms",
                        str(round((time.monotonic() - dht_started) * 1000)),
                    )

            # Start every discovery channel immediately. Previously BEP 5 was
            # delayed until even the slowest tracker request had completed.
            combined_results = await asyncio.gather(
                *[_announce_tracker(tracker_name) for tracker_name in tracker_names],
                _announce_dht(),
                asyncio.to_thread(_discover_public_ipv4_stun),
                return_exceptions=True,
            )
            tracker_results = combined_results[:len(tracker_names)]
            dht_result = combined_results[len(tracker_names)]
            observed_ipv4 = combined_results[len(tracker_names) + 1]
            total_success = 0
            for tracker_name, result in zip(tracker_names, tracker_results):
                if isinstance(result, Exception):
                    print(f"Tracker announce task crashed for {tracker_name}: {result}")
                    continue
                total_success += result
                print(f"Tracker {tracker_name} accepted {result} announce registrations.")
            if not isinstance(dht_result, Exception):
                total_success += dht_result
            else:
                print(f"Mainline DHT announce task crashed: {dht_result}")

            if isinstance(observed_ipv4, str) and observed_ipv4:
                if observed_ipv4 not in public_address_observations:
                    public_address_observations.add(observed_ipv4)
                    print(f"STUN observed our public address as {observed_ipv4}")
            return total_success

        endpoint_strings = [_format_endpoint(ep.host, ep.port) for ep in endpoints]
        print(f"Announcing endpoints for '{nickname}': {endpoint_strings}")
        success_count = loop.run_until_complete(_announce_all())
        print(f"Total successful announce registrations: {success_count}")
        return success_count > 0
    except Exception as e:
        if isinstance(e, (urllib.error.URLError, OSError)):
            print(f"Network error announcing endpoints in discovery_bridge: {e}")
        else:
            print("Error announcing endpoints in discovery_bridge:", e)
            traceback.print_exc()
        return False
    finally:
        try:
            loop.close()
        except Exception:
            pass


def announce_peer(nickname: str, fingerprint: str, host: str, port: int, tracker_name: str = "Yemekyedim HTTPS"):
    """
    Synchronous wrapper to announce this peer on a tracker under both nickname and fingerprint.
    """
    del tracker_name
    return announce_peer_endpoints(nickname, fingerprint, json.dumps([host]), port)


def announce_peer_ygg(nickname: str, fingerprint: str, ygg_host: str, port: int):
    """
    Announce this peer using the HTTP tracker so that the IPv6/Yggdrasil
    endpoint is included in the announce via the ipv6 parameter.
    """
    return announce_peer_endpoints(nickname, fingerprint, json.dumps([ygg_host]), port)


def get_tracker_diagnostics_json() -> str:
    return json.dumps(tracker_diagnostics, sort_keys=True)


def get_public_addresses_json() -> str:
    return json.dumps(sorted(public_address_observations))


# =====================================================================
# Double Ratchet P2P Messaging Integration
# =====================================================================

def register_message_listener(callback):
    global message_listener_callback
    message_listener_callback = callback
    print("Python message listener callback registered")

def register_session_listener(callback):
    global session_listener_callback
    session_listener_callback = callback
    print("Python session listener callback registered")


def _notify_session_established(peer_name, peer_fingerprint, endpoint, transport, about_me="") -> bool:
    """Let Android synchronously reject a key before application frames are read."""
    if is_fingerprint_rejected(peer_name, peer_fingerprint):
        return False
    if session_listener_callback is None:
        return True
    try:
        accepted = session_listener_callback.onSessionEstablished(
            peer_name, peer_fingerprint, endpoint, transport, about_me
        )
        if accepted is False:
            record_rejected_fingerprint(peer_name, peer_fingerprint)
            return False
        return True
    except Exception as callback_error:
        print("Error invoking session listener callback:", callback_error)
        return False

def start_p2p_listener(port=50001):
    """
    Start the background asyncio event loop and dual-stack listener thread.
    Listens on both 0.0.0.0 (IPv4) and :: (IPv6/Yggdrasil) simultaneously.
    """
    global loop, listener_port, _listener_thread, _listener_task, _listener_stopped
    global _runtime_shutdown_requested
    listener_port = port
    with _runtime_lock:
        if _listener_thread is not None and _listener_thread.is_alive():
            print(f"P2P listener already running on port {port}, skipping duplicate start")
            return True
        _runtime_shutdown_requested = False

    try:
        from messenger.core.upnp import setup_upnp_in_background
        setup_upnp_in_background(port)
    except Exception as upnp_err:
        print("[UPNP] Failed to trigger background setup:", upnp_err)

    ready = threading.Event()
    stopped = threading.Event()

    def run():
        global loop, _listener_thread, _listener_task, _listener_stopped
        global _runtime_shutdown_requested
        runtime_loop = asyncio.new_event_loop()
        try:
            asyncio.set_event_loop(runtime_loop)
            with _runtime_lock:
                loop = runtime_loop
                _listener_task = runtime_loop.create_task(_listen_loop_dual(port))
            ready.set()

            def _listener_finished(task):
                if not task.cancelled():
                    error = task.exception()
                    if error is not None:
                        print(f"P2P listener event loop crashed: {error}")
                with _runtime_lock:
                    shutting_down = _runtime_shutdown_requested
                if not shutting_down:
                    runtime_loop.call_soon(runtime_loop.stop)

            _listener_task.add_done_callback(_listener_finished)
            runtime_loop.run_forever()
        except Exception as e:
            print("P2P listener event loop crashed:", e)
            traceback.print_exc()
        finally:
            pending = [task for task in asyncio.all_tasks(runtime_loop) if not task.done()]
            for task in pending:
                task.cancel()
            if pending:
                runtime_loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            runtime_loop.close()
            with _runtime_lock:
                if loop is runtime_loop:
                    loop = None
                if _listener_thread is threading.current_thread():
                    _listener_thread = None
                _listener_task = None
                _listener_stopped = None
                _runtime_shutdown_requested = False
            stopped.set()

    t = threading.Thread(target=run, daemon=True)
    with _runtime_lock:
        _listener_thread = t
        _listener_stopped = stopped
    t.start()
    ready.wait(timeout=2.0)
    print(f"P2P Listener background thread started on port {port} (IPv4 + IPv6)")
    return ready.is_set()


async def _listen_loop_dual(port: int):
    """Listen on all interfaces (both IPv4 and IPv6/Yggdrasil) natively."""
    identity_priv = load_or_create_identity()
    signing_key = load_or_create_signing_identity()
    trust_store = TrustStore()

    print(f"Starting dual-stack P2P Server on port {port}...")
    handshake_tasks = set()
    try:
        # Binding to empty string ("") binds to all available IPv4 and IPv6 interfaces natively.
        async for reader, writer in transport_listen("direct", "", port):
            if len(handshake_tasks) >= MAX_CONCURRENT_HANDSHAKES:
                writer.close()
                await writer.wait_closed()
                print("Rejected incoming connection: handshake limit reached")
                continue
            task = asyncio.create_task(
                _handle_incoming(reader, writer, identity_priv, signing_key, trust_store)
            )
            handshake_tasks.add(task)
            task.add_done_callback(handshake_tasks.discard)
        print(f"Python P2P Server successfully listening on dual-stack port {port} (IPv4 + IPv6/Yggdrasil)")
    except Exception as e:
        print(f"Error in dual-stack P2P Server listen loop on port {port}: {e}")
        traceback.print_exc()
    finally:
        for task in list(handshake_tasks):
            task.cancel()
        if handshake_tasks:
            await asyncio.gather(*handshake_tasks, return_exceptions=True)



async def _handle_incoming(reader, writer, identity_priv, signing_key, trust_store):
    session = None
    try:
        peername = writer.get_extra_info("peername")
        if not ipv4_enabled and peername and ":" not in str(peername[0]):
            writer.close()
            await writer.wait_closed()
            print(f"Rejected incoming IPv4 connection from {peername[0]}: IPv4 is disabled")
            return
        _setup_socket_keepalive(writer)
        session = Session(
            reader,
            writer,
            identity_priv=identity_priv,
            signing_key=signing_key,
            trust_store=trust_store,
            ack_timeout=MOBILE_ACK_TIMEOUT,
            max_retries=MOBILE_MAX_RETRIES,
        )
        await asyncio.wait_for(session._exchange_keys(initiator=False), timeout=5.0)
        if session.peer_fingerprint == fingerprint(identity_priv.public_key):
            await session.close()
            print("Rejected self-connection on the local listener")
            return
        session._start_reader()
        
        fp = session.peer_fingerprint
        peer_name = peer_fingerprint_to_name.get(fp, f"Peer ({fp[:8]})")
        
        registered = await _register_authenticated_session(session, fp, initiator=False)
        if registered is not session:
            print(f"Ignored duplicate incoming session from {peer_name} (Fingerprint: {fp})")
            return

        # Do not wait for an identity probe: a normal incoming chat connection
        # must learn our nickname before the UI falls back to Peer(fingerprint).
        await _send_local_identity_info(session)
        
        print(f"Accepted Double Ratchet session from {peer_name} (Fingerprint: {fp})")
        
        peername = writer.get_extra_info('peername')
        remote_ep = ""
        remote_transport = "Direct P2P"
        if peername:
            remote_transport = "Yggdrasil" if ":" in str(peername[0]) else "Direct P2P"

        if not _notify_session_established(peer_name, fp, remote_ep, remote_transport):
            print(f"Android rejected authenticated session from {peer_name} ({fp})")
            await _invalidate_session(session)
            return
                
        asyncio.create_task(_read_loop(session, peer_name, fp))
    except Exception as e:
        is_network_noise = (
            isinstance(e, (ValueError, asyncio.TimeoutError, ConnectionError, OSError))
            or "frame size" in str(e).lower()
            or "refusing self connection" in str(e).lower()
        )
        if is_network_noise:
            peername_str = f" from {peername}" if 'peername' in locals() and peername else ""
            print(f"Closed invalid or noisy incoming connection{peername_str}: {e}")
        else:
            print("Error handling incoming connection:", e)
            traceback.print_exc()
        if session is not None:
            await session.close()
        else:
            writer.close()
            try:
                await writer.wait_closed()
            except (ConnectionError, OSError):
                pass

async def _read_loop(session, peer_name, fp):
    global message_listener_callback
    try:
        while True:
            msg = await session.receive_message()
            # Kotlin may restore a canonical name from its persisted,
            # authenticated fingerprint mapping before identity_info arrives.
            # Always use the freshest mapping for application callbacks; the
            # name captured when the read loop was created may be Peer (...).
            mapped_name = peer_fingerprint_to_name.get(fp)
            if mapped_name and not mapped_name.startswith("Peer ("):
                peer_name = mapped_name
            mtype = msg.get("type")
            if mtype == "status" and msg.get("state") == "offline":
                print(
                    f"Session with {peer_name} went offline: "
                    f"{msg.get('reason') or 'remote stream closed'}"
                )
                break
            if mtype == "identity_info":
                # Remote peer announced their real nickname — update our mappings
                real_name = msg.get("nickname", "").strip()
                about_me = msg.get("about_me", "").strip()
                claimed_fp = msg.get("fingerprint", fp)
                # The authenticated session fingerprint is authoritative. A
                # peer-controlled identity_info payload must not be able to
                # overwrite another contact's identity mapping.
                remote_fp = fp if claimed_fp != fp else claimed_fp
                if claimed_fp != fp:
                    print(f"Ignored mismatched identity_info fingerprint from {fp}")
                if real_name:
                    if real_name != peer_name:
                        print(f"Peer renamed: '{peer_name}' → '{real_name}' (fp={remote_fp})")
                    peername = session.writer.get_extra_info('peername') if hasattr(session, 'writer') else None
                    advertised_port = msg.get("listen_port")
                    if isinstance(advertised_port, int) and 1 <= advertised_port <= 65535 and peername:
                        remote_ep = _format_endpoint(peername[0], advertised_port)
                    else:
                        remote_ep = ""
                    remote_transport = (
                        "Yggdrasil" if peername and ":" in str(peername[0]) else "Direct P2P"
                    )
                    if not _notify_session_established(real_name, remote_fp, remote_ep, remote_transport, about_me):
                        print(f"Android rejected fingerprint {remote_fp} for nickname '{real_name}'")
                        await _invalidate_session(session)
                        return
                    peer_fingerprint_to_name[remote_fp] = real_name
                    peer_fingerprint_to_name[fp] = real_name
                    session.peer_label = real_name
                    # Update loop variables so cleanup is correct
                    peer_name = real_name
                    fp = remote_fp
                continue
            elif mtype == "identity_probe":
                # A search result is only shown after this authenticated reply.
                # Do not invent a name: nodes that have not configured one
                # cannot be discovered by nickname.
                await _send_local_identity_info(session)
                continue
            elif mtype == "chat":
                body = msg.get("body", "")
                if message_listener_callback:
                    try:
                        message_listener_callback.onMessageReceived(peer_name, body)
                    except Exception as cb_err:
                        print("Error invoking message listener callback:", cb_err)
            elif mtype in {"file_meta", "file_chunk"}:
                import base64
                import os
                import mimetypes
                from nacl.secret import SecretBox

                file_id_str = str(msg.get("file_id", ""))
                try:
                    file_id = base64.b64decode(file_id_str.encode(), validate=True)
                except Exception:
                    print("Rejected file transfer with invalid file_id")
                    continue
                if not 8 <= len(file_id) <= 64:
                    print("Rejected file transfer with invalid file_id length")
                    continue

                now = time.monotonic()
                _prune_incoming_files(now)

                # File IDs are peer-local. One peer cannot collide with or
                # complete another authenticated peer's transfer.
                transfer_key = (fp, file_id)
                state = incoming_files.get(transfer_key)

                if mtype == "file_meta":
                    file_size = int(msg.get("file_size", -1))
                    num_chunks = int(msg.get("num_chunks", 0))
                    if file_size < 0 or file_size > MAX_INCOMING_FILE_BYTES:
                        print(f"Rejected file transfer: invalid size {file_size}")
                        continue
                    if num_chunks < 0 or num_chunks > MAX_INCOMING_FILE_CHUNKS or (file_size > 0 and num_chunks == 0):
                        print(f"Rejected file transfer: invalid chunk count {num_chunks}")
                        continue
                    peer_transfer_count = sum(
                        1 for key in incoming_files if isinstance(key, tuple) and key[0] == fp
                    )
                    if peer_transfer_count >= MAX_INCOMING_FILES_PER_PEER:
                        print("Rejected file transfer: peer concurrency limit reached")
                        continue
                    if not _allow_incoming_file_start(fp, now):
                        print("Rejected file transfer: peer rate limit reached")
                        continue
                    if state:
                        _discard_incoming_file(transfer_key)
                    if len(incoming_files) >= MAX_INCOMING_FILES:
                        print("Rejected file transfer: too many concurrent incoming files")
                        continue
                    try:
                        file_key = base64.b64decode(str(msg["file_key"]), validate=True)
                        nonce_prefix = base64.b64decode(str(msg["file_nonce_prefix"]), validate=True)
                        expected_hash = base64.b64decode(str(msg["file_hash"]), validate=True)
                        if len(file_key) != SecretBox.KEY_SIZE or len(nonce_prefix) != 16 or len(expected_hash) != 32:
                            raise ValueError("invalid file crypto metadata")
                        config_dir = os.environ.get("P2PCHAT_CONFIG_DIR")
                        temp = tempfile.NamedTemporaryFile(prefix="2pchat-recv-", suffix=".part", dir=config_dir, delete=False)
                        state = {
                            "meta": msg, "handle": temp, "temp_path": temp.name,
                            "box": SecretBox(file_key), "nonce_prefix": nonce_prefix,
                            "digest": hashlib.sha256(), "expected_hash": expected_hash,
                            "next_index": 0, "written": 0, "updated_at": now,
                        }
                        incoming_files[transfer_key] = state
                    except Exception as meta_err:
                        print(f"Rejected invalid file metadata: {meta_err}")
                        continue
                else:
                    if not state:
                        print("Rejected file chunk received before metadata")
                        continue
                    chunk_index = int(msg.get("chunk_index", -1))
                    expected_chunks = int(state["meta"]["num_chunks"])
                    if chunk_index != state["next_index"] or chunk_index >= expected_chunks:
                        print(f"Rejected invalid file chunk index {chunk_index}")
                        continue
                    try:
                        payload = base64.b64decode(str(msg.get("payload", "")).encode(), validate=True)
                    except Exception:
                        print("Rejected file chunk with invalid payload encoding")
                        continue
                    if len(payload) > MAX_ENCRYPTED_CHUNK_SIZE:
                        print(f"Rejected oversized encrypted file chunk ({len(payload)} bytes)")
                        continue
                    try:
                        plaintext = state["box"].decrypt(payload)
                    except Exception as decrypt_err:
                        _discard_incoming_file(transfer_key)
                        print(f"Rejected unauthenticated file chunk: {decrypt_err}")
                        continue
                    declared_size = int(state["meta"]["file_size"])
                    if state["written"] + len(plaintext) > declared_size or state["written"] + len(plaintext) > MAX_INCOMING_FILE_BYTES:
                        _discard_incoming_file(transfer_key)
                        print("Rejected file transfer exceeding declared size")
                        continue
                    state["handle"].write(plaintext)
                    state["digest"].update(plaintext)
                    state["written"] += len(plaintext)
                    state["next_index"] += 1
                    state["updated_at"] = now

                meta = state.get("meta") if state else None
                if meta and state["next_index"] == int(meta.get("num_chunks", 0)):
                        file_name = f"file-{file_id_str}"
                        try:
                            requested_name = meta.get("file_name") or f"file-{file_id_str}"
                            # Keep received files inside downloads even when a
                            # remote peer supplies a path-like filename.
                            file_name = re.sub(r"[^\w.\- ]", "_", Path(str(requested_name)).name)
                            file_name = file_name.strip(" .")[:120]
                            if not file_name:
                                file_name = f"file-{file_id_str}"
                            state["handle"].flush()
                            state["handle"].close()
                            state["handle"] = None
                            if state["written"] != int(meta["file_size"]):
                                raise ValueError("decrypted file size does not match metadata")
                            if state["digest"].digest() != state["expected_hash"]:
                                raise ValueError("decrypted file hash mismatch")
                            
                            config_dir = os.environ.get("P2PCHAT_CONFIG_DIR")
                            downloads_dir = Path(config_dir) / "downloads"
                            downloads_dir.mkdir(parents=True, exist_ok=True)
                            target = downloads_dir / file_name
                            if target.resolve().parent != downloads_dir.resolve():
                                raise ValueError("unsafe download target")
                            
                            if target.exists():
                                suffix = 1
                                stem = target.stem
                                suffix_target = target
                                while suffix_target.exists():
                                    suffix_target = target.with_name(f"{stem}_{suffix}{target.suffix}")
                                    suffix += 1
                                target = suffix_target
                                
                            os.replace(state["temp_path"], target)
                            state["temp_path"] = None
                            print(f"File fully received and decrypted: {target}")
                            incoming_files.pop(transfer_key, None)
                            
                            ext_lower = target.suffix.lower()
                            ext_mime_map = {
                                ".jpg": "image/jpeg",
                                ".jpeg": "image/jpeg",
                                ".png": "image/png",
                                ".gif": "image/gif",
                                ".webp": "image/webp",
                                ".bmp": "image/bmp",
                                ".heic": "image/heic",
                            }
                            mime = ext_mime_map.get(ext_lower)
                            if not mime:
                                mime, _ = mimetypes.guess_type(file_name)
                            if not mime or mime == "application/octet-stream":
                                try:
                                    with open(target, "rb") as hfile:
                                        hdr = hfile.read(16)
                                    if hdr.startswith(b"\xff\xd8\xff"):
                                        mime = "image/jpeg"
                                    elif hdr.startswith(b"\x89PNG\r\n\x1a\n"):
                                        mime = "image/png"
                                    elif hdr.startswith(b"GIF87a") or hdr.startswith(b"GIF89a"):
                                        mime = "image/gif"
                                    elif hdr.startswith(b"RIFF") and len(hdr) >= 12 and hdr[8:12] == b"WEBP":
                                        mime = "image/webp"
                                    elif hdr.startswith(b"BM"):
                                        mime = "image/bmp"
                                except Exception:
                                    pass
                            if not mime:
                                mime = "application/octet-stream"
                                
                            file_notification = {
                                "type": "file",
                                "message_id": str(meta.get("message_id", "")),
                                "file_name": file_name,
                                "file_path": str(target),
                                "mime": mime,
                                "size": state["written"]
                            }
                            
                            if message_listener_callback:
                                try:
                                    message_listener_callback.onMessageReceived(peer_name, json.dumps(file_notification))
                                except Exception as cb_err:
                                    print("Error invoking message listener callback for file:", cb_err)
                        except Exception as decrypt_err:
                            print(f"Failed to decrypt an incoming file: {decrypt_err}")
                            traceback.print_exc()
                            _discard_incoming_file(transfer_key)
    except Exception as e:
        print(f"Session with {peer_name} read loop error:", e)
    finally:
        # A stale read loop must never delete a newer replacement session.
        if active_sessions.get(fp) is session:
            del active_sessions[fp]
        has_replacement = any(
            candidate is not session
            and candidate.is_online
            and candidate.peer_fingerprint == fp
            for candidate in active_sessions.values()
        )
        if session_listener_callback and not has_replacement:
            try:
                session_listener_callback.onSessionClosed(peer_name, fp)
            except Exception as cb_err:
                pass


def send_p2p_message(peer_name: str, endpoint: str, body: str, expected_fingerprint=None) -> bool:
    """
    Synchronous entry point called from Kotlin to send an encrypted Double Ratchet message.
    """
    global loop
    if not loop:
        print("Asyncio loop not running, starting listener loop first")
        start_p2p_listener(listener_port)
        # Give a small buffer to start
        import time
        time.sleep(1)
        if not loop:
            return False
            
    future = asyncio.run_coroutine_threadsafe(
        _send_message_async(peer_name, endpoint, body, expected_fingerprint),
        loop
    )
    try:
        return future.result(timeout=45)
    except Exception as e:
        future.cancel()
        print(f"Failed to send message to {peer_name} via python bridge:", e)
        traceback.print_exc()
        return False

async def _dial_endpoint(endpoint_str: str, identity_priv, signing_key, trust_store, expected_fingerprint=None) -> "Session":
    """
    Attempt to connect to a single 'host:port' or '[ipv6]:port' endpoint.
    Only protocol V3 is accepted.
    Returns a connected Session or raises an exception.
    """
    if not ipv4_enabled and _is_ipv4_endpoint(endpoint_str):
        raise ConnectionError("IPv4 transport is disabled in settings")
    host, port_str = endpoint_str.rsplit(":", 1)
    port = int(port_str)
    if host.startswith("[") and host.endswith("]"):
        host = host[1:-1]

    async def _close_writer_safely(writer):
        if writer is None:
            return
        try:
            writer.close()
        except Exception:
            return
        try:
            await writer.wait_closed()
        except Exception:
            pass

    reader = None
    writer = None
    session = None
    try:
        reader, writer = await asyncio.wait_for(
            transport_connect("direct", host, port), timeout=5.0
        )
        _setup_socket_keepalive(writer)
        session = Session(
            reader,
            writer,
            identity_priv=identity_priv,
            signing_key=signing_key,
            trust_store=trust_store,
            ack_timeout=MOBILE_ACK_TIMEOUT,
            max_retries=MOBILE_MAX_RETRIES,
        )
        await asyncio.wait_for(session._exchange_keys(initiator=True, expected_fingerprint=expected_fingerprint), timeout=5.0)
        if session.peer_fingerprint == fingerprint(identity_priv.public_key):
            await session.close()
            raise ValueError("refusing self connection")
        session._start_reader()
        return session
    except BaseException:
        if session is not None:
            try:
                await session.close()
            except Exception:
                pass
        await _close_writer_safely(writer)
        raise


async def _dial_identified_endpoint(
    endpoint_str: str,
    identity_priv,
    signing_key,
    trust_store,
    expected_fingerprint=None,
) -> "Session":
    """Dial a peer and make identity_info the first application frame."""
    session = await _dial_endpoint(
        endpoint_str,
        identity_priv,
        signing_key,
        trust_store,
        expected_fingerprint,
    )
    try:
        if not await _send_local_identity_info(session):
            raise RuntimeError("local identity is not configured")
        return session
    except BaseException:
        await _invalidate_session(session)
        raise


def _operation_lock(peer_name: str, expected_fingerprint=None):
    """Serialize session creation and ratchet traffic for one authenticated peer."""
    key = expected_fingerprint or peer_name.casefold()
    lock = peer_operation_locks.get(key)
    if lock is None:
        lock = asyncio.Lock()
        peer_operation_locks[key] = lock
    return lock


async def _send_message_async(peer_name: str, endpoint: str, body: str, expected_fingerprint=None) -> bool:
    async with _operation_lock(peer_name, expected_fingerprint):
        return await _send_message_unlocked(peer_name, endpoint, body, expected_fingerprint)


async def _invalidate_session(session) -> None:
    if session is None:
        return
    fp = getattr(session, "peer_fingerprint", None)
    if fp and active_sessions.get(fp) is session:
        active_sessions.pop(fp, None)
    try:
        await session.close()
    except Exception:
        pass


async def _establish_session_async(peer_name: str, endpoint: str, expected_fingerprint=None) -> "Session":
    identity_priv = load_or_create_identity()
    signing_key = load_or_create_signing_identity()
    trust_store = TrustStore()

    endpoints = [e.strip() for e in endpoint.split(",") if e.strip()]
    last_err = None
    session = None
    connected_endpoint = ""

    for ep in endpoints:
        try:
            session = await _dial_identified_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
            connected_endpoint = ep
            print(f"Connected to {peer_name} via {ep}")
            break
        except Exception as err:
            print(f"Failed to connect to {peer_name} via {ep}: {err}")
            last_err = err

    if session is None and expected_fingerprint:
        for ep in await _resolve_peer_endpoints_async(expected_fingerprint):
            if ep in endpoints:
                continue
            try:
                session = await _dial_identified_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
                connected_endpoint = ep
                print(f"Reconnected to {peer_name} via fresh discovery endpoint {ep}")
                break
            except Exception as err:
                last_err = err
                
    if session is None:
        raise ConnectionError(f"All endpoints failed for {peer_name}. Last error: {last_err}")

    fp = session.peer_fingerprint
    peer_fingerprint_to_name[fp] = peer_name
    registered = await _register_authenticated_session(session, fp, initiator=True)
    if registered is not session:
        session = registered
        print(f"Reused preferred Double Ratchet session to {peer_name} (Fingerprint: {fp})")
    else:
        asyncio.create_task(_read_loop(session, peer_name, fp))

    print(f"Established Double Ratchet session to {peer_name} (Fingerprint: {fp})")

    if not _notify_session_established(
        peer_name, fp, connected_endpoint, _transport_for_endpoint(connected_endpoint)
    ):
        await _invalidate_session(session)
        raise ValueError(f"Android rejected fingerprint {fp} for nickname '{peer_name}'")

    return session


async def _send_message_unlocked(peer_name: str, endpoint: str, body: str, expected_fingerprint=None) -> bool:
    session = _session_for_peer(peer_name, expected_fingerprint)
    was_cached = session is not None and session.is_online
    try:
        if not session or not session.is_online:
            if session and not session.is_online:
                try:
                    asyncio.create_task(session.close())
                except Exception:
                    pass
                if session.peer_fingerprint:
                    active_sessions.pop(session.peer_fingerprint, None)
            session = await _establish_session_async(peer_name, endpoint, expected_fingerprint)
            was_cached = False

        try:
            await session.send_chat(body)
            return True
        except (ConnectionError, OSError) as send_err:
            if was_cached:
                print(f"Cached session to {peer_name} failed on write: {send_err}. Retrying with a new connection...")
                await _invalidate_session(session)
                session = await _establish_session_async(peer_name, endpoint, expected_fingerprint)
                await session.send_chat(body)
                return True
            else:
                raise
    except asyncio.CancelledError:
        await _invalidate_session(session)
        raise
    except Exception as e:
        await _invalidate_session(session)
        print(f"Error in _send_message_async to {peer_name}:", e)
        traceback.print_exc()
        return False


def send_p2p_file(peer_name: str, endpoint: str, file_path: str, expected_fingerprint=None, message_id="") -> bool:
    """
    Synchronous entry point called from Kotlin to send an encrypted file/photo via Double Ratchet.
    """
    global loop
    if not loop:
        print("Asyncio loop not running, starting listener loop first")
        start_p2p_listener(listener_port)
        import time
        time.sleep(1)
        if not loop:
            return False
            
    future = asyncio.run_coroutine_threadsafe(
        _send_file_async(peer_name, endpoint, file_path, expected_fingerprint, message_id),
        loop
    )
    try:
        return future.result(timeout=60) # Allow up to 1 minute for larger files
    except Exception as e:
        future.cancel()
        print(f"Failed to send file to {peer_name} via python bridge:", e)
        traceback.print_exc()
        return False

async def _send_file_async(peer_name: str, endpoint: str, file_path: str, expected_fingerprint=None, message_id="") -> bool:
    async with _operation_lock(peer_name, expected_fingerprint):
        return await _send_file_unlocked(peer_name, endpoint, file_path, expected_fingerprint, message_id)


async def _send_file_unlocked(peer_name: str, endpoint: str, file_path: str, expected_fingerprint=None, message_id="") -> bool:
    session = _session_for_peer(peer_name, expected_fingerprint)
    was_cached = session is not None and session.is_online
    try:
        if not session or not session.is_online:
            if session and not session.is_online:
                try:
                    asyncio.create_task(session.close())
                except Exception:
                    pass
                if session.peer_fingerprint:
                    active_sessions.pop(session.peer_fingerprint, None)
            session = await _establish_session_async(peer_name, endpoint, expected_fingerprint)
            was_cached = False

        # Encrypt and send the file
        from messenger.core.crypto import encrypt_file_in_chunks
        import base64
        import os
        from datetime import datetime, timezone
        from pathlib import Path
        
        print("Starting chunked encryption for an outgoing file")
        (
            chunk_iterator,
            file_key,
            file_nonce_prefix,
            file_size,
            num_chunks,
            file_hash,
        ) = encrypt_file_in_chunks(file_path)
        
        file_id = os.urandom(12)
        meta = {
            "type": "file_meta",
            "file_id": base64.b64encode(file_id).decode(),
            "file_name": Path(file_path).name,
            "file_size": file_size,
            "num_chunks": num_chunks,
            "file_hash": base64.b64encode(file_hash).decode(),
            "file_key": base64.b64encode(file_key).decode(),
            "file_nonce_prefix": base64.b64encode(file_nonce_prefix).decode(),
            "timestamp": int(datetime.now(timezone.utc).timestamp()),
        }
        if message_id:
            meta["message_id"] = str(message_id)[:128]
        
        print(f"Sending file metadata envelope ({file_size} bytes)")
        try:
            await session.send_reliable(meta)
        except (ConnectionError, OSError) as send_err:
            if was_cached:
                print(f"Cached session to {peer_name} failed on file meta write: {send_err}. Retrying with a new connection...")
                await _invalidate_session(session)
                session = await _establish_session_async(peer_name, endpoint, expected_fingerprint)
                was_cached = False
                await session.send_reliable(meta)
            else:
                raise
        
        print("Sending file chunks...")
        for chunk_index, encrypted_chunk in chunk_iterator:
            payload = {
                "type": "file_chunk",
                "file_id": base64.b64encode(file_id).decode(),
                "chunk_index": chunk_index,
                "payload": base64.b64encode(encrypted_chunk).decode(),
            }
            await session.send_reliable(payload)
            
        print(f"File successfully transmitted to {peer_name}")
        return True
    except asyncio.CancelledError:
        await _invalidate_session(session)
        raise
    except Exception as e:
        await _invalidate_session(session)
        print(f"Error in _send_file_async to {peer_name}:", e)
        traceback.print_exc()
        return False

def _clear_account_runtime_state():
    global message_listener_callback, session_listener_callback
    global local_identity_nickname, local_identity_fingerprint
    global local_yggdrasil_available

    active_sessions.clear()
    peer_operation_locks.clear()
    peer_fingerprint_to_name.clear()
    incoming_file_starts.clear()
    for transfer_key in list(incoming_files):
        _discard_incoming_file(transfer_key)
    message_listener_callback = None
    session_listener_callback = None
    local_identity_nickname = ""
    local_identity_fingerprint = ""
    local_yggdrasil_available = False
    local_announced_ips.clear()
    public_address_observations.clear()
    tracker_diagnostics.clear()


async def _shutdown_runtime():
    """Close account-bound state on the listener loop before its key is erased."""
    global _listener_task

    sessions = list({id(session): session for session in active_sessions.values()}.values())
    if sessions:
        await asyncio.gather(
            *[session.close() for session in sessions if hasattr(session, "close")],
            return_exceptions=True,
        )
    _clear_account_runtime_state()

    listener_task = _listener_task
    if listener_task is not None and listener_task is not asyncio.current_task():
        listener_task.cancel()
        await asyncio.gather(listener_task, return_exceptions=True)


def shutdown_all_sessions(timeout_seconds=5.0):
    """Synchronously stop the listener and erase every in-memory account secret."""
    global loop, _runtime_shutdown_requested
    print("Shutting down P2P runtime and clearing account-bound caches...")
    try:
        from messenger.core.upnp import stop_upnp
        stop_upnp()
    except Exception as upnp_err:
        print("[UPNP] Failed to trigger stop_upnp:", upnp_err)

    with _runtime_lock:
        runtime_loop = loop
        runtime_thread = _listener_thread
        stopped = _listener_stopped

    if runtime_thread is threading.current_thread():
        print("Refusing synchronous P2P shutdown from the listener thread")
        return False

    if runtime_loop is not None and runtime_loop.is_running():
        try:
            with _runtime_lock:
                _runtime_shutdown_requested = True
            future = asyncio.run_coroutine_threadsafe(_shutdown_runtime(), runtime_loop)
            future.result(timeout=max(0.1, float(timeout_seconds)))
            runtime_loop.call_soon_threadsafe(runtime_loop.stop)
        except Exception as exc:
            print("Failed to stop P2P runtime cleanly:", exc)
            return False
    else:
        _clear_account_runtime_state()

    if stopped is not None:
        stopped.wait(timeout=max(0.1, float(timeout_seconds)))
    if runtime_thread is not None and runtime_thread.is_alive():
        runtime_thread.join(timeout=max(0.1, float(timeout_seconds)))
    clean = runtime_thread is None or not runtime_thread.is_alive()
    print(f"P2P runtime shutdown complete: {clean}")
    return clean

def close_peer_session(peer_name: str, expected_fingerprint=None) -> bool:
    """
    Closes the session for the specified peer and removes it from active_sessions.
    """
    global loop, active_sessions
    if not loop:
        return False
    session = _session_for_peer(peer_name, expected_fingerprint)
    if session:
        try:
            if hasattr(session, "close"):
                if loop and loop.is_running():
                    asyncio.run_coroutine_threadsafe(session.close(), loop)
        except Exception:
            pass
        if session.peer_fingerprint:
            active_sessions.pop(session.peer_fingerprint, None)
        return True
    return False

def get_active_peers_list() -> str:
    """Returns a comma-separated list of active peer names."""
    global active_sessions, peer_fingerprint_to_name
    peers = set()
    for fp, session in active_sessions.items():
        if session and session.is_online:
            if len(fp) >= 30:
                name = peer_fingerprint_to_name.get(fp, f"Peer ({fp[:8]})")
                peers.add(name)
    return ",".join(sorted(peers))


def get_active_peer_fingerprints_list() -> str:
    """Return authenticated identities for live sessions.

    Fingerprints are stable while nicknames may still be awaiting an
    ``identity_info`` message after reconnect. Android uses this list to keep
    its main-screen online indicators accurate without opening a chat.
    """
    return ",".join(sorted(
        fp for fp, session in active_sessions.items()
        if session and session.is_online and fp
    ))


async def _probe_active_peer_fingerprints() -> list[str]:
    async def probe(peer_fingerprint, session):
        try:
            # Every reliable frame is acknowledged by the authenticated remote
            # session before it reaches the application read loop.
            await session.send_reliable({"type": "heartbeat"})
            return peer_fingerprint
        except asyncio.CancelledError:
            # Reader failure cancels pending ACK futures and marks the session
            # offline. Do not invalidate a healthy session merely because the
            # bridge-level probe itself was cancelled.
            if not session.is_online:
                await _invalidate_session(session)
                return None
            raise
        except Exception:
            await _invalidate_session(session)
            return None

    results = await asyncio.gather(*(
        probe(fp, session)
        for fp, session in list(active_sessions.items())
        if session and session.is_online and fp
    ))
    return sorted(fp for fp in results if fp)


def probe_active_peer_fingerprints_list() -> str:
    """Actively confirm live sessions and return their fingerprints."""
    if not loop or not loop.is_running():
        return ""
    future = asyncio.run_coroutine_threadsafe(_probe_active_peer_fingerprints(), loop)
    try:
        return ",".join(future.result(timeout=10.0))
    except Exception:
        future.cancel()
        # A bridge-level timeout should not itself make healthy peers appear
        # offline. The next maintenance pass will retry the probe.
        return get_active_peer_fingerprints_list()


def remember_peer_name(peer_fingerprint: str, peer_name: str) -> bool:
    """Apply Android's persisted authenticated fingerprint-to-name mapping."""
    if not peer_fingerprint or not peer_name or peer_name.startswith("Peer ("):
        return False
    peer_fingerprint_to_name[peer_fingerprint] = peer_name
    session = active_sessions.get(peer_fingerprint)
    if session is not None:
        session.peer_label = peer_name
    return True

def reconnect_peer_session(peer_name: str, endpoint: str, expected_fingerprint=None) -> bool:
    """
    Closes any existing session for the peer, and starts a new connection attempt in the background/asyncio loop.
    """
    global loop, active_sessions
    if not loop:
        print("Cannot reconnect: loop is not running")
        return False
    
    if is_fingerprint_rejected(peer_name, expected_fingerprint):
        print(f"[RECONNECT] Suppressing reconnect to '{peer_name}' (fingerprint change pending approval)")
        return False

    # Close old session
    session = _session_for_peer(peer_name, expected_fingerprint)
    if session and session.is_online:
        print(f"[RECONNECT] Session with {peer_name} is already online; keeping it")
        return True
    if session:
        try:
            if hasattr(session, "close"):
                asyncio.run_coroutine_threadsafe(session.close(), loop)
        except Exception:
            pass
        if session.peer_fingerprint:
            active_sessions.pop(session.peer_fingerprint, None)

    # Establish new session by dialing in the background
    async def _reconnect_async():
        try:
            identity_priv = load_or_create_identity()
            signing_key = load_or_create_signing_identity()
            trust_store = TrustStore()
            endpoints = [e.strip() for e in endpoint.split(",") if e.strip()]
            
            connected_session = None
            connected_endpoint = ""
            for ep in endpoints:
                try:
                    connected_session = await _dial_identified_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
                    connected_endpoint = ep
                    print(f"[RECONNECT] Successfully connected to {peer_name} via {ep}")
                    break
                except Exception as err:
                    print(f"[RECONNECT] Failed to connect to {peer_name} via {ep}: {err}")
            
            if connected_session is None and expected_fingerprint:
                for ep in await _resolve_peer_endpoints_async(expected_fingerprint):
                    if ep in endpoints:
                        continue
                    try:
                        connected_session = await _dial_identified_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
                        connected_endpoint = ep
                        print(f"[RECONNECT] Reconnected to {peer_name} via fresh discovery endpoint {ep}")
                        break
                    except Exception:
                        pass

            if connected_session:
                fp = connected_session.peer_fingerprint
                peer_fingerprint_to_name[fp] = peer_name
                registered = await _register_authenticated_session(
                    connected_session, fp, initiator=True
                )
                if registered is not connected_session:
                    connected_session = registered
                else:
                    asyncio.create_task(_read_loop(connected_session, peer_name, fp))
                
                if not _notify_session_established(
                    peer_name, fp, connected_endpoint, _transport_for_endpoint(connected_endpoint)
                ):
                    await _invalidate_session(connected_session)
                    print(f"Android rejected fingerprint {fp} for nickname '{peer_name}'")
                    return
                return True
            return False
        except Exception as e:
            print(f"[RECONNECT] Error during reconnect sequence: {e}")
            return False

    asyncio.run_coroutine_threadsafe(_reconnect_async(), loop)
    return True

def is_upnp_mapped() -> bool:
    try:
        from messenger.core.upnp import _upnp_mapping
        return _upnp_mapping is not None
    except Exception:
        return False

def get_upnp_details_json() -> str:
    import json
    try:
        from messenger.core.upnp import get_upnp_status
        return json.dumps(get_upnp_status())
    except Exception as e:
        print("[UPNP_BRIDGE] Error getting details json:", e)
        return json.dumps({"mapped": False, "error": str(e)})

def trigger_upnp_reopen() -> bool:
    global listener_port
    try:
        from messenger.core.upnp import stop_upnp, setup_upnp_in_background
        print(f"[UPNP_BRIDGE] Re-opening UPnP port mapping for port {listener_port}")
        stop_upnp()
        setup_upnp_in_background(listener_port)
        return True
    except Exception as e:
        print("[UPNP_BRIDGE] Re-open failed:", e)
        return False


