package transport

import (
	"context"
	"net"
	"strconv"
	"testing"
	"time"
)

func TestAdaptiveDialerClassification(t *testing.T) {
	dialer := NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second)

	// .onion addresses must route to Tor
	if dialer.ClassifyEndpoint("3g2upl4pq6kufc4m.onion:50001") != TransportTor {
		t.Fatal("Expected TransportTor for .onion address")
	}

	// IPv6 must route to Yggdrasil / direct IPv6
	if dialer.ClassifyEndpoint("[200:1234:5678::1]:50001") != TransportYggdrasil {
		t.Fatal("Expected TransportYggdrasil for IPv6 address")
	}

	// IPv4 must route to Direct
	if dialer.ClassifyEndpoint("192.168.1.100:50001") != TransportDirect {
		t.Fatal("Expected TransportDirect for IPv4 address")
	}

	// When proxy is forced enabled, public WAN hosts route to Tor, but private LAN IPs stay Direct
	dialer.SetTorProxy(true, "127.0.0.1:9050")
	if dialer.ClassifyEndpoint("192.168.1.100:50001") != TransportDirect {
		t.Fatal("Expected TransportDirect for private LAN IP even when proxy is enabled")
	}
	if dialer.ClassifyEndpoint("8.8.8.8:50001") != TransportTor {
		t.Fatal("Expected TransportTor for public WAN endpoint when proxy is enabled")
	}

	// UDP over Tor must return error
	ctx := context.Background()
	_, err := dialer.DialContext(ctx, "udp", "3g2upl4pq6kufc4m.onion:50001")
	if err != ErrUDPOverTorNotSupported {
		t.Fatalf("Expected ErrUDPOverTorNotSupported, got: %v", err)
	}
}

func TestListenerAndDirectDial(t *testing.T) {
	listener := NewAsyncListener()

	acceptedChan := make(chan net.Conn, 1)
	err := listener.Start(0, func(conn net.Conn) {
		acceptedChan <- conn
	})
	if err != nil {
		t.Fatalf("Listener.Start failed: %v", err)
	}
	defer listener.Stop()

	port := listener.Port()
	if port <= 0 {
		t.Fatalf("Invalid port: %d", port)
	}

	dialer := NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second)
	addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(port))

	clientConn, err := dialer.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("Dial to localhost listener failed: %v", err)
	}
	defer clientConn.Close()

	select {
	case serverConn := <-acceptedChan:
		defer serverConn.Close()
		// Test sending a frame between client and server
		testMsg := []byte("Testing frame across listener and dialer")
		if err := WriteFrame(clientConn, testMsg); err != nil {
			t.Fatalf("WriteFrame failed: %v", err)
		}

		received, err := ReadFrame(serverConn, MaxFrameSize)
		if err != nil {
			t.Fatalf("ReadFrame failed: %v", err)
		}
		if string(received) != string(testMsg) {
			t.Fatalf("Message mismatch: got %q, want %q", string(received), string(testMsg))
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for accepted connection")
	}
}
