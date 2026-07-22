import argparse
import asyncio
import base64
import contextlib
import logging
import signal
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

try:
    import uvloop
except Exception:  # noqa: BLE001
    uvloop = None

from messenger.core.crypto import decrypt_file_chunks
from messenger.core import protocol
from messenger.core.discovery_base import PeerEndpoint
from messenger.core.discovery_manager import get_discovery_provider
from messenger.core.discovery_naming import generate_discovery_key, generate_discovery_name
from messenger.core.identity import (
    Outbox,
    TrustStore,
    fingerprint,
    fingerprint_from_hex,
    load_or_create_identity,
)
from messenger.core.verify import (
    build_identity_qr_payload,
    compute_sas,
    verify_identity_payload,
)
from messenger.core.session import Session
from messenger.core.transport_manager import (
    connect as transport_connect,
    listen as transport_listen,
)
from messenger.core.tracker_catalog import get_tracker_by_name, tracker_names
from messenger.utils.qr import render_qr_ascii, save_qr_png
from messenger.utils.logger import setup_logger
from nacl.encoding import Base64Encoder

logger = setup_logger("messenger.cli")


def _configure_event_loop_policy() -> None:
    """Prefer the selector loop on Windows for UDP sock_sendto support."""

    if sys.platform.startswith("win") and hasattr(asyncio, "WindowsSelectorEventLoopPolicy"):
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())


def _request_shutdown(
    stop_event: asyncio.Event,
    main_task: Optional[asyncio.Task[Any]],
) -> None:
    stop_event.set()
    if main_task and not main_task.done():
        main_task.cancel()


class FileReceiver:
    """Minimal file assembly helper for CLI sessions.

    Incoming ``file_meta`` and ``file_chunk`` messages are buffered until all
    chunks are available, then written to the downloads directory. Only
    non-secret metadata (names, sizes, fingerprints) is logged.
    """

    def __init__(self, downloads_dir: Optional[Path] = None) -> None:
        self._downloads_dir = downloads_dir or Path.home() / ".2pchat" / "downloads"
        self._downloads_dir.mkdir(parents=True, exist_ok=True)
        self._incoming: Dict[bytes, Dict[str, Any]] = {}

    def _decode_file_id(self, file_id_str: str) -> bytes:
        return base64.b64decode(file_id_str.encode())

    def _target_path(self, file_name: str) -> Path:
        target = self._downloads_dir / file_name
        counter = 1
        while target.exists():
            target = (
                self._downloads_dir
                / f"{Path(file_name).stem}_{counter}{Path(file_name).suffix}"
            )
            counter += 1
        return target

    def handle(self, message: Dict[str, Any]) -> Tuple[bool, Optional[str]]:
        mtype = message.get("type")
        if mtype not in {"file_meta", "file_chunk"}:
            return False, None

        if mtype == "file_meta":
            try:
                protocol.validate_file_metadata(message)
            except ValueError as exc:
                return True, f"Rejected unsupported file transfer: {exc}"
            file_id = self._decode_file_id(message["file_id"])
            self._incoming[file_id] = {"meta": message, "chunks": {}}
            name = message.get("file_name") or f"file-{message['file_id']}"
            size = message.get("file_size", "?")
            info = (
                f"Incoming file {name} ({size} bytes). Saving to {self._downloads_dir}"
            )
            return True, info

        file_id = self._decode_file_id(message["file_id"])
        chunk_index = int(message.get("chunk_index", 0))
        payload = message.get("payload", b"")
        if not isinstance(payload, bytes):
            return True, "Rejected non-binary file chunk payload"

        state = self._incoming.get(file_id)
        if not state:
            return True, "Rejected file chunk received before metadata"
        state["chunks"][chunk_index] = payload

        meta = state["meta"]

        expected_chunks = int(meta.get("num_chunks", 0))
        if len(state["chunks"]) < expected_chunks:
            return True, None

        file_key = base64.b64decode(meta["file_key"])
        file_nonce_prefix = base64.b64decode(meta["file_nonce_prefix"])
        file_hash = base64.b64decode(meta["file_hash"])
        file_name = meta.get("file_name") or f"file-{meta['file_id']}"

        ordered_chunks = sorted(state["chunks"].items())
        plaintext = decrypt_file_chunks(
            ordered_chunks,
            file_key=file_key,
            file_nonce_prefix=file_nonce_prefix,
            expected_sha256=file_hash,
        )

        target = self._target_path(file_name)
        target.write_bytes(plaintext)
        self._incoming.pop(file_id, None)

        info = f"Saved file {file_name} to {target}"
        logger.info("CLI saved file %s size=%s", target, len(plaintext))
        return True, info


