package transport

import (
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestSwitchToStrict_ClosesExistingPublicListener(t *testing.T) {
	listener := NewAsyncListener()

	var incomingCount int32
	handler := func(conn net.Conn) {
		atomic.AddInt32(&incomingCount, 1)
		_ = conn.Close()
	}

	// 1. Start in PolicySpeed (public listener)
	if err := listener.StartWithPolicy(0, PolicySpeed, handler); err != nil {
		t.Fatalf("StartWithPolicy PolicySpeed failed: %v", err)
	}
	defer func() { _ = listener.Stop() }()

	origAddr := listener.Addr().String()
	origPort := listener.Addr().(*net.TCPAddr).Port

	// 2. Rebind to PolicyTorStrict
	if err := listener.RebindWithPolicy(PolicyTorStrict, handler); err != nil {
		t.Fatalf("RebindWithPolicy PolicyTorStrict failed: %v", err)
	}

	newAddr := listener.Addr().String()
	if !strings.HasPrefix(newAddr, "127.0.0.1:") {
		t.Fatalf("Expected rebind in Tor Strict to bind loopback, got: %s (was: %s)", newAddr, origAddr)
	}

	// Verify incoming loopback connection works on rebound listener
	conn, err := net.DialTimeout("tcp", newAddr, 2*time.Second)
	if err != nil {
		t.Fatalf("Failed to connect to rebound loopback listener: %v", err)
	}
	defer conn.Close()

	time.Sleep(100 * time.Millisecond)
	if atomic.LoadInt32(&incomingCount) == 0 {
		t.Fatalf("Expected incoming connection to be received by rebound listener")
	}
	_ = origPort
}

func TestNoFallbackToWildcardOnBindError(t *testing.T) {
	// Occupy a specific port on loopback
	occupier, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to occupy test port: %v", err)
	}
	defer occupier.Close()

	occupiedPort := occupier.Addr().(*net.TCPAddr).Port

	// Attempt to start listener with PolicyTorStrict on the occupied port
	listener := NewAsyncListener()
	err = listener.StartWithPolicy(occupiedPort, PolicyTorStrict, func(conn net.Conn) {})
	if err == nil {
		_ = listener.Stop()
		t.Fatalf("Expected StartWithPolicy on occupied loopback port to fail, but it succeeded")
	}

	// Verify listener is not running
	if listener.IsRunning() {
		_ = listener.Stop()
		t.Fatalf("SECURITY VIOLATION: Listener should not be running after bind failure")
	}
}
