import asyncio
import base64
import os
from pathlib import Path

import pytest

from messenger.app.gui_controller import ChatController
from messenger.core.discovery_base import PeerDescriptor, PeerEndpoint
from messenger.core.crypto import encrypt_file_in_chunks
from messenger.core.identity import Outbox


class FakeSession:
    peer_fingerprint = "peer-fp"
    trust_warning = None
    their_pub = None

    def __init__(self):
        self.sent = []
        self.recv_queue: asyncio.Queue = asyncio.Queue()
        self.closed = False

    @classmethod
    async def create(cls, _reader=None, _writer=None, _initiator=None, **_kwargs):
        session = cls()
        session.peer_fingerprint = cls.peer_fingerprint
        session.trust_warning = cls.trust_warning
        session.their_pub = cls.their_pub
        return session

    async def send_chat(self, body: str, nickname: str | None = None):
        self.sent.append({"body": body, "nickname": nickname})

    async def send_reliable(self, payload):
        self.sent.append(payload)
        return payload.get("id", "")

    async def receive_message(self):
        return await self.recv_queue.get()

    async def close(self):
        self.closed = True


async def wait_for_predicate(predicate, timeout=1.0):
    loop = asyncio.get_event_loop()
    end_time = loop.time() + timeout
    while loop.time() < end_time:
        if predicate():
            return True
        await asyncio.sleep(0.05)
    return False


async def _fake_connect(_transport, _host, _port, **_options):
    return None, None


async def _fake_listen(_transport, _host, _port, **_options):
    yield None, None


class FakeDiscoveryProvider:
    def __init__(self, descriptors):
        self.descriptors = descriptors
        self.announces = []
        self.resolves = []
        self.withdraws = []

    async def announce(self, nickname, shared_code, *, transport, endpoints):
        self.announces.append((nickname, shared_code, transport, endpoints))
        return self.descriptors[0]

    async def resolve(self, nickname, shared_code, *, expected_fingerprint=None):
        self.resolves.append((nickname, shared_code, expected_fingerprint))
        return list(self.descriptors)

    async def withdraw(self, nickname, shared_code):
        self.withdraws.append((nickname, shared_code))
        return None


@pytest.mark.asyncio
async def test_controller_handles_send_and_receive(tmp_path):
    received = []
    statuses = []
    outbox = Outbox(str(tmp_path / "queue.json"))
    controller = ChatController(
        on_message=received.append,
        on_status=statuses.append,
        transport_connector=_fake_connect,
        transport_listener=_fake_listen,
        session_factory=FakeSession.create,
        outbox=outbox,
    )

    try:
        controller.set_nickname("Tester")
        controller.listen("::", 5555, "direct")
        assert await wait_for_predicate(lambda: controller.session is not None)

        controller.send_chat("hello").result(timeout=1)
        assert controller.session.sent[-1] == {"body": "hello", "nickname": "Tester"}

        incoming = {"type": "chat", "timestamp": 1, "body": "hi"}
        asyncio.run_coroutine_threadsafe(
            controller.session.recv_queue.put(incoming), controller.loop
        ).result(timeout=1)
        assert await wait_for_predicate(lambda: len(received) == 1)
        assert received[0]["body"] == "hi"
        assert any("Peer connected" in status for status in statuses)
    finally:
        controller.close()
        assert controller.session is None


@pytest.mark.asyncio
async def test_controller_auto_reconnects_on_offline(tmp_path):
    statuses = []
    connect_calls = {"count": 0}

    async def _fake_connect(_transport, _host, _port, **_options):
        connect_calls["count"] += 1
        return None, None

    async def _fake_listen(_transport, _host, _port, **_options):
        yield object(), object()

    controller = ChatController(
        on_status=statuses.append,
        transport_connector=_fake_connect,
        transport_listener=_fake_listen,
        session_factory=FakeSession.create,
    )

    try:
        controller.set_auto_reconnect_delay(0.05)
        controller.connect("127.0.0.1", 5555, "direct")
        assert await wait_for_predicate(lambda: controller.session is not None)
        assert connect_calls["count"] == 1

        offline_status = {"type": "status", "state": "offline", "reason": "drop"}
        asyncio.run_coroutine_threadsafe(
            controller.session.recv_queue.put(offline_status), controller.loop
        ).result(timeout=1)

        assert await wait_for_predicate(lambda: connect_calls["count"] >= 2, timeout=2.0)
        assert await wait_for_predicate(
            lambda: any("Auto-reconnect succeeded" in msg for msg in statuses),
            timeout=2.0,
        )
    finally:
        controller.close()


