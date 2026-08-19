package transport

import (
	"strings"
	"testing"
)

func TestBuildSSDPDiscoverPacket(t *testing.T) {
	pkt := BuildSSDPDiscoverPacket()
	str := string(pkt)

	if !strings.Contains(str, "M-SEARCH * HTTP/1.1") {
		t.Errorf("Missing M-SEARCH line")
	}
	if !strings.Contains(str, "urn:schemas-upnp-org:device:InternetGatewayDevice:1") {
		t.Errorf("Missing IGD URN")
	}
}

func TestBuildAddPortMappingSOAP(t *testing.T) {
	soap := BuildAddPortMappingSOAP("urn:schemas-upnp-org:service:WANIPConnection:1", "192.168.1.100", 50001, "2PChat Relay", 3600)

	if !strings.Contains(soap, "<NewExternalPort>50001</NewExternalPort>") {
		t.Errorf("Missing NewExternalPort in SOAP")
	}
	if !strings.Contains(soap, "<NewInternalClient>192.168.1.100</NewInternalClient>") {
		t.Errorf("Missing NewInternalClient in SOAP")
	}
	if !strings.Contains(soap, "<NewProtocol>TCP</NewProtocol>") {
		t.Errorf("Missing NewProtocol in SOAP")
	}
}

func TestBuildDeletePortMappingSOAP(t *testing.T) {
	soap := BuildDeletePortMappingSOAP("urn:schemas-upnp-org:service:WANIPConnection:1", 50001)

	if !strings.Contains(soap, "<u:DeletePortMapping") {
		t.Errorf("Missing DeletePortMapping element in SOAP")
	}
	if !strings.Contains(soap, "<NewExternalPort>50001</NewExternalPort>") {
		t.Errorf("Missing NewExternalPort in SOAP")
	}
}

func TestBuildNATPMPPacket(t *testing.T) {
	pkt := BuildNATPMPPacket(50001, 50001, 3600)
	if len(pkt) != 12 {
		t.Fatalf("Expected 12 bytes, got %d", len(pkt))
	}
	if pkt[0] != 0 || pkt[1] != 2 {
		t.Errorf("Invalid version/opcode: %d/%d", pkt[0], pkt[1])
	}
}

func TestUPnPTorBlockedGuard(t *testing.T) {
	mapper := NewUPnPMapper(true)
	err := mapper.DiscoverAndMapPort(nil, 50001)
	if err != ErrUPnPTorBlocked {
		t.Errorf("Expected ErrUPnPTorBlocked when Tor enabled, got %v", err)
	}
}
