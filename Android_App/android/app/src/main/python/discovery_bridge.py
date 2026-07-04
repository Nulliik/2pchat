import asyncio
import contextlib
import logging
import socket
import threading
import traceback
from datetime import datetime, timezone
from typing import Dict, List, Optional

from messenger.core.discovery_base import PeerEndpoint
from messenger.core.discovery_manager import get_discovery_provider
from messenger.core.identity import (
    TrustStore,
    fingerprint,
    load_or_create_identity,
    load_or_create_signing_identity,
)
from messenger.core.session import PROTOCOL_V3, Session
from messenger.core.tracker_catalog import get_tracker_by_name
from messenger.core.transport_manager import (
    connect as transport_connect,
    listen as transport_listen,
)
from messenger.utils.logger import setup_logger

DEFAULT_TRACKER = "Torrent.eu.org UDP"
DEFAULT_PORT = 50001
LOG_FORMAT = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"

logger = setup_logger("messenger.android", logging.INFO)

active_sessions_by_name: Dict[str, Session] = {}
active_sessions_by_fp: Dict[str, Session] = {}
peer_fingerprint_to_name: Dict[str, str] = {}

message_listener_callback = None
session_listener_callback = None
status_listener_callback = None

loop: Optional[asyncio.AbstractEventLoop] = None
loop_thread: Optional[threading.Thread] = None
listener_task: Optional[asyncio.Task] = None
listener_port = DEFAULT_PORT
loop_started = threading.Event()

_identity_priv = None
_signing_key = None
_trust_store: Optional[TrustStore] = None
_runtime_lock = threading.Lock()
_verbose_logging = False
_android_status_handler = None


class _AndroidStatusLogHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        if not status_listener_callback:
            return
        try:
            message = self.format(record)
            status_listener_callback.onStatus(message)
        except Exception:
            pass


def configure_logging(verbose: bool = False) -> bool:
    """Align Android Python logging with the CLI verbose flag behavior."""

    global _verbose_logging
    _verbose_logging = bool(verbose)
    level = logging.DEBUG if _verbose_logging else logging.INFO

    global _android_status_handler

    root_logger = logging.getLogger()
    root_logger.setLevel(level)
    if not root_logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter(LOG_FORMAT))
        root_logger.addHandler(handler)

    if _android_status_handler is None:
        _android_status_handler = _AndroidStatusLogHandler()
        _android_status_handler.setFormatter(logging.Formatter(LOG_FORMAT))
        root_logger.addHandler(_android_status_handler)

    for name in [
        "messenger",
        "messenger.android",
        "messenger.session",
        "messenger.crypto",
        "messenger.cli",
    ]:
        setup_logger(name, level)

    logger.info(
        "Android Python verbose logging %s",
        "enabled" if _verbose_logging else "disabled",
    )
    return _verbose_logging


def _run_sync(coro):
    temp_loop = asyncio.new_event_loop()
    try:
        return temp_loop.run_until_complete(coro)
    finally:
        temp_loop.run_until_complete(temp_loop.shutdown_asyncgens())
        temp_loop.close()


def _tracker_provider(tracker_name: str, peer_port: int, transport: str):
    tracker = get_tracker_by_name(tracker_name or DEFAULT_TRACKER)
    return get_discovery_provider(
        tracker.discovery_scheme,
        tracker_url=tracker.announce_url,
        peer_port=peer_port,
        transport=transport,
    )


def _iso_utc(timestamp: int) -> str:
    return datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def get_local_fingerprint() -> str:
    return fingerprint(load_or_create_identity().public_key)


def _local_ipv4_addresses() -> set[str]:
    hosts = {"127.0.0.1"}
    try:
        host_name = socket.gethostname()
        for info in socket.getaddrinfo(host_name, None, family=socket.AF_INET):
            hosts.add(info[4][0])
    except Exception:
        pass
    return hosts


