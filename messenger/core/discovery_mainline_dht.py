from __future__ import annotations

import base64
import hashlib
import json
import secrets
import time
from typing import Dict, List, Protocol, Sequence

from nacl.encoding import Base64Encoder
from nacl.public import PrivateKey
from nacl.signing import SigningKey, VerifyKey

from .discovery_base import DiscoveryProvider, PeerDescriptor, PeerEndpoint
from .identity import fingerprint, load_or_create_identity, load_or_create_signing_identity

DISCOVERY_CONTEXT = "2pchat-mainline-dht-v1"
DESCRIPTOR_VERSION = 1


class MutableRecordBackend(Protocol):
    async def put_record(
        self,
        namespace: bytes,
        payload: bytes,
        *,
        sequence: int,
        expires_at: int,
    ) -> None:
        """Store a mutable record under an app-defined namespace."""

    async def get_records(self, namespace: bytes) -> Sequence[bytes]:
        """Return raw records for the namespace."""


class InMemoryMutableRecordBackend:
    """Simple backend used by tests and local dry-runs.

    A real BitTorrent Mainline DHT backend can map the namespace onto BEP44
    mutable-item coordinates while reusing the provider's descriptor logic.
    """

    def __init__(self) -> None:
        self._records: Dict[bytes, List[dict]] = {}

    async def put_record(
        self,
        namespace: bytes,
        payload: bytes,
        *,
        sequence: int,
        expires_at: int,
    ) -> None:
        bucket = self._records.setdefault(namespace, [])
        bucket.append(
            {
                "payload": payload,
                "sequence": sequence,
                "expires_at": expires_at,
            }
        )

    async def get_records(self, namespace: bytes) -> Sequence[bytes]:
        entries = self._records.get(namespace, [])
        return [entry["payload"] for entry in entries]


class UnavailableMainlineDHTBackend:
    """Placeholder backend until a compatible native DHT client is installed."""

    def __init__(self, reason: str | None = None) -> None:
        self._reason = reason or (
            "No BitTorrent Mainline DHT backend is configured. "
            "Inject a backend implementing MutableRecordBackend."
        )

    async def put_record(
        self,
        namespace: bytes,
        payload: bytes,
        *,
        sequence: int,
        expires_at: int,
    ) -> None:
        raise RuntimeError(self._reason)

    async def get_records(self, namespace: bytes) -> Sequence[bytes]:
        raise RuntimeError(self._reason)


