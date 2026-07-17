import importlib.util
import asyncio
import json
from pathlib import Path
from types import SimpleNamespace


def _load_discovery_bridge():
    root = Path(__file__).resolve().parents[2]
    path = (
        root
        / "2PChat android"
        / "android"
        / "app"
        / "src"
        / "main"
        / "python"
        / "discovery_bridge.py"
    )
    spec = importlib.util.spec_from_file_location("android_discovery_bridge", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_android_initial_announce_starts_bep5_without_waiting_for_trackers(monkeypatch):
    bridge = _load_discovery_bridge()
    events = []

    class FakeTrackerProvider:
        observed_addresses = ()

        async def announce(self, *args, **kwargs):
            del args, kwargs
            import asyncio

            events.append("tracker-start")
            await asyncio.sleep(0.01)
            events.append("tracker-end")
            return object()

    class FakeDhtProvider:
        observed_addresses = ()

        async def announce(self, *args, **kwargs):
            del args, kwargs
            events.append("dht-start")
            return object()

    def fake_provider(scheme, **kwargs):
        del kwargs
        return FakeDhtProvider() if scheme == "mainline-dht" else FakeTrackerProvider()

    monkeypatch.setattr(bridge, "CLEARNET_TRACKERS", ("Fast test tracker",))
    monkeypatch.setattr(bridge, "YGG_TRACKERS", ())
    monkeypatch.setattr(
        bridge,
        "get_tracker_by_name",
        lambda _name: SimpleNamespace(
            discovery_scheme="http-tracker",
            announce_url="https://tracker.invalid/announce",
        ),
    )
    monkeypatch.setattr(bridge, "get_discovery_provider", fake_provider)
    monkeypatch.setattr(bridge, "_discover_public_ipv4_stun", lambda: None)

    result = bridge.announce_peer_endpoints(
        "alice",
        "short-fingerprint",
        '["192.0.2.10"]',
        50001,
        "shared-code",
    )

    assert result is True
    assert events.index("dht-start") < events.index("tracker-end")


def test_direct_yggdrasil_neighbour_returns_before_slow_candidates(monkeypatch):
    bridge = _load_discovery_bridge()
    slow_cancelled = asyncio.Event()

    async def fake_verify(endpoint, nickname, expected_fingerprint=None):
        assert nickname == "bob"
        assert expected_fingerprint is None
        if endpoint == "[200::2]:50001":
            return {
                "nickname": "Bob",
                "fingerprint": "peer-fingerprint",
                "endpoint": endpoint,
                "verified": True,
            }
        try:
            await asyncio.sleep(30)
        except asyncio.CancelledError:
            slow_cancelled.set()
            raise

    monkeypatch.setattr(bridge, "_verify_live_endpoint", fake_verify)

    result = bridge.verify_live_endpoints(
        json.dumps(["[200::1]:50001", "[200::2]:50001"]),
        "bob",
    )

    assert result[0]["endpoints"] == ["[200::2]:50001"]
    assert result[0]["verification_reason"] == "authenticated direct discovery peer"
    assert slow_cancelled.is_set()


def test_direct_yggdrasil_search_reuses_authenticated_active_session(monkeypatch):
    bridge = _load_discovery_bridge()

    class FakeWriter:
        def get_extra_info(self, name):
            assert name == "peername"
            return ("200::2", 50001)

    session = SimpleNamespace(is_online=True, peer_label="Bob", writer=FakeWriter())
    bridge.active_sessions["peer-fingerprint"] = session
    bridge.peer_fingerprint_to_name["peer-fingerprint"] = "Bob"
    monkeypatch.setattr(
        bridge,
        "_verify_live_endpoint",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("must not dial")),
    )

    result = bridge.verify_live_endpoints('["[200::2]:50001"]', "bob")

    assert result[0]["fingerprint"] == "peer-fingerprint"
    assert result[0]["verification_reason"] == "authenticated active direct session"


def test_local_ipv4_candidate_is_live_verified(monkeypatch):
    bridge = _load_discovery_bridge()

    async def fake_verify(endpoint, nickname, expected_fingerprint=None):
        assert endpoint == "192.0.2.20:50001"
        assert nickname == "foxy"
        assert expected_fingerprint is None
        return {
            "nickname": "foxy",
            "fingerprint": "peer-fingerprint",
            "endpoint": endpoint,
            "verified": True,
        }

    monkeypatch.setattr(bridge, "_verify_live_endpoint", fake_verify)

    result = bridge.verify_live_endpoints(
        json.dumps(["192.0.2.20:50001", "not-an-endpoint", "999.1.1.1:50001"]),
        "foxy",
    )

    assert result[0]["nickname"] == "foxy"
    assert result[0]["endpoints"] == ["192.0.2.20:50001"]


def test_local_identity_info_uses_authenticated_identity_key(monkeypatch):
    bridge = _load_discovery_bridge()
    sent = []

    class FakeSession:
        async def send_reliable(self, payload):
            sent.append(payload)

    monkeypatch.setattr(
        bridge,
        "load_or_create_identity",
        lambda: SimpleNamespace(public_key="local-public-key"),
    )
    monkeypatch.setattr(bridge, "fingerprint", lambda _key: "actual-fingerprint")
    bridge.local_identity_nickname = "jiji"
    bridge.local_identity_fingerprint = "stale-fingerprint"

    assert asyncio.run(bridge._send_local_identity_info(FakeSession())) is True
    assert sent == [{
        "type": "identity_info",
        "nickname": "jiji",
        "fingerprint": "actual-fingerprint",
        "listen_port": 50001,
    }]


def test_background_reconnect_sends_identity_before_session_callback(monkeypatch):
    bridge = _load_discovery_bridge()
    events = []

    class FakeSession:
        peer_fingerprint = "remote-fingerprint"
        is_online = True

        async def send_reliable(self, payload):
            events.append(payload["type"])

    class FakeCallback:
        def onSessionEstablished(self, *_args):
            events.append("callback")

    async def scenario():
        session = FakeSession()
        monkeypatch.setattr(
            bridge,
            "load_or_create_identity",
            lambda: SimpleNamespace(public_key="local-public-key"),
        )
        monkeypatch.setattr(bridge, "load_or_create_signing_identity", lambda: object())
        monkeypatch.setattr(bridge, "fingerprint", lambda _key: "local-fingerprint")
        monkeypatch.setattr(bridge, "TrustStore", lambda: object())
        monkeypatch.setattr(bridge, "_dial_endpoint", lambda *_args: asyncio.sleep(0, result=session))
        monkeypatch.setattr(
            bridge,
            "_register_authenticated_session",
            lambda *_args, **_kwargs: asyncio.sleep(0, result=session),
        )
        monkeypatch.setattr(bridge, "_read_loop", lambda *_args: asyncio.sleep(0))
        bridge.local_identity_nickname = "jiji"
        bridge.local_identity_fingerprint = "local-fingerprint"
        bridge.session_listener_callback = FakeCallback()
        bridge.loop = asyncio.get_running_loop()

        assert bridge.reconnect_peer_session("foxy", "192.0.2.20:50001") is True
        for _ in range(20):
            if "callback" in events:
                break
            await asyncio.sleep(0)

    asyncio.run(scenario())
    assert events[:2] == ["identity_info", "callback"]
