package transport

import (
	"bytes"
	"context"
	"crypto/rand"
	"fmt"
	"net"
	"testing"
	"time"
)

func TestRelayTunnel_EndToEndFallback(t *testing.T) {
	// 1. Start a local Blind Relay Server
	relayServer := NewRelayTunnelServer()
	relayPort, err := FindAvailablePort("127.0.0.1", 0)
	if err != nil {
		t.Fatalf("failed to find free port for relay: %v", err)
	}
	if err := relayServer.Start(relayPort); err != nil {
		t.Fatalf("failed to start relay server: %v", err)
	}
	defer relayServer.Stop()

	relayAddr := fmt.Sprintf("127.0.0.1:%d", relayPort)
	bobFP := "bob_fingerprint_hex_12345"

	// 2. Bob registers with the relay server as ready to receive connections
	bobConn, err := net.Dial("tcp", relayAddr)
	if err != nil {
		t.Fatalf("Bob failed to connect to relay: %v", err)
	}
	defer bobConn.Close()

	var sessionID [16]byte
	_, _ = rand.Read(sessionID[:])

	regFrame, err := EncodeRelayFrame(RelayFrameTypeRegister, sessionID, []byte(bobFP))
	if err != nil {
		t.Fatalf("failed to encode register frame: %v", err)
	}
	if _, err := bobConn.Write(regFrame); err != nil {
		t.Fatalf("Bob failed to register with relay: %v", err)
	}

	// 3. Alice sets up an AdaptiveDialer with invalid direct endpoints (simulating symmetric NAT failure)
	// and Bob's relay endpoint
	dialer := NewAdaptiveDialer("127.0.0.1:9050", false, 3*time.Second)
	dialer.AddRelayEndpoint(relayAddr)
	invalidDirectEndpoints := []string{"127.0.0.1:59998", "127.0.0.1:59999"} // Closed local ports (immediate connection refused)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	aliceRelayConn, endpointUsed, err := dialer.DialWithRelayFallback(
		ctx,
		invalidDirectEndpoints,
		bobFP,
		sessionID,
		300*time.Millisecond, // fast direct timeout
	)
	if err != nil {
		t.Fatalf("Alice DialWithRelayFallback failed: %v", err)
	}
	defer aliceRelayConn.Close()

	if endpointUsed != fmt.Sprintf("relay://%s#%s", relayAddr, bobFP) {
		t.Fatalf("unexpected endpoint used: %s", endpointUsed)
	}

	// 4. Test bidirectional data exchange
	// Alice writes a test payload
	testPayload := []byte("hello bob through blind relay tunnel!")
	if _, err := aliceRelayConn.Write(testPayload); err != nil {
		t.Fatalf("Alice failed to write data to relay: %v", err)
	}

	// Bob receives incoming Connect frame from relay first
	connectFrame, err := ReadRelayFrame(bobConn)
	if err != nil {
		t.Fatalf("Bob failed to read connect frame: %v", err)
	}
	if connectFrame.Type != RelayFrameTypeConnect {
		t.Fatalf("expected connect frame (type %d), got %d", RelayFrameTypeConnect, connectFrame.Type)
	}

	// Bob receives Data frame from relay
	dataFrame, err := ReadRelayFrame(bobConn)
	if err != nil {
		t.Fatalf("Bob failed to read data frame from relay: %v", err)
	}

	if dataFrame.Type != RelayFrameTypeData || !bytes.Equal(dataFrame.Payload, testPayload) {
		t.Fatalf("Bob received unexpected data frame: type=%d, payload=%s", dataFrame.Type, string(dataFrame.Payload))
	}
}