@pytest.mark.asyncio
async def test_controller_receives_file(tmp_path):
    received: list = []
    controller = ChatController(
        on_message=received.append,
        transport_connector=_fake_connect,
        transport_listener=_fake_listen,
        session_factory=FakeSession.create,
        downloads_dir=tmp_path,
    )

    file_path = tmp_path / "hello.txt"
    file_path.write_text("hello world")
    (
        chunk_iter,
        file_key,
        file_nonce_prefix,
        file_size,
        num_chunks,
        file_hash,
    ) = encrypt_file_in_chunks(str(file_path), chunk_size=8)
    chunks = list(chunk_iter)
    file_id = os.urandom(12)
    meta = {
        "type": "file_meta",
        "file_id": base64.b64encode(file_id).decode(),
        "file_name": file_path.name,
        "file_size": file_size,
        "num_chunks": num_chunks,
        "file_hash": base64.b64encode(file_hash).decode(),
        "file_key": base64.b64encode(file_key).decode(),
        "file_nonce_prefix": base64.b64encode(file_nonce_prefix).decode(),
        "timestamp": 1,
    }

    try:
        controller.listen("::", 5555, "direct")
        assert await wait_for_predicate(lambda: controller.session is not None)

        asyncio.run_coroutine_threadsafe(
            controller.session.recv_queue.put(meta), controller.loop
        ).result(timeout=1)
        for idx, payload in chunks:
            msg = {
                "type": "file_chunk",
                "file_id": meta["file_id"],
                "chunk_index": idx,
                "payload": base64.b64encode(payload).decode(),
            }
            asyncio.run_coroutine_threadsafe(
                controller.session.recv_queue.put(msg), controller.loop
            ).result(timeout=1)

        assert await wait_for_predicate(
            lambda: any(m.get("type") == "file_saved" for m in received), timeout=2
        )
        saved = next(m for m in received if m.get("type") == "file_saved")
        saved_path = Path(saved["file_path"])
        assert saved_path.exists()
        assert saved_path.read_text() == "hello world"
    finally:
        controller.close()


@pytest.mark.asyncio
async def test_rendezvous_skips_loopback_dial_to_avoid_self_connect():
    connect_calls = {"count": 0}
    listen_calls = {"count": 0}

    async def _counting_connect(_transport, _host, _port, **_options):
        connect_calls["count"] += 1
        return object(), object()

    async def _counting_listen(_transport, _host, _port, **_options):
        listen_calls["count"] += 1
        yield object(), object()

    controller = ChatController(
        transport_connector=_counting_connect,
        transport_listener=_counting_listen,
        session_factory=FakeSession.create,
    )

    try:
        controller.rendezvous("127.0.0.1", 5555, "direct", "0.0.0.0")
        assert await wait_for_predicate(lambda: controller.session is not None)
        assert listen_calls["count"] == 1
        assert connect_calls["count"] == 0
    finally:
        controller.close()


@pytest.mark.asyncio
async def test_rendezvous_keeps_dial_for_non_loopback_target():
    connect_calls = {"count": 0}

    async def _counting_connect(_transport, _host, _port, **_options):
        connect_calls["count"] += 1
        await asyncio.sleep(0.01)
        return object(), object()

    async def _delayed_listen(_transport, _host, _port, **_options):
        await asyncio.sleep(0.1)
        yield object(), object()

    controller = ChatController(
        transport_connector=_counting_connect,
        transport_listener=_delayed_listen,
        session_factory=FakeSession.create,
    )

    try:
        controller.rendezvous("10.0.0.2", 5555, "direct", "0.0.0.0")
        assert await wait_for_predicate(lambda: controller.session is not None)
        assert connect_calls["count"] == 1
    finally:
        controller.close()


