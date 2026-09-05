package discovery

import (
	"context"
	"testing"
	"time"
)

// TestTracker_NoUDPAnnounceWhenTorEnabled verifies SEC-05:
// When Tor is enabled, raw UDP BitTorrent tracker announces MUST be refused
// to prevent clearnet IP leaks (since standard Tor cannot route raw UDP).
func TestTracker_NoUDPAnnounceWhenTorEnabled(t *testing.T) {
	client := NewUDPTrackerClient(true /* torEnabled */, 2*time.Second)

	var infoHash [20]byte
	var peerID [20]byte
	copy(infoHash[:], "12345678901234567890")
	copy(peerID[:], "-2P0001-123456789012")

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	res, err := client.Announce(ctx, "udp://tracker.opentrackr.org:1337/announce", infoHash, peerID, 50001)
	if err == nil {
		t.Fatal("Expected error when announcing via UDP under Tor, but got nil")
	}
	if err != ErrUDPDisabledUnderTor {
		t.Fatalf("Expected ErrUDPDisabledUnderTor, got %v", err)
	}
	if res != nil {
		t.Fatal("Expected nil AnnounceResult when UDP is disabled under Tor")
	}
}

// TestDiscoveryService_SuppressesClearnetUDPUnderTor verifies that the top-level DiscoveryService
// fails closed with ErrUDPDisabledUnderTor for clearnet UDP trackers when Tor is active.
func TestDiscoveryService_SuppressesClearnetUDPUnderTor(t *testing.T) {
	service := NewDiscoveryService(
		"test_fingerprint",
		50001,
		nil,
		true, // torEnabled = true
		nil,
		nil,
	)

	var infoHash [20]byte
	var peerID [20]byte
	copy(infoHash[:], "12345678901234567890")
	copy(peerID[:], "-2P0001-123456789012")

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, err := service.announceSingle(ctx, "udp://tracker.openbittorrent.com:6969/announce", infoHash, peerID, 50001)
	if err != ErrUDPDisabledUnderTor {
		t.Fatalf("Expected ErrUDPDisabledUnderTor, got %v", err)
	}
}

// TestTracker_DynamicTorToggle ensures that dynamically enabling Tor on an active UDPTrackerClient
// immediately suppresses subsequent UDP queries.
func TestTracker_DynamicTorToggle(t *testing.T) {
	client := NewUDPTrackerClient(false /* torEnabled */, 2*time.Second)
	client.SetTorEnabled(true)

	var infoHash [20]byte
	var peerID [20]byte

	_, err := client.Announce(context.Background(), "udp://tracker.example.com:1337/announce", infoHash, peerID, 50001)
	if err != ErrUDPDisabledUnderTor {
		t.Fatalf("Expected ErrUDPDisabledUnderTor after dynamic toggle, got %v", err)
	}
}
