package crypto

import (
	"bytes"
	"fmt"
	"math/rand"
	"testing"
)

// TestDoubleRatchetDeepOutOfOrderStress verifies RULES.md §7:
// Severe network packet reordering across multiple symmetric ratchet steps
// decrypts all payloads cleanly using skipped message keys.
func TestDoubleRatchetDeepOutOfOrderStress(t *testing.T) {
	aliceID, _ := GenerateIdentityKeyPair()
	bobID, _ := GenerateIdentityKeyPair()

	bobPrekeyPriv, bobPrekeyPub, _ := GenerateX25519Keypair()
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
		t.Fatalf("Alice init failed: %v", err)
	}
	bobSession, err := RespondToPreKeyInit(bobID, bobPrekeyPriv, nil, aliceID.Public, aliceEph.Public)
	if err != nil {
		t.Fatalf("Bob init failed: %v", err)
	}

	totalMessages := 20
	plaintexts := make([][]byte, totalMessages)
	packets := make([][]byte, totalMessages)

	for i := 0; i < totalMessages; i++ {
		plaintexts[i] = []byte(fmt.Sprintf("Stress message payload #%03d with extra padding data 0x%04x", i, i*37))
		pkt, err := aliceSession.EncryptMessage(plaintexts[i])
		if err != nil {
			t.Fatalf("Alice EncryptMessage #%d failed: %v", i, err)
		}
		packets[i] = pkt
	}

	// Pseudo-random deterministic permutation for reproducible testing
	r := rand.New(rand.NewSource(42))
	perm := r.Perm(totalMessages)

	for _, idx := range perm {
		decrypted, err := bobSession.DecryptMessage(packets[idx])
		if err != nil {
			t.Fatalf("Bob failed to decrypt packet index %d in permuted order: %v", idx, err)
		}
		if !bytes.Equal(decrypted, plaintexts[idx]) {
			t.Fatalf("Payload mismatch for packet %d: expected %q, got %q", idx, plaintexts[idx], decrypted)
		}
	}
}

// TestDoubleRatchetReplayMatrix verifies RULES.md §9:
// All attempts to replay previously decrypted packets are blocked.
func TestDoubleRatchetReplayMatrix(t *testing.T) {
	aliceID, _ := GenerateIdentityKeyPair()
	bobID, _ := GenerateIdentityKeyPair()

	bobPrekeyPriv, bobPrekeyPub, _ := GenerateX25519Keypair()
	bobPrekeySig := SignPreKey(bobID.Signing, bobPrekeyPub)
	bobBundle := &PreKeyBundle{
		IdentityPub:       bobID.Public,
		IdentityVerifyPub: bobID.Verify,
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	aliceEph, _ := GenerateIdentityKeyPair()
	aliceSession, _ := InitializeSessionFromPreKey(aliceID, bobBundle, aliceEph)
	bobSession, _ := RespondToPreKeyInit(bobID, bobPrekeyPriv, nil, aliceID.Public, aliceEph.Public)

	// Encrypt 5 packets
	packets := make([][]byte, 5)
	for i := 0; i < 5; i++ {
		pkt, _ := aliceSession.EncryptMessage([]byte(fmt.Sprintf("Replay test payload #%d", i)))
		packets[i] = pkt
	}

	// Decrypt all 5 packets
	for i := 0; i < 5; i++ {
		_, err := bobSession.DecryptMessage(packets[i])
		if err != nil {
			t.Fatalf("Initial decryption of packet #%d failed: %v", i, err)
		}
	}

	// Now try replaying every packet — all must fail!
	for i := 0; i < 5; i++ {
		replayed, err := bobSession.DecryptMessage(packets[i])
		if err == nil {
			t.Fatalf("Replay of packet #%d succeeded unexpectedly! Decrypted: %q", i, replayed)
		}
	}
}
