from messenger.core.upnp import parse_desc_xml, get_upnp_status


def test_upnp_status_snapshot_is_available_and_isolated():
    first = get_upnp_status()
    assert first["mapped"] is False
    assert "state" in first
    first["mapped"] = True
    assert get_upnp_status()["mapped"] is False


def test_parse_desc_xml_resolves_absolute_wanip_url(monkeypatch):
    xml = b"""
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
        <controlURL>http://192.168.1.1:1234/upnp/control</controlURL>
      </service>
    </serviceList>
  </device>
</root>
"""

    class _Response:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def read(self):
            return xml

    monkeypatch.setattr("urllib.request.urlopen", lambda req, timeout=3.0: _Response())
    control_url, st = parse_desc_xml("http://192.168.1.1:1234/rootDesc.xml")
    assert control_url == "http://192.168.1.1:1234/upnp/control"
    assert st == "urn:schemas-upnp-org:service:WANIPConnection:1"


def test_parse_desc_xml_resolves_relative_wanppp_url(monkeypatch):
    xml = b"""
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>
        <controlURL>control/wanppp</controlURL>
      </service>
    </serviceList>
  </device>
</root>
"""

    class _Response:
        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def read(self):
            return xml

    monkeypatch.setattr("urllib.request.urlopen", lambda req, timeout=3.0: _Response())
    control_url, st = parse_desc_xml("http://192.168.1.1:1234/device/rootDesc.xml")
    assert control_url == "http://192.168.1.1:1234/device/control/wanppp"
    assert st == "urn:schemas-upnp-org:service:WANPPPConnection:1"
