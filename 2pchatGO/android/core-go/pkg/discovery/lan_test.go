package discovery

import (
	"encoding/json"
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

	// Use an ephemeral or custom test port
	engine := NewLANEngine("test-fp-refresh", 50001, 50123, handler)
	if engine == nil {
		t.Fatalf("NewLANEngine returned nil")
	}

	if err := engine.Start(); err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	defer engine.Stop()

	// Call RefreshAnnouncement while running
	if err := engine.RefreshAnnouncement(); err != nil {
		t.Errorf("RefreshAnnouncement failed: %v", err)
	}
}
