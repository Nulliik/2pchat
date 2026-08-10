from __future__ import annotations

import asyncio
import ipaddress
import os
import random
import socket
import struct
import time
from typing import List, Tuple
from urllib.parse import urlparse

from .discovery_base import DiscoveryProvider, PeerDescriptor, PeerEndpoint
from .discovery_rendezvous import derive_rendezvous_key, normalize_nickname

TRACKER_PROTO_ID = 0x41727101980
TRACKER_ACTION_CONNECT = 0
TRACKER_ACTION_ANNOUNCE = 1
TRACKER_ACTION_ERROR = 3
TRACKER_EVENT_NONE = 0
TRACKER_EVENT_COMPLETED = 1
TRACKER_EVENT_STARTED = 2
TRACKER_EVENT_STOPPED = 3


class UdpTrackerDiscovery(DiscoveryProvider):
    """Discovery provider backed by a UDP BitTorrent tracker.

    This provider uses the tracker only as a rendezvous source for IP/port
    peers. It cannot publish signed descriptors because UDP trackers return
    compact peer lists only, so identity verification must happen after the
    peer connection using the existing messenger session handshake.
    """

    def __init__(
        self,
        *,
        tracker_url: str,
        peer_port: int,
        transport: str = "direct",
        num_want: int = 50,
        timeout: float = 3.0,
        interval_floor: int = 60,
        retries: int = 1,
        time_fn=time.time,
    ) -> None:
        self._tracker_host, self._tracker_port = self._parse_tracker_url(tracker_url)
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
    def _parse_tracker_url(tracker_url: str) -> Tuple[str, int]:
        parsed = urlparse(tracker_url)
        if parsed.scheme != "udp":
            raise ValueError("UDP tracker discovery requires a udp:// tracker URL")
        if not parsed.hostname or not parsed.port:
            raise ValueError("Tracker URL must include hostname and port")
        return parsed.hostname, parsed.port

    @staticmethod
    def normalize_nickname(value: str) -> str:
        return normalize_nickname(value)

    @classmethod
    def derive_info_hash(cls, nickname: str, shared_code: str) -> bytes:
        return derive_rendezvous_key(nickname, shared_code)

    @staticmethod
    def _make_peer_id() -> bytes:
        suffix = os.urandom(12)
        return b"-PC0001-" + suffix

    @staticmethod
    def _random_tx() -> int:
        return random.randint(0, 0x7FFFFFFF)

    async def _tracker_endpoints(self) -> list[tuple[int, int, int, tuple]]:
        loop = asyncio.get_running_loop()
        try:
            infos = await asyncio.wait_for(
                loop.getaddrinfo(
                    self._tracker_host,
                    self._tracker_port,
                    type=socket.SOCK_DGRAM,
                    proto=socket.IPPROTO_UDP,
                ),
                timeout=self._timeout,
            )
            return [(family, socktype, proto, sockaddr) for family, socktype, proto, _, sockaddr in infos]
        except (socket.gaierror, OSError, asyncio.TimeoutError):
            return []

    async def _tracker_roundtrip(
        self,
        packet: bytes,
        *,
        sock: socket.socket | None = None,
        sockaddr: tuple | None = None,
    ) -> bytes:
        loop = asyncio.get_running_loop()
        if sock is not None:
            if sockaddr is None:
                raise ValueError("sockaddr is required when reusing a tracker socket")
            sock.sendto(packet, sockaddr)
            return await asyncio.wait_for(
                loop.sock_recv(sock, 4096),
                timeout=self._timeout,
            )

        infos = await self._tracker_endpoints()
        last_error = None
        for _attempt in range(self._retries):
            for family, socktype, proto, sockaddr in infos:
                sock = socket.socket(family, socktype, proto)
                sock.setblocking(False)
                try:
                    sock.sendto(packet, sockaddr)
                    data = await asyncio.wait_for(
                        loop.sock_recv(sock, 4096),
                        timeout=self._timeout,
                    )
                    return data
                except Exception as exc:  # noqa: BLE001
                    last_error = exc
                finally:
                    sock.close()
        if last_error:
            raise last_error
        raise RuntimeError("Unable to contact tracker")

    async def _connect(
        self,
        *,
        sock: socket.socket | None = None,
        sockaddr: tuple | None = None,
    ) -> int:
        tx = self._random_tx()
        packet = struct.pack(">QII", TRACKER_PROTO_ID, TRACKER_ACTION_CONNECT, tx)
        response = await self._tracker_roundtrip(packet, sock=sock, sockaddr=sockaddr)
        if len(response) < 16:
            raise RuntimeError("Tracker connect response too short")
        action, rx, conn_id = struct.unpack(">IIQ", response[:16])
        if action == TRACKER_ACTION_ERROR:
            raise RuntimeError(response[8:].decode("utf-8", errors="replace"))
        if action != TRACKER_ACTION_CONNECT or rx != tx:
            raise RuntimeError("Tracker connect transaction mismatch")
        return conn_id

    async def _announce(self, info_hash: bytes, *, event: int) -> tuple[int, list[PeerEndpoint]]:
        loop = asyncio.get_running_loop()
        infos = await self._tracker_endpoints()
        last_error = None
        for _attempt in range(self._retries):
            for family, socktype, proto, sockaddr in infos:
                sock = socket.socket(family, socktype, proto)
                sock.setblocking(False)
                try:
                    connection_id = await self._connect(sock=sock, sockaddr=sockaddr)
                    tx = self._random_tx()
                    packet = struct.pack(
                        ">QII20s20sQQQIIIIH",
                        connection_id,
                        TRACKER_ACTION_ANNOUNCE,
                        tx,
                        info_hash,
                        self._peer_id,
                        0,
                        0,
                        0,
                        event,
                        0,
                        self._key,
                        self._num_want,
                        self._peer_port,
                    )
                    response = await self._tracker_roundtrip(packet, sock=sock, sockaddr=sockaddr)
                    if len(response) < 20:
                        raise RuntimeError("Tracker announce response too short")
                    action, rx, interval, _leechers, _seeders = struct.unpack(">IIIII", response[:20])
                    if action == TRACKER_ACTION_ERROR:
                        raise RuntimeError(response[8:].decode("utf-8", errors="replace"))
                    if action != TRACKER_ACTION_ANNOUNCE or rx != tx:
                        raise RuntimeError("Tracker announce transaction mismatch")
                    peers = self._parse_compact_peers(response[20:], family=family)
                    return interval, peers
                except Exception as exc:  # noqa: BLE001
                    last_error = exc
                finally:
                    sock.close()
        if last_error:
            raise last_error
        raise RuntimeError("Unable to contact tracker")

    @staticmethod
    def _parse_compact_peers(payload: bytes, family: int = socket.AF_INET) -> list[PeerEndpoint]:
        peers: list[PeerEndpoint] = []
        if family == socket.AF_INET6 and len(payload) % 18 == 0:
            for offset in range(0, len(payload), 18):
                chunk = payload[offset : offset + 18]
                ip = socket.inet_ntop(socket.AF_INET6, chunk[:16])
                port = struct.unpack(">H", chunk[16:18])[0]
                peers.append(PeerEndpoint(host=ip, port=port))
            return peers

        if len(payload) % 6 != 0:
            if len(payload) % 18 == 0:
                for offset in range(0, len(payload), 18):
                    chunk = payload[offset : offset + 18]
                    ip = socket.inet_ntop(socket.AF_INET6, chunk[:16])
                    port = struct.unpack(">H", chunk[16:18])[0]
                    peers.append(PeerEndpoint(host=ip, port=port))
                return peers
            raise RuntimeError(f"Tracker returned malformed compact peer list (len={len(payload)})")

        for offset in range(0, len(payload), 6):
            chunk = payload[offset : offset + 6]
            ip = socket.inet_ntoa(chunk[:4])
            port = struct.unpack(">H", chunk[4:6])[0]
            try:
                ipaddress.IPv4Address(ip)
            except ValueError as exc:
                raise RuntimeError("Tracker returned invalid peer IP") from exc
            peers.append(PeerEndpoint(host=ip, port=port))
        return peers

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
        now_val = self._time_fn()
        if hasattr(self, "_last_announce_time") and (now_val - self._last_announce_time < 2.0):
            await asyncio.sleep(2.0 - (now_val - self._last_announce_time))
        self._last_announce_time = self._time_fn()
        info_hash = self.derive_info_hash(nickname, shared_code)
        interval, peers = await self._announce(info_hash, event=TRACKER_EVENT_STARTED)
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
        interval, peers = await self._announce(info_hash, event=TRACKER_EVENT_NONE)
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
        await self._announce(info_hash, event=TRACKER_EVENT_STOPPED)
