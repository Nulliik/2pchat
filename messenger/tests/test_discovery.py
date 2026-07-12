import pytest

from messenger.core.discovery_base import PeerEndpoint
from messenger.core.discovery_mainline_dht import InMemoryBep5Backend, MainlineDHTDiscovery
from messenger.core.discovery_manager import get_discovery_provider


@pytest.mark.asyncio
async def test_mainline_bep5_announce_and_resolve_roundtrip():
    backend = InMemoryBep5Backend()
    provider = MainlineDHTDiscovery(backend=backend, peer_port=4444)

    announced = await provider.announce(
        "Alice",
        "c7m4q9",
        transport="direct",
        endpoints=[PeerEndpoint(host="192.0.2.10", port=4444)],
    )
    resolved = await provider.resolve("  alice  ", "c7m4q9")

    assert announced.nickname == "alice"
    assert resolved[0].endpoints == (PeerEndpoint(host="127.0.0.1", port=4444),)
    assert resolved[0].identity_fingerprint is None


@pytest.mark.asyncio
async def test_mainline_bep5_namespaces_do_not_collide():
    backend = InMemoryBep5Backend()
    alice = MainlineDHTDiscovery(backend=backend, peer_port=4444)
    bob = MainlineDHTDiscovery(backend=backend, peer_port=5555)

    await alice.announce(
        "alice",
        "first-code",
        transport="direct",
        endpoints=[PeerEndpoint("192.0.2.1", 4444)],
    )
    await bob.announce(
        "alice",
        "second-code",
        transport="direct",
        endpoints=[PeerEndpoint("192.0.2.2", 5555)],
    )

    first = await alice.resolve("alice", "first-code")
    second = await alice.resolve("alice", "second-code")
    assert first[0].endpoints[0].port == 4444
    assert second[0].endpoints[0].port == 5555


@pytest.mark.asyncio
async def test_mainline_bep5_deduplicates_announces():
    backend = InMemoryBep5Backend()
    provider = MainlineDHTDiscovery(backend=backend, peer_port=4444)
    endpoint = PeerEndpoint("192.0.2.1", 4444)

    await provider.announce("alice", "code", transport="direct", endpoints=[endpoint])
    await provider.announce("alice", "code", transport="direct", endpoints=[endpoint])

    assert len(await provider.resolve("alice", "code")) == 1


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
