import argparse
import asyncio

import pytest

from messenger.app import cli_chat
from messenger.core.discovery_base import PeerDescriptor, PeerEndpoint


class DummySession:
    peer_fingerprint = "peer-fp"
    trust_status = "new"
    trust_warning = None

    async def close(self):
        return None


class FakeDiscoveryProvider:
    def __init__(self, descriptors):
        self.descriptors = descriptors
        self.announces = []
        self.resolves = []
        self.withdraws = []

    async def announce(self, nickname, shared_code, *, transport, endpoints):
        self.announces.append((nickname, shared_code, transport, endpoints))
        return self.descriptors[0]

    async def resolve(self, nickname, shared_code, *, expected_fingerprint=None):
        self.resolves.append((nickname, shared_code, expected_fingerprint))
        return list(self.descriptors)

    async def withdraw(self, nickname, shared_code):
        self.withdraws.append((nickname, shared_code))
        return None


def test_cli_parser_accepts_discovery_flags():
    parser = cli_chat.build_parser()
    args = parser.parse_args(
        [
            "--discover-nickname",
            "alice",
            "--discover-key",
            "secret",
            "--tracker-preset",
            "Open Stealth UDP",
            "--port",
            "4444",
        ]
    )

    assert args.discover_nickname == "alice"
    assert args.discover_key == "secret"
    assert args.tracker_preset == "Open Stealth UDP"


def test_cli_parser_accepts_generate_discovery_command():
    parser = cli_chat.build_parser()
    args = parser.parse_args(
        [
            "--command",
            "generate-discovery",
            "--discovery-seed",
            "Alice Cooper",
            "--port",
            "4444",
        ]
    )

    assert args.command == "generate-discovery"
    assert args.discovery_seed == "Alice Cooper"


def test_cli_parser_accepts_discovery_listen_flag():
    parser = cli_chat.build_parser()
    args = parser.parse_args(
        [
            "--discover-nickname",
            "alice",
            "--discover-key",
            "secret",
            "--discover-listen",
            "--port",
            "4444",
        ]
    )

    assert args.discover_listen is True


def test_request_shutdown_sets_event_and_cancels_task():
    loop = asyncio.new_event_loop()
    try:
        asyncio.set_event_loop(loop)
        stop_event = asyncio.Event()
        task = loop.create_future()
        cli_chat._request_shutdown(stop_event, task)
        assert stop_event.is_set()
        assert task.cancelled()
    finally:
        loop.close()
        asyncio.set_event_loop(None)


@pytest.mark.asyncio
async def test_establish_session_uses_discovery_provider(monkeypatch):
    args = argparse.Namespace(
        discover_nickname="alice",
        discover_key="secret",
        discovery_scheme="udp-tracker",
        tracker_preset="Open Stealth UDP",
        tracker_url="udp://tracker.example:80/announce",
        discover_bind="0.0.0.0",
        connect=None,
        rendezvous=None,
        listen=None,
        transport="direct",
        port=4444,
        expect_fingerprint=None,
        ack_timeout=5.0,
        max_retries=3,
        ack_backoff=1.5,
        peer_label="alice",
        discover_listen=False,
    )
    descriptor = PeerDescriptor(
        version=1,
        nickname="alice",
        identity_fingerprint=None,
        signing_public_key=None,
        transport="direct",
        endpoints=(PeerEndpoint(host="198.51.100.10", port=4444),),
        expires_at=9999999999,
        sequence=1,
        nonce="x",
        signature=None,
    )
    provider = FakeDiscoveryProvider([descriptor])
    connect_calls = []

    def _fake_get_discovery_provider(_scheme, **_options):
        return provider

    async def _fake_transport_connect(_scheme, host, port, **_options):
        connect_calls.append((host, port))
        return object(), object()

    async def _fake_session_create(*_args, **_kwargs):
        return DummySession()

    monkeypatch.setattr(cli_chat, "get_discovery_provider", _fake_get_discovery_provider)
    monkeypatch.setattr(cli_chat, "transport_connect", _fake_transport_connect)
    monkeypatch.setattr(cli_chat.Session, "create", _fake_session_create)

    session, listener, provider_used = await cli_chat._establish_session(
        args,
        identity_priv=object(),
        trust_store=object(),
        transport_options={},
    )

    assert session.peer_fingerprint == "peer-fp"
    assert listener is None
    assert provider_used is provider
    assert provider.announces
    assert provider.resolves
    assert connect_calls == [("198.51.100.10", 4444)]


@pytest.mark.asyncio
async def test_establish_session_uses_discovery_listen_mode(monkeypatch):
    args = argparse.Namespace(
        discover_nickname="alice",
        discover_key="secret",
        discovery_scheme="udp-tracker",
        tracker_preset="Open Stealth UDP",
        tracker_url="udp://tracker.example:80/announce",
        discover_bind="0.0.0.0",
        discover_listen=True,
        connect=None,
        rendezvous=None,
        listen=None,
        transport="direct",
        port=4444,
        expect_fingerprint=None,
        ack_timeout=5.0,
        max_retries=3,
        ack_backoff=1.5,
        peer_label="alice",
    )
    descriptor = PeerDescriptor(
        version=1,
        nickname="alice",
        identity_fingerprint=None,
        signing_public_key=None,
        transport="direct",
        endpoints=(PeerEndpoint(host="198.51.100.10", port=4444),),
        expires_at=9999999999,
        sequence=1,
        nonce="x",
        signature=None,
    )
    provider = FakeDiscoveryProvider([descriptor])

    async def _fake_session_create(*_args, **_kwargs):
        return DummySession()

    async def _fake_listener(_scheme, host, port, **_options):
        assert host == "0.0.0.0"
        assert port == 4444
        yield object(), object()

    def _fake_get_discovery_provider(_scheme, **_options):
        return provider

    monkeypatch.setattr(cli_chat, "get_discovery_provider", _fake_get_discovery_provider)
    monkeypatch.setattr(cli_chat, "transport_listen", _fake_listener)
    monkeypatch.setattr(cli_chat.Session, "create", _fake_session_create)

    session, listener, used_provider = await cli_chat._establish_session(
        args,
        identity_priv=object(),
        trust_store=object(),
        transport_options={},
    )

    assert session.peer_fingerprint == "peer-fp"
    assert listener is not None
    assert used_provider is provider
    assert provider.announces == []
    assert provider.resolves == []
