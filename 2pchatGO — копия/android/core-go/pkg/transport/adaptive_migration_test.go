package transport

import (
	"context"
	"net"
	"testing"
	"time"
)

// TestAdaptiveTransportClassification verifies RULES.md §10, §11:
// Endpoints are strictly categorized into Direct, Yggdrasil, or Tor to prevent DNS/traffic leaks.
func TestAdaptiveTransportClassification(t *testing.T) {
	// 1. Tor Proxy Enabled (Tor routes all public traffic, leaves LAN and Yggdrasil)
	torDialer := NewAdaptiveDialer("127.0.0.1:9050", true, 5*time.Second)

	torCases := []struct {
		endpoint string
		expected TransportClass
	}{
		{"192.168.1.50:50001", TransportLAN},
		{"10.0.0.2:50001", TransportLAN},
		{"8.8.8.8:50001", TransportWAN},
		{"peer.example.com:50001", TransportTor},
		{"[200:abcd:1234::1]:50001", TransportYggdrasil},
		{"[0200:1111:2222::1]:50001", TransportYggdrasil},
		{"[300:abcd::1]:50001", TransportYggdrasil},
		{"expyuz5wqlgah2inqqdu42q5755hkgy2ec2sp7z5bvhz2e6p3mndnxyd.onion:50001", TransportTor},
	}

	for _, tc := range torCases {
		t.Run("TorEnabled_"+tc.endpoint, func(t *testing.T) {
			got, err := torDialer.ClassifyEndpoint(tc.endpoint)
			if err != nil {
				t.Fatalf("ClassifyEndpoint(%q) unexpected error: %v", tc.endpoint, err)
			}
			if got != tc.expected {
				t.Errorf("ClassifyEndpoint(%q) = %v, expected %v", tc.endpoint, got, tc.expected)
			}
		})
	}

	// 2. Direct Clearnet Mode (proxyEnabled=false)
	directDialer := NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second)

	directCases := []struct {
		endpoint string
		expected TransportClass
	}{
		{"8.8.8.8:50001", TransportWAN},
		{"1.1.1.1:50001", TransportWAN},
		{"[200:abcd:1234::1]:50001", TransportYggdrasil},
		{"expyuz5wqlgah2inqqdu42q5755hkgy2ec2sp7z5bvhz2e6p3mndnxyd.onion:80", TransportTor},
	}

	for _, tc := range directCases {
		t.Run("DirectMode_"+tc.endpoint, func(t *testing.T) {
			got, err := directDialer.ClassifyEndpoint(tc.endpoint)
			if err != nil {
				t.Fatalf("ClassifyEndpoint(%q) unexpected error: %v", tc.endpoint, err)
			}
			if got != tc.expected {
				t.Errorf("ClassifyEndpoint(%q) = %v, expected %v", tc.endpoint, got, tc.expected)
			}
		})
	}
}

// TestAdaptiveDialerModeSwitching verifies dynamic transition between SOCKS5 Proxy and System VPN modes.
func TestAdaptiveDialerModeSwitching(t *testing.T) {
	dialer := NewAdaptiveDialer("127.0.0.1:9050", false, 3*time.Second)

	// Initially in Proxy mode with default proxy 127.0.0.1:9053
	dialer.SetYggdrasilConfig("proxy", "127.0.0.1:9053")
	if dialer.yggdrasilMode != YggdrasilModeProxy {
		t.Fatalf("Expected YggdrasilModeProxy, got %v", dialer.yggdrasilMode)
	}

	// Switch to VPN mode
	dialer.SetYggdrasilConfig("vpn", "")
	if dialer.yggdrasilMode != YggdrasilModeVPN {
		t.Fatalf("Expected YggdrasilModeVPN, got %v", dialer.yggdrasilMode)
	}

	// Switch back to custom SOCKS5 Proxy address
	customProxy := "127.0.0.1:9055"
	dialer.SetYggdrasilConfig("proxy", customProxy)
	if dialer.yggdrasilMode != YggdrasilModeProxy {
		t.Fatalf("Expected YggdrasilModeProxy, got %v", dialer.yggdrasilMode)
	}
	if dialer.yggProxyAddr != customProxy {
		t.Fatalf("Expected yggProxyAddr %q, got %q", customProxy, dialer.yggProxyAddr)
	}
}

// TestDynamicEndpointFallback verifies connecting to candidate endpoints in prioritized order.
func TestDynamicEndpointFallback(t *testing.T) {
	// 1. Create a working listener simulating the backup candidate endpoint
	backupListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to create backup listener: %v", err)
	}
	defer backupListener.Close()
	backupAddr := backupListener.Addr().String()

	// Dead endpoint on closed port
	deadAddr := "127.0.0.1:59999"

	dialer := NewAdaptiveDialer("127.0.0.1:9050", false, 1*time.Second)

	endpoints := []string{deadAddr, backupAddr}

	var connectedConn net.Conn
	var successfulEndpoint string

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	for _, ep := range endpoints {
		conn, err := dialer.DialContext(ctx, "tcp", ep)
		if err == nil {
			connectedConn = conn
			successfulEndpoint = ep
			break
		}
	}

	if connectedConn == nil {
		t.Fatalf("Fallback dialing failed: could not connect to any candidate endpoint")
	}
	defer connectedConn.Close()

	if successfulEndpoint != backupAddr {
		t.Fatalf("Expected connection to backup endpoint %q, connected to %q", backupAddr, successfulEndpoint)
	}
}
