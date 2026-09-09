package crypto

import (
	"crypto/ed25519"
	"crypto/rand"
	"strings"
	"testing"
)

func TestSafetyNumberSymmetry(t *testing.T) {
	aliceId, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("GenerateIdentityKeyPair Alice failed: %v", err)
	}
	bobId, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("GenerateIdentityKeyPair Bob failed: %v", err)
	}

	// Alice computes safety number with Bob
	aliceSN, err := SafetyNumber(
		aliceId.Public.Bytes(),
		bobId.Public.Bytes(),
		aliceId.Verify,
		bobId.Verify,
	)
	if err != nil {
		t.Fatalf("Alice SafetyNumber failed: %v", err)
	}

	// Bob computes safety number with Alice
	bobSN, err := SafetyNumber(
		bobId.Public.Bytes(),
		aliceId.Public.Bytes(),
		bobId.Verify,
		aliceId.Verify,
	)
	if err != nil {
		t.Fatalf("Bob SafetyNumber failed: %v", err)
	}

	if aliceSN == "" || bobSN == "" {
		t.Fatalf("Safety numbers must not be empty")
	}

	if aliceSN != bobSN {
		t.Fatalf("Safety number symmetry failed: Alice=%s, Bob=%s", aliceSN, bobSN)
	}

	// Verify format: exactly 60 decimal digits
	cleanDigits := strings.ReplaceAll(aliceSN, " ", "")
	if len(cleanDigits) != 60 {
		t.Errorf("Expected 60 digits, got %d in '%s'", len(cleanDigits), aliceSN)
	}
	for _, c := range cleanDigits {
		if c < '0' || c > '9' {
			t.Errorf("Safety number contains non-digit character '%c'", c)
		}
	}
}

func TestSafetyNumberTamperEvident(t *testing.T) {
	aliceId, _ := GenerateIdentityKeyPair()
	bobId, _ := GenerateIdentityKeyPair()
	eveId, _ := GenerateIdentityKeyPair()

	snAliceBob, _ := SafetyNumber(
		aliceId.Public.Bytes(),
		bobId.Public.Bytes(),
		aliceId.Verify,
		bobId.Verify,
	)

	snAliceEve, _ := SafetyNumber(
		aliceId.Public.Bytes(),
		eveId.Public.Bytes(),
		aliceId.Verify,
		eveId.Verify,
	)

	if snAliceBob == snAliceEve {
		t.Fatalf("Safety number failed to differentiate Bob and Eve")
	}
}

func TestIdentityKeyPairDeterministicFromSeed(t *testing.T) {
	seed := make([]byte, 32)
	for i := range seed {
		seed[i] = byte(i * 7)
	}

	id1, err := IdentityKeyPairFromSeed(seed)
	if err != nil {
		t.Fatalf("IdentityKeyPairFromSeed 1 failed: %v", err)
	}

	id2, err := IdentityKeyPairFromSeed(seed)
	if err != nil {
		t.Fatalf("IdentityKeyPairFromSeed 2 failed: %v", err)
	}

	fp1 := Fingerprint(id1.Public.Bytes())
	fp2 := Fingerprint(id2.Public.Bytes())

	if fp1 != fp2 {
		t.Errorf("Fingerprints differ from same seed: %s != %s", fp1, fp2)
	}
	if FingerprintHex(id1.Public.Bytes()) != FingerprintHex(id2.Public.Bytes()) {
		t.Errorf("Hex fingerprints differ from same seed")
	}
	if !ed25519.PublicKey(id1.Verify).Equal(id2.Verify) {
		t.Errorf("Verify pub keys differ from same seed")
	}
}

func TestZeroizeWiping(t *testing.T) {
	secret := make([]byte, 64)
	_, _ = rand.Read(secret)

	allZero := true
	for _, b := range secret {
		if b != 0 {
			allZero = false
			break
		}
	}
	if allZero {
		t.Fatalf("Random secret was unexpectedly all zeros")
	}

	Zeroize(secret)

	for i, b := range secret {
		if b != 0 {
			t.Fatalf("Byte %d was not zeroed: %d", i, b)
		}
	}
}

func TestSignAndVerifyEd25519(t *testing.T) {
	id, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("GenerateIdentityKeyPair failed: %v", err)
	}

	msg := []byte("2PChat Secure Protocol Message")
	sig := ed25519.Sign(id.Signing, msg)

	if !ed25519.Verify(id.Verify, msg, sig) {
		t.Fatalf("Signature verification failed with valid signature")
	}

	// Tampered message
	tamperedMsg := []byte("2PChat Modified Message")
	if ed25519.Verify(id.Verify, tamperedMsg, sig) {
		t.Fatalf("Signature verification unexpectedly passed on tampered message")
	}

	// Corrupted signature
	sigCorrupted := make([]byte, len(sig))
	copy(sigCorrupted, sig)
	sigCorrupted[0] ^= 0xFF
	if ed25519.Verify(id.Verify, msg, sigCorrupted) {
		t.Fatalf("Signature verification unexpectedly passed on corrupted signature")
	}
}

func TestIdentityKeyPairZeroize(t *testing.T) {
	id, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("GenerateIdentityKeyPair failed: %v", err)
	}

	id.Zeroize()

	for i, b := range id.Private {
		if b != 0 {
			t.Fatalf("Private key byte %d was not zeroed", i)
		}
	}
	for i, b := range id.Signing {
		if b != 0 {
			t.Fatalf("Signing key byte %d was not zeroed", i)
		}
	}
}
