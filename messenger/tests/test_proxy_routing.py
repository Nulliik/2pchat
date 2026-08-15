import asyncio
import json
import socket

import pytest

import messenger.discovery_bridge as discovery_bridge
from messenger.discovery_bridge import (
    configure_proxy,
    create_tracker_socket,
    get_proxy_configuration,
)


@pytest.fixture(autouse=True)
def reset_proxy_configuration():
    configure_proxy(json.dumps({"proxy_enabled": False}))
    yield
    configure_proxy(json.dumps({"proxy_enabled": False}))


def test_proxy_configuration_parsing():
    config = {
        "proxy_enabled": True,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050
    }
    assert configure_proxy(json.dumps(config)) is True
    active = get_proxy_configuration()
    assert active["enabled"] is True
    assert active["host"] == "127.0.0.1"
    assert active["port"] == 9050


def test_custom_socks5_port_configuration():
    config = {
        "proxy_enabled": True,
        "proxy_host": "10.0.0.1",
        "proxy_port": 1080
    }
    assert configure_proxy(json.dumps(config)) is True
    active = get_proxy_configuration()
    assert active["enabled"] is True
    assert active["host"] == "10.0.0.1"
    assert active["port"] == 1080


def test_custom_socks5_positional_configuration():
    assert configure_proxy(True, 1080, "10.0.0.1") is True
    assert get_proxy_configuration() == {
        "enabled": True,
        "host": "10.0.0.1",
        "port": 1080,
    }


def test_proxy_disabled_configuration():
    config = {
        "proxy_enabled": False,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050
    }
    assert configure_proxy(json.dumps(config)) is True
    active = get_proxy_configuration()
    assert active["enabled"] is False


def test_invalid_proxy_configuration_rejected():
    invalid_config = {
        "proxy_enabled": True,
        "proxy_host": "",
        "proxy_port": 70000
    }
    assert configure_proxy(json.dumps(invalid_config)) is False

    assert configure_proxy(json.dumps({
        "proxy_enabled": "false",
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050,
    })) is False


def test_proxy_does_not_monkeypatch_global_socket_factory():
    original_factory = socket.socket

    assert configure_proxy(json.dumps({
        "proxy_enabled": True,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050,
    })) is True

    assert socket.socket is original_factory


def test_udp_tracker_socket_fails_closed_while_proxy_is_enabled():
    assert configure_proxy(json.dumps({
        "proxy_enabled": True,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050,
    })) is True

    with pytest.raises(OSError, match="UDP"):
        create_tracker_socket(socket.AF_INET6, socket.SOCK_DGRAM)


def test_http_tracker_request_uses_scoped_proxy_opener(monkeypatch):
    calls = []

    class FakeOpener:
        def open(self, request, *, timeout):
            calls.append((request, timeout))
            return "proxied-response"

    monkeypatch.setattr(
        discovery_bridge,
        "_build_proxy_url_opener",
        lambda _host, _port: FakeOpener(),
    )
    assert configure_proxy(json.dumps({
        "proxy_enabled": True,
        "proxy_host": "2001:db8::1",
        "proxy_port": 9050,
    })) is True


def test_proxy_disabled_configuration():
    config = {
        "proxy_enabled": False,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050
    }
    assert configure_proxy(json.dumps(config)) is True
    active = get_proxy_configuration()
    assert active["enabled"] is False


def test_invalid_proxy_configuration_rejected():
    invalid_config = {
        "proxy_enabled": True,
        "proxy_host": "",
        "proxy_port": 70000
    }
    assert configure_proxy(json.dumps(invalid_config)) is False

    assert configure_proxy(json.dumps({
        "proxy_enabled": "false",
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050,
    })) is False


def test_proxy_does_not_monkeypatch_global_socket_factory():
    original_factory = socket.socket

    assert configure_proxy(json.dumps({
        "proxy_enabled": True,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050,
    })) is True

    assert socket.socket is original_factory


def test_udp_tracker_socket_fails_closed_while_proxy_is_enabled():
    assert configure_proxy(json.dumps({
        "proxy_enabled": True,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050,
    })) is True

    with pytest.raises(OSError, match="UDP"):
        create_tracker_socket(socket.AF_INET6, socket.SOCK_DGRAM)


