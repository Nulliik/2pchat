from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
import threading
from typing import Dict, Iterable, List, Optional
from uuid import uuid4

from nacl.encoding import Base64Encoder
from nacl.public import PrivateKey, PublicKey
from nacl.signing import SigningKey, VerifyKey

CONFIG_ENV = "P2PCHAT_CONFIG_DIR"
DEFAULT_DIRNAME = ".2pchat"
IDENTITY_FILENAME = "identity.key"
SIGNING_FILENAME = "identity_signing.key"
TRUST_FILENAME = "trust.json"
QUEUE_FILENAME = "outbox.json"


_TRUST_LOCK = threading.Lock()


def _config_dir() -> Path:
    env_path = os.environ.get(CONFIG_ENV)
    base = Path(env_path) if env_path else Path.home() / DEFAULT_DIRNAME
    base.mkdir(parents=True, exist_ok=True)
    return base


def identity_path() -> Path:
    return _config_dir() / IDENTITY_FILENAME


def signing_identity_path() -> Path:
    return _config_dir() / SIGNING_FILENAME


def trust_path() -> Path:
    return _config_dir() / TRUST_FILENAME


def queue_path() -> Path:
    return _config_dir() / QUEUE_FILENAME


def load_or_create_identity(path: Optional[str] = None) -> PrivateKey:
    target = Path(path) if path else identity_path()
    if target.exists():
        data = target.read_text().strip()
        return PrivateKey(Base64Encoder.decode(data))

    priv = PrivateKey.generate()
    target.write_text(priv.encode(Base64Encoder).decode("ascii"))
    return priv


def load_or_create_signing_identity(path: Optional[str] = None) -> SigningKey:
    """Load or create a persistent Ed25519 signing identity."""

    target = Path(path) if path else signing_identity_path()
    if target.exists():
        data = target.read_text().strip()
        return SigningKey(Base64Encoder.decode(data))

    sk = SigningKey.generate()
    target.write_text(sk.encode(Base64Encoder).decode("ascii"))
    return sk


def fingerprint(pubkey: PublicKey | VerifyKey, *, encoding: str = "base64") -> str:
    """Return a fingerprint string for a public key.

    The default format matches previous behavior (Base64). For QR/workflows
    that prefer hexadecimal, pass ``encoding="hex"``.
    """

    if encoding == "base64":
        return pubkey.encode(encoder=Base64Encoder).decode("ascii")
    if encoding == "hex":
        return pubkey.encode().hex()
    raise ValueError("Unsupported fingerprint encoding")


def fingerprint_from_hex(hex_value: str) -> str:
    """Convert a hex-encoded fingerprint back to the Base64 form used in-store."""

    return Base64Encoder.encode(bytes.fromhex(hex_value)).decode("ascii")


@dataclass
class PeerRecord:
    fingerprint: str
    label: Optional[str]
    first_seen: float
    last_seen: float
    state: str = "known"


@dataclass
class PeerStatus:
    state: str
    warning: Optional[str] = None


