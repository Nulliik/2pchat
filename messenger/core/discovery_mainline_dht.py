from __future__ import annotations

import asyncio
import os
import socket
import struct
import time
from dataclasses import dataclass
from typing import Awaitable, Callable, Protocol, Sequence

from .discovery_base import DiscoveryProvider, PeerDescriptor, PeerEndpoint
from .discovery_bencode import bdecode
from .discovery_rendezvous import derive_rendezvous_key, normalize_nickname


DEFAULT_BOOTSTRAP_NODES = (
    ("router.bittorrent.com", 6881),
    ("router.utorrent.com", 6881),
    ("dht.transmissionbt.com", 6881),
)


def _bencode(value) -> bytes:
    if isinstance(value, bytes):
        return str(len(value)).encode("ascii") + b":" + value
    if isinstance(value, str):
        return _bencode(value.encode("utf-8"))
    if isinstance(value, int):
        return b"i" + str(value).encode("ascii") + b"e"
    if isinstance(value, (list, tuple)):
        return b"l" + b"".join(_bencode(item) for item in value) + b"e"
    if isinstance(value, dict):
        items = sorted(
            value.items(),
            key=lambda item: (
                item[0] if isinstance(item[0], bytes) else str(item[0]).encode()
            ),
        )
        encoded = b"".join(_bencode(key) + _bencode(item) for key, item in items)
        return b"d" + encoded + b"e"
    raise TypeError(f"Cannot bencode {type(value).__name__}")


def _compact_nodes(payload: bytes, family: int) -> list[tuple[bytes, tuple]]:
    size = 26 if family == socket.AF_INET else 38
    address_size = 4 if family == socket.AF_INET else 16
    if len(payload) % size:
        return []
    nodes = []
    for offset in range(0, len(payload), size):
        chunk = payload[offset : offset + size]
        host = socket.inet_ntop(family, chunk[20 : 20 + address_size])
        port = struct.unpack(">H", chunk[-2:])[0]
        nodes.append((chunk[:20], (host, port)))
    return nodes


def _compact_peer(payload: bytes) -> PeerEndpoint | None:
    if len(payload) == 6:
        family, address_size = socket.AF_INET, 4
    elif len(payload) == 18:
        family, address_size = socket.AF_INET6, 16
    else:
        return None
    host = socket.inet_ntop(family, payload[:address_size])
    port = struct.unpack(">H", payload[address_size:])[0]
    return PeerEndpoint(host=host, port=port) if port else None


class Bep5Backend(Protocol):
    async def get_peers(self, info_hash: bytes) -> Sequence[PeerEndpoint]: ...

    async def announce_peer(self, info_hash: bytes, port: int) -> None: ...


class InMemoryBep5Backend:
    def __init__(self) -> None:
        self._peers: dict[bytes, list[PeerEndpoint]] = {}

    async def get_peers(self, info_hash: bytes) -> Sequence[PeerEndpoint]:
        return tuple(self._peers.get(info_hash, ()))

    async def announce_peer(self, info_hash: bytes, port: int) -> None:
        endpoint = PeerEndpoint("127.0.0.1", port)
        peers = self._peers.setdefault(info_hash, [])
        if endpoint not in peers:
            peers.append(endpoint)


@dataclass(frozen=True)
class _Token:
    node_id: bytes
    address: tuple
    value: bytes


