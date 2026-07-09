from unittest.mock import patch, MagicMock
import pytest
from messenger.core.upnp import parse_desc_xml

def test_parse_desc_xml():
    xml_data = b"""<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
        <controlURL>/ctl/IPConn</controlURL>
      </service>
    </serviceList>
  </device>
</root>
"""
    mock_response = MagicMock()
    mock_response.read.return_value = xml_data
    mock_response.__enter__.return_value = mock_response

    with patch("urllib.request.urlopen", return_value=mock_response):
        url, st = parse_desc_xml("http://192.168.1.1:1900/rootDesc.xml")
        assert url == "http://192.168.1.1:1900/ctl/IPConn"
        assert st == "urn:schemas-upnp-org:service:WANIPConnection:1"

def test_parse_desc_xml_relative_with_path():
    xml_data = b"""<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:WANPPPConnection:1</serviceType>
        <controlURL>ctl/PPPConn</controlURL>
      </service>
    </serviceList>
  </device>
</root>
"""
    mock_response = MagicMock()
    mock_response.read.return_value = xml_data
    mock_response.__enter__.return_value = mock_response

    with patch("urllib.request.urlopen", return_value=mock_response):
        url, st = parse_desc_xml("http://192.168.1.1:1900/desc/rootDesc.xml")
        assert url == "http://192.168.1.1:1900/desc/ctl/PPPConn"
        assert st == "urn:schemas-upnp-org:service:WANPPPConnection:1"