class TrustStore:
    """Simple TOFU store mapping peer fingerprints to timestamps/labels."""

    def __init__(self, path: Optional[str] = None):
        self.path = Path(path) if path else trust_path()
        self.records: Dict[str, PeerRecord] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            raw = json.loads(self.path.read_text())
            for fp, meta in raw.items():
                self.records[fp] = PeerRecord(
                    fingerprint=fp,
                    label=meta.get("label"),
                    first_seen=meta.get("first_seen", 0.0),
                    last_seen=meta.get("last_seen", 0.0),
                    state=meta.get("state", "known"),
                )
        except Exception as e:
            # Corrupted store; raise error to prevent silent trust reset
            raise ValueError(f"Trust store corrupted: {e}. Refusing to load.") from e

    def _persist(self) -> None:
        with _TRUST_LOCK:
            try:
                on_disk = json.loads(self.path.read_text())
            except Exception:
                on_disk = {}

            # Merge any peers already recorded on disk to avoid losing state
            # when multiple sessions persist around the same time.
            for fp, meta in on_disk.items():
                if fp not in self.records:
                    self.records[fp] = PeerRecord(
                        fingerprint=fp,
                        label=meta.get("label"),
                        first_seen=meta.get("first_seen", 0.0),
                        last_seen=meta.get("last_seen", 0.0),
                        state=meta.get("state", "known"),
                    )

            payload = {
                fp: {
                    "label": rec.label,
                    "first_seen": rec.first_seen,
                    "last_seen": rec.last_seen,
                    "state": rec.state,
                }
                for fp, rec in self.records.items()
            }
            self.path.write_text(json.dumps(payload, indent=2))

    def _label_conflict_warning(self, peer_fp: str, label: Optional[str]) -> Optional[str]:
        if not label:
            return None
        for rec in self.records.values():
            if rec.label == label and rec.fingerprint != peer_fp:
                return (
                    f"Label '{label}' already used for peer {rec.fingerprint}. "
                    "Fingerprint changed?"
                )
        return None

    def note_peer(
        self, peer_fp: str, timestamp: float, label: Optional[str] = None
    ) -> PeerStatus:
        warning = self._label_conflict_warning(peer_fp, label)
        if peer_fp not in self.records:
            self.records[peer_fp] = PeerRecord(
                fingerprint=peer_fp,
                label=label,
                first_seen=timestamp,
                last_seen=timestamp,
                state="new",
            )
            self._persist()
            return PeerStatus("new", warning)

        record = self.records[peer_fp]
        if record.state == "new":
            record.state = "known"
        record.last_seen = timestamp
        if label and not record.label:
            record.label = label
        self._persist()
        return PeerStatus(record.state, warning)

    def mark_verified(
        self, peer_fp: str, timestamp: float, label: Optional[str] = None
    ) -> PeerStatus:
        warning = self._label_conflict_warning(peer_fp, label)
        if peer_fp not in self.records:
            self.records[peer_fp] = PeerRecord(
                fingerprint=peer_fp,
                label=label,
                first_seen=timestamp,
                last_seen=timestamp,
                state="verified",
            )
        else:
            record = self.records[peer_fp]
            record.state = "verified"
            record.last_seen = timestamp
            if not record.first_seen:
                record.first_seen = timestamp
            if label and not record.label:
                record.label = label
        self._persist()
        return PeerStatus("verified", warning)

    def expected_or_raise(self, peer_fp: str, expected: Optional[str]) -> None:
        if expected and peer_fp != expected:
            raise ValueError(
                f"Peer fingerprint mismatch. Expected {expected} but saw {peer_fp}."
            )

    def label_for(self, peer_fp: str) -> Optional[str]:
        rec = self.records.get(peer_fp)
        return rec.label if rec else None

    def state_for(self, peer_fp: str) -> Optional[str]:
        rec = self.records.get(peer_fp)
        return rec.state if rec else None


class Outbox:
    """Simple persistent queue to store unsent messages for reconnects.

    Messages are tagged with the peer fingerprint they were intended for so
    that queued payloads are never replayed to a different identity after a
    disconnect.
    """

    def __init__(self, path: Optional[str] = None):
        self.path = Path(path) if path else queue_path()
        self._messages: List[Dict[str, str]] = []
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text())
            if isinstance(data, list):
                self._messages = [m for m in data if isinstance(m, dict)]
        except Exception:
            self._messages = []

    def _persist(self) -> None:
        self.path.write_text(json.dumps(self._messages, indent=2))

    def add_chat(
        self,
        body: str,
        timestamp: float,
        nickname: Optional[str] = None,
        *,
        peer_fp: Optional[str],
    ) -> Dict[str, str]:
        message = {
            "id": str(uuid4()),
            "type": "chat",
            "timestamp": timestamp,
            "body": body,
        }
        if nickname:
            message["nickname"] = nickname
        if peer_fp:
            message["peer_fp"] = peer_fp
        self._messages.append(message)
        self._persist()
        return message

    def pending(self) -> Iterable[Dict[str, str]]:
        return list(self._messages)

    def mark_sent(self, message_id: str) -> None:
        self._messages = [m for m in self._messages if m.get("id") != message_id]
        self._persist()
