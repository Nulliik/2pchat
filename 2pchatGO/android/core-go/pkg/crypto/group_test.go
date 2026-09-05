package crypto

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

func TestGroupIdentitySignAndVerify(t *testing.T) {
	privKey, pubKey, err := GenerateEd25519Keypair()
	if err != nil {
		t.Fatalf("GenerateEd25519Keypair failed: %v", err)
	}

	payload := "event_id:12345|group_id:group_xyz|action:post_message|author:alice"

	sigB64, err := SignGroupPayload(privKey, payload)
	if err != nil {
		t.Fatalf("SignGroupPayload failed: %v", err)
	}

	if sigB64 == "" {
		t.Fatal("expected non-empty signature base64")
	}

	// Verify valid signature
	if !VerifyGroupPayload(pubKey, payload, sigB64) {
		t.Fatal("VerifyGroupPayload returned false for valid signature")
	}

	// Verify signature rejected on tampered payload
	tampered := payload + "|tampered"
	if VerifyGroupPayload(pubKey, tampered, sigB64) {
		t.Fatal("VerifyGroupPayload succeeded on tampered payload")
	}

	// Verify signature rejected with wrong public key
	_, otherPubKey, _ := GenerateEd25519Keypair()
	if VerifyGroupPayload(otherPubKey, payload, sigB64) {
		t.Fatal("VerifyGroupPayload succeeded with wrong public key")
	}
}

func TestGroupSignatureAcceptsLegacyReceiveTranscripts(t *testing.T) {
	pubKey, privKey, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	const payload = "2pchat-group-event-signature-v1\n1\ngroup-1"
	for name, transcript := range map[string][]byte{
		"python-v1": append([]byte(legacyPythonGroupSignatureContextV1), []byte(payload)...),
		"go-v1":     []byte(payload),
	} {
		t.Run(name, func(t *testing.T) {
			sig := base64.StdEncoding.EncodeToString(ed25519.Sign(privKey, transcript))
			if !VerifyGroupPayload(pubKey, payload, sig) {
				t.Fatalf("rejected %s migration signature", name)
			}
		})
	}
}

func TestGroupEpochAEADEncryptAndDecrypt(t *testing.T) {
	epochSecret := make([]byte, GroupAEADKeySize)
	if _, err := rand.Read(epochSecret); err != nil {
		t.Fatalf("rand.Read failed: %v", err)
	}

	aad := []byte("group_id:abc|epoch:42")
	plaintext := []byte("Secret group message payload 12345!")

	nonceB64, ciphertextB64, err := GroupEncrypt(epochSecret, aad, plaintext)
	if err != nil {
		t.Fatalf("GroupEncrypt failed: %v", err)
	}

	if nonceB64 == "" || ciphertextB64 == "" {
		t.Fatal("expected non-empty nonce and ciphertext")
	}

	// Decrypt with matching AAD and secret
	decrypted, err := GroupDecrypt(epochSecret, aad, nonceB64, ciphertextB64)
	if err != nil {
		t.Fatalf("GroupDecrypt failed: %v", err)
	}

	if !bytes.Equal(decrypted, plaintext) {
		t.Fatalf("decrypted text %q does not match original plaintext %q", decrypted, plaintext)
	}

	// Decrypt fails with mismatched AAD
	wrongAAD := []byte("group_id:abc|epoch:43")
	_, err = GroupDecrypt(epochSecret, wrongAAD, nonceB64, ciphertextB64)
	if err == nil {
		t.Fatal("expected decryption failure with mismatched AAD")
	}

	// Decrypt fails with wrong secret
	wrongSecret := make([]byte, GroupAEADKeySize)
	rand.Read(wrongSecret)
	_, err = GroupDecrypt(wrongSecret, aad, nonceB64, ciphertextB64)
	if err == nil {
		t.Fatal("expected decryption failure with wrong epoch secret")
	}
}