def test_controller_stores_identity_and_trust_next_to_script(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)

    controller = ChatController(
        transport_connector=_fake_connect,
        transport_listener=_fake_listen,
        session_factory=FakeSession.create,
    )
    try:
        assert (tmp_path / "identity.key").exists()
        assert controller._trust_store.path == tmp_path / "trust.json"
        assert controller._outbox.path == tmp_path / "outbox.json"
    finally:
        controller.close()


@pytest.mark.asyncio
async def test_controller_connects_via_discovery(tmp_path):
    statuses = []
    discovery = FakeDiscoveryProvider(
        [
            PeerDescriptor(
                version=1,
                nickname="alice",
                identity_fingerprint=None,
                signing_public_key=None,
                transport="direct",
                endpoints=(PeerEndpoint(host="198.51.100.10", port=5555),),
                expires_at=9999999999,
                sequence=1,
                nonce="x",
                signature=None,
            )
        ]
    )
    connect_calls = []

    async def _counting_connect(_transport, host, port, **_options):
        connect_calls.append((host, port))
        return None, None

    def _discovery_factory(_scheme, **_options):
        return discovery

    controller = ChatController(
        on_status=statuses.append,
        transport_connector=_counting_connect,
        transport_listener=_fake_listen,
        discovery_factory=_discovery_factory,
        session_factory=FakeSession.create,
    )

    try:
        controller.discover_and_connect(
            "alice",
            "secret",
            "udp-tracker",
            discovery_role="connect",
            transport="direct",
            port=5555,
            bind="0.0.0.0",
            discovery_options={"tracker_url": "udp://tracker.example:80/announce"},
        )
        assert await wait_for_predicate(lambda: controller.session is not None)
        assert not discovery.announces
        assert discovery.resolves
        assert connect_calls == [("198.51.100.10", 5555)]
        assert await wait_for_predicate(
            lambda: any("via discovery" in status for status in statuses)
        )
        assert any(
            "Discovery peer 1; nickname=alice; transport=direct; endpoints=198.51.100.10:5555"
            in status
            for status in statuses
        )
    finally:
        controller.close()


@pytest.mark.asyncio
async def test_controller_discovery_skips_own_announcement():
    statuses = []
    discovery = FakeDiscoveryProvider(
        [
            PeerDescriptor(
                version=1,
                nickname="alice",
                identity_fingerprint="self-fp",
                signing_public_key=None,
                transport="direct",
                endpoints=(PeerEndpoint(host="127.0.0.1", port=5555),),
                expires_at=9999999999,
                sequence=1,
                nonce="x",
                signature=None,
            ),
            PeerDescriptor(
                version=1,
                nickname="alice",
                identity_fingerprint="peer-fp",
                signing_public_key=None,
                transport="direct",
                endpoints=(PeerEndpoint(host="198.51.100.10", port=5555),),
                expires_at=9999999999,
                sequence=2,
                nonce="y",
                signature=None,
            ),
        ]
    )
    connect_calls = []

    async def _counting_connect(_transport, host, port, **_options):
        connect_calls.append((host, port))
        return None, None

    def _discovery_factory(_scheme, **_options):
        return discovery

    controller = ChatController(
        on_status=statuses.append,
        transport_connector=_counting_connect,
        transport_listener=_fake_listen,
        discovery_factory=_discovery_factory,
        session_factory=FakeSession.create,
    )
    controller.local_fingerprint = lambda encoding="base64": "self-fp"

    try:
        controller.discover_and_connect(
            "alice",
            "secret",
            "mainline-dht",
            discovery_role="connect",
            transport="direct",
            port=5555,
            bind="127.0.0.1",
            discovery_options={},
        )
        assert await wait_for_predicate(lambda: controller.session is not None)
        assert connect_calls == [("198.51.100.10", 5555)]
        assert await wait_for_predicate(
            lambda: any("trying connect" in status for status in statuses)
        )
        assert any(
            "Discovery peer 1; nickname=alice; transport=direct; "
            "endpoints=198.51.100.10:5555; fingerprint=peer-fp"
            in status
            for status in statuses
        )
    finally:
        controller.close()


