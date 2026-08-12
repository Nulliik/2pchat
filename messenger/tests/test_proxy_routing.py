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
