package bridge

import (
	"bytes"
	"fmt"
	"net"
	"sync"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/session"
)

// TestBinaryIPCContract verifies the contract of SendMessageBinary over active sessions.
func TestBinaryIPCContract(t *testing.T) {
	aliceID, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("Failed to generate Alice identity: %v", err)
	}
	defer aliceID.Zeroize()

	bobID, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("Failed to generate Bob identity: %v", err)
	}
	defer bobID.Zeroize()

	alicePrekeyPriv, alicePrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("Failed to generate Alice prekey: %v", err)
	}
	defer crypto.Zeroize(alicePrekeyPriv[:])

	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("Failed to generate Bob prekey: %v", err)
	}
	defer crypto.Zeroize(bobPrekeyPriv[:])

	aliceFP := crypto.Fingerprint(aliceID.Public.Bytes())
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	var receivedMu sync.Mutex
	var receivedPayloads [][]byte
	var receivedIDs []string

	aliceMgr := session.NewManager(aliceID, alicePrekeyPriv, alicePrekeyPub, "127.0.0.1:9050", false, session.EventCallbacks{
		OnError: func(code int, msg string) {
			t.Logf("[Alice ERROR %d] %s", code, msg)
		},
	})
	defer func() { _ = aliceMgr.StopListener() }()

	bobMgr := session.NewManager(bobID, bobPrekeyPriv, bobPrekeyPub, "127.0.0.1:9050", false, session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
			receivedMu.Lock()
			receivedPayloads = append(receivedPayloads, append([]byte(nil), payload...))
			receivedIDs = append(receivedIDs, messageID)
			receivedMu.Unlock()
		},
	})
	defer func() { _ = bobMgr.StopListener() }()

	// Establish local in-memory pipe
	aliceConn, bobConn := net.Pipe()

	errChan := make(chan error, 2)
	go func() {
		sess, err := session.NewSession(aliceConn, true, aliceID, alicePrekeyPriv, alicePrekeyPub, bobFP, 5*time.Second)
		if err != nil {
			errChan <- fmt.Errorf("Alice session error: %w", err)
			return
		}
		aliceMgr.RegisterSession(sess, bobFP, "pipe-alice", true)
		errChan <- nil
	}()

	go func() {
		sess, err := session.NewSession(bobConn, false, bobID, bobPrekeyPriv, bobPrekeyPub, aliceFP, 5*time.Second)
		if err != nil {
			errChan <- fmt.Errorf("Bob session error: %w", err)
			return
		}
		bobMgr.RegisterSession(sess, aliceFP, "pipe-bob", false)
		errChan <- nil
	}()

	for i := 0; i < 2; i++ {
		if err := <-errChan; err != nil {
			t.Fatalf("Handshake failed: %v", err)
		}
	}

	// 1. Contract: Empty binary payload
	emptyPayload := []byte{}
	msgID, err := aliceMgr.SendMessageBinary(bobFP, emptyPayload)
	if err != nil {
		t.Fatalf("Expected empty payload to succeed over binary channel, got err: %v", err)
	}
	if msgID == "" {
		t.Fatalf("Expected non-empty message ID for empty binary payload")
	}

	// 2. Contract: Arbitrary binary buffer (Simulated Protobuf/FlatBuffers packet)
	binaryData := []byte{0x08, 0x96, 0x01, 0x12, 0x16, 0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x20, 0x50, 0x72, 0x6f, 0x74, 0x6f, 0x62, 0x75, 0x66, 0x20, 0x50, 0x32, 0x50, 0x21}
	msgID2, err := aliceMgr.SendMessageBinary(bobFP, binaryData)
	if err != nil {
		t.Fatalf("SendMessageBinary failed for protobuf data: %v", err)
	}
	if msgID2 == "" {
		t.Fatalf("Expected non-empty message ID for protobuf data")
	}

	// 3. Contract: Large binary chunk (128 KB)
	largeData := bytes.Repeat([]byte{0xAA, 0x55, 0xDE, 0xAD, 0xBE, 0xEF}, 21845) // ~128KB
	msgID3, err := aliceMgr.SendMessageBinary(bobFP, largeData)
	if err != nil {
		t.Fatalf("SendMessageBinary failed for 128KB payload: %v", err)
	}
	if msgID3 == "" {
		t.Fatalf("Expected non-empty message ID for 128KB payload")
	}

	// Verify all 3 messages arrived without corruption
	deadline := time.Now().Add(3 * time.Second)
	for {
		receivedMu.Lock()
		count := len(receivedPayloads)
		receivedMu.Unlock()
		if count >= 3 || time.Now().After(deadline) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	receivedMu.Lock()
	defer receivedMu.Unlock()
	if len(receivedPayloads) < 3 {
		t.Fatalf("Expected 3 received payloads, got %d", len(receivedPayloads))
	}

	if !bytes.Equal(receivedPayloads[0], emptyPayload) {
		t.Errorf("Payload 0 mismatch: expected empty, got %d bytes", len(receivedPayloads[0]))
	}
	if !bytes.Equal(receivedPayloads[1], binaryData) {
		t.Errorf("Payload 1 mismatch: expected protobuf bytes, got %v", receivedPayloads[1])
	}
	if !bytes.Equal(receivedPayloads[2], largeData) {
		t.Errorf("Payload 2 mismatch: 128KB data corruption detected")
	}
}