def test_http_tracker_request_uses_scoped_proxy_opener(monkeypatch):
    calls = []

    class FakeOpener:
        def open(self, request, *, timeout):
            calls.append((request, timeout))
            return "proxied-response"

    monkeypatch.setattr(
        discovery_bridge,
        "_build_proxy_url_opener",
        lambda _host, _port: FakeOpener(),
    )
    assert configure_proxy(json.dumps({
        "proxy_enabled": True,
        "proxy_host": "2001:db8::1",
        "proxy_port": 9050,
    })) is True

    request = object()
    assert discovery_bridge.open_tracker_url(request, timeout=2.5) == "proxied-response"
    assert calls == [(request, 2.5)]


def test_tor_skips_udp_trackers(monkeypatch):
    from messenger import discovery_bridge

    # Configure proxy as enabled
    discovery_bridge.configure_proxy(json.dumps({
        "proxy_enabled": True,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050,
    }))

    trackers = ["OpenTrackr HTTPS", "Torrent.eu.org UDP", "Open Stealth UDP"]
    filtered = discovery_bridge._filter_enabled_trackers(trackers)

    # Assert that UDP trackers are skipped and ONLY HTTP(S) trackers remain when proxy is active
    assert "OpenTrackr HTTPS" in filtered
    assert "Torrent.eu.org UDP" not in filtered
    assert "Open Stealth UDP" not in filtered

    # Disable proxy and verify UDP trackers are allowed again
    discovery_bridge.configure_proxy(json.dumps({
        "proxy_enabled": False,
    }))
    filtered_direct = discovery_bridge._filter_enabled_trackers(trackers)
    assert "Torrent.eu.org UDP" in filtered_direct


def test_onion_endpoint_sorting_and_transport_label():
    from messenger import discovery_bridge

    onion_ep = "v4kg3exmpl567890abcdefghijklmnopqrstuvwxyz1234567890123.onion:50001"
    ygg_ep = "[200:1e2f:e608:fa24:5b27:32ef:e364:45fe]:50001"
    ipv4_ep = "192.168.1.100:50001"

    # Transport label
    assert discovery_bridge._transport_for_endpoint(onion_ep) == "Tor Onion"
    assert discovery_bridge._transport_for_endpoint(ygg_ep) == "Yggdrasil"
    assert discovery_bridge._transport_for_endpoint(ipv4_ep) == "Direct P2P"

    # IPv4 detection
    assert discovery_bridge._is_ipv4_endpoint(onion_ep) is False
    assert discovery_bridge._is_ipv4_endpoint(ygg_ep) is False
    assert discovery_bridge._is_ipv4_endpoint(ipv4_ep) is True

    # Sorting when Tor is active
    discovery_bridge.configure_proxy(json.dumps({
        "proxy_enabled": True,
        "proxy_host": "127.0.0.1",
        "proxy_port": 9050,
    }))
    endpoints = [ipv4_ep, ygg_ep, onion_ep]
    sorted_endpoints = sorted(endpoints, key=discovery_bridge._endpoint_sort_key)
    assert sorted_endpoints[0] == onion_ep
    assert sorted_endpoints[1] == ygg_ep
    assert sorted_endpoints[2] == ipv4_ep


@pytest.mark.asyncio
async def test_direct_transport_onion_connect_via_socks(monkeypatch):
    import asyncio
    from messenger.core.transport_direct import DirectTransport

    socks_calls = []

    class MockSocksSocket:
        def __init__(self):
            pass

        def set_proxy(self, proxy_type, host, port, rdns=True):
            socks_calls.append(("set_proxy", proxy_type, host, port, rdns))

        def settimeout(self, timeout):
            socks_calls.append(("settimeout", timeout))

        def connect(self, dest):
            socks_calls.append(("connect", dest))

        def setblocking(self, mode):
            socks_calls.append(("setblocking", mode))

    import sys
    import types
    fake_socks = types.ModuleType("socks")
    fake_socks.SOCKS5 = 2
    fake_socks.socksocket = MockSocksSocket
    monkeypatch.setitem(sys.modules, "socks", fake_socks)

    async def fake_open_connection(*args, **kwargs):
        sock = kwargs.get("sock")
        assert sock is not None
        return ("reader", "writer")

    monkeypatch.setattr(asyncio, "open_connection", fake_open_connection)

    transport = DirectTransport()
    reader, writer = await transport.connect(
        "exmpl567890abcdefghijklmnopqrstuvwxyz12345678901234.onion",
        50001,
        proxy_host="127.0.0.1",
        proxy_port=9050,
    )
    assert reader == "reader"
    assert writer == "writer"
    assert ("set_proxy", 2, "127.0.0.1", 9050, True) in socks_calls
    assert ("connect", ("exmpl567890abcdefghijklmnopqrstuvwxyz12345678901234.onion", 50001)) in socks_calls
