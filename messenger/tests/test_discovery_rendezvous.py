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


def test_rendezvous_key_rejects_empty_values():
    for nickname, shared_code in [("", "secret"), ("alice", "   ")]:
        try:
            derive_rendezvous_key(nickname, shared_code)
        except ValueError:
            pass
        else:
            raise AssertionError("Expected an empty rendezvous component to be rejected")
