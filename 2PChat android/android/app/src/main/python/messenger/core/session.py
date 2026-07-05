import asyncio
import base64
import contextlib
import json
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, Optional, Tuple

from nacl.exceptions import BadSignatureError
from nacl.public import PrivateKey, PublicKey
from nacl.signing import SigningKey, VerifyKey

from messenger.utils.logger import setup_logger

from . import protocol
from .crypto import (
    PeerState,
    decrypt_message as decrypt_message_v2,
    encrypt_message as encrypt_message_v2,
    generate_identity_keypair,
)
from .double_ratchet import (
    IdentityKeyPair as DRIdentityKeyPair,
    PreKeyBundle,
    decrypt_message as decrypt_message_v3,
    encrypt_message as encrypt_message_v3,
    initialize_session_from_prekey,
    respond_to_prekey_init,
)
from .identity import (
    PeerStatus,
    TrustStore,
    fingerprint,
    load_or_create_signing_identity,
)

FRAME_HEADER = 4
PROTOCOL_V2 = 2
PROTOCOL_V3 = 3

logger = setup_logger("messenger.session", logging.INFO)
HANDSHAKE_CONTEXT = b"p2p-chat-handshake-v1"
X3DH_HANDSHAKE_CONTEXT = b"p2p-chat-x3dh-handshake-v1"
SIGNED_PREKEY_CONTEXT = b"p2p-chat-signed-prekey-v1"


@dataclass
class HandshakeV2:
    identity_pub: PublicKey
    verify_key: VerifyKey
    prekey_pub: PublicKey