class MainlineDHTBackend:
    """Small BEP 5 client used only for rendezvous peer lookup and announce."""

    def __init__(
        self,
        *,
        bootstrap_nodes: Sequence[tuple[str, int]] = DEFAULT_BOOTSTRAP_NODES,
        timeout: float = 2.5,
        max_queries: int = 64,
        node_id: bytes | None = None,
    ) -> None:
        self.bootstrap_nodes = tuple(bootstrap_nodes)
        self.timeout = timeout
        self.max_queries = max(8, max_queries)
        self.node_id = node_id or os.urandom(20)
        self.observed_addresses: set[str] = set()
        if len(self.node_id) != 20:
            raise ValueError("BEP 5 node_id must be exactly 20 bytes")

    async def _bootstrap(self) -> list[tuple[bytes, tuple]]:
        loop = asyncio.get_running_loop()

        async def _resolve(host: str, port: int) -> list[tuple[bytes, tuple]]:
            try:
                infos = await asyncio.wait_for(
                    loop.getaddrinfo(host, port, type=socket.SOCK_DGRAM),
                    timeout=2.5,
                )
            except (OSError, asyncio.TimeoutError):
                return []
            resolved = []
            for family, _kind, _proto, _name, address in infos:
                if family in {socket.AF_INET, socket.AF_INET6}:
                    resolved.append((b"", address))
            return resolved

        batches = await asyncio.gather(
            *(_resolve(host, port) for host, port in self.bootstrap_nodes)
        )
        result = []
        for batch in batches:
            result.extend(batch)
        return result

    async def _query(self, address: tuple, method: bytes, arguments: dict) -> dict:
        family = socket.AF_INET6 if len(address) == 4 else socket.AF_INET
        transaction = os.urandom(2)
        packet = _bencode({b"t": transaction, b"y": b"q", b"q": method, b"a": arguments})
        loop = asyncio.get_running_loop()
        sock = socket.socket(family, socket.SOCK_DGRAM)
        sock.setblocking(False)
        try:
            await loop.sock_sendto(sock, packet, address)
            while True:
                data, source = await asyncio.wait_for(loop.sock_recvfrom(sock, 65535), self.timeout)
                decoded = bdecode(data)
                if decoded.get("t") != transaction or decoded.get("y") != b"r":
                    continue
                response = decoded.get("r")
                if not isinstance(response, dict):
                    raise RuntimeError("Malformed BEP 5 response")
                response["_source"] = source
                observed = response.get("ip")
                if isinstance(observed, bytes):
                    endpoint = _compact_peer(observed)
                    if endpoint:
                        self.observed_addresses.add(endpoint.host)
                return response
        finally:
            sock.close()

    async def _lookup(
        self,
        info_hash: bytes,
        *,
        on_tokens: Callable[[Sequence[_Token]], Awaitable[None]] | None = None,
    ) -> tuple[list[PeerEndpoint], list[_Token]]:
        pending = await self._bootstrap()
        visited: set[tuple[str, int]] = set()
        peers: dict[tuple[str, int], PeerEndpoint] = {}
        tokens: list[_Token] = []
        queries = 0

        while pending and queries < self.max_queries:
            # Bootstrap addresses have no node ID yet and must be queried first.
            # Afterwards Kademlia walks towards the target by XOR distance.
            pending.sort(
                key=lambda item: (
                    0 if len(item[0]) != 20 else 1,
                    int.from_bytes(item[0], "big") ^ int.from_bytes(info_hash, "big")
                    if len(item[0]) == 20
                    else 0,
                )
            )
            batch = []
            while pending and len(batch) < 8 and queries + len(batch) < self.max_queries:
                _node_id, address = pending.pop(0)
                key = (str(address[0]), int(address[1]))
                if key not in visited:
                    visited.add(key)
                    batch.append(address)
            if not batch:
                continue
            queries += len(batch)
            requests = (
                self._query(
                    address,
                    b"get_peers",
                    {b"id": self.node_id, b"info_hash": info_hash},
                )
                for address in batch
            )
            responses = await asyncio.gather(
                *requests,
                return_exceptions=True,
            )
            round_tokens = []
            for response in responses:
                if not isinstance(response, dict):
                    continue
                source = response.get("_source")
                token = response.get("token")
                response_node_id = response.get("id")
                if (
                    source
                    and isinstance(token, bytes)
                    and isinstance(response_node_id, bytes)
                    and len(response_node_id) == 20
                ):
                    discovered_token = _Token(response_node_id, source, token)
                    tokens.append(discovered_token)
                    round_tokens.append(discovered_token)
                values = response.get("values", ())
                if isinstance(values, list):
                    for value in values:
                        if isinstance(value, bytes):
                            endpoint = _compact_peer(value)
                            if endpoint:
                                peers[(endpoint.host, endpoint.port)] = endpoint
                node_fields = (("nodes", socket.AF_INET), ("nodes6", socket.AF_INET6))
                for field, family in node_fields:
                    compact = response.get(field)
                    if isinstance(compact, bytes):
                        pending.extend(_compact_nodes(compact, family))

            # announce_peer can publish to the first responsive nodes here,
            # while the lookup continues towards nodes closer to info_hash.
            if round_tokens and on_tokens is not None:
                await on_tokens(tuple(round_tokens))

        return list(peers.values()), tokens

    async def get_peers(self, info_hash: bytes) -> Sequence[PeerEndpoint]:
        peers, _tokens = await self._lookup(info_hash)
        return peers

    async def announce_peer(self, info_hash: bytes, port: int) -> None:
        target_number = int.from_bytes(info_hash, "big")
        attempted: set[tuple] = set()
        successful: set[tuple] = set()

        async def _announce_to(tokens: Sequence[_Token]) -> int:
            candidates = [
                token
                for token in sorted(
                    tokens,
                    key=lambda item: int.from_bytes(item.node_id, "big") ^ target_number,
                )
                if token.address not in attempted
            ][:8]
            if not candidates:
                return 0
            attempted.update(token.address for token in candidates)
            results = await asyncio.gather(
                *(
                    self._query(
                        token.address,
                        b"announce_peer",
                        {
                            b"id": self.node_id,
                            b"info_hash": info_hash,
                            b"implied_port": 0,
                            b"port": port,
                            b"token": token.value,
                        },
                    )
                    for token in candidates
                ),
                return_exceptions=True,
            )
            for token, result in zip(candidates, results):
                if isinstance(result, dict):
                    successful.add(token.address)
            return sum(isinstance(result, dict) for result in results)

        async def _publish_early(tokens: Sequence[_Token]) -> None:
            # One successful early copy is enough to make the peer discoverable.
            # Failed nodes don't block trying freshly discovered tokens next round.
            if not successful:
                await _announce_to(tokens)

        _peers, tokens = await self._lookup(info_hash, on_tokens=_publish_early)
        if not tokens:
            raise RuntimeError("Mainline DHT bootstrap succeeded but returned no announce tokens")
        await _announce_to(tokens)
        if not successful:
            raise RuntimeError("All Mainline DHT announce_peer requests failed")


