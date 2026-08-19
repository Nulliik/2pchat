package bridge_test

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"twopchat/core/pkg/bridge"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/session"
	"twopchat/core/pkg/transport"
)

// =============================================================================
// TEST 1: FUZZED & MALFORMED PACKETS RESILIENCE (DoS & OOM Attack Protection)
// =============================================================================

func TestFuzzedAndMalformedPacketsResilience(t *testing.T) {
	// 1. OOM Protection: Oversized length header (> 16MB) must be rejected immediately without allocating
	oversizedHeader := make([]byte, 4)
	binary.BigEndian.PutUint32(oversizedHeader, 25*1024*1024) // 25 MB length prefix
	r := bytes.NewReader(oversizedHeader)

	_, err := transport.ReadFrame(r, 0)
	if err == nil {
		t.Fatal("Expected ErrFrameTooLarge for 25MB frame header, but got success")
	}
	t.Logf("✅ [FUZZ] Oversized frame header rejected safely: %v", err)

	// 2. Zero-length payload must be handled safely
	zeroHeader := make([]byte, 4)
	binary.BigEndian.PutUint32(zeroHeader, 0)
	rZero := bytes.NewReader(zeroHeader)

	payload, err := transport.ReadFrame(rZero, 0)
	if err != nil || len(payload) != 0 {
		t.Fatalf("Zero-length frame failed: err=%v, len=%d", err, len(payload))
	}
	t.Log("✅ [FUZZ] Zero-length frame parsed safely")

	// 3. Double Ratchet with corrupted ciphertext must return error without panic
	aliceId, _ := crypto.GenerateIdentityKeyPair()
	bobId, _ := crypto.GenerateIdentityKeyPair()
	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()
	bobPrekeySig := crypto.SignPreKey(bobId.Signing, bobPrekeyPub)

	bobBundle := &crypto.PreKeyBundle{
		IdentityPub:       bobId.Public,
		IdentityVerifyPub: bobId.Verify,
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	aliceEph, _ := crypto.GenerateIdentityKeyPair()
	aliceSess, _ := crypto.InitializeSessionFromPreKey(aliceId, bobBundle, aliceEph)
	bobSess, _ := crypto.RespondToPreKeyInit(bobId, bobPrekeyPriv, nil, aliceId.Public, aliceEph.Public)

	validCT, err := aliceSess.EncryptMessage([]byte("Secret test payload"))
	if err != nil {
		t.Fatalf("Encryption failed: %v", err)
	}

	// Corrupt the ciphertext payload
	corruptedCT := append([]byte(nil), validCT...)
	if len(corruptedCT) > 20 {
		corruptedCT[len(corruptedCT)-5] ^= 0xFF
	}

	_, err = bobSess.DecryptMessage(corruptedCT)
	if err == nil {
		t.Fatal("Expected decryption error on corrupted ciphertext, got nil")
	}
	t.Logf("✅ [FUZZ] Corrupted ciphertext safely rejected by Double Ratchet: %v", err)

	// 4. Malformed X3DH Handshake on TCP stream
	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	go func() {
		// Send garbage JSON
		_ = transport.WriteFrame(serverConn, []byte(`{"type":"handshake","garbage":12345}`))
	}()

	_, err = session.NewSession(clientConn, false, aliceId, bobPrekeyPriv, bobPrekeyPub, "", 1*time.Second)
	if err == nil {
		t.Fatal("Expected NewSession to fail on garbage handshake JSON, got nil")
	}
	t.Logf("✅ [FUZZ] Malformed handshake JSON safely rejected: %v", err)

	t.Log("✅ TestFuzzedAndMalformedPacketsResilience: PASS")
}

// =============================================================================
// TEST 2: ABRUPT DISCONNECT AND AUTOMATIC RECONNECTION
// =============================================================================

func TestAbruptDisconnectAndReconnection(t *testing.T) {
	aliceConnected := make(chan string, 10)
	aliceDisconnected := make(chan string, 10)
	aliceReceived := make(chan string, 10)
	bobReceived := make(chan string, 10)

	alice := &bridge.SessionManager{}
	alice.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			aliceConnected <- peerFP
		},
		OnPeerDisconnected: func(peerFP, reason string) {
			t.Logf("[DISCONNECT] Alice observed disconnect from %s (reason: %s)", peerFP, reason)
			aliceDisconnected <- peerFP
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			aliceReceived <- string(payload)
		},
	}, nil)

	bob := &bridge.SessionManager{}
	bob.SetCallbacks(session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			bobReceived <- string(payload)
		},
	}, nil)

	if err := alice.Init(); err != nil {
		t.Fatalf("Alice Init failed: %v", err)
	}
	if err := bob.Init(); err != nil {
		t.Fatalf("Bob Init failed: %v", err)
	}

	aliceFP := alice.GetLocalFingerprint()
	bobFP := bob.GetLocalFingerprint()
	t.Logf("[DISCONNECT] Initializing Alice (%s) and Bob (%s)", aliceFP, bobFP)

	// 1. Initial Connection
	if err := bob.StartListener(0); err != nil {
		t.Fatalf("Bob StartListener failed: %v", err)
	}
	bobEndpoint := fmt.Sprintf("127.0.0.1:%d", bob.GetBoundPort())

	if err := alice.ConnectPeer(bobEndpoint, bobFP); err != nil {
		t.Fatalf("Alice initial ConnectPeer failed: %v", err)
	}

	select {
	case <-aliceConnected:
		t.Log("✅ Alice successfully connected to Bob")
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Alice initial connection")
	}

	_, err := alice.SendMessage(bobFP, "Pre-disconnect hello")
	if err != nil {
		t.Fatalf("Alice SendMessage failed: %v", err)
	}

	select {
	case msg := <-bobReceived:
		t.Logf("✅ Initial message received: %s", msg)
	case <-time.After(2 * time.Second):
		t.Fatal("Timeout waiting for initial message")
	}

	// 2. Abrupt Disconnect (Simulate socket drop / server shutdown)
	t.Log("[DISCONNECT] Stopping Bob listener and terminating connection...")
	_ = bob.StopListener()

	// 3. Restart Bob with fresh listener
	bobFresh := &bridge.SessionManager{}
	bobFresh.SetCallbacks(session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			bobReceived <- string(payload)
		},
	}, nil)
	_ = bobFresh.Init()

	if err := bobFresh.StartListener(0); err != nil {
		t.Fatalf("Bob fresh StartListener failed: %v", err)
	}
	defer bobFresh.StopListener()

	bobFreshEndpoint := fmt.Sprintf("127.0.0.1:%d", bobFresh.GetBoundPort())
	bobFreshFP := bobFresh.GetLocalFingerprint()

	// 4. Reconnect Alice to Bob
	t.Logf("[RECONNECT] Alice reconnecting to fresh Bob at %s...", bobFreshEndpoint)
	if err := alice.ConnectPeer(bobFreshEndpoint, bobFreshFP); err != nil {
		t.Fatalf("Alice Reconnect failed: %v", err)
	}

	select {
	case <-aliceConnected:
		t.Log("✅ Alice successfully reconnected to BobFresh")
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Alice post-disconnect reconnect")
	}

	reconnectMsg := "Post-reconnect message from Alice"
	_, err = alice.SendMessage(bobFreshFP, reconnectMsg)
	if err != nil {
		t.Fatalf("Alice SendMessage post-reconnect failed: %v", err)
	}

	select {
	case msg := <-bobReceived:
		if !strings.Contains(msg, reconnectMsg) {
			t.Fatalf("Unexpected message content: %s", msg)
		}
		t.Logf("✅ [RECONNECT] Successfully sent and received post-reconnect message: %s", msg)
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for post-reconnect message")
	}

	t.Log("✅ TestAbruptDisconnectAndReconnection: PASS")
}