def resolve_peers(
    nickname: str,
    shared_code: str,
    tracker_name: str = DEFAULT_TRACKER,
    peer_port: int = DEFAULT_PORT,
    transport: str = "direct",
):
    """Resolve peer candidates from a tracker for the Android UI."""

    async def _resolve():
        provider = _tracker_provider(tracker_name, peer_port, transport)
        return await provider.resolve(nickname, shared_code)

    try:
        descriptors = _run_sync(_resolve())
        local_fp = get_local_fingerprint()
        local_hosts = _local_ipv4_addresses()
        results = []
        for descriptor in descriptors:
            endpoints = []
            for ep in descriptor.endpoints:
                if ep.port == listener_port and ep.host in local_hosts:
                    continue
                endpoints.append(f"{ep.host}:{ep.port}")
            if not endpoints:
                continue
            if descriptor.identity_fingerprint and descriptor.identity_fingerprint == local_fp:
                continue
            results.append(
                {
                    "nickname": descriptor.nickname,
                    "fingerprint": descriptor.identity_fingerprint or "",
                    "transport": descriptor.transport,
                    "endpoints": endpoints,
                    "sequence": descriptor.sequence,
                    "expires": _iso_utc(descriptor.expires_at),
                }
            )
        return results
    except Exception as exc:  # noqa: BLE001
        logger.exception("Error resolving peers in discovery_bridge: %s", exc)
        return []


def announce_peer(
    nickname: str,
    shared_hint: str,
    host: str,
    port: int,
    tracker_name: str = DEFAULT_TRACKER,
    transport: str = "direct",
) -> bool:
    """Announce this peer under several lookup keys for the Android UI."""

    async def _announce() -> bool:
        provider = _tracker_provider(tracker_name, port, transport)
        endpoint = PeerEndpoint(host=host, port=port)

        announce_pairs = [(nickname, nickname)]
        shared_hint = (shared_hint or "").strip()
        if shared_hint and shared_hint != nickname:
            announce_pairs.append((nickname, shared_hint))
            announce_pairs.append((shared_hint, shared_hint))

        for announce_nickname, announce_code in announce_pairs:
            await provider.announce(
                announce_nickname,
                announce_code,
                transport=transport,
                endpoints=[endpoint],
            )
        return True

    try:
        return bool(_run_sync(_announce()))
    except Exception as exc:  # noqa: BLE001
        logger.exception("Error announcing peer in discovery_bridge: %s", exc)
        return False


def register_message_listener(callback):
    global message_listener_callback
    message_listener_callback = callback


def register_session_listener(callback):
    global session_listener_callback
    session_listener_callback = callback


def register_status_listener(callback):
    global status_listener_callback
    status_listener_callback = callback


def _ensure_runtime(port: int = DEFAULT_PORT) -> None:
    global loop
    global loop_thread
    global listener_port

    with _runtime_lock:
        listener_port = port
        if not loop or not loop.is_running():
            loop_started.clear()

            def _runner():
                global loop
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                loop_started.set()
                loop.run_forever()

            loop_thread = threading.Thread(
                target=_runner,
                name="android-p2p-loop",
                daemon=True,
            )
            loop_thread.start()
            loop_started.wait(timeout=5)

    future = asyncio.run_coroutine_threadsafe(_bootstrap_runtime(port), loop)
    future.result(timeout=10)


async def _bootstrap_runtime(port: int) -> None:
    global _identity_priv
    global _signing_key
    global _trust_store
    global listener_task

    if _identity_priv is None:
        _identity_priv = load_or_create_identity()
    if _signing_key is None:
        _signing_key = load_or_create_signing_identity()
    if _trust_store is None:
        _trust_store = TrustStore()

    if listener_task and not listener_task.done() and listener_port == port:
        return

    if listener_task and not listener_task.done():
        listener_task.cancel()
        try:
            await listener_task
        except Exception:
            pass

    listener_task = asyncio.create_task(_listen_loop(port))


def start_p2p_listener(port: int = DEFAULT_PORT):
    try:
        _ensure_runtime(port)
        logger.info("Android Python P2P listener started on 0.0.0.0:%s", port)
    except Exception as exc:  # noqa: BLE001
        logger.exception("Failed to start Android Python listener: %s", exc)


async def _listen_loop(port: int) -> None:
    logger.info("Python P2P server listening on 0.0.0.0:%s over direct transport", port)
    async for reader, writer in transport_listen("direct", "0.0.0.0", port):
        asyncio.create_task(_handle_incoming(reader, writer))


def _peer_display_name(peer_fp: str) -> str:
    return peer_fingerprint_to_name.get(peer_fp) or f"Peer ({peer_fp[:8]})"


def _store_session(session: Session, peer_name: str, peer_fp: str) -> None:
    active_sessions_by_name[peer_name] = session
    active_sessions_by_fp[peer_fp] = session
    peer_fingerprint_to_name[peer_fp] = peer_name


