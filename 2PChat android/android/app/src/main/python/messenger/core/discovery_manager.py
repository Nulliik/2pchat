from typing import Callable, Dict

from .discovery_base import DiscoveryProvider
from .discovery_mainline_dht import MainlineDHTDiscovery
from .discovery_tracker_http import HttpTrackerDiscovery
from .discovery_tracker_udp import UdpTrackerDiscovery

DISCOVERY_FACTORIES: Dict[str, Callable[..., DiscoveryProvider]] = {
    "mainline-dht": lambda **kwargs: MainlineDHTDiscovery(**kwargs),
    "http-tracker": lambda **kwargs: HttpTrackerDiscovery(**kwargs),
    "udp-tracker": lambda **kwargs: UdpTrackerDiscovery(**kwargs),
}


def get_discovery_provider(scheme: str, **options) -> DiscoveryProvider:
    if scheme not in DISCOVERY_FACTORIES:
        raise ValueError(f"Unknown discovery scheme: {scheme}")
    return DISCOVERY_FACTORIES[scheme](**options)