func TestRelayTunnel_SessionIsolationAndDisconnectCleanup(t *testing.T) {
	relayServer := NewRelayTunnelServer()
	relayPort, err := FindAvailablePort("127.0.0.1", 0)
	if err != nil {
		t.Fatalf("free port failed: %v", err)
	}
	if err := relayServer.Start(relayPort); err != nil {
		t.Fatalf("start relay failed: %v", err)
	}
	defer relayServer.Stop()

	relayAddr := fmt.Sprintf("127.0.0.1:%d", relayPort)

	// 1. Register Bob and Charlie
	bobConn, err := net.Dial("tcp", relayAddr)
	if err != nil {
		t.Fatalf("Bob dial failed: %v", err)
	}
	defer bobConn.Close()

	charlieConn, err := net.Dial("tcp", relayAddr)
	if err != nil {
		t.Fatalf("Charlie dial failed: %v", err)
	}
	defer charlieConn.Close()

	var dummySID [16]byte
	regBob, _ := EncodeRelayFrame(RelayFrameTypeRegister, dummySID, []byte("bob_fp"))
	_, _ = bobConn.Write(regBob)

	regCharlie, _ := EncodeRelayFrame(RelayFrameTypeRegister, dummySID, []byte("charlie_fp"))
	_, _ = charlieConn.Write(regCharlie)

	time.Sleep(50 * time.Millisecond) // allow server to process registers

	// 2. Alice connects to Bob
	aliceConn, err := net.Dial("tcp", relayAddr)
	if err != nil {
		t.Fatalf("Alice dial failed: %v", err)
	}
	defer aliceConn.Close()

	var sessionID [16]byte
	_, _ = rand.Read(sessionID[:])
	connBob, _ := EncodeRelayFrame(RelayFrameTypeConnect, sessionID, []byte("bob_fp"))
	_, _ = aliceConn.Write(connBob)

	// Bob receives Connect frame
	cFrame, err := ReadRelayFrame(bobConn)
	if err != nil || cFrame.Type != RelayFrameTypeConnect {
		t.Fatalf("Bob failed to receive connect frame: %v", err)
	}

	// 3. Alice sends secret data intended only for Bob
	secret := []byte("secret for bob only")
	dataFrame, _ := EncodeRelayFrame(RelayFrameTypeData, sessionID, secret)
	_, _ = aliceConn.Write(dataFrame)

	// Bob receives it
	bFrame, err := ReadRelayFrame(bobConn)
	if err != nil || !bytes.Equal(bFrame.Payload, secret) {
		t.Fatalf("Bob failed to receive secret: %v", err)
	}

	// Charlie sets short read deadline to verify NO broadcast leaked to Charlie
	_ = charlieConn.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	leakedFrame, err := ReadRelayFrame(charlieConn)
	if err == nil {
		t.Fatalf("Charlie received leaked broadcast frame: type=%d, payload=%s", leakedFrame.Type, string(leakedFrame.Payload))
	}
	_ = charlieConn.SetReadDeadline(time.Time{})

	// 4. Bob disconnects abruptly
	_ = bobConn.Close()
	time.Sleep(50 * time.Millisecond)

	// Alice should receive Close frame from relay
	_ = aliceConn.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	closeFrame, err := ReadRelayFrame(aliceConn)
	if err != nil {
		t.Fatalf("Alice failed to receive disconnect notice from relay: %v", err)
	}
	if closeFrame.Type != RelayFrameTypeClose {
		t.Fatalf("expected close frame, got type %d", closeFrame.Type)
	}

	// Verify server maps are pruned
	relayServer.mu.RLock()
	subLen := len(relayServer.subscribers)
	sessLen := len(relayServer.sessions)
	relayServer.mu.RUnlock()

	if subLen != 1 { // only Charlie remains
		t.Fatalf("expected 1 subscriber remaining, got %d", subLen)
	}
	if sessLen != 0 {
		t.Fatalf("expected 0 sessions remaining, got %d", sessLen)
	}
}

func TestReliableUDP_BasicTransfer(t *testing.T) {
	// Start local UDP listener
	lAddr, err := net.ResolveUDPAddr("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("ResolveUDPAddr failed: %v", err)
	}
	serverSock, err := net.ListenUDP("udp", lAddr)
	if err != nil {
		t.Fatalf("ListenUDP failed: %v", err)
	}
	defer serverSock.Close()

	serverPort := serverSock.LocalAddr().(*net.UDPAddr).Port
	convID := uint64(999888)

	// Server-side wrapper
	serverConn := NewReliableUDPConn(serverSock, &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0}, convID)

	// Client-side dialer
	clientConn, err := DialReliableUDP(context.Background(), 0, fmt.Sprintf("127.0.0.1:%d", serverPort), convID)
	if err != nil {
		t.Fatalf("DialReliableUDP failed: %v", err)
	}
	defer clientConn.Close()

	// Client writes message
	msg := []byte("reliable udp payload over symmetric NAT")
	if _, err := clientConn.Write(msg); err != nil {
		t.Fatalf("client write failed: %v", err)
	}

	// Server reads message
	buf := make([]byte, 1024)
	n, err := serverConn.Read(buf)
	if err != nil {
		t.Fatalf("server read failed: %v", err)
	}

	if !bytes.Equal(buf[:n], msg) {
		t.Fatalf("message mismatch: got %s, expected %s", string(buf[:n]), string(msg))
	}
}