class MainlineDHTDiscovery(DiscoveryProvider):
    def __init__(
        self,
        *,
        backend: Bep5Backend | None = None,
        peer_port: int = 0,
        transport: str = "direct",
        time_fn=time.time,
        **backend_options,
    ) -> None:
        self._backend = backend or MainlineDHTBackend(**backend_options)
        self._peer_port = peer_port
        self._transport = transport
        self._time_fn = time_fn

    @property
    def observed_addresses(self) -> tuple[str, ...]:
        return tuple(getattr(self._backend, "observed_addresses", ()))

    normalize_nickname = staticmethod(normalize_nickname)

    @staticmethod
    def derive_lookup_namespace(nickname: str, shared_code: str) -> bytes:
        return derive_rendezvous_key(nickname, shared_code)

    async def announce(
        self,
        nickname: str,
        shared_code: str,
        *,
        transport: str,
        endpoints: list[PeerEndpoint],
    ) -> PeerDescriptor:
        if not endpoints and not self._peer_port:
            raise ValueError("Mainline DHT announce requires a peer port")
        port = self._peer_port or endpoints[0].port
        info_hash = self.derive_lookup_namespace(nickname, shared_code)
        await self._backend.announce_peer(info_hash, port)
        now = int(self._time_fn())
        return PeerDescriptor(
            1,
            normalize_nickname(nickname),
            None,
            None,
            transport or self._transport,
            tuple(endpoints),
            now + 900,
            now,
            self._backend.__class__.__name__,
            None,
        )

    async def resolve(
        self,
        nickname: str,
        shared_code: str,
        *,
        expected_fingerprint: str | None = None,
    ) -> list[PeerDescriptor]:
        del expected_fingerprint
        info_hash = self.derive_lookup_namespace(nickname, shared_code)
        peers = await self._backend.get_peers(info_hash)
        now = int(self._time_fn())
        return [
            PeerDescriptor(
                1,
                normalize_nickname(nickname),
                None,
                None,
                self._transport,
                (peer,),
                now + 900,
                now,
                "bep5",
                None,
            )
            for peer in peers
        ]

    async def withdraw(self, nickname: str, shared_code: str) -> None:
        del nickname, shared_code


__all__ = [
    "DEFAULT_BOOTSTRAP_NODES",
    "InMemoryBep5Backend",
    "MainlineDHTBackend",
    "MainlineDHTDiscovery",
]
