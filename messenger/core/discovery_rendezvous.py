from __future__ import annotations

import hashlib


RENDEZVOUS_CONTEXT = b"2pchat-rendezvous-v1"


def normalize_nickname(value: str) -> str:
    normalized = " ".join(value.strip().lower().split())
    if not normalized:
        raise ValueError("Nickname must not be empty")
    return normalized


def normalize_shared_code(value: str) -> str:
    normalized = value.strip()
    if not normalized:
        raise ValueError("Shared code must not be empty")
    return normalized


def derive_rendezvous_key(nickname: str, shared_code: str) -> bytes:
    """Return the common 20-byte lookup key used by every discovery provider."""

    normalized_nick = normalize_nickname(nickname)
    normalized_code = normalize_shared_code(shared_code)
    payload = (
        RENDEZVOUS_CONTEXT
        + b":"
        + normalized_nick.encode("utf-8")
        + b":"
        + normalized_code.encode("utf-8")
    )
    return hashlib.sha1(payload).digest()


__all__ = [
    "RENDEZVOUS_CONTEXT",
    "derive_rendezvous_key",
    "normalize_nickname",
    "normalize_shared_code",
]
