import asyncio
import sys
import threading
import json
import traceback
import uuid
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
tracker_diagnostics = {}
local_identity_nickname = ""
local_identity_fingerprint = ""

# Kotlin notification callbacks
message_listener_callback = None
session_listener_callback = None
loop = None

# Track which Yggdrasil listener is running
_ygg_listener_running = False
CLEARNET_TRACKERS = (
    "OpenTrackr HTTP",
    "Torrent.eu.org UDP",
    "Open Stealth UDP",
)
YGG_TRACKERS = (
    "Yggdrasil-only HTTP",
    "Yggdrasil-only UDP",
)


def _format_endpoint(host: str, port: int) -> str:
    if ":" in host:
        return f"[{host}]:{port}"
    return f"{host}:{port}"


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
    for tracker_name in (primary_tracker, *CLEARNET_TRACKERS, *YGG_TRACKERS):
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

def resolve_peers(nickname: str, shared_code: str, tracker_name: str = "OpenTrackr HTTP"):
    """
    Resolve peers from multiple trackers to maximise endpoint coverage.
    Queries the specified HTTP tracker and the Torrent.eu.org UDP tracker
    (which carries IPv6/Yggdrasil endpoints) and deduplicates results.
    Returns a list of dicts with nickname, fingerprint, and endpoints.
    """
    import socket as _socket
    import urllib.error

    async def _query_async(t_name):
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
            return result
        except (urllib.error.URLError, OSError) as e:
            _set_tracker_diagnostic(t_name, "resolve", f"FAIL ({e})")
            print(f"Network error resolving peers from {t_name}: {e}")
            return []
        except Exception as e:
            _set_tracker_diagnostic(t_name, "resolve", f"FAIL ({e})")
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
    if all_endpoints:
        print(f"Resolved {len(all_endpoints)} unique endpoints for '{nickname}': {all_endpoints}")
    if all_endpoints:
        return [{"nickname": nickname, "fingerprint": "", "transport": "direct", "endpoints": all_endpoints}]
    return []


def announce_peer_endpoints(nickname: str, fingerprint: str, endpoints_json: str, port: int) -> bool:
    """
    Announce all current IPv4 and Yggdrasil/global IPv6 endpoints across the tracker set.
    """
    import urllib.error
    global local_identity_nickname, local_identity_fingerprint

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

    loop = asyncio.new_event_loop()
    try:
        asyncio.set_event_loop(loop)

        async def _announce_tracker(tracker_name: str):
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
    global loop
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
    try:
        # Binding to empty string ("") binds to all available IPv4 and IPv6 interfaces natively.
        async for reader, writer in transport_listen("direct", "", port):
            asyncio.create_task(_handle_incoming(reader, writer, identity_priv, signing_key, trust_store))
        print(f"Python P2P Server successfully listening on dual-stack port {port} (IPv4 + IPv6/Yggdrasil)")
    except Exception as e:
        print(f"Error in dual-stack P2P Server listen loop on port {port}: {e}")
        traceback.print_exc()



async def _handle_incoming(reader, writer, identity_priv, signing_key, trust_store):
    try:
        session = Session(
            reader,
            writer,
            identity_priv=identity_priv,
            signing_key=signing_key,
            trust_store=trust_store,
        )
        await asyncio.wait_for(session._exchange_keys(initiator=False), timeout=5.0)
        session._start_reader()
        
        fp = session.peer_fingerprint
        peer_name = peer_fingerprint_to_name.get(fp, f"Peer ({fp[:8]})")
        
        active_sessions[fp] = session
        active_sessions[peer_name] = session
        
        print(f"Accepted Double Ratchet session from {peer_name} (Fingerprint: {fp})")
        
        peername = writer.get_extra_info('peername')
        remote_ep = f"{peername[0]}:50001" if peername else ""

        if session_listener_callback:
            try:
                session_listener_callback.onSessionEstablished(peer_name, fp, remote_ep)
            except Exception as cb_err:
                print("Error invoking session listener callback:", cb_err)
                
        asyncio.create_task(_read_loop(session, peer_name, fp))
    except Exception as e:
        print("Error handling incoming connection:", e)
        traceback.print_exc()

