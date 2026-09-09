package session

import (
	"bytes"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
)

// sniffingConn intercepts all wire traffic passing through a net.Conn.
type sniffingConn struct {
	net.Conn
	mu       sync.Mutex
	captured bytes.Buffer
}

func (c *sniffingConn) Write(b []byte) (int, error) {
	c.mu.Lock()
	c.captured.Write(b)
	c.mu.Unlock()
	return c.Conn.Write(b)
}

func (c *sniffingConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.mu.Lock()
		c.captured.Write(b[:n])
		c.mu.Unlock()
	}
	return n, err
}

func (c *sniffingConn) CapturedBytes() []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	dup := make([]byte, c.captured.Len())
	copy(dup, c.captured.Bytes())
	return dup
}

// TestTrafficAudit_ZeroPlaintextLeakage performs deep packet inspection (DPI) on raw wire traffic.
// It verifies that NO plaintext message bodies, sensitive strings, private keys, or internal
// JSON structures leak over the network socket.
func TestTrafficAudit_ZeroPlaintextLeakage(t *testing.T) {
	aliceId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	bobId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}

	alicePrekeyPriv, alicePrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to bind test listener: %v", err)
	}
	defer listener.Close()

	port := listener.Addr().(*net.TCPAddr).Port

	var bobSniffer *sniffingConn
	var bobSession *Session
	var bobErr error
	var wg sync.WaitGroup
	wg.Add(1)

	go func() {
		defer wg.Done()
		rawConn, err := listener.Accept()
		if err != nil {
			bobErr = err
			return
		}
		bobSniffer = &sniffingConn{Conn: rawConn}
		bobSession, bobErr = NewSession(
			bobSniffer,
			false, // responder
			bobId,
			bobPrekeyPriv,
			bobPrekeyPub,
			"",
			5*time.Second,
		)
	}()

	clientRawConn, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		t.Fatalf("Failed to dial test listener: %v", err)
	}
	aliceSniffer := &sniffingConn{Conn: clientRawConn}

	aliceSession, err := NewSession(
		aliceSniffer,
		true, // initiator
		aliceId,
		alicePrekeyPriv,
		alicePrekeyPub,
		"",
		5*time.Second,
	)
	if err != nil {
		t.Fatalf("Alice failed to create session: %v", err)
	}
	defer aliceSession.Close()

	wg.Wait()
	if bobErr != nil {
		t.Fatalf("Bob failed to create session: %v", err)
	}
	defer bobSession.Close()

	// High-sensitivity canary strings that must NEVER appear in wire capture
	canaries := []string{
		"CONFIDENTIAL_TOP_SECRET_CANARY_MESSAGE_99812",
		"PrivateMedicalData_PatientDiagnosis_Restricted",
		"CreditCard_4111_2222_3333_4444_CVV_123",
		"NuclearLaunchCode_AlphaBravoCharlie_0000",
		"Alice_Secret_Nickname_Never_Broadcast",
	}

	// 1. Send sensitive chat messages from Alice to Bob
	for idx, canary := range canaries {
		if _, err := aliceSession.SendChat(canary, "AliceCanarySender"); err != nil {
			t.Fatalf("Alice SendChat failed for canary %d: %v", idx, err)
		}

		select {
		case msg := <-bobSession.Messages():
			body, _ := msg["body"].(string)
			if body != canary {
				t.Fatalf("Bob received unexpected body: got %q, want %q", body, canary)
			}
		case <-time.After(3 * time.Second):
			t.Fatalf("Timeout waiting for Bob to receive canary %d", idx)
		}

		replyText := "BobAcknowledgmentSecretReply_" + strconv.Itoa(idx)
		if _, err := bobSession.SendChat(replyText, "BobCanaryResponder"); err != nil {
			t.Fatalf("Bob SendChat failed for reply %d: %v", idx, err)
		}

		select {
		case msg := <-aliceSession.Messages():
			body, _ := msg["body"].(string)
			if body != replyText {
				t.Fatalf("Alice received unexpected reply: got %q, want %q", body, replyText)
			}
		case <-time.After(3 * time.Second):
			t.Fatalf("Timeout waiting for Alice to receive reply %d", idx)
		}
	}

	// 2. Send sensitive binary payload from Alice to Bob
	sensitiveBinaryPattern := []byte{0xDE, 0xAD, 0xBE, 0xEF, 0xCA, 0xFE, 0xBA, 0xBE, 0x13, 0x37, 0x42, 0x99}
	largeBinaryPayload := bytes.Repeat(sensitiveBinaryPattern, 64) // 768 bytes
	if _, err := aliceSession.SendReliableBinary(largeBinaryPayload); err != nil {
		t.Fatalf("Alice SendReliableBinary failed: %v", err)
	}

	select {
	case msg := <-bobSession.Messages():
		if msg["type"] != "binary" {
			t.Fatalf("Expected type binary, got %v", msg["type"])
		}
		rawPayload, _ := msg["payload"].([]byte)
		if !bytes.Equal(rawPayload, largeBinaryPayload) {
			t.Fatal("Bob received corrupted binary payload")
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Bob to receive binary payload")
	}

	// 3. Inspect raw wire bytes captured by both sniffers
	wireBytesAlice := aliceSniffer.CapturedBytes()
	wireBytesBob := bobSniffer.CapturedBytes()
	allWireBytes := append(wireBytesAlice, wireBytesBob...)

	t.Logf("Audit: captured %d bytes on Alice side, %d bytes on Bob side", len(wireBytesAlice), len(wireBytesBob))

	// ASSERTION 0: Zero binary payload pattern leaked
	if bytes.Contains(allWireBytes, sensitiveBinaryPattern) {
		t.Fatal("CRITICAL SECURITY LEAK: Sensitive binary pattern leaked across wire!")
	}

	// ASSERTION 1: Zero plaintext canary leakage
	for _, canary := range canaries {
		if bytes.Contains(allWireBytes, []byte(canary)) {
			t.Fatalf("CRITICAL SECURITY LEAK: Canary plaintext %q found in wire traffic!", canary)
		}
		// Also test for substring leaks
		parts := strings.Split(canary, "_")
		for _, part := range parts {
			if len(part) >= 8 && bytes.Contains(allWireBytes, []byte(part)) {
				t.Fatalf("CRITICAL SECURITY LEAK: Canary fragment %q found in wire traffic!", part)
			}
		}
	}

	// ASSERTION 2: Zero internal JSON wire metadata leakage after handshake
	sensitiveJSONKeys := []string{
		"\"body\":",
		"\"nickname\":",
		"\"peer_fp\":",
		"BobAcknowledgmentSecretReply",
	}
	for _, key := range sensitiveJSONKeys {
		if bytes.Contains(allWireBytes, []byte(key)) {
			t.Fatalf("CRITICAL SECURITY LEAK: Application JSON key %q found unencrypted in wire traffic!", key)
		}
	}

	// ASSERTION 3: Zero private cryptographic keys leaked
	if bytes.Contains(allWireBytes, aliceId.Signing) {
		t.Fatal("CRITICAL SECURITY LEAK: Alice Ed25519 signing key found in wire traffic!")
	}
	if bytes.Contains(allWireBytes, bobId.Signing) {
		t.Fatal("CRITICAL SECURITY LEAK: Bob Ed25519 signing key found in wire traffic!")
	}
	if bytes.Contains(allWireBytes, alicePrekeyPriv.Bytes()) {
		t.Fatal("CRITICAL SECURITY LEAK: Alice X25519 prekey private key found in wire traffic!")
	}
	if bytes.Contains(allWireBytes, bobPrekeyPriv.Bytes()) {
		t.Fatal("CRITICAL SECURITY LEAK: Bob X25519 prekey private key found in wire traffic!")
	}

	t.Log("PASS: Deep Packet Inspection verified zero plaintext or key leakage across all wire frames.")
}