def _drop_session(peer_name: str, peer_fp: str, session: Session) -> None:
    if active_sessions_by_name.get(peer_name) is session:
        active_sessions_by_name.pop(peer_name, None)
    if active_sessions_by_fp.get(peer_fp) is session:
        active_sessions_by_fp.pop(peer_fp, None)


def _notify_session_established(peer_name: str, peer_fp: str) -> None:
    if not session_listener_callback:
        return
    try:
        session_listener_callback.onSessionEstablished(peer_name, peer_fp)
    except Exception:
        traceback.print_exc()


def _notify_session_closed(peer_name: str) -> None:
    if not session_listener_callback:
        return
    try:
        session_listener_callback.onSessionClosed(peer_name)
    except Exception:
        pass


def _notify_message(peer_name: str, body: str) -> None:
    if not message_listener_callback:
        return
    try:
        message_listener_callback.onMessageReceived(peer_name, body)
    except Exception:
        traceback.print_exc()


async def _create_session(
    reader,
    writer,
    *,
    initiator: bool,
    peer_name: Optional[str] = None,
) -> Session:
    return await Session.create(
        reader,
        writer,
        initiator=initiator,
        identity_priv=_identity_priv,
        signing_key=_signing_key,
        trust_store=_trust_store,
        peer_label=peer_name,
        protocol_version=PROTOCOL_V3,
    )


async def _handle_incoming(reader, writer) -> None:
    session = None
    peer_fp = ""
    peer_name = "Peer"
    try:
        session = await _create_session(reader, writer, initiator=False)
        peer_fp = session.peer_fingerprint or "unknown"
        peer_name = _peer_display_name(peer_fp)
        _store_session(session, peer_name, peer_fp)
        logger.info("Accepted Android P2P session from %s (%s)", peer_name, peer_fp)
        _notify_session_established(peer_name, peer_fp)
        await _session_loop(session, peer_name, peer_fp)
    except Exception as exc:  # noqa: BLE001
        logger.exception("Error handling incoming Android connection: %s", exc)
        if session is not None:
            await session.close()


async def _session_loop(session: Session, peer_name: str, peer_fp: str) -> None:
    try:
        while True:
            msg = await session.receive_message()
            msg_type = msg.get("type")
            if msg_type == "chat":
                _notify_message(peer_name, msg.get("body", ""))
                continue
            if msg_type == "status" and msg.get("state") == "offline":
                break
    except Exception as exc:  # noqa: BLE001
        logger.info("Android session loop closed for %s: %s", peer_name, exc)
    finally:
        _drop_session(peer_name, peer_fp, session)
        with contextlib.suppress(Exception):
            await session.close()
        _notify_session_closed(peer_name)


def send_p2p_message(peer_name: str, endpoint: str, body: str) -> bool:
    return bool(send_p2p_message_detailed(peer_name, endpoint, body).get("ok"))


def send_p2p_message_detailed(peer_name: str, endpoint: str, body: str) -> dict:
    try:
        _ensure_runtime(DEFAULT_PORT)
        future = asyncio.run_coroutine_threadsafe(
            _send_message_async(peer_name, endpoint, body),
            loop,
        )
        return {
            "ok": bool(future.result(timeout=20)),
            "error": "",
        }
    except Exception as exc:  # noqa: BLE001
        logger.exception("Failed to send Android P2P message to %s: %s", peer_name, exc)
        return {
            "ok": False,
            "error": f"{type(exc).__name__}: {exc}",
        }


async def _send_message_async(peer_name: str, endpoint: str, body: str) -> bool:
    session = active_sessions_by_name.get(peer_name)
    if not session or not session.is_online:
        host, port_str = endpoint.split(":", 1)
        port = int(port_str)
        reader, writer = await transport_connect("direct", host, port)
        session = await _create_session(
            reader,
            writer,
            initiator=True,
            peer_name=peer_name,
        )
        peer_fp = session.peer_fingerprint or "unknown"
        _store_session(session, peer_name, peer_fp)
        logger.info("Established Android P2P session to %s (%s)", peer_name, peer_fp)
        _notify_session_established(peer_name, peer_fp)
        asyncio.create_task(_session_loop(session, peer_name, peer_fp))

    if body:
        await session.send_chat(body)
    return True
