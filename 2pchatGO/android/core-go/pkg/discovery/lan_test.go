package discovery

import (
	"encoding/json"
	"net"
	"testing"
	"time"
)

func TestLANBeaconSerialization(t *testing.T) {
	beacon := LANBeacon{
		Service:     LANServiceName,
		Fingerprint: "test-fingerprint-base64==",
		Port:        50001,
		Timestamp:   time.Now().Unix(),
	}

	data, err := json.Marshal(beacon)
	if err != nil {
		t.Fatalf("Failed to marshal LANBeacon: %v", err)
	}

	var parsed LANBeacon
	if err := json.Unmarshal(data, &parsed); err != nil {
		t.Fatalf("Failed to unmarshal LANBeacon: %v", err)
	}

	if parsed.Service != LANServiceName {
		t.Errorf("Expected service %s, got %s", LANServiceName, parsed.Service)
	}
	if parsed.Fingerprint != beacon.Fingerprint {
		t.Errorf("Expected fingerprint %s, got %s", beacon.Fingerprint, parsed.Fingerprint)
	}
	if parsed.Port != 50001 {
		t.Errorf("Expected port 50001, got %d", parsed.Port)
	}
	if parsed.Timestamp != beacon.Timestamp {
		t.Errorf("Expected timestamp %d, got %d", beacon.Timestamp, parsed.Timestamp)
	}
}

func TestLANEngineLifecycle(t *testing.T) {
	var discoveredFP, discoveredEndpoint string
	handler := func(fp, ep string) {
		discoveredFP = fp
		discoveredEndpoint = ep
	}

	engine := NewLANEngine("local-fp-12345", 50001, 0, handler)
	if engine == nil {
		t.Fatalf("NewLANEngine returned nil")
	}

	// Stop without start should be graceful
	if err := engine.Stop(); err != nil {
		t.Errorf("Stop without start failed: %v", err)
	}

	_ = discoveredFP
	_ = discoveredEndpoint
}

func TestLANEngineRefreshAnnouncement(t *testing.T) {
	handler := func(fp, ep string) {}

	// Use dynamic port 0 to prevent port collisions on CI/Windows/macOS
	engine := NewLANEngine("test-fp-refresh", 50001, 0, handler)
	if engine == nil {
		t.Fatalf("NewLANEngine returned nil")
	}

	if err := engine.Start(); err != nil {
		t.Fatalf("Start failed on dynamic port: %v", err)
	}
	t.Cleanup(func() { _ = engine.Stop() })

	if engine.Port() <= 0 {
		t.Errorf("Expected positive bound port, got %d", engine.Port())
	}

	// Call RefreshAnnouncement while running
	if err := engine.RefreshAnnouncement(); err != nil {
		t.Errorf("RefreshAnnouncement failed: %v", err)
	}
}

func TestLANEngineDirectBeaconDispatch(t *testing.T) {
	discovered := make(chan string, 1)
	handler := func(fp, ep string) {
		discovered <- fp
	}

	engine := NewLANEngine("receiver-fp", 50001, 0, handler)
	if err := engine.Start(); err != nil {
		t.Fatalf("Engine start failed: %v", err)
	}
	t.Cleanup(func() { _ = engine.Stop() })

	port := engine.Port()
	if port <= 0 {
		t.Fatalf("Invalid bound port: %d", port)
	}

	// Send a simulated unicast beacon to the bound UDP port
	targetAddr := &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: port}
	senderConn, err := net.DialUDP("udp4", nil, targetAddr)
	if err != nil {
		t.Fatalf("DialUDP failed: %v", err)
	}
	defer senderConn.Close()

	beacon := LANBeacon{
		Service:     LANServiceName,
		Fingerprint: "sender-peer-fp-xyz",
		Port:        50002,
		Timestamp:   time.Now().Unix(),
	}
	raw, _ := json.Marshal(beacon)
	_, _ = senderConn.Write(raw)

	select {
	case fp := <-discovered:
		if fp != "sender-peer-fp-xyz" {
			t.Errorf("Expected sender-peer-fp-xyz, got %s", fp)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("Timeout waiting for LAN beacon reception")
	}
}