class MainlineDHTDiscovery(DiscoveryProvider):
    """Discovery provider that publishes signed ephemeral descriptors.

    This class owns the application-level record format and lookup-key
    derivation. A backend adapter is responsible for talking to a specific DHT
    client or implementation.
    """

    def __init__(
        self,
        *,
        backend: MutableRecordBackend | None = None,
        identity_priv: PrivateKey | None = None,
        signing_key: SigningKey | None = None,
        ttl_seconds: int = 600,
        time_fn=time.time,
    ) -> None:
        self._backend = backend or UnavailableMainlineDHTBackend()
        self._identity_priv = identity_priv or load_or_create_identity()
        self._signing_key = signing_key or load_or_create_signing_identity()
        self._ttl_seconds = max(30, ttl_seconds)
        self._time_fn = time_fn

    @staticmethod
    def normalize_nickname(value: str) -> str:
        normalized = " ".join(value.strip().lower().split())
        if not normalized:
            raise ValueError("Nickname must not be empty")
        return normalized

    @staticmethod
    def _normalize_shared_code(value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("Shared code must not be empty")
        return normalized

    @classmethod
    def derive_lookup_namespace(cls, nickname: str, shared_code: str) -> bytes:
        normalized_nick = cls.normalize_nickname(nickname)
        normalized_code = cls._normalize_shared_code(shared_code)
        material = (
            f"{DISCOVERY_CONTEXT}:lookup:{normalized_nick}:{normalized_code}".encode("utf-8")
        )
        return hashlib.sha256(material).digest()

    @staticmethod
    def _serialize_endpoint(endpoint: PeerEndpoint) -> dict:
        return {"host": endpoint.host, "port": endpoint.port}

    @classmethod
    def _unsigned_descriptor(
        cls,
        *,
        nickname: str,
        identity_fingerprint: str,
        signing_public_key: str,
        transport: str,
        endpoints: Sequence[PeerEndpoint],
        expires_at: int,
        sequence: int,
        nonce: str,
    ) -> dict:
        return {
            "v": DESCRIPTOR_VERSION,
            "nickname": cls.normalize_nickname(nickname),
            "identity_fingerprint": identity_fingerprint,
            "signing_public_key": signing_public_key,
            "transport": transport,
            "endpoints": [cls._serialize_endpoint(endpoint) for endpoint in endpoints],
            "expires_at": expires_at,
            "sequence": sequence,
            "nonce": nonce,
        }

    @staticmethod
    def _canonical_json(payload: dict) -> bytes:
        return json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")

    def _sign_descriptor(self, payload: dict) -> str:
        signature = self._signing_key.sign(self._canonical_json(payload)).signature
        return base64.b64encode(signature).decode("ascii")

    @staticmethod
    def _decode_signing_key(encoded: str) -> VerifyKey:
        return VerifyKey(Base64Encoder.decode(encoded))

    @classmethod
    def _parse_endpoint(cls, payload: dict) -> PeerEndpoint:
        host = str(payload.get("host", "")).strip()
        port = int(payload.get("port", 0))
        if not host:
            raise ValueError("Discovery endpoint host is required")
        if port < 1 or port > 65535:
            raise ValueError("Discovery endpoint port is out of range")
        return PeerEndpoint(host=host, port=port)

    @classmethod
    def descriptor_from_payload(
        cls,
        payload: bytes,
        *,
        now: int,
    ) -> PeerDescriptor:
        try:
            data = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("Invalid discovery payload encoding") from exc

        if data.get("v") != DESCRIPTOR_VERSION:
            raise ValueError("Unsupported discovery descriptor version")

        signature = data.get("sig")
        if not isinstance(signature, str) or not signature:
            raise ValueError("Missing descriptor signature")

        unsigned = dict(data)
        unsigned.pop("sig", None)
        verify_key = cls._decode_signing_key(str(unsigned.get("signing_public_key", "")))
        verify_key.verify(
            cls._canonical_json(unsigned), base64.b64decode(signature.encode("ascii"))
        )

        endpoints = tuple(cls._parse_endpoint(entry) for entry in unsigned.get("endpoints", []))
        if not endpoints:
            raise ValueError("Descriptor must contain at least one endpoint")

        expires_at = int(unsigned.get("expires_at", 0))
        if expires_at <= now:
            raise ValueError("Descriptor has expired")

        descriptor = PeerDescriptor(
            version=int(unsigned["v"]),
            nickname=cls.normalize_nickname(str(unsigned["nickname"])),
            identity_fingerprint=str(unsigned["identity_fingerprint"]),
            signing_public_key=str(unsigned["signing_public_key"]),
            transport=str(unsigned["transport"]),
            endpoints=endpoints,
            expires_at=expires_at,
            sequence=int(unsigned["sequence"]),
            nonce=str(unsigned["nonce"]),
            signature=signature,
        )
        return descriptor

    async def announce(
        self,
        nickname: str,
        shared_code: str,
        *,
        transport: str,
        endpoints: List[PeerEndpoint],
    ) -> PeerDescriptor:
        normalized_nick = self.normalize_nickname(nickname)
        namespace = self.derive_lookup_namespace(normalized_nick, shared_code)
        now = int(self._time_fn())
        expires_at = now + self._ttl_seconds
        sequence = now
        nonce = secrets.token_urlsafe(9)
        identity_fp = fingerprint(self._identity_priv.public_key)
        signing_pub = self._signing_key.verify_key.encode(encoder=Base64Encoder).decode("ascii")
        unsigned = self._unsigned_descriptor(
            nickname=normalized_nick,
            identity_fingerprint=identity_fp,
            signing_public_key=signing_pub,
            transport=transport,
            endpoints=endpoints,
            expires_at=expires_at,
            sequence=sequence,
            nonce=nonce,
        )
        signature = self._sign_descriptor(unsigned)
        signed = dict(unsigned)
        signed["sig"] = signature
        payload = self._canonical_json(signed)
        await self._backend.put_record(
            namespace,
            payload,
            sequence=sequence,
            expires_at=expires_at,
        )
        return self.descriptor_from_payload(payload, now=now)

    async def resolve(
        self,
        nickname: str,
        shared_code: str,
        *,
        expected_fingerprint: str | None = None,
    ) -> List[PeerDescriptor]:
        namespace = self.derive_lookup_namespace(nickname, shared_code)
        now = int(self._time_fn())
        raw_records = await self._backend.get_records(namespace)
        best_by_identity: Dict[str, PeerDescriptor] = {}

        for payload in raw_records:
            try:
                descriptor = self.descriptor_from_payload(payload, now=now)
            except Exception:
                continue
            if expected_fingerprint and descriptor.identity_fingerprint != expected_fingerprint:
                continue
            current = best_by_identity.get(descriptor.identity_fingerprint)
            if current is None or descriptor.sequence > current.sequence:
                best_by_identity[descriptor.identity_fingerprint] = descriptor

        return sorted(
            best_by_identity.values(),
            key=lambda descriptor: descriptor.sequence,
            reverse=True,
        )

    async def withdraw(self, nickname: str, shared_code: str) -> None:
        del nickname, shared_code
        return None


__all__ = [
    "InMemoryMutableRecordBackend",
    "MainlineDHTDiscovery",
    "MutableRecordBackend",
    "UnavailableMainlineDHTBackend",
]