async def _read_loop(session, peer_name, fp):
    global message_listener_callback
    try:
        while True:
            msg = await session.receive_message()
            mtype = msg.get("type")
            if mtype == "identity_info":
                # Remote peer announced their real nickname — update our mappings
                real_name = msg.get("nickname", "").strip()
                remote_fp = msg.get("fingerprint", fp)
                if real_name and real_name != peer_name:
                    print(f"Peer renamed: '{peer_name}' → '{real_name}' (fp={remote_fp})")
                    peer_fingerprint_to_name[remote_fp] = real_name
                    peer_fingerprint_to_name[fp] = real_name
                    # Re-register session under real name
                    active_sessions[real_name] = session
                    active_sessions.pop(peer_name, None)
                    # Notify Kotlin so UI can open/rename the chat
                    if session_listener_callback:
                        try:
                            peername = session.writer.get_extra_info('peername') if hasattr(session, 'writer') else None
                            remote_ep = f"{peername[0]}:50001" if peername else ""
                            session_listener_callback.onSessionEstablished(real_name, remote_fp, remote_ep)
                        except Exception as cb_err:
                            print("Error invoking session listener on identity_info:", cb_err)
                    # Update loop variables so cleanup is correct
                    peer_name = real_name
                    fp = remote_fp
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
                from pathlib import Path
                from messenger.core.crypto import decrypt_file_chunks

                file_id_str = msg["file_id"]
                file_id = base64.b64decode(file_id_str.encode())
                
                state = incoming_files.get(file_id)
                if not state:
                    state = {"meta": None, "chunks": {}}
                    incoming_files[file_id] = state
                
                if mtype == "file_meta":
                    state["meta"] = msg
                else:
                    state["chunks"][int(msg.get("chunk_index", 0))] = base64.b64decode(msg["payload"])
                
                meta = state.get("meta")
                if meta:
                    expected = int(meta.get("num_chunks", 0))
                    if len(state["chunks"]) >= expected:
                        try:
                            file_name = meta.get("file_name") or f"file-{file_id_str}"
                            file_key = base64.b64decode(meta["file_key"])
                            file_nonce_prefix = base64.b64decode(meta["file_nonce_prefix"])
                            file_hash = base64.b64decode(meta["file_hash"])
                            ordered = sorted(state["chunks"].items())
                            
                            plaintext = decrypt_file_chunks(
                                ordered,
                                file_key=file_key,
                                file_nonce_prefix=file_nonce_prefix,
                                expected_sha256=file_hash,
                            )
                            
                            config_dir = os.environ.get("P2PCHAT_CONFIG_DIR")
                            downloads_dir = Path(config_dir) / "downloads"
                            downloads_dir.mkdir(parents=True, exist_ok=True)
                            target = downloads_dir / file_name
                            
                            if target.exists():
                                suffix = 1
                                stem = target.stem
                                suffix_target = target
                                while suffix_target.exists():
                                    suffix_target = target.with_name(f"{stem}_{suffix}{target.suffix}")
                                    suffix += 1
                                target = suffix_target
                                
                            target.write_bytes(plaintext)
                            print(f"File fully received and decrypted: {target}")
                            
                            if file_id in incoming_files:
                                del incoming_files[file_id]
                            
                            mime, _ = mimetypes.guess_type(file_name)
                            if not mime:
                                mime = "application/octet-stream"
                                
                            file_notification = {
                                "type": "file",
                                "file_name": file_name,
                                "file_path": str(target),
                                "mime": mime,
                                "size": len(plaintext)
                            }
                            
                            if message_listener_callback:
                                try:
                                    message_listener_callback.onMessageReceived(peer_name, json.dumps(file_notification))
                                except Exception as cb_err:
                                    print("Error invoking message listener callback for file:", cb_err)
                        except Exception as decrypt_err:
                            print(f"Failed to decrypt incoming file {file_name}: {decrypt_err}")
                            traceback.print_exc()
    except Exception as e:
        print(f"Session with {peer_name} read loop error:", e)
    finally:
        # Cleanup session references
        if fp in active_sessions:
            del active_sessions[fp]
        if peer_name in active_sessions:
            del active_sessions[peer_name]
        if session_listener_callback:
            try:
                session_listener_callback.onSessionClosed(peer_name)
            except Exception as cb_err:
                pass


def send_p2p_message(peer_name: str, endpoint: str, body: str) -> bool:
    """
    Synchronous entry point called from Kotlin to send an encrypted Double Ratchet message.
    """
    global loop
    if not loop:
        print("Asyncio loop not running, starting listener loop first")
        start_p2p_listener(50001)
        # Give a small buffer to start
        import time
        time.sleep(1)
        if not loop:
            return False
            
    future = asyncio.run_coroutine_threadsafe(
        _send_message_async(peer_name, endpoint, body),
        loop
    )
    try:
        return future.result(timeout=15)
    except Exception as e:
        print(f"Failed to send message to {peer_name} via python bridge:", e)
        traceback.print_exc()
        return False

