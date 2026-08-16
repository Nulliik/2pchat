import asyncio
import json
import pytest

import messenger.discovery_bridge as discovery_bridge
from messenger.discovery_bridge import (
    _STALE_EP_THRESHOLD,
    _record_endpoint_failure,
    _record_endpoint_success,
    _is_endpoint_in_cooldown,
    _categorize_endpoint_tier,
    _dial_fastest_endpoint,
    configure_proxy,
)


@pytest.fixture(autouse=True)
def reset_discovery_state():
    configure_proxy(json.dumps({"proxy_enabled": False}))
    with discovery_bridge._stale_ep_lock:
        discovery_bridge._stale_endpoint_failures.clear()
    yield
    configure_proxy(json.dumps({"proxy_enabled": False}))
    with discovery_bridge._stale_ep_lock:
        discovery_bridge._stale_endpoint_failures.clear()


def test_stale_endpoint_cooldown_threshold_is_two():
    assert _STALE_EP_THRESHOLD == 2
    ep = "192.168.1.100:50001"

    assert not _is_endpoint_in_cooldown(ep)
    _record_endpoint_failure(ep)
    assert not _is_endpoint_in_cooldown(ep)

    # Second failure triggers cooldown
    _record_endpoint_failure(ep)
    assert _is_endpoint_in_cooldown(ep)

    # Success clears cooldown
    _record_endpoint_success(ep)
    assert not _is_endpoint_in_cooldown(ep)


def test_onion_endpoints_never_enter_cooldown():
    ep = "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"
    for _ in range(5):
        _record_endpoint_failure(ep)
    assert not _is_endpoint_in_cooldown(ep)


@pytest.mark.asyncio
async def test_dial_fastest_endpoint_caps_tier3_to_four_candidates(monkeypatch):
    dialed = []

    async def mock_dial_identified_endpoint(ep, identity_priv, signing_key, trust_store, expected_fingerprint=None):
        dialed.append(ep)
        raise ConnectionError("simulated offline host")

    monkeypatch.setattr(discovery_bridge, "_dial_identified_endpoint", mock_dial_identified_endpoint)

    # 8 direct endpoints in Tier 3
    endpoints = [f"192.168.1.{i}:50001" for i in range(1, 9)]

    with pytest.raises(ConnectionError):
        await _dial_fastest_endpoint(endpoints, None, None, None)

    # Exactly top 4 freshest endpoints should be attempted, capping socket exhaustion
    assert len(dialed) == 4
    assert dialed == [f"192.168.1.{i}:50001" for i in range(1, 5)]


@pytest.mark.asyncio
async def test_adaptive_timeout_constants_selection(monkeypatch):
    recorded_timeouts = []

    async def mock_transport_connect(transport, host, port, **kwargs):
        raise ConnectionError("not connected")

    async def mock_wait_for(fut, timeout):
        recorded_timeouts.append(timeout)
        return await fut

    monkeypatch.setattr(discovery_bridge, "transport_connect", mock_transport_connect)
    monkeypatch.setattr(asyncio, "wait_for", mock_wait_for)

    # Test Clearnet IPv4 endpoint: connect_timeout should be 2.0
    with pytest.raises(Exception):
        await discovery_bridge._dial_endpoint("192.168.1.50:50001", None, None, None)
    assert 2.0 in recorded_timeouts

    # Test Yggdrasil IPv6 endpoint: connect_timeout should be 3.5
    recorded_timeouts.clear()
    with pytest.raises(Exception):
        await discovery_bridge._dial_endpoint("[200:1e::5]:50001", None, None, None)
    assert 3.5 in recorded_timeouts

    # Test Tor .onion endpoint: connect_timeout should be 8.0
    recorded_timeouts.clear()
    with pytest.raises(Exception):
        await discovery_bridge._dial_endpoint("ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001", None, None, None)
    assert 8.0 in recorded_timeouts
