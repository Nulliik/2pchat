package crypto

import (
	"crypto/rand"
	"testing"
)

// TestZeroizeByteSlice verifies RULES.md §8:
// Sensitive memory buffers of various sizes are reliably cleared to zero.
func TestZeroizeByteSlice(t *testing.T) {
	sizes := []int{1, 16, 32, 64, 128, 256, 1024, 4096}

	for _, size := range sizes {
		buf := make([]byte, size)
		_, err := rand.Read(buf)
		if err != nil {
			t.Fatalf("rand.Read failed: %v", err)
		}

		// Ensure buffer is non-zero
		hasNonZero := false
		for _, b := range buf {
			if b != 0 {
				hasNonZero = true
				break
			}
		}
		if !hasNonZero {
			buf[0] = 0xAA
		}

		Zeroize(buf)

		for i, b := range buf {
			if b != 0 {
				t.Fatalf("Zeroize failed at size %d, index %d: byte is 0x%02x", size, i, b)
			}
		}
	}
}

// TestIdentityKeyPairZeroizeAudit verifies cryptographic private key zeroization.
func TestIdentityKeyPairZeroizeAudit(t *testing.T) {
	kp, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("GenerateIdentityKeyPair failed: %v", err)
	}

	// Verify non-zero private key before wipe
	if kp.Private == nil {
		t.Fatalf("Private key is nil")
	}

	kp.Zeroize()

	if kp.Private != nil {
		for i, b := range kp.Private {
			if b != 0 {
				t.Fatalf("Identity private key byte %d was not zeroed", i)
			}
		}
	}

	for i, b := range kp.Signing {
		if b != 0 {
			t.Fatalf("Ed25519 signing private key byte %d was not zeroed", i)
		}
	}
}

// TestSessionStateZeroizeDeepAudit verifies Double Ratchet root and chain key erasure.
func TestSessionStateZeroizeDeepAudit(t *testing.T) {
	aliceID, _ := GenerateIdentityKeyPair()
	bobID, _ := GenerateIdentityKeyPair()

	_, bobPrekeyPub, _ := GenerateX25519Keypair()
	bobPrekeySig := SignPreKey(bobID.Signing, bobPrekeyPub)
	bobBundle := &PreKeyBundle{
		IdentityPub:       bobID.Public,
		IdentityVerifyPub: bobID.Verify,
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	aliceEph, _ := GenerateIdentityKeyPair()
	aliceSession, err := InitializeSessionFromPreKey(aliceID, bobBundle, aliceEph)
	if err != nil {
		t.Fatalf("InitializeSessionFromPreKey failed: %v", err)
	}

	// Encrypt a message to populate symmetric chain keys
	_, err = aliceSession.EncryptMessage([]byte("Zeroization test payload"))
	if err != nil {
		t.Fatalf("EncryptMessage failed: %v", err)
	}

	// Call Zeroize
	aliceSession.Zeroize()

	// Verify all sensitive key slots are zeroes
	for i, b := range aliceSession.RootKey {
		if b != 0 {
			t.Fatalf("RootKey byte %d was not zeroed: 0x%02x", i, b)
		}
	}
	for i, b := range aliceSession.SendChainKey {
		if b != 0 {
			t.Fatalf("SendChainKey byte %d was not zeroed: 0x%02x", i, b)
		}
	}
	for i, b := range aliceSession.RecvChainKey {
		if b != 0 {
			t.Fatalf("RecvChainKey byte %d was not zeroed: 0x%02x", i, b)
		}
	}
	for i, b := range aliceSession.HeaderKey {
		if b != 0 {
			t.Fatalf("HeaderKey byte %d was not zeroed: 0x%02x", i, b)
		}
	}
	if aliceSession.DHSendKey != nil {
		for i, b := range aliceSession.DHSendKey {
			if b != 0 {
				t.Fatalf("DHSendKey byte %d was not zeroed: 0x%02x", i, b)
			}
		}
	}
}
