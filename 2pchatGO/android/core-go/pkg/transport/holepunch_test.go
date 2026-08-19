package transport

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestHolePuncherLocalDirect(t *testing.T) {
	// Start a test listener to accept simulated punched connection
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Listen failed: %v", err)
	}
	defer l.Close()

	addr := l.Addr().String()

	hp := NewHolePuncher(0, false)

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	connCh := make(chan net.Conn, 1)
	go func() {
		conn, err := l.Accept()
		if err == nil {
			connCh <- conn
		}
	}()

	clientConn, err := hp.Punch(ctx, []string{addr}, 3, 500*time.Millisecond)
	if err != nil {
		t.Fatalf("HolePunch failed: %v", err)
	}
	defer clientConn.Close()

	select {
	case sConn := <-connCh:
		defer sConn.Close()
	case <-time.After(2 * time.Second):
		t.Fatalf("Server timed out waiting for connection")
	}
}

func TestHolePuncherTorDisabledGuard(t *testing.T) {
	hp := NewHolePuncher(50001, true)
	ctx := context.Background()

	_, err := hp.Punch(ctx, []string{"198.51.100.1:50001"}, 1, 100*time.Millisecond)
	if err != ErrHolePunchTor {
		t.Errorf("Expected ErrHolePunchTor when Tor active, got %v", err)
	}
}
