package session

import (
	"bytes"
	"crypto/ed25519"
	"net"
	"sync"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

func TestPeerActorLifecycleAndMessaging(t *testing.T) {
	// Generate Alice & Bob keys
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	bobID, _ := crypto.GenerateIdentityKeyPair()
	defer aliceID.Zeroize()
	defer bobID.Zeroize()

	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()
	defer crypto.Zeroize(bobPrekeyPriv[:])

	aliceFP := crypto.Fingerprint(aliceID.Public.Bytes())
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	aliceEph, _ := crypto.GenerateIdentityKeyPair()

	bobBundle := &crypto.PreKeyBundle{
		IdentityPub:       bobID.Public,
		IdentityVerifyPub: bobID.Signing.Public().(ed25519.PublicKey),
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   crypto.SignPreKey(bobID.Signing, bobPrekeyPub),
	}

	aliceDR, err := crypto.InitializeSessionFromPreKey(aliceID, bobBundle, aliceEph)
	if err != nil {
		t.Fatalf("InitializeSessionFromPreKey failed: %v", err)
	}

	bobDR, err := crypto.RespondToPreKeyInit(
		bobID,
		bobPrekeyPriv,
		nil,
		aliceID.Public,
		aliceEph.Public,
	)
	if err != nil {
		t.Fatalf("RespondToPreKeyInit failed: %v", err)
	}

	// Create in-memory network pipe
	aliceConn, bobConn := net.Pipe()

	// Initialize Yamux multiplexer on both sides
	aliceMux, err := transport.NewMultiplexedSession(aliceConn, false)
	if err != nil {
		t.Fatalf("NewMultiplexedSession Alice failed: %v", err)
	}
	defer func() { _ = aliceMux.Close() }()

	bobMux, err := transport.NewMultiplexedSession(bobConn, true)
	if err != nil {
		t.Fatalf("NewMultiplexedSession Bob failed: %v", err)
	}
	defer func() { _ = bobMux.Close() }()

	var bobReceivedMu sync.Mutex
	var bobReceivedPayloads [][]byte

	bobCallbacks := EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
			bobReceivedMu.Lock()
			bobReceivedPayloads = append(bobReceivedPayloads, append([]byte(nil), payload...))
			bobReceivedMu.Unlock()
		},
	}

	aliceCallbacks := EventCallbacks{}

	// Spawn Alice & Bob Actors
	aliceActor, err := NewPeerActor(bobFP, "pipe-alice", true, false, aliceDR, aliceMux, aliceCallbacks)
	if err != nil {
		t.Fatalf("NewPeerActor Alice failed: %v", err)
	}
	defer func() { _ = aliceActor.Close("test done") }()

	bobActor, err := NewPeerActor(aliceFP, "pipe-bob", false, false, bobDR, bobMux, bobCallbacks)
	if err != nil {
		t.Fatalf("NewPeerActor Bob failed: %v", err)
	}
	defer func() { _ = bobActor.Close("test done") }()

	// 1. Send Chat Message from Alice to Bob Actor
	msgID, err := aliceActor.SendChat("Hello Bob from Alice Actor!", "Alice")
	if err != nil {
		t.Fatalf("SendChat failed: %v", err)
	}
	if msgID == "" {
		t.Fatalf("Expected valid msgID from SendChat")
	}

	// 2. Send Binary Message from Alice to Bob Actor
	rawBinary := []byte{0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03}
	msgID2, err := aliceActor.SendBinary(rawBinary)
	if err != nil {
		t.Fatalf("SendBinary failed: %v", err)
	}
	if msgID2 == "" {
		t.Fatalf("Expected valid msgID from SendBinary")
	}

	// Wait for Bob to receive
	deadline := time.Now().Add(3 * time.Second)
	for {
		bobReceivedMu.Lock()
		count := len(bobReceivedPayloads)
		bobReceivedMu.Unlock()
		if count >= 2 || time.Now().After(deadline) {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}

	bobReceivedMu.Lock()
	defer bobReceivedMu.Unlock()
	if len(bobReceivedPayloads) < 2 {
		t.Fatalf("Expected Bob to receive 2 messages, got %d", len(bobReceivedPayloads))
	}

	if !bytes.Equal(bobReceivedPayloads[1], rawBinary) {
		t.Errorf("Binary payload mismatch: got %v, want %v", bobReceivedPayloads[1], rawBinary)
	}
}

func TestPeerActorAuxiliaryStreamClosureDoesNotTerminateActor(t *testing.T) {
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	bobID, _ := crypto.GenerateIdentityKeyPair()
	defer aliceID.Zeroize()
	defer bobID.Zeroize()

	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()
	defer crypto.Zeroize(bobPrekeyPriv[:])

	aliceFP := crypto.Fingerprint(aliceID.Public.Bytes())
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	aliceEph, _ := crypto.GenerateIdentityKeyPair()

	bobBundle := &crypto.PreKeyBundle{
		IdentityPub:       bobID.Public,
		IdentityVerifyPub: bobID.Signing.Public().(ed25519.PublicKey),
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   crypto.SignPreKey(bobID.Signing, bobPrekeyPub),
	}

	aliceDR, _ := crypto.InitializeSessionFromPreKey(aliceID, bobBundle, aliceEph)
	bobDR, _ := crypto.RespondToPreKeyInit(bobID, bobPrekeyPriv, nil, aliceID.Public, aliceEph.Public)

	aliceConn, bobConn := net.Pipe()
	aliceMux, _ := transport.NewMultiplexedSession(aliceConn, false)
	defer func() { _ = aliceMux.Close() }()
	bobMux, _ := transport.NewMultiplexedSession(bobConn, true)
	defer func() { _ = bobMux.Close() }()

	var bobDisconnected bool
	var bobMu sync.Mutex

	bobCallbacks := EventCallbacks{
		OnPeerDisconnected: func(peerFP, reason string) {
			bobMu.Lock()
			bobDisconnected = true
			bobMu.Unlock()
		},
	}

	aliceActor, _ := NewPeerActor(bobFP, "pipe-alice", true, false, aliceDR, aliceMux, EventCallbacks{})
	defer func() { _ = aliceActor.Close("test done") }()

	bobActor, _ := NewPeerActor(aliceFP, "pipe-bob", false, false, bobDR, bobMux, bobCallbacks)
	defer func() { _ = bobActor.Close("test done") }()

	// Alice opens a secondary auxiliary stream (e.g. StreamTypeFile), sends some bytes, and closes it
	auxStream, err := aliceMux.OpenStream(transport.StreamTypeFile)
	if err != nil {
		t.Fatalf("OpenStream failed: %v", err)
	}
	_ = auxStream.Close()

	// Give time for reader loop on auxStream to hit EOF
	time.Sleep(50 * time.Millisecond)

	bobMu.Lock()
	disc := bobDisconnected
	bobMu.Unlock()

	if disc {
		t.Fatalf("Bob actor disconnected prematurely when auxiliary stream was closed")
	}

	if !bobActor.IsOnline() {
		t.Fatalf("Bob actor online status is false after auxiliary stream close")
	}
}
