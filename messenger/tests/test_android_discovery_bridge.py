import importlib.util
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
