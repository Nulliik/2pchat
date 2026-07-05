import contextlib
import os
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler
from http.server import ThreadingHTTPServer

import pytest

from messenger.core.discovery_base import PeerEndpoint
from messenger.core.discovery_manager import get_discovery_provider
from messenger.core.discovery_tracker_http import HttpTrackerDiscovery
from messenger.core.tracker_catalog import BASE_TRACKERS


def _bencode(value) -> bytes:
    if isinstance(value, int):
        return b"i" + str(value).encode("ascii") + b"e"
    if isinstance(value, bytes):
        return str(len(value)).encode("ascii") + b":" + value
    if isinstance(value, str):
        return _bencode(value.encode("utf-8"))
    if isinstance(value, list):
        return b"l" + b"".join(_bencode(item) for item in value) + b"e"
    if isinstance(value, dict):
        items = []
        for key in sorted(value.keys()):
            items.append(_bencode(str(key)))
            items.append(_bencode(value[key]))
        return b"d" + b"".join(items) + b"e"
    raise TypeError(f"Unsupported type: {type(value)!r}")


class FakeHttpTrackerHandler(BaseHTTPRequestHandler):
    swarms = {}

    def do_GET(self):  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        pairs = urllib.parse.parse_qs(
            parsed.query,
            keep_blank_values=True,
            encoding="latin-1",
            errors="strict",
        )
        info_hash = pairs["info_hash"][0].encode("latin-1")
        event = pairs.get("event", ["started"])[0]
        port = int(pairs["port"][0])
        peer = ("127.0.0.1", port)
        swarm = self.swarms.setdefault(info_hash, [])
        if event == "stopped":
            swarm[:] = [entry for entry in swarm if entry != peer]
        elif peer not in swarm:
            swarm.append(peer)

        peers = b"".join(
            bytes(map(int, host.split("."))) + peer_port.to_bytes(2, "big")
            for host, peer_port in swarm
        )
        body = _bencode({"interval": 120, "peers": peers})
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):  # noqa: A003
        return


@pytest.mark.asyncio
async def test_http_tracker_discovery_roundtrip_with_fake_tracker():
    server = ThreadingHTTPServer(("127.0.0.1", 0), FakeHttpTrackerHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        host, port = server.server_address
        tracker_url = f"http://{host}:{port}/announce"
        alice = HttpTrackerDiscovery(tracker_url=tracker_url, peer_port=42001)
        bob = HttpTrackerDiscovery(tracker_url=tracker_url, peer_port=42002)

        await alice.announce(
            "Alice",
            "key-1",
            transport="direct",
            endpoints=[PeerEndpoint(host="127.0.0.1", port=42001)],
        )
        await bob.announce(
            "Alice",
            "key-1",
            transport="direct",
            endpoints=[PeerEndpoint(host="127.0.0.1", port=42002)],
        )

        resolved = await alice.resolve("alice", "key-1")
        ports = {descriptor.endpoints[0].port for descriptor in resolved}
        assert 42001 in ports
        assert 42002 in ports

        await bob.withdraw("alice", "key-1")
        resolved_after = await alice.resolve("alice", "key-1")
        ports_after = {descriptor.endpoints[0].port for descriptor in resolved_after}
        assert 42002 not in ports_after
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_http_tracker_discovery_manager_and_info_hash():
    provider = get_discovery_provider(
        "http-tracker",
        tracker_url="http://127.0.0.1:8080/announce",
        peer_port=4444,
    )
    assert isinstance(provider, HttpTrackerDiscovery)
    assert (
        HttpTrackerDiscovery.derive_info_hash("Alice", "secret")
        == HttpTrackerDiscovery.derive_info_hash(" alice ", "secret")
    )


@pytest.mark.asyncio
@pytest.mark.live_network
@pytest.mark.skipif(
    os.environ.get("P2PCHAT_RUN_LIVE_TRACKER_TESTS") != "1",
    reason="set P2PCHAT_RUN_LIVE_TRACKER_TESTS=1 to run live tracker checks",
)
@pytest.mark.parametrize(
    ("tracker_name", "require_both_ports"),
    [
        ("OpenTrackr HTTP", True),
        ("Dler HTTP", False),
        ("Qu.Ax HTTP", True),
        ("Yemekyedim HTTPS", False),
        ("Nyacat HTTPS", False),
    ],
)
async def test_http_tracker_live_matrix(tracker_name, require_both_ports):
    tracker = next(spec for spec in BASE_TRACKERS if spec.name == tracker_name)
    base_port = 49130 + list(
        name for name in [
            "OpenTrackr HTTP",
            "Dler HTTP",
            "Qu.Ax HTTP",
            "Yemekyedim HTTPS",
            "Nyacat HTTPS",
        ]
    ).index(tracker_name) * 2
    alice = HttpTrackerDiscovery(tracker_url=tracker.announce_url, peer_port=base_port)
    bob = HttpTrackerDiscovery(tracker_url=tracker.announce_url, peer_port=base_port + 1)
    nickname = "codex-http-live"
    shared_code = tracker_name.lower().replace(" ", "-")
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
            pytest.skip(f"Tracker {tracker_name} unavailable during live run: {exc}")
        ports = {descriptor.endpoints[0].port for descriptor in resolved}
        expected = {base_port, base_port + 1}
        assert ports & expected
        if require_both_ports:
            assert expected.issubset(ports)
    finally:
        with contextlib.suppress(Exception):
            await alice.withdraw(nickname, shared_code)
        with contextlib.suppress(Exception):
            await bob.withdraw(nickname, shared_code)
