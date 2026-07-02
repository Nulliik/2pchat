import asyncio
import base64
import contextlib
import json
import logging
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Tuple

from nacl.public import PrivateKey, PublicKey
from nacl.signing import SigningKey, VerifyKey
from nacl.exceptions import BadSignatureError

from . import protocol
from .crypto import (
    PeerState,
    decrypt_message,
    encrypt_message,
    generate_identity_keypair,
)
from .identity import (
    PeerStatus,
    TrustStore,
    fingerprint,
    load_or_create_signing_identity,
)
from messenger.utils.logger import setup_logger

FRAME_HEADER = 4

logger = setup_logger("messenger.session", logging.INFO)
HANDSHAKE_CONTEXT = b"p2p-chat-handshake-v1"


async def _read_frame(reader: asyncio.StreamReader) -> bytes:
    length_data = await reader.readexactly(FRAME_HEADER)
    length = int.from_bytes(length_data, "big")
    return await reader.readexactly(length)


async def _write_frame(writer: asyncio.StreamWriter, payload: bytes) -> None:
    writer.write(len(payload).to_bytes(FRAME_HEADER, "big") + payload)
    await writer.drain()


class Session:
    """Manage encrypted session over an asyncio stream pair with reliability."""

    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        *,
        identity_priv: Optional[PrivateKey] = None,
        signing_key: Optional[SigningKey] = None,
        trust_store: Optional[TrustStore] = None,
        ack_timeout: float = 5.0,
        max_retries: int = 3,
        backoff_factor: float = 1.5,
        peer_label: Optional[str] = None,
    ):
        self.reader = reader
        self.writer = writer
        self.my_priv: PrivateKey = identity_priv or generate_identity_keypair()[1]
        self.my_pub: PublicKey = self.my_priv.public_key
        self.prekey_priv: PrivateKey = PrivateKey.generate()
        self.prekey_pub: PublicKey = self.prekey_priv.public_key
        self.my_signing: SigningKey = signing_key or load_or_create_signing_identity()
        self.my_verify: VerifyKey = self.my_signing.verify_key
        self.their_pub: Optional[PublicKey] = None
        self.their_prekey_pub: Optional[PublicKey] = None
        self.their_verify: Optional[VerifyKey] = None
        self.trust_store = trust_store
        self.ack_timeout = ack_timeout
        self.max_retries = max_retries
        self.backoff_factor = max(1.0, backoff_factor)
        self.peer_label = peer_label

        self._crypto_state = PeerState()

        self._pending_acks: Dict[str, asyncio.Future] = {}
        self._message_queue: asyncio.Queue = asyncio.Queue()
        self._reader_task: Optional[asyncio.Task] = None
        self._peer_fp: Optional[str] = None
        self.peer_fingerprint: Optional[str] = None
        self.trust_status: Optional[str] = None
        self.trust_warning: Optional[str] = None
        self._message_counter = 0
        self._online = True
        self._status_sent = False

    @staticmethod
    def _serialize_pubkey(pub: PublicKey) -> bytes:
        return bytes(pub)

    @staticmethod
    def _deserialize_pubkey(data: bytes) -> PublicKey:
        return PublicKey(data)

    def _handshake_payload(self) -> bytes:
        eph_pub = self._serialize_pubkey(self.my_pub)
        prekey_pub = self._serialize_pubkey(self.prekey_pub)
        id_pub = bytes(self.my_verify)
        to_sign = HANDSHAKE_CONTEXT + eph_pub + prekey_pub + id_pub
        signature = self.my_signing.sign(to_sign).signature
        payload = {
            "type": "handshake",
            "version": 2,
            "ephPub": base64.b64encode(eph_pub).decode(),
            "prekeyPub": base64.b64encode(prekey_pub).decode(),
            "identityPub": base64.b64encode(id_pub).decode(),
            "signature": base64.b64encode(signature).decode(),
        }
        return json.dumps(payload, separators=(",", ":")).encode()

    @staticmethod
    def _parse_handshake(data: bytes) -> Tuple[PublicKey, VerifyKey, PublicKey]:
        try:
            obj = json.loads(data.decode())
            if obj.get("type") != "handshake":
                raise ValueError("unexpected handshake payload")
            if obj.get("version") != 2:
                raise ValueError("unsupported handshake version")
            eph_pub_b = base64.b64decode(obj["ephPub"])
            prekey_pub_b = base64.b64decode(obj["prekeyPub"])
            id_pub_b = base64.b64decode(obj["identityPub"])
            sig = base64.b64decode(obj["signature"])
            verify_key = VerifyKey(id_pub_b)
            verify_key.verify(HANDSHAKE_CONTEXT + eph_pub_b + prekey_pub_b + id_pub_b, sig)
            return PublicKey(eph_pub_b), verify_key, PublicKey(prekey_pub_b)
        except (KeyError, ValueError, BadSignatureError, json.JSONDecodeError) as exc:
            raise ValueError("Invalid signed handshake payload") from exc

    async def _exchange_keys(
        self,
        initiator: bool,
        expected_fingerprint: Optional[str] = None,
    ) -> Tuple[PublicKey, PrivateKey, PublicKey]:
        if initiator:
            await _write_frame(self.writer, self._handshake_payload())
            their_data = await _read_frame(self.reader)
        else:
            their_data = await _read_frame(self.reader)
            await _write_frame(self.writer, self._handshake_payload())

        their_pub, their_verify, their_prekey = self._parse_handshake(their_data)
        self.their_pub = their_pub
        self.their_verify = their_verify
        self.their_prekey_pub = their_prekey
        peer_fp_source = their_pub
        self._peer_fp = fingerprint(peer_fp_source)
        self.peer_fingerprint = self._peer_fp
        logger.debug(
            "Key exchange complete (initiator=%s, peer_fp=%s, recv_bytes=%s)",
            initiator,
            self._peer_fp,
            len(their_data),
        )
        if self.trust_store:
            if expected_fingerprint and expected_fingerprint not in (
                self._peer_fp,
                fingerprint(their_pub),
            ):
                raise ValueError(
                    f"Peer fingerprint mismatch. Expected {expected_fingerprint} but saw {self._peer_fp}."
                )
            self.trust_store.expected_or_raise(self._peer_fp, expected_fingerprint)
            status: PeerStatus = self.trust_store.note_peer(
                self._peer_fp,
                timestamp=datetime.now(timezone.utc).timestamp(),
                label=self.peer_label,
            )
            if status.warning:
                raise ValueError(f"TOFU Verification Conflict: {status.warning}")
            self.trust_status = status.state
            self.trust_warning = status.warning


        return self.my_pub, self.my_priv, their_pub

    @classmethod
    async def create(
        cls,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        initiator: bool,
        *,
        identity_priv: Optional[PrivateKey] = None,
        signing_key: Optional[SigningKey] = None,
        trust_store: Optional[TrustStore] = None,
        expected_fingerprint: Optional[str] = None,
        ack_timeout: float = 5.0,
        max_retries: int = 3,
        backoff_factor: float = 1.5,
        peer_label: Optional[str] = None,
    ) -> "Session":
        session = cls(
            reader,
            writer,
            identity_priv=identity_priv,
            trust_store=trust_store,
            ack_timeout=ack_timeout,
            max_retries=max_retries,
            backoff_factor=backoff_factor,
            peer_label=peer_label,
            signing_key=signing_key,
        )
        await session._exchange_keys(initiator, expected_fingerprint)
        session._start_reader()
        return session

    def _start_reader(self) -> None:
        if self._reader_task:
            return

        async def _loop():
            reason: Optional[str] = None
            try:
                while True:
                    ciphertext = await _read_frame(self.reader)
                    logger.debug(
                        "Recv frame: %s bytes from peer %s", len(ciphertext), self._peer_fp
                    )
                    plaintext = decrypt_message(
                        self.my_priv,
                        self.their_pub,  # type: ignore[arg-type]
                        self._crypto_state,
                        ciphertext,
                        my_prekey_priv=self.prekey_priv,
                    )
                    message = protocol.decode_message(plaintext)

                    if message.get("type") == "ack":
                        ack_id = message.get("ack_id")
                        future = self._pending_acks.get(ack_id)
                        if future and not future.done():
                            future.set_result(True)
                        continue

                    await self._send_ack(message.get("id"))
                    logger.debug(
                        "Received message type=%s id=%s len=%s",
                        message.get("type"),
                        message.get("id"),
                        len(plaintext),
                    )
                    await self._message_queue.put(message)
            except asyncio.CancelledError:
                reason = "local-closed"
                return
            except Exception as exc:  # noqa: BLE001
                reason = repr(exc)
                for fut in self._pending_acks.values():
                    if not fut.done():
                        fut.cancel()
            finally:
                await self._emit_offline(reason)
                self._pending_acks.clear()

        self._reader_task = asyncio.create_task(_loop())

    async def _emit_offline(self, reason: Optional[str] = None) -> None:
        if not self._online or self._status_sent:
            return
        self._online = False
        self._status_sent = True
        await self._message_queue.put(
            {
                "type": "status",
                "state": "offline",
                "timestamp": int(datetime.now(timezone.utc).timestamp()),
                "reason": reason,
            }
        )

    async def _send_payload(self, message: Dict[str, Any]) -> None:
        if not self.their_pub:
            raise RuntimeError("Session not established")
        plaintext = protocol.encode_message(message)
        ciphertext = encrypt_message(
            self.my_priv,
            self.their_pub,
            self._crypto_state,
            plaintext,
            their_prekey_pub=self.their_prekey_pub,
        )
        logger.debug(
            "Send message type=%s id=%s plain=%sB cipher=%sB",
            message.get("type"),
            message.get("id"),
            len(plaintext),
            len(ciphertext),
        )
        await _write_frame(self.writer, ciphertext)

    async def send_reliable(self, message: Dict[str, Any]) -> str:
        """Send a message and wait for an ACK with retries."""

        if "id" not in message:
            message["id"] = str(self._message_counter)
            self._message_counter += 1
        msg_id = message["id"]

        loop = asyncio.get_running_loop()
        ack_future = loop.create_future()
        self._pending_acks[msg_id] = ack_future

        delay = self.ack_timeout
        for attempt in range(self.max_retries + 1):
            await self._send_payload(message)
            try:
                await asyncio.wait_for(
                    asyncio.shield(ack_future), timeout=self.ack_timeout
                )
                break
            except asyncio.TimeoutError:
                if attempt < self.max_retries:
                    await asyncio.sleep(delay)
                    delay *= self.backoff_factor

        if not ack_future.done():
            self._pending_acks.pop(msg_id, None)
            raise TimeoutError(f"ACK for message {msg_id} not received")

        await ack_future
        logger.debug("ACK received for message %s", msg_id)
        self._pending_acks.pop(msg_id, None)
        return msg_id

    async def send_chat(self, body: str, nickname: Optional[str] = None) -> str:
        message = {
            "type": "chat",
            "timestamp": int(datetime.now(timezone.utc).timestamp()),
            "body": body,
        }
        if nickname:
            message["nickname"] = nickname
        return await self.send_reliable(message)

    async def _send_ack(self, msg_id: Optional[str]) -> None:
        if msg_id is None:
            return
        ack = {
            "type": "ack",
            "ack_id": msg_id,
            "timestamp": int(datetime.now(timezone.utc).timestamp()),
        }
        await self._send_payload(ack)

    async def receive_message(self) -> Dict[str, Any]:
        if not self.their_pub:
            raise RuntimeError("Session not established")

        return await self._message_queue.get()

    @property
    def peer_fp(self) -> Optional[str]:
        return self.peer_fingerprint

    @property
    def is_online(self) -> bool:
        return self._online

    async def close(self) -> None:
        if self._reader_task:
            self._reader_task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._reader_task
        self.writer.close()
        try:
            await self.writer.wait_closed()
        except Exception:
            pass