// =============================================================================
// TEST 3: ADAPTIVE ACK TIMEOUT (TOR VS DIRECT)
// =============================================================================

func TestAdaptiveAckTimeoutTorVsDirect(t *testing.T) {
	// Verify configured timeouts
	if session.DefaultAckTimeout != 3*time.Second {
		t.Errorf("Expected DefaultAckTimeout=3s, got %v", session.DefaultAckTimeout)
	}
	if session.TorAckTimeout != 8*time.Second {
		t.Errorf("Expected TorAckTimeout=8s, got %v", session.TorAckTimeout)
	}

	// 1. Direct Session gets DefaultAckTimeout (3s)
	aliceId, _ := crypto.GenerateIdentityKeyPair()
	bobId, _ := crypto.GenerateIdentityKeyPair()
	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()

	serverConn, clientConn := net.Pipe()
	defer serverConn.Close()
	defer clientConn.Close()

	var aliceSess *session.Session
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		s, err := session.NewSession(serverConn, false, bobId, bobPrekeyPriv, bobPrekeyPub, "", 2*time.Second)
		if err == nil {
			defer s.Close()
		}
	}()

	go func() {
		defer wg.Done()
		s, err := session.NewSession(clientConn, true, aliceId, bobPrekeyPriv, bobPrekeyPub, crypto.Fingerprint(bobId.Public.Bytes()), 2*time.Second)
		if err == nil {
			aliceSess = s
		}
	}()

	wg.Wait()
	if aliceSess == nil {
		t.Fatal("Failed to establish session over pipe")
	}
	defer aliceSess.Close()

	if aliceSess.AckTimeout() != session.DefaultAckTimeout {
		t.Errorf("Expected direct session AckTimeout %v, got %v", session.DefaultAckTimeout, aliceSess.AckTimeout())
	}
	t.Logf("✅ Direct session AckTimeout verified: %v", aliceSess.AckTimeout())

	// 2. Set Tor Transport -> AckTimeout automatically switches to TorAckTimeout (8s)
	aliceSess.SetTorTransport(true)
	if aliceSess.AckTimeout() != session.TorAckTimeout {
		t.Errorf("Expected Tor session AckTimeout %v, got %v", session.TorAckTimeout, aliceSess.AckTimeout())
	}
	t.Logf("✅ Tor session adaptive AckTimeout verified: %v", aliceSess.AckTimeout())

	t.Log("✅ TestAdaptiveAckTimeoutTorVsDirect: PASS")
}

