from __future__ import annotations

import re
import secrets

_NAME_ALLOWED = re.compile(r"[^a-z0-9-]+")
_TOKEN_ALPHABET = "23456789bcdfghjkmnpqrstvwxyz"


def _random_token(length: int) -> str:
    return "".join(secrets.choice(_TOKEN_ALPHABET) for _ in range(length))


def generate_discovery_name(seed: str | None = None) -> str:
    """Return a short human-readable rendezvous nickname suggestion."""

    normalized = (seed or "").strip().lower().replace(" ", "-")
    normalized = _NAME_ALLOWED.sub("", normalized)
    normalized = normalized.strip("-")
    if not normalized:
        normalized = "contact"
    normalized = normalized[:20].rstrip("-") or "contact"
    return f"{normalized}-{_random_token(4)}"


def generate_discovery_key(groups: int = 3, group_length: int = 4) -> str:
    """Return a shareable discovery secret with grouped random characters."""

    safe_groups = max(2, groups)
    safe_length = max(3, group_length)
    return "-".join(_random_token(safe_length) for _ in range(safe_groups))
