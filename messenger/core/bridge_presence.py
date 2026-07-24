"""Presence and session state tracking helpers for discovery bridge."""

from __future__ import annotations

import time
from typing import Dict, Set

MAX_CONSECUTIVE_SESSION_PROBE_FAILURES = 2


class PresenceTracker:
    def __init__(self) -> None:
        self.active_sessions: Dict[str, object] = {}
        self.session_probe_failures: Dict[str, int] = {}
        self.peer_fingerprint_to_name: Dict[str, str] = {}

    def get_active_peer_fingerprints(self) -> list[str]:
        return list(self.peer_fingerprint_to_name.keys())

    def record_probe_failure(self, fingerprint: str) -> int:
        count = self.session_probe_failures.get(fingerprint, 0) + 1
        self.session_probe_failures[fingerprint] = count
        return count

    def reset_probe_failures(self, fingerprint: str) -> None:
        self.session_probe_failures.pop(fingerprint, None)

    def is_session_healthy(self, fingerprint: str) -> bool:
        return self.session_probe_failures.get(fingerprint, 0) < MAX_CONSECUTIVE_SESSION_PROBE_FAILURES
