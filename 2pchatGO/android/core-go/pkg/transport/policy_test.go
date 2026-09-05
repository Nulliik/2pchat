package transport

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

// TestNetworkPolicy_ZeroValue_DeniesEverything tests П1:
// Zero-value struct MUST deny all transport classes (fail-closed).
func TestNetworkPolicy_ZeroValue_DeniesEverything(t *testing.T) {
	var p NetworkPolicy // zero-value

	classes := []TransportClass{
		TransportLAN,
		TransportWAN,
		TransportYggdrasil,
		TransportTor,
		TransportInvalid,
		TransportDirect,
	}

	for _, c := range classes {
		if p.Allows(c) {
			t.Fatalf("Zero-value NetworkPolicy unexpectedly allowed transport class %q (must be fail-closed)", c)
		}
	}
}

func TestNetworkPolicy_Presets(t *testing.T) {
	// PolicySpeed must allow all valid transports
	if !PolicySpeed.Allows(TransportLAN) || !PolicySpeed.Allows(TransportWAN) ||
		!PolicySpeed.Allows(TransportYggdrasil) || !PolicySpeed.Allows(TransportTor) {
		t.Errorf("PolicySpeed must allow all transports")
	}

	// PolicyTorStrict must only allow Onion
	if !PolicyTorStrict.Allows(TransportTor) {
		t.Errorf("PolicyTorStrict must allow TransportTor")
	}
	if PolicyTorStrict.Allows(TransportLAN) {
		t.Errorf("PolicyTorStrict must NOT allow TransportLAN")
	}
	if PolicyTorStrict.Allows(TransportWAN) {
		t.Errorf("PolicyTorStrict must NOT allow TransportWAN")
	}
	if PolicyTorStrict.Allows(TransportYggdrasil) {
		t.Errorf("PolicyTorStrict must NOT allow TransportYggdrasil")
	}
}

func TestPolicyFlags_RoundTrip(t *testing.T) {
	p := NetworkPolicy{
		AllowLAN:       true,
		AllowWAN:       false,
		AllowYggdrasil: true,
		AllowOnion:     true,
		AllowLocalDNS:  false,
	}

	flags := p.ToFlags()
	reconstructed := PolicyFromFlags(flags)

	if reconstructed != p {
		t.Errorf("PolicyFromFlags(p.ToFlags()) = %+v, expected %+v", reconstructed, p)
	}
}

// TestDialer_TorStrict_RawPublicIP_ReturnsPolicyDenied_NoDial verifies SEC-07a:
// In Tor Strict mode, attempting to dial a public IP directly is rejected by policy
// BEFORE any network sockets or dials are attempted.
func TestDialer_TorStrict_RawPublicIP_ReturnsPolicyDenied_NoDial(t *testing.T) {
	dialer := NewAdaptiveDialer("127.0.0.1:9050", false, 2*time.Second)
	dialer.SetPolicy(PolicyTorStrict)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	// Dial a public IP (TransportWAN) - must be rejected by policy
	conn, err := dialer.DialContext(ctx, "tcp", "8.8.8.8:50001")
	if conn != nil {
		_ = conn.Close()
		t.Fatalf("Expected connection to be nil, got %v", conn)
	}
	if err == nil {
		t.Fatalf("Expected error dialing public IP under TorStrict policy")
	}
	if !errors.Is(err, ErrPolicyDenied) {
		t.Fatalf("Expected ErrPolicyDenied, got: %v", err)
	}

	// Dial a LAN IP (TransportLAN) - must be rejected by policy
	conn, err = dialer.DialContext(ctx, "tcp", "192.168.1.50:50001")
	if conn != nil {
		_ = conn.Close()
		t.Fatalf("Expected connection to be nil, got %v", conn)
	}
	if !errors.Is(err, ErrPolicyDenied) {
		t.Fatalf("Expected ErrPolicyDenied for LAN IP under TorStrict, got: %v", err)
	}

	// Dial a Loopback IP (TransportLAN) - must be rejected by policy
	conn, err = dialer.DialContext(ctx, "tcp", "127.0.0.1:50001")
	if conn != nil {
		_ = conn.Close()
		t.Fatalf("Expected connection to be nil, got %v", conn)
	}
	if !errors.Is(err, ErrPolicyDenied) {
		t.Fatalf("Expected ErrPolicyDenied for Loopback IP under TorStrict, got: %v", err)
	}
}

// TestDialer_Onion_NeverCallsLocalDNS verifies that .onion names are never passed to local DNS resolver.
func TestDialer_Onion_NeverCallsLocalDNS(t *testing.T) {
	resolverCalled := false
	fakeResolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			resolverCalled = true
			return nil, errors.New("DNS resolution must never be invoked for .onion")
		},
	}

	dialer := NewAdaptiveDialer("127.0.0.1:9050", true, 500*time.Millisecond)
	dialer.directDialer.Resolver = fakeResolver

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()

	// Valid 56-character v3 onion
	validOnion := "v2222222222222222222222222222222222222222222222222222222.onion:50001"
	_, _ = dialer.DialContext(ctx, "tcp", validOnion)

	if resolverCalled {
		t.Fatalf("CRITICAL LEAK: Local DNS resolver was called when dialing .onion address!")
	}
}
