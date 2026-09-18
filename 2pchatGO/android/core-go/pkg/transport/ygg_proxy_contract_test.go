package transport

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"testing"
	"time"
)

// This is a loopback-only SOCKS contract test, not a mesh connectivity test.
// The fixture never forwards requests to an external address.
func TestYggProxyIPv6ConnectContract(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	done := make(chan error, 1)
	go func() {
		done <- func() error {
			conn, err := listener.Accept()
			if err != nil {
				return err
			}
			defer conn.Close()
			if err := conn.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
				return err
			}
			greeting := make([]byte, 2)
			if _, err := io.ReadFull(conn, greeting); err != nil {
				return err
			}
			if greeting[0] != 5 || greeting[1] == 0 {
				return fmt.Errorf("invalid greeting")
			}
			methods := make([]byte, int(greeting[1]))
			if _, err := io.ReadFull(conn, methods); err != nil {
				return err
			}
			if !bytes.Contains(methods, []byte{0}) {
				return fmt.Errorf("no no-auth method")
			}
			if _, err := conn.Write([]byte{5, 0}); err != nil {
				return err
			}
			request := make([]byte, 22)
			if _, err := io.ReadFull(conn, request); err != nil {
				return err
			}
			if !bytes.Equal(request[:4], []byte{5, 1, 0, 4}) {
				return fmt.Errorf("expected IPv6 CONNECT")
			}
			if !net.IP(request[4:20]).Equal(net.ParseIP("200::1234")) || request[20] != 0xc3 || request[21] != 0x51 {
				return fmt.Errorf("wrong destination or port")
			}
			// Exactly the IPv4-bound-address reply shape used by the Android shim.
			if _, err := conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); err != nil {
				return err
			}
			payload := make([]byte, 4)
			if _, err := io.ReadFull(conn, payload); err != nil {
				return err
			}
			if !bytes.Equal(payload, []byte("test")) {
				return fmt.Errorf("wrong payload")
			}
			_, err = conn.Write([]byte("okay"))
			return err
		}()
	}()
	dialer := NewAdaptiveDialer("", false, 3*time.Second)
	dialer.SetYggdrasilConfig(YggdrasilModeProxy, listener.Addr().String())
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := dialer.DialContext(ctx, "tcp", "[200::1234]:50001")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write([]byte("test")); err != nil {
		t.Fatal(err)
	}
	response := make([]byte, 4)
	if _, err := io.ReadFull(conn, response); err != nil {
		t.Fatal(err)
	}
	if string(response) != "okay" {
		t.Fatalf("unexpected response %q", response)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
}
