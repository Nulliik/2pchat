import pytest
import json
from messenger.discovery_bridge import configure_proxy, get_proxy_configuration

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
