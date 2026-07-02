"""GUI-friendly controller that bridges transports, sessions, and callbacks.

This module keeps UI frameworks (like Kivy) decoupled from the networking and
crypto layers by providing a small orchestration helper. It runs an asyncio
loop in a background thread by default so UI event loops stay responsive.
"""
import asyncio
import base64
import contextlib
import ipaddress
import logging
import mimetypes
import os
import sys
from concurrent.futures import TimeoutError as FutureTimeout
from datetime import datetime, timezone
from pathlib import Path
from threading import Event, Thread
from typing import Any, Callable, Coroutine, Dict, Optional

from nacl.public import PrivateKey

from messenger.core.crypto import decrypt_file_chunks, encrypt_file_in_chunks
from messenger.core.identity import (
    IDENTITY_FILENAME,
    QUEUE_FILENAME,
    TRUST_FILENAME,
    Outbox,
    TrustStore,
    fingerprint,
    load_or_create_identity,
)
from messenger.core.session import Session
from messenger.core.transport_manager import connect as transport_connect
from messenger.core.transport_manager import listen as transport_listen
from messenger.core.verify import build_identity_qr_payload, compute_sas
from messenger.utils.logger import setup_logger

logger = setup_logger("messenger.gui")


def _local_app_dir() -> Path:
    """Return writable folder colocated with the app executable/script."""

    if getattr(sys, "frozen", False):
        base = Path(sys.executable).resolve().parent
    else:
        base = Path.cwd()
    base.mkdir(parents=True, exist_ok=True)
    return base


MessageCallback = Callable[[Dict[str, Any]], None]
StatusCallback = Callable[[str], None]


