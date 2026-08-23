package bridge

import (
	"bytes"
	"crypto/rand"
	"testing"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

// TestRedTeamMalformedAndOversizedPackets tests core resilience against malformed, truncated, and attack inputs.
func TestRedTeamMalformedAndOversizedPackets(t *testing.T) {
	// 1. Zero-byte packet decryption
	cipherState := crypto.NewNoiseCipherState()
	defer cipherState.Zeroize()

	emptyDec, err := cipherState.DecryptWithAd(nil, []byte{})
	if err != nil {
		t.Fatalf("Expected empty unencrypted cipher state to return empty, got %v", err)
	}
	if len(emptyDec) != 0 {
		t.Errorf("Expected 0 bytes, got %d", len(emptyDec))
	}

	// 2. Truncated Noise packet
	fakeKey := make([]byte, 32)
	_, _ = rand.Read(fakeKey)
	cipherState.InitializeKey(fakeKey)

	_, err = cipherState.DecryptWithAd(nil, []byte{0x01, 0x02, 0x03})
	if err == nil {
		t.Errorf("Expected authentication failure on truncated ciphertext")
	}

	// 3. Fuzzed Noise Handshake packets
	alicePriv, _, _ := crypto.GenerateX25519Keypair()
	bobPriv, bobPub, _ := crypto.GenerateX25519Keypair()
	defer crypto.Zeroize(alicePriv.Bytes())
	defer crypto.Zeroize(bobPriv.Bytes())

	aliceHS, _ := crypto.NewNoiseIKHandshake(true, alicePriv, bobPub)
	defer aliceHS.Zeroize()

	msg1, err := aliceHS.StepInitiatorMsg1([]byte("Attack test"))
	if err != nil {
		t.Fatalf("StepInitiatorMsg1 failed: %v", err)
	}

	// Try bitflips across 50 random positions
	for i := 0; i < 50; i++ {
		corrupted := append([]byte(nil), msg1...)
		corrupted[i%len(corrupted)] ^= 0x5A

		bobHS, _ := crypto.NewNoiseIKHandshake(false, bobPriv, nil)
		_, _, _, err := bobHS.StepResponderMsg1(corrupted, []byte("Reply"))
		bobHS.Zeroize()
		if err == nil {
			t.Errorf("Security invariant violated: Bob accepted corrupted Msg1 at byte offset %d", i%len(corrupted))
		}
	}
}

// TestRedTeamIdentityAndSafetyNumberInvariants verifies identity persistence and MitM tamper detection.
func TestRedTeamIdentityAndSafetyNumberInvariants(t *testing.T) {
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	bobID, _ := crypto.GenerateIdentityKeyPair()
	defer aliceID.Zeroize()
	defer bobID.Zeroize()

	aliceFP := crypto.Fingerprint(aliceID.Public.Bytes())
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	if len(aliceFP) != 44 || len(bobFP) != 44 {
		t.Fatalf("Base64 Fingerprint length invariant violated: expected 44 chars, got %d", len(aliceFP))
	}

	aliceHex := crypto.FingerprintHex(aliceID.Public.Bytes())
	bobHex := crypto.FingerprintHex(bobID.Public.Bytes())
	if len(aliceHex) != 64 || len(bobHex) != 64 {
		t.Fatalf("Hex Fingerprint length invariant violated: expected 64 chars, got %d", len(aliceHex))
	}

	// Safety Number Symmetry
	snAlice, err := crypto.SafetyNumber(aliceID.Public.Bytes(), bobID.Public.Bytes(), aliceID.Verify, bobID.Verify)
	if err != nil {
		t.Fatalf("SafetyNumber Alice failed: %v", err)
	}

	snBob, err := crypto.SafetyNumber(bobID.Public.Bytes(), aliceID.Public.Bytes(), bobID.Verify, aliceID.Verify)
	if err != nil {
		t.Fatalf("SafetyNumber Bob failed: %v", err)
	}

	if snAlice != snBob {
		t.Errorf("Safety number symmetry invariant violated: Alice=%s, Bob=%s", snAlice, snBob)
	}

	if len(snAlice) != 60 {
		t.Errorf("Safety number length invariant violated: expected 60 digits, got %d", len(snAlice))
	}
}

// TestRedTeamZeroizationAudit tests that all key zeroization routines wipe memory to zero.
func TestRedTeamZeroizationAudit(t *testing.T) {
	buf := bytes.Repeat([]byte{0xAA}, 64)
	crypto.Zeroize(buf)

	for i, b := range buf {
		if b != 0 {
			t.Fatalf("Zeroize failed at index %d: byte is 0x%02X instead of 0x00", i, b)
		}
	}

	id, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("GenerateIdentityKeyPair failed: %v", err)
	}

	id.Zeroize()
	for i, b := range id.Private.Bytes() {
		if b != 0 {
			t.Fatalf("IdentityKeyPair.Zeroize failed on private key at index %d", i)
		}
	}
}

// TestRedTeamYamuxMaxFrameBounds tests that oversized frames cannot exhaust memory.
func TestRedTeamYamuxMaxFrameBounds(t *testing.T) {
	// Frame size > MaxFrameSize (16MB) must be rejected
	var oversizedBuf bytes.Buffer
	oversizedBuf.Write([]byte{0x01, 0x00, 0x00, 0x01}) // 16MB + 1 byte length header

	_, err := transport.ReadFromStream(&oversizedBuf, 16*1024*1024)
	if err == nil {
		t.Errorf("Expected rejection of frame exceeding MaxFrameSize")
	}
}
