import messenger.discovery_bridge as discovery_bridge


def test_exported_discovery_bridge_callable_smoketest():
    """
    Directly execute parameter-free and query functions to verify they don't crash or raise unexpected exceptions.
    """
    # Test diagnostics functions return valid JSON strings
    diag_json = discovery_bridge.get_tracker_diagnostics_json()
    assert isinstance(diag_json, str)

    upnp_json = discovery_bridge.get_upnp_details_json()
    assert isinstance(upnp_json, str)

    active_peers = discovery_bridge.get_active_peers_list()
    assert isinstance(active_peers, str)

    public_addrs = discovery_bridge.get_public_addresses_json()
    assert isinstance(public_addrs, str)

    # Test reset functions execute safely
    discovery_bridge.reset_stale_endpoint_cooldowns()
    try:
        discovery_bridge.set_ipv4_enabled(False)
    finally:
        discovery_bridge.set_ipv4_enabled(True)