func TestSenderKeysRatchetProgression(t *testing.T) {
	seed := make([]byte, KeySize)
	rand.Read(seed)

	senderState, err := NewSenderSessionState(seed)
	if err != nil {
		t.Fatalf("NewSenderSessionState failed: %v", err)
	}

	// Step 0
	k0, err := senderState.RatchetKeyForIteration(0)
	if err != nil {
		t.Fatalf("RatchetKeyForIteration(0) failed: %v", err)
	}
	if k0.Iteration != 0 || len(k0.CipherKey) != KeySize || len(k0.Nonce) != GroupAEADNonceSize {
		t.Fatalf("invalid key0 properties: %+v", k0)
	}

	// Step 2 (skipping 1)
	k2, err := senderState.RatchetKeyForIteration(2)
	if err != nil {
		t.Fatalf("RatchetKeyForIteration(2) failed: %v", err)
	}
	if k2.Iteration != 2 {
		t.Fatalf("expected iteration 2, got %d", k2.Iteration)
	}

	// Retrieve skipped step 1
	k1, err := senderState.RatchetKeyForIteration(1)
	if err != nil {
		t.Fatalf("RatchetKeyForIteration(1) failed to retrieve from cache: %v", err)
	}
	if k1.Iteration != 1 {
		t.Fatalf("expected iteration 1, got %d", k1.Iteration)
	}

	// Double retrieval of consumed key should fail
	_, err = senderState.RatchetKeyForIteration(1)
	if err == nil {
		t.Fatal("expected error on re-using consumed message key 1")
	}
}

func TestSenderSessionStateZeroize(t *testing.T) {
	seed := make([]byte, KeySize)
	for i := range seed {
		seed[i] = byte(i + 1)
	}

	senderState, err := NewSenderSessionState(seed)
	if err != nil {
		t.Fatalf("failed to create sender state: %v", err)
	}

	_, _ = senderState.RatchetKeyForIteration(2) // Creates skipped keys

	senderState.Zeroize()

	for i, b := range senderState.ChainKey.Seed {
		if b != 0 {
			t.Fatalf("Chain key seed byte %d was not zeroed", i)
		}
	}
	if len(senderState.SkippedKeys) != 0 {
		t.Fatalf("Expected skipped keys to be cleared, got %d", len(senderState.SkippedKeys))
	}
}

func TestComputeRosterHashKat(t *testing.T) {
	// Out-of-order test entries to ensure canonical sorting
	entries := []string{
		"carol-dev-003:carol-key-abcde",
		"alice-dev-001:alice-key-12345",
		"bob-dev-002:bob-key-67890",
	}
	expectedHash := "32b2d1e3e8bf3805522fc0dbf7d8b0ab2b304eb91763aa7bc853156a928fce08"
	actual := ComputeRosterHash(entries)
	if actual != expectedHash {
		t.Fatalf("RosterHash KAT mismatch: expected %s, got %s", expectedHash, actual)
	}
}

type KatVectorsFile struct {
	Version           int `json:"version"`
	RosterHashVectors []struct {
		Name               string   `json:"name"`
		Entries            []string `json:"entries"`
		ExpectedRosterHash string   `json:"expected_roster_hash"`
	} `json:"roster_hash_vectors"`
	EventIDVectors []struct {
		Name                  string `json:"name"`
		CanonicalForSignature string `json:"canonical_for_signature"`
		ExpectedEventID       string `json:"expected_event_id"`
	} `json:"event_id_vectors"`
}

func TestCryptoVectorsJsonMatches(t *testing.T) {
	candidatePaths := []string{
		"../../testdata/group_crypto_test_vectors.json",
		"testdata/group_crypto_test_vectors.json",
		"../testdata/group_crypto_test_vectors.json",
	}
	var data []byte
	var err error
	for _, p := range candidatePaths {
		data, err = os.ReadFile(p)
		if err == nil {
			break
		}
	}
	if err != nil {
		t.Fatalf("failed to read test vectors file: %v", err)
	}

	var kat KatVectorsFile
	if err := json.Unmarshal(data, &kat); err != nil {
		t.Fatalf("failed to unmarshal test vectors: %v", err)
	}

	for _, rv := range kat.RosterHashVectors {
		actual := ComputeRosterHash(rv.Entries)
		if actual != rv.ExpectedRosterHash {
			t.Errorf("RosterHash vector %s mismatch: expected %s, got %s", rv.Name, rv.ExpectedRosterHash, actual)
		}
	}

	for _, ev := range kat.EventIDVectors {
		h := sha256.Sum256([]byte(ev.CanonicalForSignature))
		actual := hex.EncodeToString(h[:])
		if actual != ev.ExpectedEventID {
			t.Errorf("EventID vector %s mismatch: expected %s, got %s", ev.Name, ev.ExpectedEventID, actual)
		}
	}
}

