from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TrackerSpec:
    name: str
    announce_url: str
    discovery_scheme: str
    protocol: str
    notes: str = ""


BASE_TRACKERS: tuple[TrackerSpec, ...] = (
    TrackerSpec(
        name="Torrent.eu.org UDP",
        announce_url="udp://tracker.torrent.eu.org:451/announce",
        discovery_scheme="udp-tracker",
        protocol="udp",
        notes="validated 2026-07-03",
    ),
    TrackerSpec(
        name="Open Stealth UDP",
        announce_url="udp://open.stealth.si:80/announce",
        discovery_scheme="udp-tracker",
        protocol="udp",
        notes="validated 2026-07-03",
    ),
    TrackerSpec(
        name="Exodus UDP",
        announce_url="udp://exodus.desync.com:6969/announce",
        discovery_scheme="udp-tracker",
        protocol="udp",
        notes="validated 2026-07-03",
    ),
    TrackerSpec(
        name="OpenTrackr UDP",
        announce_url="udp://tracker.opentrackr.org:1337/announce",
        discovery_scheme="udp-tracker",
        protocol="udp",
        notes="high capacity UDP tracker",
    ),
    TrackerSpec(
        name="Dler UDP",
        announce_url="udp://tracker2.dler.org:80/announce",
        discovery_scheme="udp-tracker",
        protocol="udp",
        notes="public UDP tracker",
    ),

    TrackerSpec(
        name="BitSearch UDP",
        announce_url="udp://tracker.bitsearch.to:6969/announce",
        discovery_scheme="udp-tracker",
        protocol="udp",
        notes="public UDP tracker",
    ),
    TrackerSpec(
        name="OpenTrackr HTTP",
        announce_url="http://tracker.opentrackr.org:1337/announce",
        discovery_scheme="http-tracker",
        protocol="http",
        notes="unencrypted; use only when enabled by the user",
    ),
    TrackerSpec(
        name="Dler HTTP",
        announce_url="http://tracker2.dler.org:80/announce",
        discovery_scheme="http-tracker",
        protocol="http",
        notes="unencrypted; use only when enabled by the user",
    ),
    TrackerSpec(
        name="Qu.Ax HTTP",
        announce_url="http://tracker.qu.ax:6969/announce",
        discovery_scheme="http-tracker",
        protocol="http",
        notes="unencrypted; use only when enabled by the user",
    ),
    TrackerSpec(
        name="OpenTrackr HTTPS",
        announce_url="https://tracker.opentrackr.org:443/announce",
        discovery_scheme="http-tracker",
        protocol="https",
        notes="encrypted HTTPS tracker",
    ),
    TrackerSpec(
        name="Yemekyedim HTTPS",
        announce_url="https://tracker.yemekyedim.com:443/announce",
        discovery_scheme="http-tracker",
        protocol="https",
        notes="validated 2026-07-03",
    ),
    TrackerSpec(
        name="Nyacat HTTPS",
        announce_url="https://tr.nyacat.pw:443/announce",
        discovery_scheme="http-tracker",
        protocol="https",
        notes="validated 2026-07-03",
    ),
    TrackerSpec(
        name="Yggdrasil-only HTTP",
        announce_url="http://[200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7]/announce",
        discovery_scheme="http-tracker",
        protocol="http",
        notes="unencrypted Yggdrasil-only tracker; user opt-in",
    ),
    TrackerSpec(
        name="Yggdrasil-only UDP",
        announce_url="udp://[202:68d0:f0d5:b88d:1d1a:555e:2f6b:3148]:6969/announce",
        discovery_scheme="udp-tracker",
        protocol="udp",
        notes="public Yggdrasil-only tracker from services list, verified 2026-07-09",
    ),
)


def tracker_names() -> tuple[str, ...]:
    return tuple(spec.name for spec in BASE_TRACKERS)


def get_tracker_by_name(name: str) -> TrackerSpec:
    for spec in BASE_TRACKERS:
        if spec.name == name:
            return spec
    raise ValueError(f"Unknown tracker preset: {name}")