class ChatController:
    """Manage background networking for a GUI chat client."""

    def __init__(
        self,
        on_message: Optional[MessageCallback] = None,
        on_status: Optional[StatusCallback] = None,
        *,
        loop: Optional[asyncio.AbstractEventLoop] = None,
        transport_connector=transport_connect,
        transport_listener=transport_listen,
        session_factory=Session.create,
        outbox: Optional[Outbox] = None,
        identity_priv: Optional[PrivateKey] = None,
        trust_store: Optional[TrustStore] = None,
        log_level: int = logging.INFO,
        downloads_dir: Optional[Path] = None,
    ) -> None:
        self._on_message = on_message
        self._on_status = on_status
        self.loop = loop or asyncio.new_event_loop()
        self._owns_loop = loop is None
        self._thread: Optional[Thread] = None
        self._loop_ready = Event()
        self._transport_connector = transport_connector
        self._transport_listener = transport_listener
        self._session_factory = session_factory
        app_dir = _local_app_dir()
        self._outbox = outbox or Outbox(str(app_dir / QUEUE_FILENAME))
        self._nickname: str = ""
        self._auto_reconnect_delay: float = 1.5
        self._downloads_dir = downloads_dir or (app_dir / "downloads")
        self._downloads_dir.mkdir(parents=True, exist_ok=True)

        self._identity_priv = identity_priv or load_or_create_identity(
            str(app_dir / IDENTITY_FILENAME)
        )
        self._trust_store = trust_store or TrustStore(str(app_dir / TRUST_FILENAME))

        self.set_log_level(log_level)

        self.session: Optional[Session] = None
        self._receive_task: Optional[asyncio.Task] = None
        self._listener = None
        self._last_request: Optional[Dict[str, Any]] = None
        self._reconnect_task: Optional[asyncio.Task] = None
        self._incoming_files: Dict[bytes, Dict[str, Any]] = {}

        if self._owns_loop:
            self._start_loop_thread()

    def local_fingerprint(self, *, encoding: str = "base64") -> str:
        return fingerprint(self._identity_priv.public_key, encoding=encoding)

    def peer_fingerprint(self) -> Optional[str]:
        if self.session:
            return getattr(self.session, "peer_fingerprint", None)
        return None

    def session_sas(self) -> Optional[str]:
        if self.session and self.session.their_pub:
            return compute_sas(
                bytes(self._identity_priv.public_key), bytes(self.session.their_pub)
            )
        return None

    def identity_qr_payload(self, label: Optional[str] = None) -> str:
        fp_base64 = self.local_fingerprint()
        return build_identity_qr_payload(fp_base64, label)

    def _peer_status_identity(self) -> Optional[str]:
        if not self.session:
            return None
        peer_fp = getattr(self.session, "peer_fingerprint", None)
        if not peer_fp:
            return None
        peer_label = self._trust_store.label_for(peer_fp)
        return f"{peer_label} ({peer_fp})" if peer_label else peer_fp

    def _start_loop_thread(self) -> None:
        if self._thread:
            return

        def _runner():
            asyncio.set_event_loop(self.loop)
            self._loop_ready.set()
            self.loop.run_forever()

        self._thread = Thread(target=_runner, daemon=True)
        self._thread.start()
        self._loop_ready.wait()

    def _notify_message(self, payload: Dict[str, Any]) -> None:
        if self._on_message:
            try:
                self._on_message(payload)
            except Exception:
                logger.exception("Message callback failed")

    def _notify_status(self, text: str) -> None:
        logger.info(text)
        if self._on_status:
            try:
                self._on_status(text)
            except Exception:
                logger.exception("Status callback failed")

    def set_log_level(self, level: int) -> None:
        """Bump verbosity for GUI-related logging and shared crypto/session traces."""

        for name in [
            "messenger",
            "messenger.gui",
            "messenger.session",
            "messenger.crypto",
        ]:
            setup_logger(name, level)

    def set_nickname(self, nickname: str) -> None:
        """Update the nickname attached to outgoing chat messages."""

        self._nickname = nickname.strip()

    def set_auto_reconnect_delay(self, delay: float) -> None:
        """Adjust how quickly the GUI will attempt automatic reconnection."""

        self._auto_reconnect_delay = max(0.1, delay)

    def _run_coro(self, coro: Coroutine[Any, Any, Any]) -> asyncio.Future:
        if self._owns_loop and not self.loop.is_running():
            self._start_loop_thread()
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    async def _start_session(
        self, host: str, port: int, transport: str, initiator: bool, options: Dict[str, Any]
    ) -> None:
        if self.session:
            await self._shutdown_session()

        self._last_request = {
            "mode": "connect" if initiator else "listen",
            "host": host,
            "port": port,
            "transport": transport,
            "options": options,
        }

        logger.debug(
            "GUI session start initiator=%s host=%s port=%s transport=%s options=%s",
            initiator,
            host,
            port,
            transport,
            options,
        )

        if initiator:
            reader, writer = await self._transport_connector(transport, host, port, **options)
            status = f"Connected to {host}:{port} over {transport}"
        else:
            self._listener = self._transport_listener(transport, host, port, **options)
            self._notify_status(f"Listening on {host}:{port} over {transport}")
            reader, writer = await self._listener.__anext__()
            status = "Peer connected"

        self.session = await self._session_factory(
            reader,
            writer,
            initiator,
            identity_priv=self._identity_priv,
            trust_store=self._trust_store,
        )
        peer_identity = self._peer_status_identity()
        if peer_identity:
            status = f"{status} (peer {peer_identity})"
            if getattr(self.session, "trust_warning", None):
                status = f"{status} — {self.session.trust_warning}"
        self._notify_status(status)
        await self._flush_outbox()
        self._receive_task = asyncio.create_task(self._receive_loop())


    @staticmethod
    def _is_loopback_host(host: str) -> bool:
        normalized = (host or "").strip().lower()
        if normalized == "localhost":
            return True
        try:
            return ipaddress.ip_address(normalized).is_loopback
        except ValueError:
            return False

    @classmethod
    def _should_skip_rendezvous_dial(cls, target: str, bind: str) -> bool:
        if not cls._is_loopback_host(target):
            return False
        bind_normalized = (bind or "").strip().lower()
        if bind_normalized in {"", "0.0.0.0", "::"}:
            return True
        return cls._is_loopback_host(bind_normalized)

    async def _start_rendezvous(
        self,
        target: str,
        port: int,
        transport: str,
        bind: str,
        options: Dict[str, Any],
    ) -> None:
        if self.session:
            await self._shutdown_session()

        self._last_request = {
            "mode": "rendezvous",
            "host": target,
            "port": port,
            "bind": bind,
            "transport": transport,
            "options": options,
        }

        self._listener = self._transport_listener(transport, bind, port, **options)
        listen_note = f"Rendezvous: listening on {bind}:{port}"
        dial_enabled = not self._should_skip_rendezvous_dial(target, bind)
        if dial_enabled:
            self._notify_status(f"{listen_note} and dialing {target} over {transport}")
            dial_task = asyncio.create_task(
                self._transport_connector(transport, target, port, **options)
            )
        else:
            self._notify_status(
                f"{listen_note} over {transport} (dial disabled to avoid loopback self-connect)"
            )
            dial_task = None

        accept_task = asyncio.create_task(self._listener.__anext__())

        reader = writer = None
        initiator = False
        pending = {accept_task}
        if dial_task is not None:
            pending.add(dial_task)
        while pending and reader is None:
            done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                if task.cancelled():
                    continue
                if task.exception():
                    continue
                reader, writer = task.result()
                initiator = task is dial_task
                break

        for task in pending:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task

        if reader is None or writer is None:
            raise RuntimeError("Rendezvous attempts failed")

        self.session = await self._session_factory(
            reader,
            writer,
            initiator,
            identity_priv=self._identity_priv,
            trust_store=self._trust_store,
        )
        status = f"Rendezvous established over {transport}"
        peer_identity = self._peer_status_identity()
        if peer_identity:
            status = f"{status} (peer {peer_identity})"
        self._notify_status(status)
        await self._flush_outbox()
        self._receive_task = asyncio.create_task(self._receive_loop())

    async def _flush_outbox(self) -> None:
        if not self.session:
            return
        for pending in list(self._outbox.pending()):
            target_fp = pending.get("peer_fp")
            if not target_fp:
                self._notify_status(
                    "Queued message lacks peer fingerprint; leaving in outbox until the original peer reconnects",
                )
                continue
            if (
                getattr(self.session, "peer_fingerprint", None)
                and target_fp != self.session.peer_fingerprint
            ):
                self._notify_status(
                    f"Skipped queued message intended for {target_fp} (current peer {self.session.peer_fingerprint})",
                )
                continue
            try:
                await self.session.send_reliable(dict(pending))
                self._outbox.mark_sent(pending["id"])
            except Exception as exc:  # noqa: BLE001
                self._notify_status(f"Failed to replay queued message: {exc}")
                break

    async def _receive_loop(self) -> None:
        assert self.session
        while True:
            try:
                message = await self.session.receive_message()
            except asyncio.CancelledError:
                break
            except Exception as exc:  # noqa: BLE001
                self._notify_status(f"Receive error: {exc}")
                self._schedule_auto_reconnect("receive-error")
                break
            else:
                if message.get("type") == "status":
                    state = message.get("state")
                    reason = message.get("reason")
                    note = f"Peer is {state}" if state else "Peer status update"
                    if reason:
                        note = f"{note} ({reason})"
                    self._notify_status(note)
                    if state == "offline":
                        self._schedule_auto_reconnect("peer-offline")
                        break
                    continue

                if self._handle_file_message(message):
                    continue

                self._notify_message(message)

    async def _shutdown_session(self) -> None:
        if self._receive_task:
            self._receive_task.cancel()
            with contextlib.suppress(Exception):
                await self._receive_task
        self._receive_task = None

        if self.session:
            with contextlib.suppress(Exception):
                await self.session.close()
            self.session = None

        if self._listener:
            with contextlib.suppress(Exception):
                await self._listener.aclose()
            self._listener = None

    def connect(self, host: str, port: int, transport: str, **options) -> asyncio.Future:
        """Initiate an outgoing connection in the background."""
        return self._run_coro(self._start_session(host, port, transport, True, options))

    def listen(self, host: str, port: int, transport: str, **options) -> asyncio.Future:
        """Wait for a peer connection in the background."""
        return self._run_coro(self._start_session(host, port, transport, False, options))

    def rendezvous(
        self, target: str, port: int, transport: str, bind: str, **options
    ) -> asyncio.Future:
        """Race dial/listen attempts for NAT-friendly rendezvous mode."""

        return self._run_coro(
            self._start_rendezvous(target, port, transport, bind, options)
        )

    def send_chat(self, body: str) -> asyncio.Future:
        async def _send():
            if not self.session:
                self._notify_status(
                    "No session; connect to a peer before sending to avoid losing the intended fingerprint",
                )
                return None
            try:
                return await self.session.send_chat(body, nickname=self._nickname or None)
            except Exception as exc:  # noqa: BLE001
                message = self._outbox.add_chat(
                    body,
                    datetime.now(timezone.utc).timestamp(),
                    self._nickname or None,
                    peer_fp=self.session.peer_fingerprint,
                )
                self._notify_status(f"Send failed, queued for retry: {exc}")
                return message["id"]

        return self._run_coro(_send())

    def send_file(self, file_path: str) -> asyncio.Future:
        """Encrypt and send a file with metadata + chunks."""

        async def _send() -> str:
            if not self.session:
                raise RuntimeError("No active session; start a connection first")

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

            await self.session.send_reliable(meta)

            for chunk_index, encrypted_chunk in chunk_iterator:
                payload = {
                    "type": "file_chunk",
                    "file_id": base64.b64encode(file_id).decode(),
                    "chunk_index": chunk_index,
                    "payload": base64.b64encode(encrypted_chunk).decode(),
                }
                await self.session.send_reliable(payload)

            mime, _ = mimetypes.guess_type(file_path)
            self._notify_message(
                {
                    "type": "file_saved",
                    "file_id": base64.b64encode(file_id).decode(),
                    "file_name": Path(file_path).name,
                    "file_path": str(Path(file_path).resolve()),
                    "mime": mime,
                    "timestamp": meta.get("timestamp"),
                    "size": file_size,
                    "outbound": True,
                }
            )
            self._notify_status(
                f"Sent file {meta['file_name']} ({file_size} bytes, {num_chunks} chunks)"
            )
            return base64.b64encode(file_id).decode()

        return self._run_coro(_send())

    def reconnect(self) -> asyncio.Future:
        if not self._last_request:
            raise RuntimeError("No previous session to reconnect")
        req = self._last_request
        if req.get("mode") == "rendezvous":
            return self.rendezvous(
                req["host"],
                req["port"],
                req["transport"],
                req.get("bind", "0.0.0.0"),
                **req["options"],
            )
        return self._run_coro(
            self._start_session(
                req["host"],
                req["port"],
                req["transport"],
                req.get("mode") == "connect",
                req["options"],
            )
        )

    def _schedule_auto_reconnect(self, reason: str) -> None:
        if not self._last_request:
            return
        if self._reconnect_task and not self._reconnect_task.done():
            return

        async def _auto():
            await asyncio.sleep(self._auto_reconnect_delay)
            await self._shutdown_session()
            try:
                await self._perform_reconnect()
            except Exception as exc:  # noqa: BLE001
                self._notify_status(f"Auto-reconnect failed: {exc}")
            else:
                self._notify_status("Auto-reconnect succeeded")

        self._reconnect_task = asyncio.create_task(_auto())
        self._notify_status(f"Peer offline ({reason}); attempting auto-reconnect")

    async def _perform_reconnect(self) -> None:
        if not self._last_request:
            return
        req = self._last_request
        if req.get("mode") == "rendezvous":
            await self._start_rendezvous(
                req["host"],
                req["port"],
                req["transport"],
                req.get("bind", "0.0.0.0"),
                req["options"],
            )
            return
        await self._start_session(
            req["host"],
            req["port"],
            req["transport"],
            req.get("mode") == "connect",
            req["options"],
        )

    def disconnect(self) -> None:
        """Disconnect active transport/session while keeping controller reusable."""

        future = self._run_coro(self._shutdown_session())
        with contextlib.suppress(FutureTimeout):
            future.result(timeout=5)

    def close(self) -> None:
        async def _close():
            await self._shutdown_session()
            if self._owns_loop and self.loop.is_running():
                self.loop.stop()

        future = self._run_coro(_close())
        with contextlib.suppress(FutureTimeout):
            future.result(timeout=5)
        if self._thread:
            self._thread.join(timeout=5)

    def _decode_file_id(self, file_id_str: str) -> bytes:
        return base64.b64decode(file_id_str.encode())

    def _handle_file_message(self, message: Dict[str, Any]) -> bool:
        mtype = message.get("type")
        if mtype not in {"file_meta", "file_chunk"}:
            return False

        if mtype == "file_meta":
            file_id = self._decode_file_id(message["file_id"])
            self._incoming_files[file_id] = {"meta": message, "chunks": {}}
            self._notify_message(
                {
                    "type": "file_offer",
                    "file_id": message["file_id"],
                    "file_name": message.get("file_name"),
                    "file_size": message.get("file_size"),
                    "num_chunks": message.get("num_chunks"),
                    "timestamp": message.get("timestamp"),
                }
            )
            return True

        file_id = self._decode_file_id(message["file_id"])
        state = self._incoming_files.get(file_id)
        if not state:
            state = {"meta": None, "chunks": {}}
            self._incoming_files[file_id] = state
        state["chunks"][int(message.get("chunk_index", 0))] = base64.b64decode(
            message["payload"]
        )

        meta = state.get("meta")
        if not meta:
            return True

        expected = int(meta.get("num_chunks", 0))
        if len(state["chunks"]) < expected:
            return True

        try:
            file_name = meta.get("file_name") or f"file-{meta['file_id']}"
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
            target = self._downloads_dir / file_name
            if target.exists():
                suffix = 1
                stem = target.stem
                suffix_target = target
                while suffix_target.exists():
                    suffix_target = target.with_name(f"{stem}_{suffix}{target.suffix}")
                    suffix += 1
                target = suffix_target
            target.write_bytes(plaintext)

            mime, _ = mimetypes.guess_type(file_name)
            self._notify_message(
                {
                    "type": "file_saved",
                    "file_id": base64.b64encode(file_id).decode(),
                    "file_name": file_name,
                    "file_path": str(target),
                    "mime": mime,
                    "timestamp": meta.get("timestamp"),
                    "size": len(plaintext),
                }
            )
        except Exception as exc:  # noqa: BLE001
            self._notify_status(f"Failed to process file: {exc}")
        finally:
            self._incoming_files.pop(file_id, None)

        return True
