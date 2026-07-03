from __future__ import annotations

import asyncio
import ipaddress
import os
import random
import socket
import time
import urllib.parse
import urllib.request
from typing import List

from .discovery_base import DiscoveryProvider, PeerDescriptor, PeerEndpoint
from .discovery_bencode import bdecode

TRACKER_HTTP_CONTEXT = b"2pchat-http-tracker-v1"


class HttpTrackerDiscovery(DiscoveryProvider):
    """Discovery provider backed by HTTP(S) BitTorrent trackers."""

    def __init__(
        self,
        *,
        tracker_url: str,
        peer_port: int,
        transport: str = "direct",
        num_want: int = 50,
        timeout: float = 10.0,
        interval_floor: int = 60,
        retries: int = 2,
        time_fn=time.time,
    ) -> None:
        self._tracker_url = tracker_url
        parsed = urllib.parse.urlparse(tracker_url)
        if parsed.scheme not in {"http", "https"}:
            raise ValueError("HTTP tracker discovery requires an http:// or https:// URL")
        if not parsed.netloc:
            raise ValueError("Tracker URL must include hostname")
        self._peer_port = peer_port
        self._transport = transport
        self._num_want = max(1, num_want)
        self._timeout = timeout
        self._interval_floor = max(15, interval_floor)
        self._retries = max(1, retries)
        self._time_fn = time_fn
        self._peer_id = self._make_peer_id()
        self._key = random.randint(0, 0xFFFFFFFF)

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
    def derive_info_hash(cls, nickname: str, shared_code: str) -> bytes:
        import hashlib

        normalized_nick = cls.normalize_nickname(nickname)
        normalized_code = cls._normalize_shared_code(shared_code)
        payload = (
            TRACKER_HTTP_CONTEXT
            + b":"
            + normalized_nick.encode("utf-8")
            + b":"
            + normalized_code.encode("utf-8")
        )
        return hashlib.sha1(payload).digest()

    @staticmethod
    def _make_peer_id() -> bytes:
        return b"-PC0001-" + os.urandom(12)

    @staticmethod
    def _compact_query(params: dict) -> str:
        pieces = []
        for key, value in params.items():
            if isinstance(value, bytes):
                pieces.append(f"{key}=" + urllib.parse.quote_from_bytes(value))
            else:
                pieces.append(f"{key}={urllib.parse.quote(str(value), safe='')}")
        return "&".join(pieces)

    def _announce_request(self, info_hash: bytes, *, event: str) -> bytes:
        params = {
            "info_hash": info_hash,
            "peer_id": self._peer_id,
            "port": self._peer_port,
            "uploaded": 0,
            "downloaded": 0,
            "left": 0,
            "compact": 1,
            "numwant": self._num_want,
            "event": event,
            "key": self._key,
        }
        url = self._tracker_url + "?" + self._compact_query(params)
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "2PChat/1.0"},
        )
        last_error = None
        for _attempt in range(self._retries):
            try:
                with urllib.request.urlopen(request, timeout=self._timeout) as response:
                    return response.read()
            except Exception as exc:  # noqa: BLE001
                last_error = exc
        if last_error:
            raise last_error
        raise RuntimeError("Unable to contact tracker")

    @staticmethod
    def _parse_compact_peers(payload: bytes) -> list[PeerEndpoint]:
        if len(payload) % 6 != 0:
            raise RuntimeError("Tracker returned malformed compact peer list")
        peers: list[PeerEndpoint] = []
        for offset in range(0, len(payload), 6):
            chunk = payload[offset : offset + 6]
            ip = socket.inet_ntoa(chunk[:4])
            port = int.from_bytes(chunk[4:], "big")
            try:
                ipaddress.IPv4Address(ip)
            except ValueError as exc:
                raise RuntimeError("Tracker returned invalid peer IP") from exc
            peers.append(PeerEndpoint(host=ip, port=port))
        return peers

    @classmethod
    def _parse_response(cls, payload: bytes) -> tuple[int, list[PeerEndpoint]]:
        decoded = bdecode(payload)
        if not isinstance(decoded, dict):
            raise RuntimeError("Tracker returned an unexpected payload")
        if "failure reason" in decoded:
            reason = decoded["failure reason"]
            if isinstance(reason, bytes):
                reason = reason.decode("utf-8", errors="replace")
            raise RuntimeError(str(reason))
        interval = int(decoded.get("interval", 0))
        peers_field = decoded.get("peers", b"")
        if isinstance(peers_field, list):
            peers = []
            for entry in peers_field:
                if not isinstance(entry, dict):
                    continue
                host = entry.get("ip")
                if isinstance(host, bytes):
                    host = host.decode("utf-8", errors="replace")
                port = int(entry.get("port", 0))
                if host and port:
                    peers.append(PeerEndpoint(host=str(host), port=port))
            return interval, peers
        if not isinstance(peers_field, bytes):
            raise RuntimeError("Tracker returned an unsupported peer list format")
        return interval, cls._parse_compact_peers(peers_field)

    async def announce(
        self,
        nickname: str,
        shared_code: str,
        *,
        transport: str,
        endpoints: List[PeerEndpoint],
    ) -> PeerDescriptor:
        if not endpoints:
            raise ValueError("Tracker discovery requires at least one endpoint")
        endpoint = endpoints[0]
        if endpoint.port != self._peer_port:
            raise ValueError("Endpoint port must match tracker discovery peer_port")
        info_hash = self.derive_info_hash(nickname, shared_code)
        payload = await asyncio.to_thread(self._announce_request, info_hash, event="started")
        interval, peers = self._parse_response(payload)
        now = int(self._time_fn())
        ttl = max(interval, self._interval_floor)
        return PeerDescriptor(
            version=1,
            nickname=self.normalize_nickname(nickname),
            identity_fingerprint=None,
            signing_public_key=None,
            transport=transport or self._transport,
            endpoints=tuple(peers) if peers else (endpoint,),
            expires_at=now + ttl,
            sequence=now,
            nonce=self._peer_id.hex(),
            signature=None,
        )

    async def resolve(
        self,
        nickname: str,
        shared_code: str,
        *,
        expected_fingerprint: str | None = None,
    ) -> List[PeerDescriptor]:
        del expected_fingerprint
        info_hash = self.derive_info_hash(nickname, shared_code)
        payload = await asyncio.to_thread(self._announce_request, info_hash, event="started")
        interval, peers = self._parse_response(payload)
        now = int(self._time_fn())
        ttl = max(interval, self._interval_floor)
        unique = []
        seen = set()
        for peer in peers:
            key = (peer.host, peer.port)
            if key in seen:
                continue
            seen.add(key)
            unique.append(peer)
        return [
            PeerDescriptor(
                version=1,
                nickname=self.normalize_nickname(nickname),
                identity_fingerprint=None,
                signing_public_key=None,
                transport=self._transport,
                endpoints=(peer,),
                expires_at=now + ttl,
                sequence=now,
                nonce=self._peer_id.hex(),
                signature=None,
            )
            for peer in unique
        ]

    async def withdraw(self, nickname: str, shared_code: str) -> None:
        info_hash = self.derive_info_hash(nickname, shared_code)
        await asyncio.to_thread(self._announce_request, info_hash, event="stopped")
