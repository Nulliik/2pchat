package transport

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestNoLANDNSAnswersCannotBypassPolicy(t *testing.T) {
	for _, address := range []string{"127.0.0.1", "192.168.1.1", "10.0.2.2", "fe80::1", "fc00::1"} {
		if allowedResolvedWAN(PolicyNoLAN, net.ParseIP(address)) {
			t.Fatalf("private DNS answer %s was permitted", address)
		}
	}
	if !allowedResolvedWAN(PolicyNoLAN, net.ParseIP("8.8.8.8")) {
		t.Fatal("public WAN answer rejected")
	}
	dialer := NewAdaptiveDialer("127.0.0.1:9050", false, time.Second)
	dialer.SetPolicy(PolicyNoLAN)
	_, err := dialer.DialContext(context.Background(), "tcp", "localhost:50001")
	if !errors.Is(err, ErrPolicyDenied) {
		t.Fatalf("localhost DNS bypass: got %v, want ErrPolicyDenied", err)
	}
}