def _configure_logging(verbose: bool) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    for name in [
        "messenger",
        "messenger.cli",
        "messenger.session",
        "messenger.crypto",
        "messenger.gui",
    ]:
        setup_logger(name, level)


TRANSPORT_CHOICES = ["direct", "ygg", "ygg-embedded"]
DISCOVERY_CHOICES = ["mainline-dht", "udp-tracker", "http-tracker"]


def _decode_fp_bytes(value: str) -> bytes:
    try:
        return bytes.fromhex(value)
    except ValueError:
        try:
            return Base64Encoder.decode(value)
        except Exception as exc:  # noqa: BLE001
            raise ValueError("Invalid fingerprint encoding") from exc


def _normalize_peer_fingerprint(value: str) -> str:
    try:
        return fingerprint_from_hex(value)
    except ValueError:
        Base64Encoder.decode(value)  # validate base64
        return value


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Encrypted P2P chat")
    parser.add_argument(
        "--command",
        choices=[
            "chat",
            "show-identity",
            "export-identity",
            "verify-identity",
            "generate-discovery",
        ],
        default="chat",
        help="Run chat (default) or identity verification helpers",
    )
    group = parser.add_mutually_exclusive_group(required=False)
    group.add_argument("--listen", dest="listen", help="Host/IP to bind and listen")
    group.add_argument("--connect", dest="connect", help="Host/IP to connect to")
    group.add_argument(
        "--rendezvous",
        dest="rendezvous",
        help="Attempt simultaneous dial+listen to this host (direct-style workflows)",
    )
    parser.add_argument(
        "--discover-nickname",
        help="Resolve a peer using discovery by nickname instead of host/IP",
    )
    parser.add_argument(
        "--discover-key",
        help="Shared discovery key used together with --discover-nickname",
    )
    parser.add_argument(
        "--discovery-scheme",
        choices=DISCOVERY_CHOICES,
        help="Discovery backend to use (defaults to the selected tracker preset)",
    )
    parser.add_argument(
        "--tracker-preset",
        default=tracker_names()[0],
        choices=tracker_names(),
        help="Tracker preset used for tracker-backed discovery providers",
    )
    parser.add_argument(
        "--tracker-url",
        help="Override tracker announce URL for tracker-backed discovery providers",
    )
    parser.add_argument(
        "--discover-bind",
        default="0.0.0.0",
        help="Local address announced for discovery flows (default: 0.0.0.0)",
    )
    parser.add_argument(
        "--discover-listen",
        action="store_true",
        help=(
            "Server mode for discovery: keep announcing/refreshing presence and "
            "listen for inbound peer connections"
        ),
    )
    parser.add_argument("--port", type=int, required=True, help="Port to connect/listen")
    parser.add_argument("--transport", choices=TRANSPORT_CHOICES, default="direct")
    parser.add_argument(
        "--identity",
        help="Path to persistent identity key (default: ~/.2pchat/identity.key)",
    )
    parser.add_argument(
        "--trust-store",
        help="Path to trust store for TOFU fingerprints (default: ~/.2pchat/trust.json)",
    )
    parser.add_argument(
        "--peer-label",
        help="Friendly name for the peer; warns if a different fingerprint reuses it",
    )
    parser.add_argument(
        "--expect-fingerprint",
        help="Optionally require a specific peer fingerprint before proceeding",
    )
    parser.add_argument(
        "--yggdrasil-binary",
        default="yggdrasil",
        help="Path to yggdrasil binary (for ygg-embedded transport)",
    )
    parser.add_argument(
        "--yggdrasil-config",
        help="Path to JSON config file generated by yggdrasil -genconf -json",
    )
    parser.add_argument(
        "--yggdrasil-peer",
        action="append",
        default=[],
        help="Public peer URI to add to the generated config (repeatable)",
    )
    parser.add_argument(
        "--max-retries",
        type=int,
        default=3,
        help="Reliable send retry count before buffering to outbox",
    )
    parser.add_argument(
        "--ack-timeout",
        type=float,
        default=5.0,
        help="Seconds to wait for ACK before backing off",
    )
    parser.add_argument(
        "--ack-backoff",
        type=float,
        default=1.5,
        help="Backoff factor between ACK retries (>=1.0)",
    )
    parser.add_argument(
        "--rendezvous-bind",
        default="0.0.0.0",
        help="Local bind address when using --rendezvous (default: 0.0.0.0)",
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Enable verbose debug logging including packet sizes",
    )
    parser.add_argument(
        "--qr-file",
        help="Optional path to save QR PNG output (for identity commands)",
    )
    parser.add_argument(
        "--payload",
        help="Identity payload JSON to verify or export (verify-identity/export-identity)",
    )
    parser.add_argument(
        "--output-file",
        help="Path to save exported identity payload (export-identity)",
    )
    parser.add_argument(
        "--peer-fingerprint",
        help="Peer fingerprint (hex or Base64) for SAS/verification workflows",
    )
    parser.add_argument(
        "--fingerprint",
        help="Raw peer fingerprint (hex/Base64) for verify-identity if not using --payload",
    )
    parser.add_argument(
        "--discovery-seed",
        help="Optional nickname/label to turn into a suggested discovery name",
    )
    parser.add_argument(
        "--user-label",
        help="Optional label/nickname to embed in exported identity payloads",
    )
    parser.add_argument(
        "--downloads-dir",
        help="Directory to save incoming file transfers (default: ~/.2pchat/downloads)",
    )
    return parser


