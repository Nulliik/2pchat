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
from .discovery_rendezvous import derive_rendezvous_key, normalize_nickname


class HttpTrackerDiscovery(DiscoveryProvider):
    """Discovery provider backed by HTTP(S) BitTorrent trackers."""

    def __init__(
        self,
        *,
        tracker_url: str,
        peer_port: int,
        transport: str = "direct",
        num_want: int = 50,
        timeout: float = 3.5,
        interval_floor: int = 60,
        retries: int = 1,
        time_fn=time.time,
        urlopen_fn=None,
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
        self._urlopen = urlopen_fn or urllib.request.urlopen
        self._peer_id = self._make_peer_id()
        self._key = random.randint(0, 0xFFFFFFFF)
        self.observed_addresses: set[str] = set()

    @staticmethod
    def normalize_nickname(value: str) -> str:
        return normalize_nickname(value)

    @classmethod
    def derive_info_hash(cls, nickname: str, shared_code: str) -> bytes:
        return derive_rendezvous_key(nickname, shared_code)

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

    @staticmethod
    def _split_endpoints(endpoints: List[PeerEndpoint]) -> tuple[PeerEndpoint | None, PeerEndpoint | None]:
        ipv4_endpoint = None
        ipv6_endpoint = None
        for endpoint in endpoints:
            try:
                addr = ipaddress.ip_address(endpoint.host)
            except ValueError:
                continue
            if isinstance(addr, ipaddress.IPv4Address) and ipv4_endpoint is None:
                ipv4_endpoint = endpoint
            elif isinstance(addr, ipaddress.IPv6Address) and ipv6_endpoint is None:
                ipv6_endpoint = endpoint
        return ipv4_endpoint, ipv6_endpoint

    def _announce_request(
        self,
        info_hash: bytes,
        *,
        event: str,
        ipv4_endpoint: PeerEndpoint | None = None,
        ipv6_endpoint: PeerEndpoint | None = None,
    ) -> bytes:
        params = {
            "info_hash": info_hash,
            "peer_id": self._peer_id,
            "port": self._peer_port,
            "uploaded": 0,
            "downloaded": 0,
            "left": 0,
            "compact": 1,
            "numwant": self._num_want,
            "key": self._key,
        }
        if event != "none":
            params["event"] = event
        if ipv4_endpoint is not None:
            params["ip"] = ipv4_endpoint.host
        if ipv6_endpoint is not None:
            params["ipv6"] = ipv6_endpoint.host
        url = self._tracker_url + "?" + self._compact_query(params)
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "2PChat/1.0"},
        )

        last_error = None
        for _attempt in range(self._retries):
            try:
                with self._urlopen(request, timeout=self._timeout) as resp:
                    payload = resp.read()
                    self._record_observed_addresses(payload)
                    return payload
            except Exception as exc:  # noqa: BLE001
                last_error = exc
        if last_error:
            raise last_error
        raise RuntimeError("Unable to contact tracker")

    def _record_observed_addresses(self, payload: bytes) -> None:
        try:
            decoded = bdecode(payload)
        except Exception:
            return
        if not isinstance(decoded, dict):
            return
        for key in ("external ip", "external_ip", "ip"):
            value = decoded.get(key)
            if not isinstance(value, bytes):
                continue
            try:
                if len(value) == 4:
                    self.observed_addresses.add(socket.inet_ntop(socket.AF_INET, value))
                elif len(value) == 16:
                    self.observed_addresses.add(socket.inet_ntop(socket.AF_INET6, value))
                else:
                    candidate = value.decode("ascii").strip()
                    ipaddress.ip_address(candidate)
                    self.observed_addresses.add(candidate)
            except (ValueError, UnicodeDecodeError, OSError):
                continue

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

    @staticmethod
    def _parse_compact_peers6(payload: bytes) -> list[PeerEndpoint]:
        if len(payload) % 18 != 0:
            raise RuntimeError("Tracker returned malformed compact IPv6 peer list")
        peers: list[PeerEndpoint] = []
        for offset in range(0, len(payload), 18):
            chunk = payload[offset : offset + 18]
            ip = socket.inet_ntop(socket.AF_INET6, chunk[:16])
            port = int.from_bytes(chunk[16:], "big")
            try:
                ipaddress.IPv6Address(ip)
            except ValueError as exc:
                raise RuntimeError("Tracker returned invalid IPv6 peer IP") from exc
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
        peers6_field = decoded.get("peers6", b"")
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
            if isinstance(peers6_field, bytes):
                peers.extend(cls._parse_compact_peers6(peers6_field))
            return interval, peers
        if not isinstance(peers_field, bytes):
            raise RuntimeError("Tracker returned an unsupported peer list format")
        peers = cls._parse_compact_peers(peers_field)
        if isinstance(peers6_field, bytes) and peers6_field:
            peers.extend(cls._parse_compact_peers6(peers6_field))
        return interval, peers

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
        if any(endpoint.port != self._peer_port for endpoint in endpoints):
            raise ValueError("Endpoint port must match tracker discovery peer_port")
        ipv4_endpoint, ipv6_endpoint = self._split_endpoints(endpoints)
        endpoint = ipv4_endpoint or ipv6_endpoint or endpoints[0]
        info_hash = self.derive_info_hash(nickname, shared_code)
        payload = await asyncio.to_thread(
            self._announce_request,
            info_hash,
            event="started",
            ipv4_endpoint=ipv4_endpoint,
            ipv6_endpoint=ipv6_endpoint,
        )
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
        # Resolving must not join the swarm: a `started` announce here would
        # make the lookup device appear as a peer for every searched nickname.
        payload = await asyncio.to_thread(self._announce_request, info_hash, event="none")
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
