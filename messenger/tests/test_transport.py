import asyncio
import json
from pathlib import Path

import pytest

from messenger.core.transport_direct import DirectTransport
from messenger.core.transport_manager import get_transport
from messenger.core.transport_yggdrasil import YggdrasilTransport
from messenger.core.transport_yggdrasil_embedded import EmbeddedYggdrasilTransport


@pytest.mark.asyncio
async def test_direct_transport_loopback(unused_tcp_port):
    host = "127.0.0.1"
    port = unused_tcp_port
    transport = DirectTransport()
    listener = transport.listen(host, port)
    server_conn = asyncio.create_task(listener.__anext__())
    await asyncio.sleep(0.05)

    reader, writer = await transport.connect(host, port)
    server_reader, server_writer = await server_conn

    writer.write(b"hi")
    await writer.drain()
    data = await server_reader.read(2)

    server_writer.write(b"ok")
    await server_writer.drain()
    resp = await reader.read(2)

    writer.close()
    server_writer.close()
    await writer.wait_closed()
    await server_writer.wait_closed()
    await listener.aclose()

    assert data == b"hi"
    assert resp == b"ok"


def test_yggdrasil_validates_ipv6_addresses():
    transport = YggdrasilTransport()

    # Valid IPv6 should pass
    transport._validate_ipv6("200:abcd:1234::5")

    with pytest.raises(ValueError):
        transport._validate_ipv6("not-an-ipv6")


def test_transport_manager_instances_are_new():
    first = get_transport("direct")
    second = get_transport("direct")

    assert isinstance(first, DirectTransport)
    assert isinstance(second, DirectTransport)
    assert first is not second


def test_transport_manager_unknown_scheme():
    with pytest.raises(ValueError):
        get_transport("missing")


@pytest.mark.asyncio
async def test_embedded_transport_requires_config():
    transport = EmbeddedYggdrasilTransport()
    with pytest.raises(ValueError):
        await transport.connect("::1", 0)


def test_embedded_transport_overlays_public_peers(tmp_path):
    config_path = tmp_path / "ygg.json"
    config_path.write_text(json.dumps({"Peers": ["tcp://old"], "Other": "value"}))
    transport = EmbeddedYggdrasilTransport(
        config_path=str(config_path), public_peers=["tcp://new"]
    )

    patched_path = transport._prepare_config()
    try:
        data = json.loads(Path(patched_path).read_text())
    finally:
        if patched_path != config_path:
            Path(patched_path).unlink(missing_ok=True)

    assert data["Peers"] == ["tcp://new"]


@pytest.mark.asyncio
async def test_direct_transport_onion_connect_uses_socks5(monkeypatch):
    import socket
    transport = DirectTransport()
    recorded_calls = []

    def fake_create_socks(proxy_host, proxy_port, target_host, target_port, timeout=15.0):
        recorded_calls.append((proxy_host, proxy_port, target_host, target_port))
        # Return a real pair of connected loopback sockets to test asyncio socket wrapping
        s1, s2 = socket.socketpair()
        s1.setblocking(False)
        return s1

    monkeypatch.setattr("messenger.core.transport_direct._create_socks_connection", fake_create_socks)

    onion_host = "v4kg3abcdefghijklmnopqrstuvwxyz234567abcdefghijklmno.onion"
    reader, writer = await transport.connect(onion_host, 50001)
    writer.close()
    await writer.wait_closed()

    assert len(recorded_calls) == 1
    assert recorded_calls[0][2] == onion_host
    assert recorded_calls[0][3] == 50001