def _collect_transport_options(args: argparse.Namespace) -> dict:
    if args.transport != "ygg-embedded":
        return {}

    return {
        "binary_path": args.yggdrasil_binary,
        "config_path": args.yggdrasil_config,
        "public_peers": args.yggdrasil_peer,
    }


def _infer_tracker_discovery_scheme(url: str) -> str:
    if url.startswith("udp://"):
        return "udp-tracker"
    if url.startswith("http://") or url.startswith("https://"):
        return "http-tracker"
    raise ValueError("Could not infer discovery scheme from tracker URL")


def _collect_discovery_config(args: argparse.Namespace) -> tuple[str, dict]:
    if not args.discover_nickname:
        raise ValueError("Discovery config requested without --discover-nickname")
    if not args.discover_key:
        raise ValueError("--discover-key is required with --discover-nickname")

    if args.discovery_scheme == "mainline-dht":
        return "mainline-dht", {}

    if args.tracker_url:
        scheme = args.discovery_scheme or _infer_tracker_discovery_scheme(args.tracker_url)
        return scheme, {"tracker_url": args.tracker_url}

    tracker = get_tracker_by_name(args.tracker_preset)
    scheme = args.discovery_scheme or tracker.discovery_scheme
    return scheme, {"tracker_url": tracker.announce_url}


def _show_identity(args: argparse.Namespace) -> None:
    identity_priv = load_or_create_identity(args.identity)
    fp_base64 = fingerprint(identity_priv.public_key)
    payload = build_identity_qr_payload(fp_base64, args.user_label)

    print("Your identity fingerprint (Base64):")
    print(fp_base64)
    print("\nShare this fingerprint to verify your identity.")

    if args.peer_fingerprint:
        peer_bytes = _decode_fp_bytes(args.peer_fingerprint)
        sas = compute_sas(identity_priv.public_key.encode(), peer_bytes)
        print(f"\nSafety number (SAS) with peer: {sas}")

    print("\nQR code (ASCII):")
    print(render_qr_ascii(payload))
    if args.qr_file:
        save_qr_png(payload, args.qr_file)
        print(f"Saved QR to {args.qr_file}")


