"""Application compatibility over authenticated identity_info (wire v3 unchanged)."""

import json
import re
from dataclasses import dataclass

CURRENT_VERSION = 1
MIN_SUPPORTED_VERSION = 1
BASELINE = frozenset({"pairwise_x3dh_v1", "pairwise_double_ratchet_v1"})
KNOWN = BASELINE | {
    "group_suite_v1", "group_suite_v2", "group_tombstones_v1", "group_succession_v1"
}
_NAME = re.compile(r"[a-z][a-z0-9_]*_v[1-9][0-9]*", re.ASCII)


def local_declaration():
    # The desktop client does not implement the Android group runtime.
    return {
        "protocol_version": CURRENT_VERSION,
        "min_supported_version": MIN_SUPPORTED_VERSION,
        "capabilities": sorted(BASELINE),
    }


def unique_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate identity_info field")
        result[key] = value
    return result


def validate(declaration):
    if not isinstance(declaration, dict):
        raise ValueError("expected capability object")
    version = declaration.get("protocol_version")
    minimum = declaration.get("min_supported_version")
    caps = declaration.get("capabilities")
    if (type(version) is not int or type(minimum) is not int
            or not 1 <= minimum <= version <= 65535
            or not isinstance(caps, list) or len(caps) > 64):
        raise ValueError("invalid capability declaration bounds")
    if any(not isinstance(c, str) or len(c) > 64 or not _NAME.fullmatch(c) for c in caps):
        raise ValueError("invalid capability name")
    if len(set(caps)) != len(caps):
        raise ValueError("duplicate capability")
    if len(json.dumps(declaration, separators=(",", ":")).encode()) > 8192:
        raise ValueError("capability declaration too large")


def canonical(declaration):
    validate(declaration)
    return json.dumps({
        "protocol_version": declaration["protocol_version"],
        "min_supported_version": declaration["min_supported_version"],
        "capabilities": sorted(declaration["capabilities"]),
    }, separators=(",", ":"))


@dataclass(frozen=True)
class NegotiatedSession:
    protocol_version: int
    active_capabilities: frozenset
    peer_is_outdated: bool = False
    peer_is_legacy: bool = False

    def supports(self, capability):
        return capability in self.active_capabilities


def negotiate(local, remote):
    validate(local)
    validate(remote)
    version = min(local["protocol_version"], remote["protocol_version"])
    if version < max(local["min_supported_version"], remote["min_supported_version"]):
        raise ValueError("incompatible application protocol")
    active = frozenset(local["capabilities"]) & frozenset(remote["capabilities"]) & KNOWN
    if not BASELINE <= active:
        raise ValueError("incompatible application protocol: missing baseline")
    outdated = remote["protocol_version"] < local["protocol_version"]
    return NegotiatedSession(version, active, outdated)


def legacy():
    return NegotiatedSession(0, BASELINE, peer_is_legacy=True)
