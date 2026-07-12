import contextlib
import os
import asyncio
import struct
import socket

import pytest

from messenger.core.discovery_base import PeerEndpoint
from messenger.core.discovery_manager import get_discovery_provider
from messenger.core.discovery_tracker_udp import (
    TRACKER_ACTION_ANNOUNCE,
    TRACKER_ACTION_CONNECT,
    TRACKER_EVENT_STARTED,
    TRACKER_EVENT_STOPPED,
    UdpTrackerDiscovery,
)
from messenger.core.tracker_catalog import BASE_TRACKERS


class FakeUdpTrackerProtocol(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.transport = None
        self.connection_id = 0x0102030405060708
        self.swarms = {}

    def connection_made(self, transport) -> None:
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:
        action = struct.unpack(">I", data[8:12])[0]
        if action == TRACKER_ACTION_CONNECT:
            _proto_id, _action, tx = struct.unpack(">QII", data)
            response = struct.pack(">IIQ", TRACKER_ACTION_CONNECT, tx, self.connection_id)
            self.transport.sendto(response, addr)
            return

        if action != TRACKER_ACTION_ANNOUNCE:
            return

        unpacked = struct.unpack(">QII20s20sQQQIIIIH", data)
        (
            connection_id,
            _action,
            tx,
            info_hash,
            peer_id,
            _downloaded,
            _left,
            _uploaded,
            event,
            _ip,
            _key,
            _num_want,
            port,
        ) = unpacked
        if connection_id != self.connection_id:
            return

        peer = (addr[0], port, peer_id)
        swarm = self.swarms.setdefault(info_hash, [])
        if event == TRACKER_EVENT_STARTED:
            if peer not in swarm:
                swarm.append(peer)
        elif event == TRACKER_EVENT_STOPPED:
            swarm[:] = [entry for entry in swarm if entry[:2] != peer[:2]]

        compact = b"".join(
            bytes(map(int, ip.split("."))) + struct.pack(">H", peer_port)
            for ip, peer_port, _ in swarm
        )
        response = struct.pack(">IIIII", TRACKER_ACTION_ANNOUNCE, tx, 120, 0, len(swarm)) + compact
        self.transport.sendto(response, addr)


class PortBoundUdpTrackerProtocol(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.transport = None
        self.connection_id = 0x0102030405060708
        self.connection_ports = {}

    def connection_made(self, transport) -> None:
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:
        action = struct.unpack(">I", data[8:12])[0]
        if action == TRACKER_ACTION_CONNECT:
            _proto_id, _action, tx = struct.unpack(">QII", data)
            self.connection_ports[self.connection_id] = addr[1]
            response = struct.pack(">IIQ", TRACKER_ACTION_CONNECT, tx, self.connection_id)
            self.transport.sendto(response, addr)
            return

        if action != TRACKER_ACTION_ANNOUNCE:
            return

        unpacked = struct.unpack(">QII20s20sQQQIIIIH", data)
        connection_id, _action, tx, *_rest = unpacked
        bound_port = self.connection_ports.get(connection_id)
        if bound_port != addr[1]:
            payload = b"Connection ID missmatch.\x00"
            response = struct.pack(">II", 3, tx) + payload
            self.transport.sendto(response, addr)
            return

        response = struct.pack(">IIIII", TRACKER_ACTION_ANNOUNCE, tx, 120, 0, 0)
        self.transport.sendto(response, addr)


@pytest.mark.asyncio
async def test_udp_tracker_discovery_roundtrip_with_fake_tracker():
    loop = asyncio.get_running_loop()
    transport, protocol = await loop.create_datagram_endpoint(
        FakeUdpTrackerProtocol,
        local_addr=("127.0.0.1", 0),
    )
    try:
        host, port = transport.get_extra_info("sockname")
        tracker_url = f"udp://{host}:{port}/announce"
        alice = UdpTrackerDiscovery(tracker_url=tracker_url, peer_port=41001)
        bob = UdpTrackerDiscovery(tracker_url=tracker_url, peer_port=41002)

        await alice.announce(
            "Alice",
            "c7m4q9",
            transport="direct",
            endpoints=[PeerEndpoint(host="127.0.0.1", port=41001)],
        )
        await bob.announce(
            "Alice",
            "c7m4q9",
            transport="direct",
            endpoints=[PeerEndpoint(host="127.0.0.1", port=41002)],
        )

        resolved = await alice.resolve("alice", "c7m4q9")
        ports = {descriptor.endpoints[0].port for descriptor in resolved}
        assert 41001 in ports
        assert 41002 in ports

        await bob.withdraw("alice", "c7m4q9")
        resolved_after = await alice.resolve("alice", "c7m4q9")
        ports_after = {descriptor.endpoints[0].port for descriptor in resolved_after}
        assert 41002 not in ports_after
    finally:
        transport.close()


@pytest.mark.asyncio
async def test_udp_tracker_reuses_socket_between_connect_and_announce():
    loop = asyncio.get_running_loop()
    transport, _protocol = await loop.create_datagram_endpoint(
        PortBoundUdpTrackerProtocol,
        local_addr=("127.0.0.1", 0),
    )
    try:
        host, port = transport.get_extra_info("sockname")
        tracker_url = f"udp://{host}:{port}/announce"
        discovery = UdpTrackerDiscovery(tracker_url=tracker_url, peer_port=41001)
        descriptor = await discovery.announce(
            "Alice",
            "same-port-check",
            transport="direct",
            endpoints=[PeerEndpoint(host="127.0.0.1", port=41001)],
        )
        assert descriptor.endpoints[0].port == 41001
    finally:
        transport.close()


def test_udp_tracker_discovery_manager_and_info_hash():
    provider = get_discovery_provider(
        "udp-tracker",
        tracker_url="udp://127.0.0.1:451/announce",
        peer_port=4444,
    )
    assert isinstance(provider, UdpTrackerDiscovery)

    first = UdpTrackerDiscovery.derive_info_hash("Alice", "secret")
    second = UdpTrackerDiscovery.derive_info_hash(" alice ", "secret")
    assert first == second
    assert len(first) == 20


@pytest.mark.asyncio
@pytest.mark.live_network
@pytest.mark.skipif(
    os.environ.get("P2PCHAT_RUN_LIVE_TRACKER_TESTS") != "1",
    reason="set P2PCHAT_RUN_LIVE_TRACKER_TESTS=1 to run live tracker checks",
)
@pytest.mark.parametrize(
    ("tracker_url", "base_port"),
    [
        ("udp://tracker.torrent.eu.org:451/announce", 49070),
        ("udp://open.stealth.si:80/announce", 49072),
        ("udp://exodus.desync.com:6969/announce", 49074),
    ],
)
async def test_udp_tracker_live_matrix(tracker_url, base_port):
    alice = UdpTrackerDiscovery(tracker_url=tracker_url, peer_port=base_port)
    bob = UdpTrackerDiscovery(tracker_url=tracker_url, peer_port=base_port + 1)
    nickname = "codex-live-check"
    shared_code = tracker_url.split("//", 1)[1].split("/", 1)[0].replace(":", "-")

    try:
        try:
            await alice.announce(
                nickname,
                shared_code,
                transport="direct",
                endpoints=[PeerEndpoint(host="0.0.0.0", port=base_port)],
            )
            await bob.announce(
                nickname,
                shared_code,
                transport="direct",
                endpoints=[PeerEndpoint(host="0.0.0.0", port=base_port + 1)],
            )
            resolved = await alice.resolve(nickname, shared_code)
        except Exception as exc:  # noqa: BLE001
            pytest.skip(f"Tracker {tracker_url} unavailable during live run: {exc}")
        ports = {descriptor.endpoints[0].port for descriptor in resolved}
        assert base_port in ports
        assert (base_port + 1) in ports
    finally:
        with contextlib.suppress(Exception):
            await alice.withdraw(nickname, shared_code)
        with contextlib.suppress(Exception):
            await bob.withdraw(nickname, shared_code)


def test_tracker_catalog_contains_multiple_protocols():
    protocols = {spec.protocol for spec in BASE_TRACKERS}
    assert {"udp", "http", "https"}.issubset(protocols)


def test_parse_compact_peers_ipv6():
    ipv6_raw = socket.inet_pton(socket.AF_INET6, "200:f144:2f1d:20c9:7479:f0b0:be48:dd1b")
    port_raw = struct.pack(">H", 50001)
    payload = ipv6_raw + port_raw
    peers = UdpTrackerDiscovery._parse_compact_peers(payload, family=socket.AF_INET6)
    assert len(peers) == 1
    assert peers[0].host == "200:f144:2f1d:20c9:7479:f0b0:be48:dd1b"
    assert peers[0].port == 50001