def _export_identity(args: argparse.Namespace) -> None:
    identity_priv = load_or_create_identity(args.identity)
    fp_base64 = fingerprint(identity_priv.public_key)
    payload = build_identity_qr_payload(fp_base64, args.user_label)

    if args.output_file:
        with open(args.output_file, "w", encoding="utf-8") as f:
            f.write(payload)
        print(f"Identity payload written to {args.output_file}")
    else:
        print(payload)


def _verify_identity_command(args: argparse.Namespace) -> int:
    trust_store = TrustStore(args.trust_store)
    if not args.peer_fingerprint:
        raise SystemExit("--peer-fingerprint is required for verify-identity")
    current_peer_fp = _normalize_peer_fingerprint(args.peer_fingerprint)

    payload_source = args.payload or args.fingerprint
    if not payload_source:
        payload_source = sys.stdin.read().strip()
    if not payload_source:
        raise SystemExit("Provide --payload JSON or --fingerprint string to verify")

    verified = False
    try:
        verified = verify_identity_payload(
            payload_source, current_peer_fp, trust_store, label=args.user_label
        )
    except ValueError as exc:
        print(f"Failed to parse payload: {exc}")
        return 1

    if verified:
        print(
            "✅ Identity verified. This contact is now protected against impersonation"
            " unless their key changes."
        )
        return 0

    print(
        "❌ Identity mismatch! The provided fingerprint does not match the connected peer.\n"
        "Possible active MITM or wrong contact."
    )
    return 2


def _generate_discovery_command(args: argparse.Namespace) -> None:
    print(f"Discovery name: {generate_discovery_name(args.discovery_seed)}")
    print(f"Discovery key:  {generate_discovery_key()}")
    print("Share both values with your peer and use the same pair on both sides.")


async def _handle_input(queue: asyncio.Queue, stop_event: asyncio.Event) -> None:
    """Read user input without relying on proactor read pipes (Windows-safe)."""

    loop = asyncio.get_running_loop()
    try:
        while not stop_event.is_set():
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                break
            text = line.rstrip("\n")
            await queue.put(text)
    except asyncio.CancelledError:
        return


async def _receive_messages(session: Session, file_receiver: FileReceiver) -> None:
    while True:
        msg = await session.receive_message()
        processed, info = file_receiver.handle(msg)
        if processed:
            if info:
                print(info)
            continue
        if msg.get("type") == "status":
            state = msg.get("state")
            reason = msg.get("reason")
            ts = datetime.utcfromtimestamp(msg.get("timestamp", 0)).isoformat()
            note = f"[{ts}] Peer is {state}"
            if reason:
                note = f"{note} ({reason})"
            print(note)
            if state == "offline":
                break
            continue

        ts = datetime.utcfromtimestamp(msg.get("timestamp", 0)).isoformat()
        print(f"[{ts}] {msg.get('body')}")


async def _flush_outbox(session: Session, outbox: Outbox) -> None:
    for pending in list(outbox.pending()):
        target_fp = pending.get("peer_fp")
        if not target_fp:
            logger.warning(
                "Skipping queued message %s without peer fingerprint; "
                "reconnect to the original peer and resend",
                pending.get("id"),
            )
            continue
        if session.peer_fingerprint and target_fp != session.peer_fingerprint:
            logger.warning(
                "Skipping queued message %s for peer %s (current peer %s)",
                pending.get("id"),
                target_fp,
                session.peer_fingerprint,
            )
            continue
        await session.send_reliable(dict(pending))
        outbox.mark_sent(pending["id"])


async def _send_loop(
    session: Session,
    outbox: Outbox,
    queue: asyncio.Queue,
    stop_event: asyncio.Event,
) -> None:
    await _flush_outbox(session, outbox)
    while not stop_event.is_set():
        body = await queue.get()
        now = datetime.now(timezone.utc).timestamp()
        pending = outbox.add_chat(
            body,
            now,
            peer_fp=session.peer_fingerprint,
        )
        await session.send_reliable(dict(pending))
        outbox.mark_sent(pending["id"])


