import importlib.util
import asyncio
import json
import threading
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


def test_qr_and_classic_name_code_round_trip_through_tracker(monkeypatch):
    bridge = _load_discovery_bridge()
    calls = []

    class FakeTrackerProvider:
        observed_addresses = ()

        async def announce(self, nickname, shared_code, **kwargs):
            calls.append(("announce", nickname, shared_code))
            return SimpleNamespace()

        async def resolve(self, nickname, shared_code):
            calls.append(("resolve", nickname, shared_code))
            return [SimpleNamespace(
                nickname=nickname,
                identity_fingerprint="peer-fingerprint",
                endpoints=(SimpleNamespace(host="198.51.100.20", port=50001),),
            )]

    async def fake_verify(endpoint, nickname, expected_fingerprint=None):
        assert endpoint == "198.51.100.20:50001"
        assert nickname == "Anne_Marie#2"
        assert expected_fingerprint is None
        return {
            "nickname": nickname,
            "fingerprint": "peer-fingerprint",
            "endpoint": endpoint,
            "verified": True,
        }

    monkeypatch.setattr(bridge, "CLEARNET_TRACKERS", ("Test tracker",))
    monkeypatch.setattr(bridge, "YGG_TRACKERS", ())
    monkeypatch.setattr(bridge, "_dht_enabled", False)
    monkeypatch.setattr(
        bridge,
        "get_tracker_by_name",
        lambda _name: SimpleNamespace(
            discovery_scheme="http-tracker",
            announce_url="https://tracker.invalid/announce",
            protocol="https",
        ),
    )
    monkeypatch.setattr(bridge, "get_discovery_provider", lambda *_args, **_kwargs: FakeTrackerProvider())
    monkeypatch.setattr(bridge, "_discover_public_ipv4_stun", lambda: None)
    monkeypatch.setattr(bridge, "_verify_live_endpoint", fake_verify)

    assert bridge.announce_peer_endpoints(
        "Anne_Marie#2",
        "short-fingerprint",
        '["192.0.2.10"]',
        50001,
        "abcd-2345",
    ) is True
    result = bridge.resolve_peers(
        "Anne_Marie#2",
        "abcd-2345",
        "Test tracker",
        "Anne_Marie#2",
    )

    assert ("announce", "Anne_Marie#2", "abcd-2345") in calls
    assert ("resolve", "Anne_Marie#2", "abcd-2345") in calls
    assert result[0]["verified"] is True


def test_qr_candidates_are_verified_sequentially_in_the_supplied_order(monkeypatch):
    bridge = _load_discovery_bridge()
    attempted = []

    async def fake_verify(endpoint, nickname, expected_fingerprint=None):
        assert nickname == "bob"
        assert expected_fingerprint is None
        attempted.append(endpoint)
        if endpoint == "[200::3]:50001":
            return {
                "nickname": "Bob",
                "fingerprint": "peer-fingerprint",
                "endpoint": endpoint,
                "verified": True,
            }
        return {"endpoint": endpoint, "verified": False}

    monkeypatch.setattr(bridge, "_verify_live_endpoint", fake_verify)

    result = bridge.verify_live_endpoints(
        json.dumps([
            "192.168.1.20:50001",
            "198.51.100.20:50001",
            "[200::3]:50001",
            "[200::4]:50001",
        ]),
        "bob",
    )

    assert attempted == [
        "192.168.1.20:50001",
        "198.51.100.20:50001",
        "[200::3]:50001",
    ]
    assert result[0]["endpoints"] == ["[200::3]:50001"]
    assert result[0]["verification_reason"] == "authenticated direct discovery peer"


def test_public_ipv4_discovery_is_exposed_for_qr(monkeypatch):
    bridge = _load_discovery_bridge()
    monkeypatch.setattr(bridge, "_discover_public_ipv4_stun", lambda: "203.0.113.20")

    assert bridge.discover_public_ipv4() == "203.0.113.20"
    assert json.loads(bridge.get_public_addresses_json()) == ["203.0.113.20"]


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
        "about_me": "",
        "fingerprint": "actual-fingerprint",
        "listen_port": 50001,
    }]


def test_incoming_file_start_rate_is_bounded_per_authenticated_peer(monkeypatch):
    bridge = _load_discovery_bridge()
    monkeypatch.setattr(bridge, "MAX_INCOMING_FILE_STARTS_PER_WINDOW", 2)

    assert bridge._allow_incoming_file_start("peer-a", now=10.0) is True
    assert bridge._allow_incoming_file_start("peer-a", now=11.0) is True
    assert bridge._allow_incoming_file_start("peer-a", now=12.0) is False
    assert bridge._allow_incoming_file_start("peer-b", now=12.0) is True
    assert bridge._allow_incoming_file_start("peer-a", now=71.0) is True


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