@pytest.mark.asyncio
async def test_controller_reports_discovery_candidate_failures():
    statuses = []
    discovery = FakeDiscoveryProvider(
        [
            PeerDescriptor(
                version=1,
                nickname="alice",
                identity_fingerprint="peer-1",
                signing_public_key=None,
                transport="direct",
                endpoints=(PeerEndpoint(host="198.51.100.10", port=5555),),
                expires_at=9999999999,
                sequence=1,
                nonce="x",
                signature=None,
            ),
            PeerDescriptor(
                version=1,
                nickname="alice",
                identity_fingerprint="peer-2",
                signing_public_key=None,
                transport="direct",
                endpoints=(PeerEndpoint(host="198.51.100.11", port=5556),),
                expires_at=9999999999,
                sequence=2,
                nonce="y",
                signature=None,
            ),
        ]
    )

    async def _failing_connect(_transport, host, port, **_options):
        raise ConnectionError(f"dial to {host}:{port} refused")

    def _discovery_factory(_scheme, **_options):
        return discovery

    controller = ChatController(
        on_status=statuses.append,
        transport_connector=_failing_connect,
        transport_listener=_fake_listen,
        discovery_factory=_discovery_factory,
        session_factory=FakeSession.create,
    )

    try:
        future = controller.discover_and_connect(
            "alice",
            "secret",
            "udp-tracker",
            discovery_role="connect",
            transport="direct",
            port=5555,
            bind="0.0.0.0",
            discovery_options={"tracker_url": "udp://tracker.example:80/announce"},
        )
        with pytest.raises(
            RuntimeError,
            match=(
                "Discovery found peers but connect failed: "
                "198.51.100.10:5555 -> dial to 198.51.100.10:5555 refused; "
                "198.51.100.11:5556 -> dial to 198.51.100.11:5556 refused"
            ),
        ):
            future.result(timeout=2)
        assert any("Discovery peer 1; nickname=alice" in status for status in statuses)
        assert any("Discovery peer 2; nickname=alice" in status for status in statuses)
    finally:
        controller.close()


@pytest.mark.asyncio
async def test_controller_connect_contact_prefers_cached_route_and_updates_contact():
    statuses = []
    contact_updates = []
    discovery = FakeDiscoveryProvider(
        [
            PeerDescriptor(
                version=1,
                nickname="alice",
                identity_fingerprint="peer-fp",
                signing_public_key=None,
                transport="direct",
                endpoints=(PeerEndpoint(host="198.51.100.50", port=6666),),
                expires_at=9999999999,
                sequence=1,
                nonce="x",
                signature=None,
            )
        ]
    )
    connect_calls = []

    async def _counting_connect(_transport, host, port, **_options):
        connect_calls.append((host, port))
        return None, None

    def _discovery_factory(_scheme, **_options):
        return discovery

    controller = ChatController(
        on_status=statuses.append,
        on_contact_update=contact_updates.append,
        transport_connector=_counting_connect,
        transport_listener=_fake_listen,
        discovery_factory=_discovery_factory,
        session_factory=FakeSession.create,
    )

    contact = {
        "label": "Alice",
        "discovery_nickname": "alice",
        "discovery_key": "secret",
        "identity_fingerprint": "peer-fp",
        "last_known_host": "203.0.113.10",
        "last_known_port": "5555",
        "transport": "direct",
        "port": "4444",
        "tracker_preset": "Open Stealth UDP",
        "discovery_scheme": "udp-tracker",
    }

    try:
        controller.connect_contact(
            contact,
            bind="0.0.0.0",
            discovery_scheme="udp-tracker",
            transport="direct",
            port=4444,
            discovery_options={"tracker_url": "udp://tracker.example:80/announce"},
        )
        assert await wait_for_predicate(lambda: controller.session is not None, timeout=2.0)
        assert connect_calls == [("203.0.113.10", 5555)]
        assert discovery.announces
        assert discovery.resolves == [("alice", "secret", "peer-fp")]
        assert contact_updates
        latest = contact_updates[-1]
        assert latest["identity_fingerprint"] == "peer-fp"
        assert latest["last_known_host"] == "203.0.113.10"
        assert latest["last_known_port"] == "5555"
        assert latest["last_known_transport"] == "direct"
        assert any("Trying last known route" in status for status in statuses)
    finally:
        controller.disconnect()
        assert discovery.withdraws == [("alice", "secret")]
        controller.close()


