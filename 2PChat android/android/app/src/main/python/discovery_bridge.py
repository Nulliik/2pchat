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
from pathlib import Path
from datetime import datetime, timezone

from messenger.core.discovery_manager import get_discovery_provider
from messenger.core.tracker_catalog import get_tracker_by_name
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
    logger.info(msg)

active_sessions = {}
peer_fingerprint_to_name = {}
incoming_files = {}
MAX_INCOMING_FILES = 16
INCOMING_FILE_TTL_SECONDS = 120
MAX_ENCRYPTED_CHUNK_SIZE = 1024 * 1024
MAX_CONCURRENT_HANDSHAKES = 10
tracker_diagnostics = {}
local_identity_nickname = ""
local_identity_fingerprint = ""
local_yggdrasil_available = False

# Kotlin notification callbacks
message_listener_callback = None
session_listener_callback = None
loop = None

# Track which Yggdrasil listener is running
_ygg_listener_running = False
listener_port = 50001
ipv4_enabled = True
CLEARNET_TRACKERS = (
    "OpenTrackr HTTP",
    "Torrent.eu.org UDP",
    "Open Stealth UDP",
)
YGG_TRACKERS = (
    "Yggdrasil-only HTTP",
    "Yggdrasil-only UDP",
)


def _session_for_peer(peer_name: str, expected_fingerprint: str | None = None):
    """Resolve a live session through authenticated identity, not a name key."""
    if expected_fingerprint:
        return active_sessions.get(expected_fingerprint)
    matches = {
        fp: active_sessions.get(fp)
        for fp, name in peer_fingerprint_to_name.items()
        if name == peer_name and active_sessions.get(fp) is not None
    }
    return next(iter(matches.values())) if len(matches) == 1 else None


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
    # Prefer IPv4 first for the default direct path, then try IPv6/Yggdrasil.
    return (0 if not endpoint_str.startswith("[") else 1, endpoint_str)


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
    names = []
    requested = (primary_tracker, *CLEARNET_TRACKERS)
    if local_yggdrasil_available:
        requested = (*requested, *YGG_TRACKERS)
    for tracker_name in requested:
        if tracker_name and tracker_name not in names:
            names.append(tracker_name)
    return names


def _announce_tracker_names(endpoints: list[PeerEndpoint]) -> list[str]:
    names = list(CLEARNET_TRACKERS)
    if _has_ipv6_endpoint(endpoints):
        names.extend(YGG_TRACKERS)
    return names


def _set_tracker_diagnostic(tracker_name: str, operation: str, status: str) -> None:
    tracker_diagnostics.setdefault(tracker_name, {})[operation] = status


def _same_nickname(left: str, right: str) -> bool:
    """Match display names without making case or repeated spaces significant."""
    return " ".join(left.strip().casefold().split()) == " ".join(right.strip().casefold().split())


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
            message = await asyncio.wait_for(session.receive_message(), timeout=3.0)
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
            return {
                "endpoint": endpoint,
                "verified": False,
                "verification_reason": "live identity did not match the requested name",
            }
    except Exception as exc:
        print(f"Live peer verification failed for {endpoint}: {exc}")
        reason = str(exc).strip() or type(exc).__name__
        return {
            "endpoint": endpoint,
            "verified": False,
            "verification_reason": reason,
            "is_self": "refusing self connection" in reason.lower(),
        }
    finally:
        if session is not None:
            await session.close()

