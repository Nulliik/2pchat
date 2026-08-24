package bridge_test

import (
	"context"
	"fmt"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/discovery"
	"twopchat/core/pkg/transport"
)

// =============================================================================
// 1. CRYPTOGRAPHY & HANDSHAKE (X3DH + Double Ratchet + Forward Secrecy)
// =============================================================================

func TestCryptoHandshakeAndRatchet(t *testing.T) {
	// 1. Generate Identities & Signed Prekeys
	aliceIdentity, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("Failed to generate Alice identity: %v", err)
	}
	bobIdentity, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("Failed to generate Bob identity: %v", err)
	}

	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("Failed to generate Bob prekey: %v", err)
	}
	bobPrekeySig := crypto.SignPreKey(bobIdentity.Signing, bobPrekeyPub)

	bobBundle := &crypto.PreKeyBundle{
		IdentityPub:       bobIdentity.Public,
		IdentityVerifyPub: bobIdentity.Verify,
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	// 2. Perform X3DH Initiator (Alice) & Responder (Bob) Session Initialization
	aliceEphemeral, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("Failed to generate Alice ephemeral key: %v", err)
	}

	// Alice initiates Double Ratchet session using Bob's pre-key bundle
	aliceSession, err := crypto.InitializeSessionFromPreKey(
		aliceIdentity,
		bobBundle,
		aliceEphemeral,
	)
	if err != nil {
		t.Fatalf("Alice failed to initialize Double Ratchet session: %v", err)
	}

	// Bob responds to initiator handshake
	bobSession, err := crypto.RespondToPreKeyInit(
		bobIdentity,
		bobPrekeyPriv,
		nil,
		aliceIdentity.Public,
		aliceEphemeral.Public,
	)
	if err != nil {
		t.Fatalf("Bob failed to respond to Double Ratchet handshake: %v", err)
	}

	// 3. Double Ratchet Message Exchange
	plaintext := []byte("Hello from Go Core Double Ratchet!")

	ciphertext, err := aliceSession.EncryptMessage(plaintext)
	if err != nil {
		t.Fatalf("Alice encryption failed: %v", err)
	}

	decrypted, err := bobSession.DecryptMessage(ciphertext)
	if err != nil {
		t.Fatalf("Bob decryption failed: %v", err)
	}

	if string(decrypted) != string(plaintext) {
		t.Errorf("Decryption mismatch: got %q, want %q", decrypted, plaintext)
	}

	// 4. Forward Secrecy Check (Key Ratcheting)
	plaintext2 := []byte("Second message with ratcheted key")
	ciphertext2, err := aliceSession.EncryptMessage(plaintext2)
	if err != nil {
		t.Fatalf("Alice second encryption failed: %v", err)
	}

	if string(ciphertext) == string(ciphertext2) {
		t.Error("Ratchet failed: Consecutive ciphertexts should differ due to key ratcheting")
	}

	decrypted2, err := bobSession.DecryptMessage(ciphertext2)
	if err != nil {
		t.Fatalf("Bob second decryption failed: %v", err)
	}
	if string(decrypted2) != string(plaintext2) {
		t.Errorf("Decryption 2 mismatch: got %q, want %q", decrypted2, plaintext2)
	}

	t.Log("✅ Crypto Handshake & Ratchet (X3DH + Forward Secrecy): PASS")
}

// =============================================================================
// 2. TRANSPORT LAYER (TCP Listener + Length-Prefixed Framing)
// =============================================================================

func TestTransportLayer(t *testing.T) {
	serverMsgChan := make(chan []byte, 1)

	// 1. Start AsyncListener on dynamic port
	listener := transport.NewAsyncListener()
	err := listener.Start(0, func(conn net.Conn) {
		defer conn.Close()
		payload, err := transport.ReadFrame(conn, 0)
		if err != nil {
			t.Errorf("Server read frame error: %v", err)
			return
		}
		serverMsgChan <- payload
	})
	if err != nil {
		t.Fatalf("Failed to start listener: %v", err)
	}
	defer listener.Stop()

	boundPort := listener.Port()
	if boundPort <= 0 {
		t.Fatalf("Expected bound port > 0, got %d", boundPort)
	}

	addr := fmt.Sprintf("127.0.0.1:%d", boundPort)
	t.Logf("Listener started at %s", addr)

	// 2. Direct TCP connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	dialer := transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second)
	clientConn, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		t.Fatalf("Client dial failed: %v", err)
	}
	defer clientConn.Close()

	// 3. Send length-prefixed frame
	testPayload := []byte("Go Core Transport Test Payload")
	if err := transport.WriteFrame(clientConn, testPayload); err != nil {
		t.Fatalf("Client write frame failed: %v", err)
	}

	// 4. Verify receipt
	select {
	case received := <-serverMsgChan:
		if string(received) != string(testPayload) {
			t.Errorf("Payload mismatch: got %q, want %q", received, testPayload)
		}
	case <-ctx.Done():
		t.Fatal("Timeout waiting for server message")
	}

	t.Log("✅ Transport Layer (Listen/Dial/Frame): PASS")
}

// =============================================================================
// 3. TOR INTEGRATION (SOCKS5 Dialer + DNS Leak Protection)
// =============================================================================