async def _dial_endpoint(endpoint_str: str, identity_priv, signing_key, trust_store) -> "Session":
    """
    Attempt to connect to a single 'host:port' or '[ipv6]:port' endpoint.
    Tries Protocol V3 first, falls back to V2.
    Returns a connected Session or raises an exception.
    """
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
                          signing_key=signing_key, trust_store=trust_store, protocol_version=3)
        await asyncio.wait_for(session._exchange_keys(initiator=True), timeout=5.0)
        session._start_reader()
        return session
    except Exception as e3:
        await _close_writer_safely(writer)
        print(f"V3 failed to {endpoint_str}: {e3}, trying V2...")
        reader = None
        writer = None
        reader, writer = await asyncio.wait_for(
            transport_connect("direct", host, port), timeout=5.0
        )
        try:
            session = Session(reader, writer, identity_priv=identity_priv,
                              signing_key=signing_key, trust_store=trust_store, protocol_version=2)
            await asyncio.wait_for(session._exchange_keys(initiator=True), timeout=5.0)
            session._start_reader()
            return session
        except Exception:
            await _close_writer_safely(writer)
            raise


async def _send_message_async(peer_name: str, endpoint: str, body: str) -> bool:
    try:
        session = active_sessions.get(peer_name)
        if not session or not session.is_online:
            # Close the old dead session explicitly so its _read_loop finally-block
            # doesn't race-delete the new session we're about to create.
            if session and not session.is_online:
                try:
                    asyncio.create_task(session.close())
                except Exception:
                    pass
                active_sessions.pop(peer_name, None)

            identity_priv = load_or_create_identity()
            signing_key = load_or_create_signing_identity()
            trust_store = TrustStore()

            # Support comma-separated list of endpoints for fallback
            endpoints = [e.strip() for e in endpoint.split(",") if e.strip()]
            last_err = None
            session = None

            for ep in endpoints:
                try:
                    session = await _dial_endpoint(ep, identity_priv, signing_key, trust_store)
                    print(f"Connected to {peer_name} via {ep}")
                    break
                except Exception as err:
                    print(f"Failed to connect to {peer_name} via {ep}: {err}")
                    last_err = err

            if session is None:
                raise ConnectionError(f"All endpoints failed for {peer_name}. Last error: {last_err}")

            fp = session.peer_fingerprint
            peer_fingerprint_to_name[fp] = peer_name
            active_sessions[fp] = session
            active_sessions[peer_name] = session

            print(f"Established Double Ratchet session to {peer_name} (Fingerprint: {fp})")

            asyncio.create_task(_read_loop(session, peer_name, fp))

            if session_listener_callback:
                try:
                    session_listener_callback.onSessionEstablished(peer_name, fp, ep)
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


def send_p2p_file(peer_name: str, endpoint: str, file_path: str) -> bool:
    """
    Synchronous entry point called from Kotlin to send an encrypted file/photo via Double Ratchet.
    """
    global loop
    if not loop:
        print("Asyncio loop not running, starting listener loop first")
        start_p2p_listener(50001)
        import time
        time.sleep(1)
        if not loop:
            return False
            
    future = asyncio.run_coroutine_threadsafe(
        _send_file_async(peer_name, endpoint, file_path),
        loop
    )
    try:
        return future.result(timeout=60) # Allow up to 1 minute for larger files
    except Exception as e:
        print(f"Failed to send file to {peer_name} via python bridge:", e)
        traceback.print_exc()
        return False

async def _send_file_async(peer_name: str, endpoint: str, file_path: str) -> bool:
    try:
        session = active_sessions.get(peer_name)
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
                    session = await _dial_endpoint(ep, identity_priv, signing_key, trust_store)
                    print(f"Connected to {peer_name} via {ep} for file sending")
                    break
                except Exception as err:
                    print(f"Failed to connect to {peer_name} via {ep} for file sending: {err}")
                    last_err = err

            if session is None:
                raise ConnectionError(f"All endpoints failed for {peer_name} file sending. Last error: {last_err}")

            fp = session.peer_fingerprint
            peer_fingerprint_to_name[fp] = peer_name
            active_sessions[fp] = session
            active_sessions[peer_name] = session

            print(f"Established Double Ratchet session to {peer_name} (Fingerprint: {fp})")

            asyncio.create_task(_read_loop(session, peer_name, fp))

            if session_listener_callback:
                try:
                    session_listener_callback.onSessionEstablished(peer_name, fp, ep)
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
    incoming_files.clear()

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

