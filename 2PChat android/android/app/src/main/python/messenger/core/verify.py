"""Helpers for identity verification payloads, SAS, and trust updates.

Only public information is encoded; fingerprints and SAS strings can be safely
shared via QR/text without exposing secrets.
"""

from __future__ import annotations

import json
import re
import time
import hashlib
from typing import Dict, Optional

from nacl.encoding import Base64Encoder

from .identity import TrustStore, fingerprint_from_hex


QR_TYPE = "p2p-chat-identity"
QR_VERSION = 1
QR_ALGO = "x25519+ed25519"


def _format_sas(number: int, digits: int = 9, group: int = 3) -> str:
    formatted = str(number % (10**digits)).zfill(digits)
    return " ".join(
        formatted[i : i + group] for i in range(0, len(formatted), group)
    )


def compute_sas(our_identity_pub: bytes, their_identity_pub: bytes) -> str:
    """Return a numeric SAS derived from both identity public keys.

    Inputs are treated symmetrically so the result is the same on both sides.
    """

    ours, theirs = sorted([our_identity_pub, their_identity_pub])
    digest = hashlib.sha256(b"p2p-chat-sas" + ours + theirs).digest()
    value = int.from_bytes(digest, "big")
    return _format_sas(value)


def build_identity_qr_payload(
    identity_fp_b64: str, user_label: Optional[str] = None
) -> str:
    """Build a JSON payload for QR/text export containing only public data.

    The payload contains the Base64 fingerprint used throughout the app. Hex is
    also embedded for backwards compatibility with earlier payloads, but the
    canonical field is ``fingerprint_b64``.
    """

    payload: Dict[str, str] = {
        "type": QR_TYPE,
        "version": QR_VERSION,
        "fingerprint_b64": identity_fp_b64,
        "fingerprint": Base64Encoder.decode(identity_fp_b64).hex(),
        "algorithm": QR_ALGO,
    }
    if user_label:
        payload["user_label"] = user_label
    return json.dumps(payload, separators=(",", ":"))


def parse_identity_qr_payload(payload: str) -> Dict[str, str]:
    """Validate and parse a QR/text payload.

    Raises ValueError if the payload is malformed or not the expected format.
    """

    try:
        data = json.loads(payload)
    except json.JSONDecodeError as exc:  # noqa: PERF203
        raise ValueError("Invalid QR payload JSON") from exc

    if data.get("type") != QR_TYPE:
        raise ValueError("Unexpected payload type")
    if data.get("version") != QR_VERSION:
        raise ValueError("Unsupported payload version")

    fp_hex = data.get("fingerprint")
    fp_b64 = data.get("fingerprint_b64")
    if not (fp_hex or fp_b64):
        raise ValueError("Missing fingerprint fields")
    if fp_b64:
        try:
            Base64Encoder.decode(fp_b64)
        except Exception as exc:  # noqa: BLE001
            raise ValueError("Invalid fingerprint_b64 encoding") from exc
    if fp_hex:
        if not isinstance(fp_hex, str) or not re.fullmatch(r"[0-9a-fA-F]+", fp_hex):
            raise ValueError("Invalid fingerprint encoding; expected hex")

    algo = data.get("algorithm")
    if algo not in (QR_ALGO,):
        raise ValueError("Unsupported algorithm")

    return data


def _extract_fingerprint_base64(payload_or_fp: str) -> str:
    """Convert a payload or fingerprint string to the Base64 form used in-store."""

    try:
        data = parse_identity_qr_payload(payload_or_fp)
        if data.get("fingerprint_b64"):
            return data["fingerprint_b64"]
        if data.get("fingerprint"):
            return fingerprint_from_hex(data["fingerprint"])
    except ValueError:
        pass

    raw = payload_or_fp.strip()
    try:
        return fingerprint_from_hex(raw)
    except ValueError:
        # not hex; try base64 validation
        try:
            Base64Encoder.decode(raw)
            return raw
        except Exception as exc:  # noqa: BLE001
            raise ValueError("Invalid fingerprint or payload") from exc


def verify_identity_payload(
    payload_or_fp: str,
    current_peer_fingerprint: str,
    trust_store: TrustStore,
    *,
    label: Optional[str] = None,
) -> bool:
    """Verify peer identity using a public payload.

    Returns True and marks the peer as verified when the payload fingerprint
    matches the currently connected peer. No secret material is read from the
    payload; it only carries public fingerprint and optional label.
    """

    label_from_payload: Optional[str] = None
    try:
        parsed = parse_identity_qr_payload(payload_or_fp)
        peer_fp = fingerprint_from_hex(parsed["fingerprint"])
        label_from_payload = parsed.get("user_label")
    except ValueError:
        peer_fp = _extract_fingerprint_base64(payload_or_fp)
    if peer_fp != current_peer_fingerprint:
        return False

    trust_store.mark_verified(
        peer_fp, timestamp=time.time(), label=label or label_from_payload
    )
    return True


__all__ = [
    "compute_sas",
    "build_identity_qr_payload",
    "parse_identity_qr_payload",
    "verify_identity_payload",
]