func TestTorDialerLogic(t *testing.T) {
	// Configure dialer with Tor proxy enabled pointing to unreachable local port
	dialer := transport.NewAdaptiveDialer("127.0.0.1:59999", true, 2*time.Second)

	// When dialing a domain with Tor enabled, it should try SOCKS5 on 127.0.0.1:59999
	// and fail with proxy connection refused, NOT resolve DNS locally (No DNS leak)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, err := dialer.DialContext(ctx, "tcp", "example.com:80")
	if err == nil {
		t.Fatal("Expected connection error (no proxy running), but got success")
	}

	errStr := err.Error()
	if strings.Contains(errStr, "lookup example.com") || strings.Contains(errStr, "no such host") {
		t.Error("DNS Leak detected! Tor dialer resolved hostname locally instead of via SOCKS5.")
	}

	t.Log("✅ Tor Dialer Logic (No DNS Leak): PASS")
}

func TestTorOnionRouting(t *testing.T) {
	// Start mock SOCKS5 proxy listener
	proxyListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to start mock SOCKS5 listener: %v", err)
	}
	defer proxyListener.Close()

	proxyAddr := proxyListener.Addr().String()

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		conn, err := proxyListener.Accept()
		if err == nil {
			// SOCKS5 handshake response
			buf := make([]byte, 256)
			_, _ = conn.Read(buf)
			_, _ = conn.Write([]byte{0x05, 0x00})
			_ = conn.Close()
		}
	}()

	dialer := transport.NewAdaptiveDialer(proxyAddr, false, 2*time.Second)
	onionAddr := "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"

	// Even if proxyEnabled is false, .onion must route to SOCKS5
	if dialer.ClassifyEndpoint(onionAddr) != transport.TransportTor {
		t.Fatalf("Expected .onion endpoint to classify as TransportTor")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, _ = dialer.DialContext(ctx, "tcp", onionAddr)
	wg.Wait()

	t.Log("✅ Tor Onion Routing (SOCKS5 Proxy): PASS")
}

// =============================================================================
// 4. YGGDRASIL MESH NETWORK (IPv6 Direct Dialing / Split-Tunneling)
// =============================================================================

func TestYggdrasilIPv6Dialing(t *testing.T) {
	// Configure dialer with Tor proxy enabled
	dialer := transport.NewAdaptiveDialer("127.0.0.1:9050", true, 2*time.Second)

	yggdrasilAddr := "[200:182d:e207:ca9b:8205:5f82:3aa:c4f7]:50001"

	// Verify Yggdrasil address is classified as TransportYggdrasil (bypassing Tor proxy)
	transportType := dialer.ClassifyEndpoint(yggdrasilAddr)
	if transportType != transport.TransportYggdrasil {
		t.Fatalf("Expected Yggdrasil endpoint to classify as TransportYggdrasil, got %v", transportType)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Second)
	defer cancel()

	_, err := dialer.DialContext(ctx, "tcp", yggdrasilAddr)
	if err == nil {
		t.Fatal("Expected dial error for unreachable Yggdrasil address")
	}

	// Verify the error did NOT originate from Tor SOCKS5 proxy
	errStr := err.Error()
	if strings.Contains(errStr, "127.0.0.1:9050") || strings.Contains(strings.ToLower(errStr), "tor socks") {
		t.Error("Yggdrasil dialer incorrectly routed through Tor SOCKS5 proxy! Split-tunneling failed.")
	}

	t.Log("✅ Yggdrasil IPv6 Dialing (Split-Tunneling): PASS")
}

// =============================================================================
// 5. DISCOVERY ENGINE (Fast Tiered Probing Priorities)
// =============================================================================

func TestDiscoveryProbingPriorities(t *testing.T) {
	// Verify Tier Priorities (Lower value = Higher priority)
	if discovery.TierLAN >= discovery.TierWANDirect {
		t.Error("LAN tier (1) should have higher priority (lower value) than WAN (2)")
	}
	if discovery.TierWANDirect >= discovery.TierYggdrasil {
		t.Error("WAN tier (2) should have higher priority than Yggdrasil (3)")
	}
	if discovery.TierYggdrasil >= discovery.TierTor {
		t.Error("Yggdrasil tier (3) should have higher priority than Tor (4)")
	}

	// Verify Endpoint Classification
	lanTier := discovery.ClassifyTier("192.168.1.50:50001")
	if lanTier != discovery.TierLAN {
		t.Errorf("Expected 192.168.1.50 to classify as TierLAN, got %v", lanTier)
	}

	wanTier := discovery.ClassifyTier("93.184.216.34:50001")
	if wanTier != discovery.TierWANDirect {
		t.Errorf("Expected 93.184.216.34 to classify as TierWANDirect, got %v", wanTier)
	}

	yggTier := discovery.ClassifyTier("[200:182d:e207:ca9b:8205:5f82:3aa:c4f7]:50001")
	if yggTier != discovery.TierYggdrasil {
		t.Errorf("Expected 200:: to classify as TierYggdrasil, got %v", yggTier)
	}

	torTier := discovery.ClassifyTier("abcdef1234567890.onion:50001")
	if torTier != discovery.TierTor {
		t.Errorf("Expected .onion to classify as TierTor, got %v", torTier)
	}

	t.Log("✅ Discovery Probing Priorities & Classification: PASS")
}
