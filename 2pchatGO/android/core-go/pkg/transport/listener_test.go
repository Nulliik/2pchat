package transport

import (
	"net"
	"testing"
)

func TestAsyncListenerStartIsIdempotentForConfiguredPort(t *testing.T) {
	listener := NewAsyncListener()
	if err := listener.Start(0, func(conn net.Conn) { _ = conn.Close() }); err != nil {
		t.Fatalf("initial listener start: %v", err)
	}
	defer listener.Stop()

	port := listener.Port()
	if port == 0 {
		t.Fatal("listener did not retain its bound port")
	}
	if err := listener.Start(port, func(conn net.Conn) { _ = conn.Close() }); err != nil {
		t.Fatalf("same-port restart must be a successful no-op, got: %v", err)
	}
	if err := listener.Start(0, func(conn net.Conn) { _ = conn.Close() }); err != nil {
		t.Fatalf("port-zero restart must retain the active listener, got: %v", err)
	}
	if err := listener.Start(port+1, func(conn net.Conn) { _ = conn.Close() }); err != ErrListenerAlreadyRunning {
		t.Fatalf("different port must still be rejected, got: %v", err)
	}
}
