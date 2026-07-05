import json

import pytest
from nacl.public import PrivateKey
from nacl.signing import SigningKey

from messenger.core.discovery_base import PeerEndpoint
from messenger.core.discovery_mainline_dht import (
    InMemoryMutableRecordBackend,
    MainlineDHTDiscovery,
)
from messenger.core.discovery_manager import get_discovery_provider
from messenger.core.identity import fingerprint


class Clock:
    def __init__(self, now: int = 1_700_000_000) -> None:
        self.now = now

    def __call__(self) -> int:
        return self.now


@pytest.mark.asyncio
async def test_mainline_discovery_announce_and_resolve_roundtrip():
    backend = InMemoryMutableRecordBackend()
    clock = Clock()
    identity_priv = PrivateKey.generate()
    signing_key = SigningKey.generate()
    provider = MainlineDHTDiscovery(
        backend=backend,
        identity_priv=identity_priv,
        signing_key=signing_key,
        time_fn=clock,
        ttl_seconds=300,
    )

    announced = await provider.announce(
        "Alice",
        "c7m4q9",
        transport="direct",
        endpoints=[PeerEndpoint(host="198.51.100.10", port=4444)],
    )
    resolved = await provider.resolve("  alice  ", "c7m4q9")

    assert announced.nickname == "alice"
    assert len(resolved) == 1
    assert resolved[0] == announced
    assert resolved[0].identity_fingerprint == fingerprint(identity_priv.public_key)
    assert resolved[0].endpoints == (PeerEndpoint(host="198.51.100.10", port=4444),)


@pytest.mark.asyncio
async def test_mainline_discovery_filters_by_expected_fingerprint():
    backend = InMemoryMutableRecordBackend()
    clock = Clock()
    alice = MainlineDHTDiscovery(
        backend=backend,
        identity_priv=PrivateKey.generate(),
        signing_key=SigningKey.generate(),
        time_fn=clock,
    )
    bob_priv = PrivateKey.generate()
    bob = MainlineDHTDiscovery(
        backend=backend,
        identity_priv=bob_priv,
        signing_key=SigningKey.generate(),
        time_fn=clock,
    )

    await alice.announce(
        "alice",
        "shared-code",
        transport="direct",
        endpoints=[PeerEndpoint(host="203.0.113.10", port=5555)],
    )
    await bob.announce(
        "alice",
        "shared-code",
        transport="direct",
        endpoints=[PeerEndpoint(host="203.0.113.11", port=6666)],
    )

    resolved = await alice.resolve(
        "alice",
        "shared-code",
        expected_fingerprint=fingerprint(bob_priv.public_key),
    )

    assert len(resolved) == 1
    assert resolved[0].identity_fingerprint == fingerprint(bob_priv.public_key)
    assert resolved[0].endpoints[0].host == "203.0.113.11"


@pytest.mark.asyncio
async def test_mainline_discovery_skips_expired_and_invalid_records():
    backend = InMemoryMutableRecordBackend()
    clock = Clock()
    provider = MainlineDHTDiscovery(
        backend=backend,
        identity_priv=PrivateKey.generate(),
        signing_key=SigningKey.generate(),
        time_fn=clock,
        ttl_seconds=60,
    )

    descriptor = await provider.announce(
        "alice",
        "secret",
        transport="direct",
        endpoints=[PeerEndpoint(host="127.0.0.1", port=4444)],
    )
    namespace = provider.derive_lookup_namespace("alice", "secret")

    payload = {
        "v": descriptor.version,
        "nickname": descriptor.nickname,
        "identity_fingerprint": descriptor.identity_fingerprint,
        "signing_public_key": descriptor.signing_public_key,
        "transport": descriptor.transport,
        "endpoints": [{"host": "evil.example", "port": 1}],
        "expires_at": descriptor.expires_at,
        "sequence": descriptor.sequence + 10,
        "nonce": descriptor.nonce,
        "sig": descriptor.signature,
    }
    await backend.put_record(
        namespace,
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8"),
        sequence=payload["sequence"],
        expires_at=payload["expires_at"],
    )

    resolved = await provider.resolve("alice", "secret")
    assert len(resolved) == 1
    assert resolved[0].endpoints == (PeerEndpoint(host="127.0.0.1", port=4444),)

    clock.now = descriptor.expires_at + 1
    assert await provider.resolve("alice", "secret") == []


@pytest.mark.asyncio
async def test_mainline_discovery_keeps_newest_record_per_identity():
    backend = InMemoryMutableRecordBackend()
    clock = Clock()
    identity_priv = PrivateKey.generate()
    signing_key = SigningKey.generate()
    provider = MainlineDHTDiscovery(
        backend=backend,
        identity_priv=identity_priv,
        signing_key=signing_key,
        time_fn=clock,
        ttl_seconds=300,
    )

    await provider.announce(
        "alice",
        "secret",
        transport="direct",
        endpoints=[PeerEndpoint(host="203.0.113.10", port=4444)],
    )
    clock.now += 30
    await provider.announce(
        "alice",
        "secret",
        transport="direct",
        endpoints=[PeerEndpoint(host="203.0.113.10", port=5555)],
    )

    resolved = await provider.resolve("alice", "secret")

    assert len(resolved) == 1
    assert resolved[0].endpoints == (PeerEndpoint(host="203.0.113.10", port=5555),)


def test_mainline_discovery_manager_instances_are_new():
    first = get_discovery_provider("mainline-dht")
    second = get_discovery_provider("mainline-dht")

    assert isinstance(first, MainlineDHTDiscovery)
    assert isinstance(second, MainlineDHTDiscovery)
    assert first is not second


def test_mainline_discovery_manager_unknown_scheme():
    with pytest.raises(ValueError):
        get_discovery_provider("missing")


def test_mainline_discovery_normalization_rejects_empty_values():
    with pytest.raises(ValueError):
        MainlineDHTDiscovery.normalize_nickname("   ")

    with pytest.raises(ValueError):
        MainlineDHTDiscovery.derive_lookup_namespace("alice", "   ")
