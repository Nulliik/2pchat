package session

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

// TestResponder_DoesNotRevealIdentityToUnknownInitiator verifies SEC-03:
// When Bob runs as a responder expecting Alice (expectedFingerprint set),
// an unknown initiator (Eve) who connects and sends a validly signed init
// MUST NOT receive Bob's IdentityPub or VerifyPub in a handshake reply.
func TestResponder_DoesNotRevealIdentityToUnknownInitiator(t *testing.T) {
	bobId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}

	aliceId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	aliceFingerprint := crypto.Fingerprint(aliceId.Public.Bytes())

	eveId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	evePrekeyPriv, evePrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	eveEphPriv, eveEphPub, err := crypto.GenerateX25519Keypair()
	_ = eveEphPriv
	_ = evePrekeyPriv

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to start test listener: %v", err)
	}
	defer listener.Close()

	var bobErr error
	var wg sync.WaitGroup
	wg.Add(1)

	// Bob listens, expecting ONLY Alice
	go func() {
		defer wg.Done()
		conn, err := listener.Accept()
		if err != nil {
			bobErr = err
			return
		}
		defer conn.Close()

		_, bobErr = NewSession(
			conn,
			false, // responder
			bobId,
			bobPrekeyPriv,
			bobPrekeyPub,
			aliceFingerprint, // expected Alice
			3*time.Second,
		)
	}()

	// Eve connects to Bob and sends an init handshake
	eveConn, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatalf("Eve failed to connect to Bob: %v", err)
	}
	defer eveConn.Close()

	evePrekeySig := crypto.SignPreKey(eveId.Signing, evePrekeyPub)
	toSign := append([]byte(crypto.X3DHHandshakeContext), []byte("init")...)
	toSign = append(toSign, eveId.Public.Bytes()...)
	toSign = append(toSign, eveId.Verify...)
	toSign = append(toSign, evePrekeyPub.Bytes()...)
	toSign = append(toSign, eveEphPub.Bytes()...)
	eveSessionSig := ed25519.Sign(eveId.Signing, toSign)

	eveInitPayload := &HandshakeJSON{
		Type:            "handshake",
		Version:         crypto.HandshakeVersion,
		Role:            "init",
		IdentityPub:     base64.StdEncoding.EncodeToString(eveId.Public.Bytes()),
		VerifyPub:       base64.StdEncoding.EncodeToString(eveId.Verify),
		SignedPrekeyPub: base64.StdEncoding.EncodeToString(evePrekeyPub.Bytes()),
		PrekeySignature: base64.StdEncoding.EncodeToString(evePrekeySig),
		EphemeralPub:    base64.StdEncoding.EncodeToString(eveEphPub.Bytes()),
		Signature:       base64.StdEncoding.EncodeToString(eveSessionSig),
	}

	rawInit, err := json.Marshal(eveInitPayload)
	if err != nil {
		t.Fatal(err)
	}

	if err := transport.WriteFrame(eveConn, rawInit); err != nil {
		t.Fatalf("Eve failed to write init frame: %v", err)
	}

	// Eve tries to read Bob's reply frame
	_ = eveConn.SetReadDeadline(time.Now().Add(1 * time.Second))
	replyFrame, err := transport.ReadFrame(eveConn, transport.MaxHandshakeSize)

	// Bob must NOT have sent a valid reply containing his IdentityPub!
	if err == nil && len(replyFrame) > 0 {
		var leaked HandshakeJSON
		if jsonErr := json.Unmarshal(replyFrame, &leaked); jsonErr == nil {
			t.Fatalf("SEC-03 VIOLATION: Bob leaked IdentityPub (%s) to unauthorized peer Eve before authentication!", leaked.IdentityPub)
		}
		t.Fatalf("SEC-03 VIOLATION: Bob sent non-empty frame (%d bytes) to unauthorized peer Eve", len(replyFrame))
	}

	wg.Wait()
	if bobErr == nil {
		t.Fatal("Expected Bob NewSession to fail with fingerprint mismatch, but got nil error")
	}
}

