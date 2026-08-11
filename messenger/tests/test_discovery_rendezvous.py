from messenger.core.discovery_mainline_dht import MainlineDHTDiscovery
from messenger.core.discovery_rendezvous import RENDEZVOUS_CONTEXT, derive_rendezvous_key
from messenger.core.discovery_tracker_http import HttpTrackerDiscovery
from messenger.core.discovery_tracker_udp import UdpTrackerDiscovery


def test_all_discovery_providers_use_the_same_v1_rendezvous_key():
    expected = derive_rendezvous_key("Alice", "shared-secret")

    assert RENDEZVOUS_CONTEXT == b"2pchat-rendezvous-v1"
    assert len(expected) == 20
    assert UdpTrackerDiscovery.derive_info_hash(" alice ", "shared-secret") == expected
    assert HttpTrackerDiscovery.derive_info_hash("ALICE", "shared-secret") == expected
    assert MainlineDHTDiscovery.derive_lookup_namespace("Alice", "shared-secret") == expected


def test_rendezvous_key_supports_optional_epoch_bucket():
    base_key = derive_rendezvous_key("Alice", "shared-secret")
    epoch_key_1 = derive_rendezvous_key("Alice", "shared-secret", epoch_bucket=20500)
    epoch_key_2 = derive_rendezvous_key("Alice", "shared-secret", epoch_bucket=20501)

    assert len(epoch_key_1) == 20
    assert len(epoch_key_2) == 20
    assert epoch_key_1 != base_key
    assert epoch_key_1 != epoch_key_2

