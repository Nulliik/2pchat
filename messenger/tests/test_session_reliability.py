import asyncio
import os
from pathlib import Path

import pytest

from messenger.core.identity import TrustStore, fingerprint, load_or_create_identity
from messenger.core.session import Session


async def _create_sessions(loopback_host: str = "127.0.0.1"):
    base = Path(os.environ.get("P2PCHAT_CONFIG_DIR", "."))
    server_identity = load_or_create_identity(str(base / "server.key"))
    client_identity = load_or_create_identity(str(base / "client.key"))
    shared_trust = TrustStore()

    server_session_box = {}

    async def handle(reader, writer):
        session = await Session.create(
            reader,
            writer,
            initiator=False,
            identity_priv=server_identity,
            trust_store=shared_trust,
        )
        server_session_box["session"] = session
        incoming = await session.receive_message()
        await session.send_chat(f"echo:{incoming['body']}")

    server = await asyncio.start_server(handle, loopback_host, 0)
    port = server.sockets[0].getsockname()[1]

    reader, writer = await asyncio.open_connection(loopback_host, port)
    client_session = await Session.create(
        reader,
        writer,
        initiator=True,
        identity_priv=client_identity,
        trust_store=shared_trust,
    )

    return server, client_session, server_session_box


@pytest.mark.asyncio
async def test_reliable_send_and_ack(monkeypatch, tmp_path):
    monkeypatch.setenv("P2PCHAT_CONFIG_DIR", str(tmp_path))
    server, client_session, server_box = await _create_sessions()

    msg_id = await client_session.send_chat("hi")
    response = await client_session.receive_message()

    await client_session.close()
    if server_box.get("session"):
        await server_box["session"].close()
    server.close()
    await server.wait_closed()

    assert msg_id is not None
    assert response["body"].startswith("echo:")


@pytest.mark.asyncio
async def test_reliable_sends_can_cross_in_both_directions(monkeypatch, tmp_path):
    """Mobile health probes may be emitted by both peers at the same time."""
    monkeypatch.setenv("P2PCHAT_CONFIG_DIR", str(tmp_path))
    base = Path(tmp_path)
    server_identity = load_or_create_identity(str(base / "server.key"))
    client_identity = load_or_create_identity(str(base / "client.key"))
    shared_trust = TrustStore()
    server_ready = asyncio.Future()

    async def handle(reader, writer):
        session = await Session.create(
            reader,
            writer,
            initiator=False,
            identity_priv=server_identity,
            trust_store=shared_trust,
        )
        server_ready.set_result(session)

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    client = await Session.create(
        reader,
        writer,
        initiator=True,
        identity_priv=client_identity,
        trust_store=shared_trust,
    )
    peer = await server_ready

    for sequence in range(10):
        await asyncio.gather(
            client.send_reliable({"type": "heartbeat", "sequence": sequence}),
            peer.send_reliable({"type": "heartbeat", "sequence": sequence}),
        )

    assert client.is_online
    assert peer.is_online
    await client.close()
    await peer.close()
    server.close()
    await server.wait_closed()


@pytest.mark.asyncio
async def test_binary_file_chunks_round_trip_with_parallel_acks(monkeypatch, tmp_path):
    monkeypatch.setenv("P2PCHAT_CONFIG_DIR", str(tmp_path))
    server_identity = load_or_create_identity(str(tmp_path / "server-file.key"))
    client_identity = load_or_create_identity(str(tmp_path / "client-file.key"))
    server_ready = asyncio.Future()

    async def handle(reader, writer):
        session = await Session.create(
            reader,
            writer,
            initiator=False,
            identity_priv=server_identity,
        )
        server_ready.set_result(session)

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    client = await Session.create(
        reader,
        writer,
        initiator=True,
        identity_priv=client_identity,
    )
    peer = await server_ready

    file_id = os.urandom(12)
    chunks = [(index, os.urandom(1024 + index)) for index in range(7)]
    try:
        acknowledged = await client.send_file_chunks(file_id, chunks, window_size=4)
        received = [await peer.receive_message() for _ in chunks]

        assert acknowledged == len(chunks)
        assert [message["chunk_index"] for message in received] == list(range(7))
        assert [message["payload"] for message in received] == [
            payload for _, payload in chunks
        ]
        assert all(isinstance(message["payload"], bytes) for message in received)
    finally:
        await client.close()
        await peer.close()
        server.close()
        await server.wait_closed()


@pytest.mark.asyncio
async def test_file_chunk_window_allows_four_ack_waiters():
    release = asyncio.Event()

    class WindowHarness:
        send_file_chunks = Session.send_file_chunks

        def __init__(self):
            self.active = 0
            self.max_active = 0

        async def send_file_chunk(self, _file_id, _chunk_index, _payload):
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            if self.active == 4:
                release.set()
            await release.wait()
            await asyncio.sleep(0)
            self.active -= 1

    harness = WindowHarness()
    chunks = [(index, bytes([index])) for index in range(8)]

    acknowledged = await harness.send_file_chunks(b"x" * 12, chunks, window_size=4)

    assert acknowledged == 8
    assert harness.max_active == 4


@pytest.mark.asyncio
async def test_trust_store_tracks_peer(monkeypatch, tmp_path):
    monkeypatch.setenv("P2PCHAT_CONFIG_DIR", str(tmp_path))
    server, client_session, server_box = await _create_sessions()

    client_fp = fingerprint(client_session.their_pub)
    server_fp = fingerprint(server_box["session"].their_pub)

    await client_session.close()
    await server_box["session"].close()
    server.close()
    await server.wait_closed()

    client_store = TrustStore()
    server_store = TrustStore()
    assert client_store.records.get(server_fp)
    assert server_store.records.get(client_fp)


@pytest.mark.asyncio
async def test_offline_status_and_reconnect(monkeypatch, tmp_path):
    monkeypatch.setenv("P2PCHAT_CONFIG_DIR", str(tmp_path))
    server, client_session, server_box = await _create_sessions()

    # Round-trip a message
    await client_session.send_chat("ping")
    response = await client_session.receive_message()
    assert response["body"].startswith("echo:")

    # Close the server side to force an offline signal
    await server_box["session"].close()
    offline = await client_session.receive_message()
    assert offline["type"] == "status"
    assert offline["state"] == "offline"
    assert client_session.is_online is False

    # Recreate both sessions to ensure we can come back online cleanly
    server.close()
    await server.wait_closed()

    server, client_session, server_box = await _create_sessions()
    await client_session.send_chat("second")
    follow_up = await client_session.receive_message()
    assert follow_up["body"].startswith("echo:")

    await client_session.close()
    await server_box["session"].close()
    server.close()
    await server.wait_closed()
