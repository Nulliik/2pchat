package discovery

import (
	"context"
	"errors"
	"testing"
	"time"
	"twopchat/core/pkg/transport"
)

func TestSTUN_UPnP_Tracker_DisabledInStrict(t *testing.T) {
	dialer := transport.NewAdaptiveDialer("127.0.0.1:9050", true, 2*time.Second)
	dialer.SetPolicy(transport.PolicyTorStrict)

	var discoveredPeers []string
	svc := NewDiscoveryService(
		"test-fp",
		50001,
		dialer,
		true,
		func(infoHashHex, endpoint, source string) {
			discoveredPeers = append(discoveredPeers, endpoint)
		},
		nil,
	)
	defer svc.Stop()

	// Initial policy should be PolicyTorStrict from dialer
	if svc.GetPolicy() != transport.PolicyTorStrict {
		t.Fatalf("Expected DiscoveryService to inherit PolicyTorStrict, got %v", svc.GetPolicy())
	}

	// 1. Verify LAN engine does not start under PolicyTorStrict
	if err := svc.Start(); err != nil {
		t.Fatalf("svc.Start failed: %v", err)
	}

	// Under strict policy, LANEngine.Start() is a no-op (running remains 0)
	if svc.lanEngine.IsRunning() {
		t.Fatal("LANEngine should not be running under PolicyTorStrict (Б2 violation)")
	}

	// 2. Verify tracker announce is rejected under PolicyTorStrict for clearnet trackers
	var dummyHash [20]byte
	copy(dummyHash[:], []byte("12345678901234567890"))

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	// Clearnet UDP tracker must be denied
	_, err := svc.announceSingle(ctx, "udp://tracker.openbittorrent.com:6969/announce", dummyHash, [20]byte{}, 50001)
	if !errors.Is(err, transport.ErrPolicyDenied) {
		t.Fatalf("Expected ErrPolicyDenied for clearnet UDP tracker under Tor Strict, got %v", err)
	}

	// Clearnet HTTP tracker must be denied
	_, err = svc.announceSingle(ctx, "http://tracker.example.com/announce", dummyHash, [20]byte{}, 50001)
	if !errors.Is(err, transport.ErrPolicyDenied) {
		t.Fatalf("Expected ErrPolicyDenied for clearnet HTTP tracker under Tor Strict, got %v", err)
	}

	// Onion tracker should be allowed under PolicyTorStrict
	onionTracker := "http://expyuzz5wqqfdgah56trldbdymgah72ire75vp45w532nlr5lauma7ad.onion/announce"
	if !svc.isTrackerAllowed(onionTracker) {
		t.Fatalf("Expected onion tracker to be allowed under PolicyTorStrict")
	}

	// 3. Test runtime policy transition from Speed to Strict
	svc.ApplyPolicy(transport.PolicySpeed)
	if !svc.lanEngine.IsRunning() {
		t.Fatal("LANEngine should be running after switching to PolicySpeed")
	}

	svc.ApplyPolicy(transport.PolicyTorStrict)
	if svc.lanEngine.IsRunning() {
		t.Fatal("LANEngine should be stopped after switching to PolicyTorStrict (Б2 violation)")
	}
}