@dataclass
class HandshakeV3:
    role: str
    identity_pub: PublicKey
    verify_key: VerifyKey
    signed_prekey_pub: PublicKey
    prekey_signature: bytes
    session_signature: bytes
    ephemeral_pub: Optional[PublicKey] = None


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
        protocol_version: int = PROTOCOL_V3,
    ):
        self.reader = reader
        self.writer = writer
        self.protocol_version = protocol_version
        self.my_priv: PrivateKey = identity_priv or generate_identity_keypair()[1]
        self.my_pub: PublicKey = self.my_priv.public_key
        self.prekey_priv: PrivateKey = PrivateKey.generate()
        self.prekey_pub: PublicKey = self.prekey_priv.public_key
        self.bootstrap_eph_priv: PrivateKey = PrivateKey.generate()
        self.bootstrap_eph_pub: PublicKey = self.bootstrap_eph_priv.public_key
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
        self._dr_state = None

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
    def _sign_prekey(signing_key: SigningKey, prekey_pub: PublicKey) -> bytes:
        return signing_key.sign(SIGNED_PREKEY_CONTEXT + bytes(prekey_pub)).signature

    def _handshake_payload(self) -> bytes:
        eph_pub = self._serialize_pubkey(self.my_pub)
        prekey_pub = self._serialize_pubkey(self.prekey_pub)
        id_pub = bytes(self.my_verify)
        to_sign = HANDSHAKE_CONTEXT + eph_pub + prekey_pub + id_pub
        signature = self.my_signing.sign(to_sign).signature
        payload = {
            "type": "handshake",
            "version": PROTOCOL_V2,
            "ephPub": base64.b64encode(eph_pub).decode(),
            "prekeyPub": base64.b64encode(prekey_pub).decode(),
            "identityPub": base64.b64encode(id_pub).decode(),
            "signature": base64.b64encode(signature).decode(),
        }
        return json.dumps(payload, separators=(",", ":")).encode()

    def _x3dh_payload(self, role: str) -> bytes:
        identity_pub = bytes(self.my_pub)
        verify_pub = bytes(self.my_verify)
        signed_prekey_pub = bytes(self.prekey_pub)
        prekey_signature = self._sign_prekey(self.my_signing, self.prekey_pub)
        eph_pub = bytes(self.bootstrap_eph_pub) if role == "init" else b""
        to_sign = (
            X3DH_HANDSHAKE_CONTEXT
            + role.encode("ascii")
            + identity_pub
            + verify_pub
            + signed_prekey_pub
            + eph_pub
        )
        session_signature = self.my_signing.sign(to_sign).signature
        payload = {
            "type": "handshake",
            "version": PROTOCOL_V3,
            "role": role,
            "identityPub": base64.b64encode(identity_pub).decode(),
            "verifyPub": base64.b64encode(verify_pub).decode(),
            "signedPrekeyPub": base64.b64encode(signed_prekey_pub).decode(),
            "prekeySignature": base64.b64encode(prekey_signature).decode(),
            "signature": base64.b64encode(session_signature).decode(),
        }
        if role == "init":
            payload["ephPub"] = base64.b64encode(eph_pub).decode()
        return json.dumps(payload, separators=(",", ":")).encode()

    @staticmethod
    def _parse_handshake(data: bytes) -> Tuple[PublicKey, VerifyKey, PublicKey]:
        parsed = Session._parse_any_handshake(data)
        if not isinstance(parsed, HandshakeV2):
            raise ValueError("expected version 2 handshake")
        return parsed.identity_pub, parsed.verify_key, parsed.prekey_pub

    @staticmethod
    def _parse_x3dh_handshake(data: bytes) -> HandshakeV3:
        parsed = Session._parse_any_handshake(data)
        if not isinstance(parsed, HandshakeV3):
            raise ValueError("expected version 3 handshake")
        return parsed

    @staticmethod
    def _parse_any_handshake(data: bytes) -> HandshakeV2 | HandshakeV3:
        try:
            obj = json.loads(data.decode())
        except json.JSONDecodeError as exc:
            raise ValueError("Invalid signed handshake payload") from exc

        if obj.get("type") != "handshake":
            raise ValueError("unexpected handshake payload")

        version = obj.get("version")
        if version == PROTOCOL_V2:
            try:
                eph_pub_b = base64.b64decode(obj["ephPub"])
                prekey_pub_b = base64.b64decode(obj["prekeyPub"])
                id_pub_b = base64.b64decode(obj["identityPub"])
                sig = base64.b64decode(obj["signature"])
                verify_key = VerifyKey(id_pub_b)
                verify_key.verify(HANDSHAKE_CONTEXT + eph_pub_b + prekey_pub_b + id_pub_b, sig)
            except (KeyError, ValueError, BadSignatureError) as exc:
                raise ValueError("Invalid signed handshake payload") from exc
            return HandshakeV2(
                identity_pub=PublicKey(eph_pub_b),
                verify_key=verify_key,
                prekey_pub=PublicKey(prekey_pub_b),
            )

        if version == PROTOCOL_V3:
            try:
                role = obj["role"]
                if role not in {"init", "reply"}:
                    raise ValueError("invalid v3 handshake role")
                identity_pub_b = base64.b64decode(obj["identityPub"])
                verify_pub_b = base64.b64decode(obj["verifyPub"])
                signed_prekey_pub_b = base64.b64decode(obj["signedPrekeyPub"])
                prekey_sig = base64.b64decode(obj["prekeySignature"])
                session_sig = base64.b64decode(obj["signature"])
                eph_pub_b64 = obj.get("ephPub")
                if role == "init" and not isinstance(eph_pub_b64, str):
                    raise ValueError("missing initiator ephemeral key")
                eph_pub_b = (
                    base64.b64decode(eph_pub_b64) if isinstance(eph_pub_b64, str) else b""
                )
                verify_key = VerifyKey(verify_pub_b)
                verify_key.verify(
                    SIGNED_PREKEY_CONTEXT + signed_prekey_pub_b,
                    prekey_sig,
                )
                verify_key.verify(
                    X3DH_HANDSHAKE_CONTEXT
                    + role.encode("ascii")
                    + identity_pub_b
                    + verify_pub_b
                    + signed_prekey_pub_b
                    + eph_pub_b,
                    session_sig,
                )
            except (KeyError, ValueError, BadSignatureError) as exc:
                raise ValueError("Invalid signed handshake payload") from exc
            return HandshakeV3(
                role=role,
                identity_pub=PublicKey(identity_pub_b),
                verify_key=verify_key,
                signed_prekey_pub=PublicKey(signed_prekey_pub_b),
                prekey_signature=prekey_sig,
                session_signature=session_sig,
                ephemeral_pub=PublicKey(eph_pub_b) if eph_pub_b else None,
            )

        raise ValueError("unsupported handshake version")

    def _note_peer(
        self,
        their_pub: PublicKey,
        expected_fingerprint: Optional[str],
    ) -> None:
        self._peer_fp = fingerprint(their_pub)
        self.peer_fingerprint = self._peer_fp
        if not self.trust_store:
            return
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

    async def _exchange_v2(
        self,
        initiator: bool,
        expected_fingerprint: Optional[str],
        initial_remote: Optional[HandshakeV2] = None,
    ) -> Tuple[PublicKey, PrivateKey, PublicKey]:
        if initiator:
            await _write_frame(self.writer, self._handshake_payload())
            remote = Session._parse_handshake(await _read_frame(self.reader))
        else:
            if initial_remote is None:
                initial_remote = Session._parse_handshake(await _read_frame(self.reader))
            await _write_frame(self.writer, self._handshake_payload())
            remote = (
                initial_remote.identity_pub,
                initial_remote.verify_key,
                initial_remote.prekey_pub,
            )

        their_pub, their_verify, their_prekey = remote
        self.their_pub = their_pub
        self.their_verify = their_verify
        self.their_prekey_pub = their_prekey
        self._note_peer(their_pub, expected_fingerprint)
        logger.debug(
            "Key exchange complete (initiator=%s, peer_fp=%s, protocol=v2)",
            initiator,
            self._peer_fp,
        )
        return self.my_pub, self.my_priv, their_pub

    async def _exchange_v3(
        self,
        initiator: bool,
        expected_fingerprint: Optional[str],
        initial_remote: Optional[HandshakeV3] = None,
    ) -> Tuple[PublicKey, PrivateKey, PublicKey]:
        if initiator:
            await _write_frame(self.writer, self._x3dh_payload("init"))
            remote = Session._parse_x3dh_handshake(await _read_frame(self.reader))
            if remote.role != "reply":
                raise ValueError("initiator expected reply handshake")
            local_eph_pub = self.bootstrap_eph_pub
        else:
            if initial_remote is None:
                initial_remote = Session._parse_x3dh_handshake(await _read_frame(self.reader))
            if initial_remote.role != "init" or initial_remote.ephemeral_pub is None:
                raise ValueError("responder expected init handshake")
            await _write_frame(self.writer, self._x3dh_payload("reply"))
            remote = initial_remote
            local_eph_pub = initial_remote.ephemeral_pub

        self.their_pub = remote.identity_pub
        self.their_verify = remote.verify_key
        self.their_prekey_pub = remote.signed_prekey_pub
        self._note_peer(remote.identity_pub, expected_fingerprint)

        local_identity = DRIdentityKeyPair(
            public=self.my_pub,
            private=self.my_priv,
            signing=self.my_signing,
        )
        if initiator:
            local_ephemeral = DRIdentityKeyPair(
                public=self.bootstrap_eph_pub,
                private=self.bootstrap_eph_priv,
                signing=self.my_signing,
            )
            remote_bundle = PreKeyBundle(
                identity_pub=remote.identity_pub,
                identity_verify_pub=remote.verify_key,
                signed_prekey_pub=remote.signed_prekey_pub,
                signed_prekey_sig=remote.prekey_signature,
            )
            self._dr_state = initialize_session_from_prekey(
                local_identity,
                remote_bundle,
                local_ephemeral,
            )
        else:
            self._dr_state = respond_to_prekey_init(
                local_identity=local_identity,
                signed_prekey=self.prekey_priv,
                local_one_time_prekey=None,
                initiator_identity_pub=remote.identity_pub,
                initiator_ephemeral_pub=local_eph_pub,
            )

        logger.debug(
            "Key exchange complete (initiator=%s, peer_fp=%s, protocol=v3)",
            initiator,
            self._peer_fp,
        )
        return self.my_pub, self.my_priv, remote.identity_pub

    async def _exchange_keys(
        self,
        initiator: bool,
        expected_fingerprint: Optional[str] = None,
    ) -> Tuple[PublicKey, PrivateKey, PublicKey]:
        if initiator:
            if self.protocol_version == PROTOCOL_V2:
                return await self._exchange_v2(True, expected_fingerprint)
            if self.protocol_version == PROTOCOL_V3:
                return await self._exchange_v3(True, expected_fingerprint)
            raise ValueError("unsupported session protocol version")

        initial_payload = await _read_frame(self.reader)
        parsed = Session._parse_any_handshake(initial_payload)
        if isinstance(parsed, HandshakeV2):
            return await self._exchange_v2(False, expected_fingerprint, parsed)
        return await self._exchange_v3(False, expected_fingerprint, parsed)

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
        protocol_version: int = PROTOCOL_V3,
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
            protocol_version=protocol_version,
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
                    if self.protocol_version == PROTOCOL_V3:
                        plaintext = decrypt_message_v3(self._dr_state, ciphertext)
                    else:
                        plaintext = decrypt_message_v2(
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

    @staticmethod
    def _message_ref(message: Dict[str, Any]) -> str:
        if message.get("id") is not None:
            return f"id={message.get('id')}"
        if message.get("ack_id") is not None:
            return f"ack_id={message.get('ack_id')}"
        return "id=None"

    async def _send_payload(self, message: Dict[str, Any]) -> None:
        if not self.their_pub:
            raise RuntimeError("Session not established")
        plaintext = protocol.encode_message(message)
        if self.protocol_version == PROTOCOL_V3:
            ciphertext = encrypt_message_v3(self._dr_state, plaintext)
        else:
            ciphertext = encrypt_message_v2(
                self.my_priv,
                self.their_pub,
                self._crypto_state,
                plaintext,
                their_prekey_pub=self.their_prekey_pub,
            )
        logger.debug(
            "Send message type=%s %s plain=%sB cipher=%sB",
            message.get("type"),
            self._message_ref(message),
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
                await asyncio.wait_for(asyncio.shield(ack_future), timeout=self.ack_timeout)
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