@pytest.mark.asyncio
async def test_controller_connect_contact_falls_back_to_rendezvous():
    statuses = []
    discovery = FakeDiscoveryProvider(
        [
            PeerDescriptor(
                version=1,
                nickname="alice",
                identity_fingerprint=None,
                signing_public_key=None,
                transport="direct",
                endpoints=(PeerEndpoint(host="198.51.100.77", port=5555),),
                expires_at=9999999999,
                sequence=1,
                nonce="x",
                signature=None,
            )
        ]
    )
    connect_attempts = {"count": 0}

    async def _failing_connect(_transport, _host, _port, **_options):
        connect_attempts["count"] += 1
        raise ConnectionError("dial failed")

    async def _listen_peer(_transport, _host, _port, **_options):
        await asyncio.sleep(0.01)
        yield "peer-reader", "peer-writer"

    def _discovery_factory(_scheme, **_options):
        return discovery

    controller = ChatController(
        on_status=statuses.append,
        transport_connector=_failing_connect,
        transport_listener=_listen_peer,
        discovery_factory=_discovery_factory,
        session_factory=FakeSession.create,
    )

    contact = {
        "label": "Alice",
        "discovery_nickname": "alice",
        "discovery_key": "secret",
        "transport": "direct",
        "port": "4444",
        "tracker_preset": "Open Stealth UDP",
        "discovery_scheme": "udp-tracker",
    }

    try:
        controller.connect_contact(
            contact,
            bind="0.0.0.0",
            discovery_scheme="udp-tracker",
            transport="direct",
            port=4444,
            discovery_options={"tracker_url": "udp://tracker.example:80/announce"},
        )
        assert await wait_for_predicate(lambda: controller.session is not None, timeout=2.0)
        assert connect_attempts["count"] >= 2
        assert any("switching to rendezvous behavior" in status for status in statuses)
        assert any("Rendezvous established" in status for status in statuses)
    finally:
        controller.close()


@pytest.mark.asyncio
async def test_controller_rendezvous_skips_self_connection_and_accepts_peer():
    statuses = []

    async def _dial_self(_transport, _host, _port, **_options):
        await asyncio.sleep(0.01)
        return "dial-self-reader", "dial-self-writer"

    async def _listen_peer(_transport, _host, _port, **_options):
        await asyncio.sleep(0.02)
        yield "peer-reader", "peer-writer"

    async def _session_factory(reader, _writer, _initiator, **_kwargs):
        session = FakeSession()
        if reader == "dial-self-reader":
            session.peer_fingerprint = "self-fp"
        else:
            session.peer_fingerprint = "peer-fp"
        return session

    controller = ChatController(
        on_status=statuses.append,
        transport_connector=_dial_self,
        transport_listener=_listen_peer,
        session_factory=_session_factory,
    )
    controller.local_fingerprint = lambda encoding="base64": "self-fp"

    try:
        controller.rendezvous("198.51.100.10", 5555, "direct", "0.0.0.0")
        assert await wait_for_predicate(
            lambda: controller.session is not None
            and getattr(controller.session, "peer_fingerprint", None) == "peer-fp",
            timeout=2.0,
        )
        assert await wait_for_predicate(
            lambda: any("Ignored self-connection candidate" in status for status in statuses)
        )
        assert await wait_for_predicate(
            lambda: any("Rendezvous established" in status for status in statuses)
        )
    finally:
        controller.close()
