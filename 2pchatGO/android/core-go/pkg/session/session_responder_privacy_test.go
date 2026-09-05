package session

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net"
	"sync"
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
