"""File transfer tracking and rate limiting helpers for discovery bridge."""

from __future__ import annotations

import threading
import time
from typing import Dict, Set

MAX_INCOMING_FILES = 16
MAX_INCOMING_FILES_PER_PEER = 4
MAX_INCOMING_FILE_BYTES = 100 * 1024 * 1024
INCOMING_FILE_RATE_WINDOW_SECONDS = 60
MAX_INCOMING_FILE_STARTS_PER_WINDOW = 8
INCOMING_FILE_TTL_SECONDS = 120


class FileTransferTracker:
    def __init__(self) -> None:
        self.incoming_files: Dict[str, dict] = {}
        self.incoming_file_starts: Dict[str, list[float]] = {}
        self.outgoing_file_futures: Dict[str, object] = {}
        self.cancelled_outgoing_message_ids: Set[str] = set()
        self._lock = threading.Lock()

    def check_incoming_rate_limit(self, peer_name: str) -> bool:
        now = time.time()
        with self._lock:
            starts = self.incoming_file_starts.setdefault(peer_name, [])
            cutoff = now - INCOMING_FILE_RATE_WINDOW_SECONDS
            starts[:] = [t for t in starts if t >= cutoff]
            if len(starts) >= MAX_INCOMING_FILE_STARTS_PER_WINDOW:
                return False
            starts.append(now)
            return True

    def cancel_outgoing(self, message_id: str) -> None:
        with self._lock:
            self.cancelled_outgoing_message_ids.add(message_id)

    def is_cancelled(self, message_id: str) -> bool:
        with self._lock:
            return message_id in self.cancelled_outgoing_message_ids