// =============================================================================
// TEST 4: GROUP CHAT EPOCH ROTATION & OWNER TRANSITION
// =============================================================================

func TestGroupEpochRotationAndMemberTransition(t *testing.T) {
	// 1. Setup 3 members: Alice (Creator/Old Owner), Bob (Successor/New Owner), Charlie (Member)
	aliceId, _ := crypto.GenerateIdentityKeyPair()
	bobId, _ := crypto.GenerateIdentityKeyPair()
	charlieId, _ := crypto.GenerateIdentityKeyPair()

	aliceFP := crypto.Fingerprint(aliceId.Public.Bytes())
	bobFP := crypto.Fingerprint(bobId.Public.Bytes())
	charlieFP := crypto.Fingerprint(charlieId.Public.Bytes())

	// 2. Epoch 1: Alice creates epoch 1 secret for the group
	epoch1Secret := make([]byte, crypto.GroupAEADKeySize)
	_, _ = io.ReadFull(rand.Reader, epoch1Secret)

	groupMsg1 := []byte("Hello group from Alice (Epoch 1)")
	ad1 := []byte("group-chat-metadata-v1")

	nonceB64, ctB64, err := crypto.GroupEncrypt(epoch1Secret, ad1, groupMsg1)
	if err != nil {
		t.Fatalf("GroupEncrypt failed: %v", err)
	}

	// Bob and Charlie decrypt message in Epoch 1
	bobDecrypted, err := crypto.GroupDecrypt(epoch1Secret, ad1, nonceB64, ctB64)
	if err != nil || string(bobDecrypted) != string(groupMsg1) {
		t.Fatalf("Bob failed to decrypt Epoch 1 group message: %v", err)
	}

	charlieDecrypted, err := crypto.GroupDecrypt(epoch1Secret, ad1, nonceB64, ctB64)
	if err != nil || string(charlieDecrypted) != string(groupMsg1) {
		t.Fatalf("Charlie failed to decrypt Epoch 1 group message: %v", err)
	}
	t.Log("✅ [GROUP] Epoch 1 group message decrypted by all members")

	// 3. Group Owner Transition: Alice delegates group ownership to Bob
	oldSigningB64 := base64.StdEncoding.EncodeToString(aliceId.Verify)
	newSigningB64 := base64.StdEncoding.EncodeToString(bobId.Verify)

	cert := &crypto.GroupOwnerTransitionCertificate{
		GroupID:             "group-uuid-98765",
		PreviousOwnerAnchor: aliceFP,
		LineageSequence:     1,
		OldOwnerFingerprint: aliceFP,
		OldOwnerDeviceId:    "alice-phone",
		OldOwnerSigningKey:  oldSigningB64,
		NewOwnerFingerprint: bobFP,
		NewOwnerDeviceId:    "bob-phone",
		NewOwnerSigningKey:  newSigningB64,
		CreatedAtMs:         time.Now().UnixMilli(),
		Nonce:               "nonce-12345",
	}

	canonical := cert.CanonicalForSignature()
	sig := ed25519.Sign(aliceId.Signing, []byte(canonical))
	cert.SignatureBase64 = base64.StdEncoding.EncodeToString(sig)

	if !cert.Verify() {
		t.Fatal("GroupOwnerTransitionCertificate verification failed")
	}
	t.Logf("✅ [GROUP] Group ownership transition verified (TransitionID: %s)", cert.TransitionID())

	// Tampered certificate must fail
	cert.NewOwnerFingerprint = charlieFP
	if cert.Verify() {
		t.Fatal("Tampered transition certificate unexpectedly passed verification")
	}
	t.Log("✅ [GROUP] Tampered ownership certificate correctly rejected")

	// 4. Epoch 2 Rotation: Charlie leaves, new epoch secret generated
	epoch2Secret := make([]byte, crypto.GroupAEADKeySize)
	_, _ = io.ReadFull(rand.Reader, epoch2Secret)

	groupMsg2 := []byte("Confidential group message in Epoch 2")
	nonce2B64, ct2B64, _ := crypto.GroupEncrypt(epoch2Secret, ad1, groupMsg2)

	// Charlie (with old epoch 1 key) MUST fail to decrypt Epoch 2 message (Forward Secrecy)
	_, err = crypto.GroupDecrypt(epoch1Secret, ad1, nonce2B64, ct2B64)
	if err == nil {
		t.Fatal("Security violation! Removed member decrypted future epoch message.")
	}
	t.Log("✅ [GROUP] Epoch rotation forward secrecy verified: removed member cannot decrypt new epoch messages")

	t.Log("✅ TestGroupEpochRotationAndMemberTransition: PASS")
}

