import pytest

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

        def read(self, _limit=-1):
            return xml

    monkeypatch.setattr("messenger.core.upnp._open_upnp_url", lambda req, timeout=3.0: _Response())
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

        def read(self, _limit=-1):
            return xml

    monkeypatch.setattr("messenger.core.upnp._open_upnp_url", lambda req, timeout=3.0: _Response())
    control_url, st = parse_desc_xml("http://192.168.1.1:1234/device/rootDesc.xml")
    assert control_url == "http://192.168.1.1:1234/device/control/wanppp"
    assert st == "urn:schemas-upnp-org:service:WANPPPConnection:1"


@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1/admin",
        "http://169.254.169.254/latest/meta-data",
        "http://8.8.8.8/root.xml",
        "http://router.example/root.xml",
        "file:///etc/passwd",
    ],
)
def test_parse_desc_xml_rejects_non_lan_ssrf_targets(monkeypatch, url):
    called = False

    def fail_if_called(*_args, **_kwargs):
        nonlocal called
        called = True
        raise AssertionError("network request must not be attempted")

    monkeypatch.setattr("messenger.core.upnp._open_upnp_url", fail_if_called)
    assert parse_desc_xml(url) == (None, None)
    assert called is False


def test_parse_desc_xml_pins_location_to_ssdp_responder(monkeypatch):
    monkeypatch.setattr(
        "messenger.core.upnp._open_upnp_url",
        lambda *_args, **_kwargs: pytest.fail("network request must not be attempted"),
    )
    assert parse_desc_xml(
        "http://192.168.1.2/root.xml", responder_ip="192.168.1.1"
    ) == (None, None)
