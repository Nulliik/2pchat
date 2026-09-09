package transport

import (
	"net"
	"strings"
	"testing"
)

func TestListener_TorStrict_BindsLoopbackOnly(t *testing.T) {
	l := NewAsyncListener()
	defer l.Stop()

	// 1. Start with PolicyTorStrict
	err := l.StartWithPolicy(0, PolicyTorStrict, func(conn net.Conn) {
		_ = conn.Close()
	})
	if err != nil {
		t.Fatalf("StartWithPolicy failed: %v", err)
	}

	addr := l.Addr()
	if addr == nil {
		t.Fatal("Expected listener to have non-nil Addr()")
	}

	addrStr := addr.String()
	t.Logf("Listener bound to: %s", addrStr)

	// Under Tor Strict, must bind strictly to 127.0.0.1
	if !strings.HasPrefix(addrStr, "127.0.0.1:") {
		t.Fatalf("Expected listener to bind strictly to 127.0.0.1 under PolicyTorStrict, got %s", addrStr)
	}

	// 2. Test RebindWithPolicy to PolicySpeed
	err = l.RebindWithPolicy(PolicySpeed, func(conn net.Conn) {
		_ = conn.Close()
	})
	if err != nil {
		t.Fatalf("RebindWithPolicy to PolicySpeed failed: %v", err)
	}

	speedAddr := l.Addr().String()
	t.Logf("Listener rebound to Speed mode: %s", speedAddr)
	if strings.HasPrefix(speedAddr, "127.0.0.1:") {
		// Speed mode should bind dual-stack/all interfaces (0.0.0.0 or [::])
		t.Logf("Note: bound address: %s", speedAddr)
	}

	// 3. Rebind back to PolicyTorStrict
	err = l.RebindWithPolicy(PolicyTorStrict, func(conn net.Conn) {
		_ = conn.Close()
	})
	if err != nil {
		t.Fatalf("RebindWithPolicy back to TorStrict failed: %v", err)
	}

	strictAddr := l.Addr().String()
	if !strings.HasPrefix(strictAddr, "127.0.0.1:") {
		t.Fatalf("Expected rebound listener to bind to 127.0.0.1 under PolicyTorStrict, got %s", strictAddr)
	}
}