// TestResponder_PeerValidator_PreReplyDenial_ZeroIdentityLeaked verifies SEC-03:
// When an incoming connection arrives without an expectedFingerprint (as occurs in Manager's listener),
// the responder's WithPeerValidator option validates the initiator's fingerprint extracted from the init
// frame BEFORE generating or transmitting the reply frame.
// If the validator rejects the peer (e.g. policy denial), the connection is closed immediately
// and ZERO bytes of Bob's identity are transmitted over the wire.
func TestResponder_PeerValidator_PreReplyDenial_ZeroIdentityLeaked(t *testing.T) {
	bobId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}

	eveId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	evePrekeyPriv, evePrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	eveEphPriv, eveEphPub, err := crypto.GenerateX25519Keypair()
	_ = eveEphPriv
	_ = evePrekeyPriv

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to start test listener: %v", err)
	}
	defer listener.Close()

	var bobErr error
	var wg sync.WaitGroup
	wg.Add(1)

	// Bob listens accepting any initiator (""), but configures a peerValidator callback that denies Eve
	go func() {
		defer wg.Done()
		conn, err := listener.Accept()
		if err != nil {
			bobErr = err
			return
		}
		defer conn.Close()

		peerValidator := func(peerFP string) error {
			// Reject Eve
			if peerFP == crypto.Fingerprint(eveId.Public.Bytes()) {
				return errors.New("peer is prohibited by transport policy")
			}
			return nil
		}

		_, bobErr = NewSession(
			conn,
			false, // responder
			bobId,
			bobPrekeyPriv,
			bobPrekeyPub,
			"", // no expected fingerprint upfront
			3*time.Second,
			WithPeerValidator(peerValidator),
		)
	}()

	// Eve connects to Bob and sends an init handshake
	eveConn, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatalf("Eve failed to connect to Bob: %v", err)
	}
	defer eveConn.Close()

	evePrekeySig := crypto.SignPreKey(eveId.Signing, evePrekeyPub)
	toSign := append([]byte(crypto.X3DHHandshakeContext), []byte("init")...)
	toSign = append(toSign, eveId.Public.Bytes()...)
	toSign = append(toSign, eveId.Verify...)
	toSign = append(toSign, evePrekeyPub.Bytes()...)
	toSign = append(toSign, eveEphPub.Bytes()...)
	eveSessionSig := ed25519.Sign(eveId.Signing, toSign)

	eveInitPayload := &HandshakeJSON{
		Type:            "handshake",
		Version:         crypto.HandshakeVersion,
		Role:            "init",
		IdentityPub:     base64.StdEncoding.EncodeToString(eveId.Public.Bytes()),
		VerifyPub:       base64.StdEncoding.EncodeToString(eveId.Verify),
		SignedPrekeyPub: base64.StdEncoding.EncodeToString(evePrekeyPub.Bytes()),
		PrekeySignature: base64.StdEncoding.EncodeToString(evePrekeySig),
		EphemeralPub:    base64.StdEncoding.EncodeToString(eveEphPub.Bytes()),
		Signature:       base64.StdEncoding.EncodeToString(eveSessionSig),
	}

	rawInit, err := json.Marshal(eveInitPayload)
	if err != nil {
		t.Fatal(err)
	}

	if err := transport.WriteFrame(eveConn, rawInit); err != nil {
		t.Fatalf("Eve failed to write init frame: %v", err)
	}

	// Eve tries to read Bob's reply frame
	_ = eveConn.SetReadDeadline(time.Now().Add(1 * time.Second))
	replyFrame, err := transport.ReadFrame(eveConn, transport.MaxHandshakeSize)

	// Bob MUST NOT have sent a reply frame!
	if err == nil && len(replyFrame) > 0 {
		var leaked HandshakeJSON
		if jsonErr := json.Unmarshal(replyFrame, &leaked); jsonErr == nil {
			t.Fatalf("SEC-03 CRITICAL VIOLATION: Bob leaked IdentityPub (%s) to prohibited peer before policy validation!", leaked.IdentityPub)
		}
		t.Fatalf("SEC-03 VIOLATION: Bob transmitted %d bytes to prohibited peer instead of closing socket", len(replyFrame))
	}

	wg.Wait()
	if bobErr == nil {
		t.Fatal("Expected Bob NewSession to fail with policy rejection, but got nil error")
	}
}

// TestManager_GlobalPolicyTorStrict_InboundClearnet_DropsBeforeHandshake verifies SEC-03:
// When Bob's manager is configured in Tor Strict mode, an incoming clearnet connection is dropped
// immediately BEFORE any handshake frames are processed or cryptographic operations run.
func TestManager_GlobalPolicyTorStrict_InboundClearnet_DropsBeforeHandshake(t *testing.T) {
	bobId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}

	var rejectedCount int32
	callbacks := EventCallbacks{
		OnError: func(code int, msg string) {
			atomic.AddInt32(&rejectedCount, 1)
		},
	}

	bobMgr := NewManager(bobId, bobPrekeyPriv, bobPrekeyPub, "127.0.0.1:9050", false, callbacks)
	// Put Bob in Tor Strict mode
	bobMgr.ApplyPolicy(transport.PolicyTorStrict)

	if err := bobMgr.StartListener(0); err != nil {
		t.Fatalf("StartListener failed: %v", err)
	}
	defer func() { _ = bobMgr.StopListener() }()

	bobAddr := fmt.Sprintf("127.0.0.1:%d", bobMgr.Port())

	// Attacker connects over clearnet TCP
	conn, err := net.DialTimeout("tcp", bobAddr, 2*time.Second)
	if err != nil {
		t.Fatalf("Failed to dial Bob listener: %v", err)
	}
	defer conn.Close()

	// Attacker tries to read from socket. Bob should close it immediately.
	buf := make([]byte, 256)
	_ = conn.SetReadDeadline(time.Now().Add(1 * time.Second))
	n, readErr := conn.Read(buf)

	if n > 0 {
		t.Fatalf("SEC-03 VIOLATION: Attacker read %d bytes from Bob in Tor Strict mode over clearnet", n)
	}
	if readErr == nil {
		t.Fatal("Expected read from closed connection to return EOF or error, got nil")
	}

	// Verify Bob's error callback fired for policy rejection
	time.Sleep(100 * time.Millisecond)
	if atomic.LoadInt32(&rejectedCount) == 0 {
		t.Fatal("Expected OnError to fire for rejected inbound clearnet connection in Tor Strict mode")
	}
}