def test_account_shutdown_closes_sessions_and_stops_identity_listener(monkeypatch):
    bridge = _load_discovery_bridge()
    listener_started = threading.Event()
    listener_cancelled = threading.Event()
    session_closed = threading.Event()

    async def fake_listener(_port):
        listener_started.set()
        try:
            await asyncio.Event().wait()
        finally:
            listener_cancelled.set()

    class FakeSession:
        async def close(self):
            session_closed.set()

    monkeypatch.setattr(bridge, "_listen_loop_dual", fake_listener)
    monkeypatch.setattr(bridge, "_discard_incoming_file", lambda key: bridge.incoming_files.pop(key, None))
    bridge.active_sessions["old-fingerprint"] = FakeSession()
    bridge.peer_fingerprint_to_name["old-fingerprint"] = "alice"
    bridge.incoming_files["old-transfer"] = object()
    bridge.local_identity_nickname = "alice"
    bridge.local_identity_fingerprint = "old-fingerprint"

    assert bridge.start_p2p_listener(0) is True
    assert listener_started.wait(timeout=1.0)
    assert bridge.shutdown_all_sessions(timeout_seconds=2.0) is True

    assert session_closed.is_set()
    assert listener_cancelled.is_set()
    assert bridge.active_sessions == {}
    assert bridge.peer_fingerprint_to_name == {}
    assert bridge.incoming_files == {}
    assert bridge.local_identity_nickname == ""
    assert bridge.local_identity_fingerprint == ""
    assert bridge.loop is None


def test_listener_can_restart_with_a_new_account_runtime(monkeypatch):
    bridge = _load_discovery_bridge()
    starts = []

    async def fake_listener(port):
        starts.append(port)
        await asyncio.Event().wait()

    monkeypatch.setattr(bridge, "_listen_loop_dual", fake_listener)

    assert bridge.start_p2p_listener(41001) is True
    assert bridge.shutdown_all_sessions(timeout_seconds=2.0) is True
    assert bridge.start_p2p_listener(41002) is True
    assert bridge.shutdown_all_sessions(timeout_seconds=2.0) is True

    assert starts == [41001, 41002]


def test_rejected_same_name_identity_is_closed_before_chat_delivery():
    bridge = _load_discovery_bridge()
    delivered = []

    class FakeWriter:
        def get_extra_info(self, name):
            assert name == "peername"
            return ("192.0.2.20", 50001)

    class FakeSession:
        peer_fingerprint = "new-fingerprint"
        is_online = True
        writer = FakeWriter()

        def __init__(self):
            self.closed = False
            self.messages = iter([
                {
                    "type": "identity_info",
                    "nickname": "alice",
                    "fingerprint": "new-fingerprint",
                    "listen_port": 50001,
                },
                {"type": "chat", "body": "must-not-reach-old-chat"},
            ])

        async def receive_message(self):
            return next(self.messages)

        async def close(self):
            self.closed = True
            self.is_online = False

    class RejectingSessionListener:
        def onSessionEstablished(self, peer_name, *_args):
            return peer_name != "alice"

        def onSessionClosed(self, *_args):
            pass

    class MessageListener:
        def onMessageReceived(self, sender, body):
            delivered.append((sender, body))

    session = FakeSession()
    bridge.active_sessions[session.peer_fingerprint] = session
    bridge.session_listener_callback = RejectingSessionListener()
    bridge.message_listener_callback = MessageListener()

    asyncio.run(bridge._read_loop(session, "Peer (new-fing)", session.peer_fingerprint))

    assert session.closed is True
    assert delivered == []
    assert session.peer_fingerprint not in bridge.active_sessions


def test_tracker_configuration_filters_protocols_presets_and_custom_trackers():
    bridge = _load_discovery_bridge()

    assert bridge.configure_trackers(json.dumps({
        "enabled_protocols": ["https"],
        "disabled_builtin_trackers": ["Nyacat HTTPS"],
        "custom_trackers": [
            {
                "id": "secure-one",
                "name": "Secure custom",
                "url": "https://tracker.example/announce",
                "enabled": True,
            },
            {
                "id": "plain-one",
                "name": "Plain custom",
                "url": "http://tracker.example/announce",
                "enabled": True,
            },
        ],
        "dht_enabled": False,
        "announce_enabled": True,
    })) is True

    assert bridge._resolve_tracker_names("OpenTrackr HTTP") == [
        "Yemekyedim HTTPS",
        "custom:secure-one",
    ]


def test_invalid_tracker_configuration_does_not_replace_active_settings():
    bridge = _load_discovery_bridge()
    assert bridge.configure_trackers(json.dumps({"enabled_protocols": ["udp"]})) is True

    assert bridge.configure_trackers(json.dumps({
        "enabled_protocols": ["https"],
        "custom_trackers": [{
            "id": "bad",
            "name": "Bad tracker",
            "url": "https://user:password@tracker.example/announce",
        }],
    })) is False

    names = bridge._resolve_tracker_names("Yemekyedim HTTPS")
    assert "Torrent.eu.org UDP" in names
    assert "Yemekyedim HTTPS" not in names


def test_disabled_announce_performs_no_network_work(monkeypatch):
    bridge = _load_discovery_bridge()
    assert bridge.configure_trackers(json.dumps({"announce_enabled": False})) is True
    monkeypatch.setattr(
        bridge,
        "get_discovery_provider",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("network access is disabled")),
    )

    assert bridge.announce_peer_endpoints(
        "alice",
        "fingerprint",
        '["192.0.2.10"]',
        50001,
        "shared-code",
    ) is True
