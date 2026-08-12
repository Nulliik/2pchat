import pytest
import json
from messenger.discovery_bridge import configure_proxy, get_proxy_configuration, _patch_pysocks_ipv6

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

def test_pysocks_ipv6_4tuple_sanitization(monkeypatch):
    import socks
    
    received_addresses = []
    
    # Mock original socksocket methods before patching
    def mock_orig_bind(self, addr):
        received_addresses.append(("bind", addr))
        
    def mock_orig_connect(self, addr):
        received_addresses.append(("connect", addr))
        
    def mock_orig_sendto(self, data, *args):
        received_addresses.append(("sendto", args[0]))

    monkeypatch.setattr(socks.socksocket, "bind", mock_orig_bind)
    monkeypatch.setattr(socks.socksocket, "connect", mock_orig_connect)
    monkeypatch.setattr(socks.socksocket, "sendto", mock_orig_sendto)
    
    # Reset patch flag to apply new mocks
    if hasattr(socks.socksocket, "_2pchat_ipv6_patched"):
        delattr(socks.socksocket, "_2pchat_ipv6_patched")

    _patch_pysocks_ipv6()
    
    dummy = socks.socksocket()
    
    # Test IPv6 4-tuples get truncated to 2-tuples
    dummy.bind(("200:1e2f::1", 80, 0, 0))
    dummy.connect(("200:1e2f::1", 80, 0, 0))
    dummy.sendto(b"test", ("200:1e2f::1", 80, 0, 0))
    
    assert received_addresses[0] == ("bind", ("200:1e2f::1", 80))
    assert received_addresses[1] == ("connect", ("200:1e2f::1", 80))
    assert received_addresses[2] == ("sendto", ("200:1e2f::1", 80))
    
    # Test standard IPv4 2-tuples pass unchanged
    received_addresses.clear()
    dummy.bind(("127.0.0.1", 9050))
    dummy.connect(("127.0.0.1", 9050))
    dummy.sendto(b"test", ("127.0.0.1", 9050))
    
    assert received_addresses[0] == ("bind", ("127.0.0.1", 9050))
    assert received_addresses[1] == ("connect", ("127.0.0.1", 9050))
    assert received_addresses[2] == ("sendto", ("127.0.0.1", 9050))


def test_yggdrasil_proxy_bypass(monkeypatch):
    import socks
    from messenger.discovery_bridge import _is_yggdrasil_host

    assert _is_yggdrasil_host("200:44dd:c0a0::1") is True
    assert _is_yggdrasil_host("201:229e:d0b0::2") is True
    assert _is_yggdrasil_host("202:a2a5:dead::3") is True
    assert _is_yggdrasil_host("peer.ygg") is True
    assert _is_yggdrasil_host("93.184.216.34") is False
    assert _is_yggdrasil_host("opentrackr.org") is False

    # Test that proxy is set to None during Yggdrasil connect call
    proxy_states = []

    def mock_connect_check_proxy(self, dest_pair):
        proxy_states.append((dest_pair[0], getattr(self, "proxy", None)))

    monkeypatch.setattr(socks.socksocket, "connect", mock_connect_check_proxy)
    if hasattr(socks.socksocket, "_2pchat_ipv6_patched"):
        delattr(socks.socksocket, "_2pchat_ipv6_patched")

    _patch_pysocks_ipv6()

    dummy = socks.socksocket()
    dummy.proxy = (socks.SOCKS5, "127.0.0.1", 9050, True, None, None)

    # Yggdrasil connect should temporarily clear proxy
    dummy.connect(("200:44dd:c0a0::1", 50001))
    assert proxy_states[0] == ("200:44dd:c0a0::1", None)
    # Ensure proxy setting is restored afterwards
    assert dummy.proxy == (socks.SOCKS5, "127.0.0.1", 9050, True, None, None)

    # Public IP connect should keep proxy intact
    dummy.connect(("93.184.216.34", 80))
    assert proxy_states[1] == ("93.184.216.34", (socks.SOCKS5, "127.0.0.1", 9050, True, None, None))

