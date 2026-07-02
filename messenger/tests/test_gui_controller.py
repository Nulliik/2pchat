import asyncio
import base64
import os
from pathlib import Path

import pytest

from messenger.app.gui_controller import ChatController
from messenger.core.crypto import encrypt_file_in_chunks
from messenger.core.identity import Outbox


class FakeSession:
    def __init__(self):
        self.sent = []
        self.recv_queue: asyncio.Queue = asyncio.Queue()
        self.closed = False

    @classmethod
    async def create(cls, _reader=None, _writer=None, _initiator=None, **_kwargs):
        return cls()

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
