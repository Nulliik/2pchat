package transport

import (
	"context"
	"errors"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func TestDialer_LocalDNSDenied_Hostname_NoResolverCall(t *testing.T) {
	dialer := NewAdaptiveDialer("127.0.0.1:9050", false, 2*time.Second)

	// Policy denies local DNS resolution and clearnet WAN
	dialer.SetPolicy(PolicyTorStrict)

	var resolverCallCount int32
	mockResolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			atomic.AddInt32(&resolverCallCount, 1)
			return nil, errors.New("resolver should never be called under AllowLocalDNS=false")
		},
	}
	dialer.SetResolver(mockResolver)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	// Attempt to dial a hostname
	_, err := dialer.DialContext(ctx, "tcp", "malicious-peer.example.com:50001")
	if err == nil {
		t.Fatalf("Expected DialContext to fail when AllowLocalDNS=false and proxy=false, but it succeeded")
	}

	if !errors.Is(err, ErrPolicyDenied) {
		t.Fatalf("Expected ErrPolicyDenied, got: %v", err)
	}

	if count := atomic.LoadInt32(&resolverCallCount); count != 0 {
		t.Fatalf("DNS LEAK DETECTED: Resolver was invoked %d times despite AllowLocalDNS=false", count)
	}
}