async def _discovery_presence_loop(
    provider,
    *,
    nickname: str,
    shared_code: str,
    transport: str,
    bind: str,
    port: int,
    stop_event: asyncio.Event,
) -> None:
    endpoint = PeerEndpoint(host=bind, port=port)
    withdraw_needed = False
    try:
        while not stop_event.is_set():
            descriptor = await provider.announce(
                nickname,
                shared_code,
                transport=transport,
                endpoints=[endpoint],
            )
            withdraw_needed = True
            ttl = max(
                15,
                int(
                    descriptor.expires_at
                    - datetime.now(timezone.utc).timestamp()
                ),
            )
            refresh_in = max(15, ttl // 2)
            logger.info(
                "Discovery presence refreshed for %s; endpoint=%s:%s expires=%s "
                "(next refresh in about %ss)",
                nickname,
                bind,
                port,
                datetime.fromtimestamp(
                    descriptor.expires_at,
                    timezone.utc,
                ).strftime("%Y-%m-%d %H:%M:%SZ"),
                refresh_in,
            )
            try:
                await asyncio.wait_for(stop_event.wait(), timeout=refresh_in)
            except asyncio.TimeoutError:
                continue
    except asyncio.CancelledError:
        raise
    finally:
        if withdraw_needed:
            with contextlib.suppress(Exception):
                await provider.withdraw(nickname, shared_code)


async def _establish_session(
    args: argparse.Namespace,
    identity_priv,
    trust_store: TrustStore,
    transport_options: dict,
    existing_listener=None,
    existing_provider=None,
):
    if args.discover_nickname and args.discover_listen:
        discovery_scheme, discovery_options = _collect_discovery_config(args)
        provider = existing_provider or get_discovery_provider(
            discovery_scheme,
            peer_port=args.port,
            transport=args.transport,
            **discovery_options,
        )
        listener_host = args.listen or args.discover_bind
        listener = existing_listener or transport_listen(
            args.transport,
            listener_host,
            args.port,
            **transport_options,
        )
        if not existing_listener:
            logger.info(
                "Discovery listen mode: %s announced via %s, listening on %s:%s over %s",
                args.discover_nickname,
                discovery_scheme,
                listener_host,
                args.port,
                args.transport,
            )
        reader, writer = await listener.__anext__()
        session = await Session.create(
            reader,
            writer,
            initiator=False,
            identity_priv=identity_priv,
            trust_store=trust_store,
            expected_fingerprint=args.expect_fingerprint,
            ack_timeout=args.ack_timeout,
            max_retries=args.max_retries,
            backoff_factor=args.ack_backoff,
            peer_label=args.peer_label,
        )
        logger.info(
            "Discovery listener accepted peer (fingerprint %s, trust %s)",
            session.peer_fingerprint,
            session.trust_status or "unknown",
        )
        return session, listener, provider

    if args.discover_nickname:
        discovery_scheme, discovery_options = _collect_discovery_config(args)
        provider = existing_provider or get_discovery_provider(
            discovery_scheme,
            peer_port=args.port,
            transport=args.transport,
            **discovery_options,
        )
        logger.info(
            "Discovery mode: announcing %s via %s and resolving peers",
            args.discover_nickname,
            discovery_scheme,
        )
        await provider.announce(
            args.discover_nickname,
            args.discover_key,
            transport=args.transport,
            endpoints=[PeerEndpoint(host=args.discover_bind, port=args.port)],
        )
        descriptors = await provider.resolve(
            args.discover_nickname,
            args.discover_key,
            expected_fingerprint=args.expect_fingerprint,
        )
        if not descriptors:
            raise RuntimeError("Discovery found no active peers for that nickname and key")

        last_error: Optional[Exception] = None
        for descriptor in descriptors:
            for endpoint in descriptor.endpoints:
                try:
                    reader, writer = await transport_connect(
                        args.transport, endpoint.host, endpoint.port, **transport_options
                    )
                    session = await Session.create(
                        reader,
                        writer,
                        initiator=True,
                        identity_priv=identity_priv,
                        trust_store=trust_store,
                        expected_fingerprint=args.expect_fingerprint,
                        ack_timeout=args.ack_timeout,
                        max_retries=args.max_retries,
                        backoff_factor=args.ack_backoff,
                        peer_label=args.peer_label,
                    )
                    logger.info(
                        "Discovery connected to %s:%s over %s (peer %s, trust %s)",
                        endpoint.host,
                        endpoint.port,
                        args.transport,
                        session.peer_fingerprint,
                        session.trust_status or "unknown",
                    )
                    return session, None, provider
                except Exception as exc:  # noqa: BLE001
                    last_error = exc
        raise RuntimeError(f"Discovery found peers but connect failed: {last_error}")
    if args.connect:
        reader, writer = await transport_connect(
            args.transport, args.connect, args.port, **transport_options
        )
        session = await Session.create(
            reader,
            writer,
            initiator=True,
            identity_priv=identity_priv,
            trust_store=trust_store,
            expected_fingerprint=args.expect_fingerprint,
            ack_timeout=args.ack_timeout,
            max_retries=args.max_retries,
            backoff_factor=args.ack_backoff,
            peer_label=args.peer_label,
        )
        logger.info(
            "Connected to %s:%s over %s (peer %s, trust %s)",
            args.connect,
            args.port,
            args.transport,
            session.peer_fingerprint,
            session.trust_status or "unknown",
        )
    elif args.rendezvous:
        listener = existing_listener or transport_listen(
            args.transport, args.rendezvous_bind, args.port, **transport_options
        )
        if not existing_listener:
            logger.info(
                "Rendezvous mode: listening on %s:%s and dialing %s over %s",
                args.rendezvous_bind,
                args.port,
                args.rendezvous,
                args.transport,
            )
        dial_task = asyncio.create_task(
            transport_connect(args.transport, args.rendezvous, args.port, **transport_options)
        )
        accept_task = asyncio.create_task(listener.__anext__())
        reader = writer = None
        initiator = False
        pending = {dial_task, accept_task}
        while pending and reader is None:
            done, pending = await asyncio.wait(
                pending, return_when=asyncio.FIRST_COMPLETED
            )
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
            with contextlib.suppress(Exception):
                await task
        if reader is None or writer is None:
            raise RuntimeError("Rendezvous connect/listen attempts failed")

        session = await Session.create(
            reader,
            writer,
            initiator=initiator,
            identity_priv=identity_priv,
            trust_store=trust_store,
            expected_fingerprint=args.expect_fingerprint,
            ack_timeout=args.ack_timeout,
            max_retries=args.max_retries,
            backoff_factor=args.ack_backoff,
            peer_label=args.peer_label,
        )
        logger.info(
            "Rendezvous established over %s (peer %s, trust %s, role %s)",
            args.transport,
            session.peer_fingerprint,
            session.trust_status or "unknown",
            "dialer" if initiator else "listener",
        )
        return session, listener, None
    else:
        listener = existing_listener or transport_listen(
            args.transport, args.listen, args.port, **transport_options
        )
        if not existing_listener:
            logger.info(
                "Listening on %s:%s over %s", args.listen, args.port, args.transport
            )
        reader, writer = await listener.__anext__()
        session = await Session.create(
            reader,
            writer,
            initiator=False,
            identity_priv=identity_priv,
            trust_store=trust_store,
            expected_fingerprint=args.expect_fingerprint,
            ack_timeout=args.ack_timeout,
            max_retries=args.max_retries,
            backoff_factor=args.ack_backoff,
            peer_label=args.peer_label,
        )
        logger.info(
            "Peer connected (fingerprint %s, trust %s)",
            session.peer_fingerprint,
            session.trust_status or "unknown",
        )
        return session, listener, None
    return session, None, None


async def run(args) -> None:
    _configure_logging(args.verbose)

    if args.command != "chat":
        if args.command == "show-identity":
            _show_identity(args)
        elif args.command == "export-identity":
            _export_identity(args)
        elif args.command == "verify-identity":
            code = _verify_identity_command(args)
            if code:
                raise SystemExit(code)
        elif args.command == "generate-discovery":
            _generate_discovery_command(args)
        return

    if bool(args.discover_nickname) != bool(args.discover_key):
        raise SystemExit("Use --discover-nickname and --discover-key together")

    if args.discover_listen and not args.discover_nickname:
        raise SystemExit("--discover-listen requires --discover-nickname and --discover-key")

    if not any([args.listen, args.connect, args.rendezvous, args.discover_nickname]):
        raise SystemExit(
            "Specify --listen, --connect, --rendezvous, or --discover-nickname for chat mode"
        )

    session: Optional[Session] = None
    transport_options = _collect_transport_options(args)

    identity_priv = load_or_create_identity(args.identity)
    trust_store = TrustStore(args.trust_store)
    outbox = Outbox()
    downloads_dir = Path(args.downloads_dir).expanduser() if args.downloads_dir else None
    file_receiver = FileReceiver(downloads_dir)

    logger.info("Local fingerprint: %s", fingerprint(identity_priv.public_key))

    user_queue: asyncio.Queue = asyncio.Queue()
    stop_event = asyncio.Event()

    listener = None
    provider = None
    presence_task: Optional[asyncio.Task] = None

    input_task = asyncio.create_task(_handle_input(user_queue, stop_event))
    main_task = asyncio.current_task()
    loop = asyncio.get_running_loop()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(
                sig,
                lambda sig=sig: _request_shutdown(stop_event, main_task),
            )
        except NotImplementedError:
            pass

    backoff = 1.0
    try:
        if args.discover_nickname and args.discover_listen:
            discovery_scheme, discovery_options = _collect_discovery_config(args)
            provider = get_discovery_provider(
                discovery_scheme,
                peer_port=args.port,
                transport=args.transport,
                **discovery_options,
            )
            presence_task = asyncio.create_task(
                _discovery_presence_loop(
                    provider,
                    nickname=args.discover_nickname,
                    shared_code=args.discover_key,
                    transport=args.transport,
                    bind=args.discover_bind,
                    port=args.port,
                    stop_event=stop_event,
                )
            )

        while not stop_event.is_set():
            tasks: list[asyncio.Task] = []
            try:
                session, listener, provider = await _establish_session(
                    args,
                    identity_priv,
                    trust_store,
                    transport_options,
                    listener,
                    provider,
                )
                backoff = 1.0
                if session.trust_warning:
                    logger.warning("%s", session.trust_warning)

                tasks = [
                    asyncio.create_task(_receive_messages(session, file_receiver)),
                    asyncio.create_task(_send_loop(session, outbox, user_queue, stop_event)),
                ]
                done, pending = await asyncio.wait(
                    tasks, return_when=asyncio.FIRST_COMPLETED
                )
                for task in pending:
                    task.cancel()
                await asyncio.gather(*pending, return_exceptions=True)
                for task in done:
                    exc = task.exception()
                    if exc:
                        raise exc
            except asyncio.CancelledError:
                break
            except Exception as exc:  # noqa: BLE001
                logger.warning("Connection loop error: %s", exc)
            finally:
                if session:
                    await session.close()
                session = None
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                if stop_event.is_set():
                    break

            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 10)
    except asyncio.CancelledError:
        logger.info("Shutdown requested; stopping CLI")
    finally:
        stop_event.set()
        input_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await input_task
        if presence_task:
            presence_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await presence_task
        if session:
            await session.close()
        if listener:
            with contextlib.suppress(Exception):
                await listener.aclose()


if __name__ == "__main__":
    _configure_event_loop_policy()
    parser = build_parser()
    args = parser.parse_args()
    runner = uvloop.run if uvloop is not None else asyncio.run
    try:
        runner(run(args))
    except KeyboardInterrupt:
        pass