def resolve_peers(
    nickname: str,
    shared_code: str,
    tracker_name: str = "OpenTrackr HTTP",
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
            tracker = get_tracker_by_name(t_name)
            provider = get_discovery_provider(
                tracker.discovery_scheme,
                tracker_url=tracker.announce_url,
                peer_port=50001,
                transport="direct"
            )
            result = await provider.resolve(nickname, shared_code)
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
            _set_tracker_diagnostic(t_name, "resolve_rtt_ms", str(round((time.monotonic() - started) * 1000)))
            print(f"Error resolving peers from {t_name}: {e}")
            return []

    async def _resolve_all():
        tasks = []
        for t_name in _resolve_tracker_names(tracker_name):
            tasks.append(_query_async(t_name))
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
            ep_str = _format_endpoint(ep.host, ep.port)
            key = ep_str
            if key not in seen_ep:
                seen_ep.add(key)
                all_endpoints.append(ep_str)

    all_endpoints.sort(key=_endpoint_sort_key)
    print(f"Resolved {len(all_endpoints)} endpoints from trackers for nickname '{nickname}': {all_endpoints}")
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
            tracker = get_tracker_by_name(tracker_name)
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

    batches = await asyncio.gather(
        *[_query(name) for name in _resolve_tracker_names("OpenTrackr HTTP")],
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
                endpoints.append(_format_endpoint(endpoint.host, endpoint.port))
    result = sorted(dict.fromkeys(endpoints), key=_endpoint_sort_key)
    return result if ipv4_enabled else [ep for ep in result if not _is_ipv4_endpoint(ep)]


def announce_peer_endpoints(nickname: str, fingerprint: str, endpoints_json: str, port: int) -> bool:
    """
    Announce all current IPv4 and Yggdrasil/global IPv6 endpoints across the tracker set.
    """
    import urllib.error
    global local_identity_nickname, local_identity_fingerprint, local_yggdrasil_available

    try:
        addresses = json.loads(endpoints_json)
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

        async def _announce_tracker(tracker_name: str):
            started = time.monotonic()
            tracker = get_tracker_by_name(tracker_name)
            provider = get_discovery_provider(
                tracker.discovery_scheme,
                tracker_url=tracker.announce_url,
                peer_port=port,
                transport="direct",
            )
            variants = [
                (nickname, nickname),
            ]
            if fingerprint and len(fingerprint) > 10:
                variants.append((nickname, fingerprint))
                variants.append((fingerprint, fingerprint))

            tasks = [
                provider.announce(nick, shared_code, transport="direct", endpoints=endpoints)
                for nick, shared_code in variants
            ]
            results = await asyncio.gather(*tasks, return_exceptions=True)
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
            results = await asyncio.gather(
                *[_announce_tracker(tracker_name) for tracker_name in tracker_names],
                return_exceptions=True,
            )
            total_success = 0
            for tracker_name, result in zip(tracker_names, results):
                if isinstance(result, Exception):
                    print(f"Tracker announce task crashed for {tracker_name}: {result}")
                    continue
                total_success += result
                print(f"Tracker {tracker_name} accepted {result} announce registrations.")
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


def announce_peer(nickname: str, fingerprint: str, host: str, port: int, tracker_name: str = "OpenTrackr HTTP"):
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

def start_p2p_listener(port=50001):
    """
    Start the background asyncio event loop and dual-stack listener thread.
    Listens on both 0.0.0.0 (IPv4) and :: (IPv6/Yggdrasil) simultaneously.
    """
    global loop, listener_port
    listener_port = port
    if loop and loop.is_running():
        print(f"P2P listener already running on port {port}, skipping duplicate start")
        return

    try:
        from messenger.core.upnp import setup_upnp_in_background
        setup_upnp_in_background(port)
    except Exception as upnp_err:
        print("[UPNP] Failed to trigger background setup:", upnp_err)

    def run():
        global loop
        try:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            loop.run_until_complete(_listen_loop_dual(port))
        except Exception as e:
            print("P2P listener event loop crashed:", e)
            traceback.print_exc()

    t = threading.Thread(target=run, daemon=True)
    t.start()
    print(f"P2P Listener background thread started on port {port} (IPv4 + IPv6)")


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



async def _handle_incoming(reader, writer, identity_priv, signing_key, trust_store):
    session = None
    try:
        peername = writer.get_extra_info("peername")
        if not ipv4_enabled and peername and ":" not in str(peername[0]):
            writer.close()
            await writer.wait_closed()
            print(f"Rejected incoming IPv4 connection from {peername[0]}: IPv4 is disabled")
            return
        session = Session(
            reader,
            writer,
            identity_priv=identity_priv,
            signing_key=signing_key,
            trust_store=trust_store,
        )
        await asyncio.wait_for(session._exchange_keys(initiator=False), timeout=5.0)
        if session.peer_fingerprint == fingerprint(identity_priv.public_key):
            await session.close()
            print("Rejected self-connection on the local listener")
            return
        session._start_reader()
        
        fp = session.peer_fingerprint
        peer_name = peer_fingerprint_to_name.get(fp, f"Peer ({fp[:8]})")
        
        active_sessions[fp] = session
        
        print(f"Accepted Double Ratchet session from {peer_name} (Fingerprint: {fp})")
        
        peername = writer.get_extra_info('peername')
        remote_ep = ""
        remote_transport = "Direct P2P"
        if peername:
            remote_transport = "Yggdrasil" if ":" in str(peername[0]) else "Direct P2P"

        if session_listener_callback:
            try:
                session_listener_callback.onSessionEstablished(peer_name, fp, remote_ep, remote_transport)
            except Exception as cb_err:
                print("Error invoking session listener callback:", cb_err)
                
        asyncio.create_task(_read_loop(session, peer_name, fp))
    except Exception as e:
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
            mtype = msg.get("type")
            if mtype == "identity_info":
                # Remote peer announced their real nickname — update our mappings
                real_name = msg.get("nickname", "").strip()
                claimed_fp = msg.get("fingerprint", fp)
                # The authenticated session fingerprint is authoritative. A
                # peer-controlled identity_info payload must not be able to
                # overwrite another contact's identity mapping.
                remote_fp = fp if claimed_fp != fp else claimed_fp
                if claimed_fp != fp:
                    print(f"Ignored mismatched identity_info fingerprint from {fp}")
                if real_name and real_name != peer_name:
                    print(f"Peer renamed: '{peer_name}' → '{real_name}' (fp={remote_fp})")
                    peer_fingerprint_to_name[remote_fp] = real_name
                    peer_fingerprint_to_name[fp] = real_name
                    # Re-register session under real name
                    # Notify Kotlin so UI can open/rename the chat
                    if session_listener_callback:
                        try:
                            peername = session.writer.get_extra_info('peername') if hasattr(session, 'writer') else None
                            advertised_port = msg.get("listen_port")
                            if isinstance(advertised_port, int) and 1 <= advertised_port <= 65535 and peername:
                                remote_ep = _format_endpoint(peername[0], advertised_port)
                            else:
                                remote_ep = ""
                            remote_transport = (
                                "Yggdrasil" if peername and ":" in str(peername[0]) else "Direct P2P"
                            )
                            session_listener_callback.onSessionEstablished(
                                real_name, remote_fp, remote_ep, remote_transport
                            )
                        except Exception as cb_err:
                            print("Error invoking session listener on identity_info:", cb_err)
                    # Update loop variables so cleanup is correct
                    peer_name = real_name
                    fp = remote_fp
                continue
            elif mtype == "identity_probe":
                # A search result is only shown after this authenticated reply.
                # Do not invent a name: nodes that have not configured one
                # cannot be discovered by nickname.
                if local_identity_nickname:
                    await session.send_reliable({
                        "type": "identity_info",
                        "nickname": local_identity_nickname,
                        "fingerprint": fingerprint(load_or_create_identity().public_key),
                        "listen_port": listener_port,
                    })
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
                    if file_size < 0:
                        print(f"Rejected file transfer: invalid size {file_size}")
                        continue
                    if num_chunks < 0 or (file_size > 0 and num_chunks == 0):
                        print(f"Rejected file transfer: invalid chunk count {num_chunks}")
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
                        temp = tempfile.NamedTemporaryFile(prefix="2pchat-recv-", suffix=".part", delete=False)
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
                        nonce = state["nonce_prefix"] + chunk_index.to_bytes(8, "big")
                        plaintext = state["box"].decrypt(payload, nonce)
                    except Exception as decrypt_err:
                        _discard_incoming_file(transfer_key)
                        print(f"Rejected unauthenticated file chunk: {decrypt_err}")
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
                            
                            mime, _ = mimetypes.guess_type(file_name)
                            if not mime:
                                mime = "application/octet-stream"
                                
                            file_notification = {
                                "type": "file",
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
                            print(f"Failed to decrypt incoming file {file_name}: {decrypt_err}")
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
                session_listener_callback.onSessionClosed(peer_name)
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
        return future.result(timeout=15)
    except Exception as e:
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
    try:
        reader, writer = await asyncio.wait_for(
            transport_connect("direct", host, port), timeout=5.0
        )
        session = Session(reader, writer, identity_priv=identity_priv,
                          signing_key=signing_key, trust_store=trust_store)
        await asyncio.wait_for(session._exchange_keys(initiator=True, expected_fingerprint=expected_fingerprint), timeout=5.0)
        if session.peer_fingerprint == fingerprint(identity_priv.public_key):
            await session.close()
            raise ValueError("refusing self connection")
        session._start_reader()
        return session
    except Exception:
        await _close_writer_safely(writer)
        raise


async def _send_message_async(peer_name: str, endpoint: str, body: str, expected_fingerprint=None) -> bool:
    try:
        session = _session_for_peer(peer_name, expected_fingerprint)
        if not session or not session.is_online:
            # Close the old dead session explicitly so its _read_loop finally-block
            # doesn't race-delete the new session we're about to create.
            if session and not session.is_online:
                try:
                    asyncio.create_task(session.close())
                except Exception:
                    pass
                if session.peer_fingerprint:
                    active_sessions.pop(session.peer_fingerprint, None)

            identity_priv = load_or_create_identity()
            signing_key = load_or_create_signing_identity()
            trust_store = TrustStore()

            # Support comma-separated list of endpoints for fallback
            endpoints = [e.strip() for e in endpoint.split(",") if e.strip()]
            last_err = None
            session = None

            for ep in endpoints:
                try:
                    session = await _dial_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
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
                        session = await _dial_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
                        print(f"Reconnected to {peer_name} via fresh discovery endpoint {ep}")
                        break
                    except Exception as err:
                        last_err = err
            if session is None:
                raise ConnectionError(f"All endpoints failed for {peer_name}. Last error: {last_err}")

            fp = session.peer_fingerprint
            peer_fingerprint_to_name[fp] = peer_name
            active_sessions[fp] = session

            print(f"Established Double Ratchet session to {peer_name} (Fingerprint: {fp})")

            asyncio.create_task(_read_loop(session, peer_name, fp))

            if session_listener_callback:
                try:
                    session_listener_callback.onSessionEstablished(
                        peer_name, fp, ep, _transport_for_endpoint(ep)
                    )
                except Exception:
                    pass

            # Send identity_info so the remote side learns our nickname immediately.
            try:
                local_identity = load_or_create_identity()
                local_fp = local_identity_fingerprint or fingerprint(local_identity.public_key)
                local_name = local_identity_nickname
                if local_name:
                    await session.send_reliable({
                        "type": "identity_info",
                        "nickname": local_name,
                        "fingerprint": local_fp,
                        "listen_port": listener_port,
                    })
            except Exception as id_err:
                print(f"Could not send identity_info to {peer_name}: {id_err}")

        # Send the chat message
        await session.send_chat(body)
        return True
    except Exception as e:
        print(f"Error in _send_message_async to {peer_name}:", e)
        traceback.print_exc()
        return False


def send_p2p_file(peer_name: str, endpoint: str, file_path: str, expected_fingerprint=None) -> bool:
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
        _send_file_async(peer_name, endpoint, file_path, expected_fingerprint),
        loop
    )
    try:
        return future.result(timeout=60) # Allow up to 1 minute for larger files
    except Exception as e:
        print(f"Failed to send file to {peer_name} via python bridge:", e)
        traceback.print_exc()
        return False

async def _send_file_async(peer_name: str, endpoint: str, file_path: str, expected_fingerprint=None) -> bool:
    try:
        session = _session_for_peer(peer_name, expected_fingerprint)
        if not session or not session.is_online:
            identity_priv = load_or_create_identity()
            signing_key = load_or_create_signing_identity()
            trust_store = TrustStore()

            # Support comma-separated list of endpoints for fallback
            endpoints = [e.strip() for e in endpoint.split(",") if e.strip()]
            last_err = None
            session = None

            for ep in endpoints:
                try:
                    session = await _dial_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
                    print(f"Connected to {peer_name} via {ep} for file sending")
                    break
                except Exception as err:
                    print(f"Failed to connect to {peer_name} via {ep} for file sending: {err}")
                    last_err = err

            if session is None and expected_fingerprint:
                for ep in await _resolve_peer_endpoints_async(expected_fingerprint):
                    if ep in endpoints:
                        continue
                    try:
                        session = await _dial_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
                        break
                    except Exception as err:
                        last_err = err
            if session is None:
                raise ConnectionError(f"All endpoints failed for {peer_name} file sending. Last error: {last_err}")

            fp = session.peer_fingerprint
            peer_fingerprint_to_name[fp] = peer_name
            active_sessions[fp] = session

            print(f"Established Double Ratchet session to {peer_name} (Fingerprint: {fp})")

            asyncio.create_task(_read_loop(session, peer_name, fp))

            if session_listener_callback:
                try:
                    session_listener_callback.onSessionEstablished(
                        peer_name, fp, ep, _transport_for_endpoint(ep)
                    )
                except Exception:
                    pass

            try:
                local_identity = load_or_create_identity()
                local_fp = local_identity_fingerprint or fingerprint(local_identity.public_key)
                local_name = local_identity_nickname
                if local_name:
                    await session.send_reliable({
                        "type": "identity_info",
                        "nickname": local_name,
                        "fingerprint": local_fp,
                        "listen_port": listener_port,
                    })
            except Exception as id_err:
                print(f"Could not send identity_info to {peer_name} during file transfer: {id_err}")
                     
        # Encrypt and send the file
        from messenger.core.crypto import encrypt_file_in_chunks
        import base64
        import os
        from datetime import datetime, timezone
        from pathlib import Path
        
        print(f"Starting chunked encryption for file: {file_path}")
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
        
        print(f"Sending file_meta envelope for file_id {meta['file_id']} ({meta['file_name']}, {file_size} bytes)")
        await session.send_reliable(meta)
        
        print("Sending file chunks...")
        for chunk_index, encrypted_chunk in chunk_iterator:
            payload = {
                "type": "file_chunk",
                "file_id": base64.b64encode(file_id).decode(),
                "chunk_index": chunk_index,
                "payload": base64.b64encode(encrypted_chunk).decode(),
            }
            await session.send_reliable(payload)
            
        print(f"File {file_path} successfully transmitted to {peer_name}!")
        return True
    except Exception as e:
        print(f"Error in _send_file_async to {peer_name}:", e)
        traceback.print_exc()
        return False

def shutdown_all_sessions():
    """
    Close all active P2P connections and clear session caches (e.g. on duress wipe).
    """
    global active_sessions, incoming_files, loop
    print("Shutdown all active sessions and clearing caches...")
    try:
        from messenger.core.upnp import stop_upnp
        stop_upnp()
    except Exception as upnp_err:
        print("[UPNP] Failed to trigger stop_upnp:", upnp_err)

    for fp, session in list(active_sessions.items()):
        try:
            if hasattr(session, "close"):
                if loop and loop.is_running():
                    asyncio.run_coroutine_threadsafe(session.close(), loop)
        except Exception as e:
            print("Error closing session during shutdown:", e)
    active_sessions.clear()
    for transfer_key in list(incoming_files):
        _discard_incoming_file(transfer_key)

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

def reconnect_peer_session(peer_name: str, endpoint: str, expected_fingerprint=None) -> bool:
    """
    Closes any existing session for the peer, and starts a new connection attempt in the background/asyncio loop.
    """
    global loop, active_sessions
    if not loop:
        print("Cannot reconnect: loop is not running")
        return False
    
    # Close old session
    session = _session_for_peer(peer_name, expected_fingerprint)
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
                    connected_session = await _dial_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
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
                        connected_session = await _dial_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint)
                        connected_endpoint = ep
                        print(f"[RECONNECT] Reconnected to {peer_name} via fresh discovery endpoint {ep}")
                        break
                    except Exception:
                        pass

            if connected_session:
                fp = connected_session.peer_fingerprint
                peer_fingerprint_to_name[fp] = peer_name
                active_sessions[fp] = connected_session
                
                # Trigger Kotlin session listener
                if session_listener_callback:
                    try:
                        session_listener_callback.onSessionEstablished(
                            peer_name, fp, connected_endpoint, _transport_for_endpoint(connected_endpoint)
                        )
                    except Exception as callback_err:
                        print("Error triggering Kotlin session listener on reconnect:", callback_err)
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


