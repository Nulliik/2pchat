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

# Kotlin notification callbacks
message_listener_callback = None
session_listener_callback = None
loop = None

def resolve_peers(nickname: str, shared_code: str, tracker_name: str = "OpenTrackr HTTP"):
    """
    Synchronous wrapper to resolve peers from a specific BitTorrent tracker.
    Returns a list of dicts with nickname, fingerprint, and endpoints.
    """
    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        
        tracker = get_tracker_by_name(tracker_name)
        
        # Use http-tracker or udp-tracker scheme
        provider = get_discovery_provider(
            tracker.discovery_scheme,
            tracker_url=tracker.announce_url,
            peer_port=50001,
            transport="direct"
        )
        
        # Resolve peers
        descriptors = loop.run_until_complete(provider.resolve(nickname, shared_code))
        loop.close()
        
        results = []
        for d in descriptors:
            endpoints = []
            for ep in d.endpoints:
                endpoints.append(f"{ep.host}:{ep.port}")
            results.append({
                "nickname": d.nickname,
                "fingerprint": d.identity_fingerprint or "",
                "transport": d.transport,
                "endpoints": endpoints
            })
        return results
    except Exception as e:
        print("Error resolving peers in discovery_bridge:", e)
        traceback.print_exc()
        return []


def announce_peer(nickname: str, fingerprint: str, host: str, port: int, tracker_name: str = "OpenTrackr HTTP"):
    """
    Synchronous wrapper to announce this peer on a tracker under both nickname and fingerprint.
    """
    try:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        
        tracker = get_tracker_by_name(tracker_name)
        provider = get_discovery_provider(
            tracker.discovery_scheme,
            tracker_url=tracker.announce_url,
            peer_port=port,
            transport="direct"
        )
        
        # Announce 1: using nickname as shared_code
        loop.run_until_complete(provider.announce(
            nickname,
            nickname,
            transport="direct",
            endpoints=[PeerEndpoint(host=host, port=port)]
        ))
        
        # Announce 2: using fingerprint as shared_code if available
        if fingerprint and len(fingerprint) > 10:
            loop.run_until_complete(provider.announce(
                nickname,
                fingerprint,
                transport="direct",
                endpoints=[PeerEndpoint(host=host, port=port)]
            ))
            
        # Announce 3: using fingerprint as both nickname and shared_code
        if fingerprint and len(fingerprint) > 10:
            loop.run_until_complete(provider.announce(
                fingerprint,
                fingerprint,
                transport="direct",
                endpoints=[PeerEndpoint(host=host, port=port)]
            ))
            
        loop.close()
        return True
    except Exception as e:
        print("Error announcing peer in discovery_bridge:", e)
        traceback.print_exc()
        return False


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
    Start the background asyncio event loop and listener thread.
    """
    def run():
        global loop
        try:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            loop.run_until_complete(_listen_loop(port))
        except Exception as e:
            print("P2P listener event loop crashed:", e)
            traceback.print_exc()
            
    t = threading.Thread(target=run, daemon=True)
    t.start()
    print(f"P2P Listener background thread started on port {port}")

async def _listen_loop(port):
    identity_priv = load_or_create_identity()
    signing_key = load_or_create_signing_identity()
    trust_store = TrustStore()
    
    listener = transport_listen("direct", "0.0.0.0", port)
    print(f"Python P2P Server listening on 0.0.0.0:{port} over direct transport...")
    
    try:
        async for reader, writer in listener:
            asyncio.create_task(_handle_incoming(reader, writer, identity_priv, signing_key, trust_store))
    except Exception as e:
        print("Error in Python P2P Server listen loop:", e)
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
        
        if session_listener_callback:
            try:
                session_listener_callback.onSessionEstablished(peer_name, fp)
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
            if mtype == "chat":
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

async def _send_message_async(peer_name: str, endpoint: str, body: str) -> bool:
    try:
        session = active_sessions.get(peer_name)
        if not session or not session.is_online:
            print(f"No active session with {peer_name}. Dialing direct endpoint {endpoint}...")
            host, port = endpoint.split(":")
            port = int(port)
            
            identity_priv = load_or_create_identity()
            signing_key = load_or_create_signing_identity()
            trust_store = TrustStore()
            
            try:
                # Try Protocol V3 first (X3DH + Double Ratchet)
                reader, writer = await asyncio.wait_for(
                    transport_connect("direct", host, port),
                    timeout=5.0
                )
                session = Session(
                    reader,
                    writer,
                    identity_priv=identity_priv,
                    signing_key=signing_key,
                    trust_store=trust_store,
                    protocol_version=3
                )
                await asyncio.wait_for(session._exchange_keys(initiator=True), timeout=3.0)
                session._start_reader()
            except Exception as e3:
                print(f"Protocol V3 handshake failed: {e3}. Retrying with Protocol V2 fallback...")
                # Re-connect and try Protocol V2 (fallback)
                reader, writer = await asyncio.wait_for(
                    transport_connect("direct", host, port),
                    timeout=5.0
                )
                session = Session(
                    reader,
                    writer,
                    identity_priv=identity_priv,
                    signing_key=signing_key,
                    trust_store=trust_store,
                    protocol_version=2
                )
                await asyncio.wait_for(session._exchange_keys(initiator=True), timeout=3.0)
                session._start_reader()
            
            fp = session.peer_fingerprint
            peer_fingerprint_to_name[fp] = peer_name
            active_sessions[fp] = session
            active_sessions[peer_name] = session
            
            print(f"Established Double Ratchet session to {peer_name} (Fingerprint: {fp})")
            
            # Start read loop for replies
            asyncio.create_task(_read_loop(session, peer_name, fp))
            
            if session_listener_callback:
                try:
                    session_listener_callback.onSessionEstablished(peer_name, fp)
                except Exception:
                    pass
                    
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
            print(f"No active session with {peer_name}. Dialing direct endpoint {endpoint}...")
            host, port = endpoint.split(":")
            port = int(port)
            
            identity_priv = load_or_create_identity()
            signing_key = load_or_create_signing_identity()
            trust_store = TrustStore()
            
            try:
                # Try Protocol V3 first
                reader, writer = await asyncio.wait_for(
                    transport_connect("direct", host, port),
                    timeout=5.0
                )
                session = Session(
                    reader,
                    writer,
                    identity_priv=identity_priv,
                    signing_key=signing_key,
                    trust_store=trust_store,
                    protocol_version=3
                )
                await asyncio.wait_for(session._exchange_keys(initiator=True), timeout=3.0)
                session._start_reader()
            except Exception as e3:
                print(f"Protocol V3 handshake failed: {e3}. Retrying with Protocol V2 fallback...")
                reader, writer = await asyncio.wait_for(
                    transport_connect("direct", host, port),
                    timeout=5.0
                )
                session = Session(
                    reader,
                    writer,
                    identity_priv=identity_priv,
                    signing_key=signing_key,
                    trust_store=trust_store,
                    protocol_version=2
                )
                await asyncio.wait_for(session._exchange_keys(initiator=True), timeout=3.0)
                session._start_reader()
                
            fp = session.peer_fingerprint
            peer_fingerprint_to_name[fp] = peer_name
            active_sessions[fp] = session
            active_sessions[peer_name] = session
            
            print(f"Established Double Ratchet session to {peer_name} (Fingerprint: {fp})")
            
            asyncio.create_task(_read_loop(session, peer_name, fp))
            
            if session_listener_callback:
                try:
                    session_listener_callback.onSessionEstablished(peer_name, fp)
                except Exception:
                    pass
                    
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