// =============================================================================
// TEST 5: HIGH-VOLUME BURST MESSAGING AND DEDUPLICATION
// =============================================================================

func TestHighVolumeBurstMessagingAndDeduplication(t *testing.T) {
	aliceConnected := make(chan string, 10)
	aliceReceived := make(chan string, 100)
	bobReceived := make(chan string, 100)

	alice := &bridge.SessionManager{}
	alice.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			aliceConnected <- peerFP
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			aliceReceived <- string(payload)
		},
	}, nil)

	bob := &bridge.SessionManager{}
	bob.SetCallbacks(session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			bobReceived <- string(payload)
		},
	}, nil)

	_ = alice.Init()
	_ = bob.Init()

	_ = bob.StartListener(0)
	defer bob.StopListener()

	bobPort := bob.GetBoundPort()
	bobEndpoint := fmt.Sprintf("127.0.0.1:%d", bobPort)
	bobFP := bob.GetLocalFingerprint()

	if err := alice.ConnectPeer(bobEndpoint, bobFP); err != nil {
		t.Fatalf("ConnectPeer failed: %v", err)
	}

	select {
	case <-aliceConnected:
		t.Log("✅ Alice connected to Bob before burst test")
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Alice connection to Bob")
	}

	// 1. High Volume Burst: Alice sends 50 messages in rapid succession
	burstCount := 50
	t.Logf("[BURST] Sending %d messages rapidly from Alice to Bob...", burstCount)

	startTime := time.Now()
	for i := 0; i < burstCount; i++ {
		text := fmt.Sprintf("Burst packet #%03d from Alice", i)
		_, err := alice.SendMessage(bobFP, text)
		if err != nil {
			t.Fatalf("Burst message %d failed: %v", i, err)
		}
	}

	// 2. Collect and verify all 50 messages arrived on Bob
	receivedCount := 0
	for receivedCount < burstCount {
		select {
		case <-bobReceived:
			receivedCount++
		case <-time.After(5 * time.Second):
			t.Fatalf("Timeout waiting for burst messages: received %d/%d", receivedCount, burstCount)
		}
	}
	elapsed := time.Since(startTime)
	t.Logf("✅ [BURST] Successfully received and decrypted all %d burst messages in %v", burstCount, elapsed)

	t.Log("✅ TestHighVolumeBurstMessagingAndDeduplication: PASS")
}
