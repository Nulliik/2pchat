package crypto

import (
	"bytes"
	"crypto/rand"
	"testing"
)

func TestBackupV2Roundtrip(t *testing.T) {
	password := "SecureP@ssw0rd123!"
	payload := []byte("This is a secret 2PChat profile payload containing keys and settings.")
	fingerprint := "fp_alice_mock_fingerprint_base64_123456"

	encrypted, err := EncryptBackup(password, payload, fingerprint)
	if err != nil {
		t.Fatalf("EncryptBackup failed: %v", err)
	}

	if len(encrypted) <= len(payload) {
		t.Fatalf("Encrypted size %d should be greater than plaintext %d", len(encrypted), len(payload))
	}

	// Verify header inspection without password
	header, err := InspectBackupHeader(encrypted)
	if err != nil {
		t.Fatalf("InspectBackupHeader failed: %v", err)
	}
	if header.Version != BackupVersionV2 {
		t.Fatalf("Expected version %d, got %d", BackupVersionV2, header.Version)
	}
	if header.Fingerprint != fingerprint {
		t.Fatalf("Expected fingerprint '%s', got '%s'", fingerprint, header.Fingerprint)
	}

	// Decrypt
	decrypted, decryptedHeader, err := DecryptBackup(password, encrypted)
	if err != nil {
		t.Fatalf("DecryptBackup failed: %v", err)
	}

	if !bytes.Equal(decrypted, payload) {
		t.Fatalf("Decrypted payload does not match original!\nGot: %s\nWant: %s", string(decrypted), string(payload))
	}
	if decryptedHeader.Fingerprint != fingerprint {
		t.Fatalf("Decrypted header fingerprint mismatch")
	}
}

func TestBackupV2WrongPasswordRejection(t *testing.T) {
	password := "CorrectPassword123"
	wrongPassword := "WrongPassword123"
	payload := []byte("Sensitive profile data")
	fingerprint := "fp_test"

	encrypted, err := EncryptBackup(password, payload, fingerprint)
	if err != nil {
		t.Fatalf("EncryptBackup failed: %v", err)
	}

	_, _, err = DecryptBackup(wrongPassword, encrypted)
	if err == nil {
		t.Fatalf("Expected DecryptBackup to fail with wrong password, but succeeded")
	}
}

func TestBackupV2TamperedHeaderRejection(t *testing.T) {
	password := "CorrectPassword123"
	payload := []byte("Sensitive profile data")
	fingerprint := "fp_test"

	encrypted, err := EncryptBackup(password, payload, fingerprint)
	if err != nil {
		t.Fatalf("EncryptBackup failed: %v", err)
	}

	// Tamper with byte in the header (e.g. version or salt)
	tampered := make([]byte, len(encrypted))
	copy(tampered, encrypted)
	tampered[15] ^= 0xFF // Flip bit in salt

	_, _, err = DecryptBackup(password, tampered)
	if err == nil {
		t.Fatalf("Expected DecryptBackup to fail for tampered header, but succeeded")
	}
}

func TestBackupV2TamperedCiphertextRejection(t *testing.T) {
	password := "CorrectPassword123"
	payload := []byte("Sensitive profile data")
	fingerprint := "fp_test"

	encrypted, err := EncryptBackup(password, payload, fingerprint)
	if err != nil {
		t.Fatalf("EncryptBackup failed: %v", err)
	}

	// Tamper with the last byte (auth tag)
	tampered := make([]byte, len(encrypted))
	copy(tampered, encrypted)
	tampered[len(tampered)-1] ^= 0x01

	_, _, err = DecryptBackup(password, tampered)
	if err == nil {
		t.Fatalf("Expected DecryptBackup to fail for tampered ciphertext, but succeeded")
	}
}

func TestBackupManifestSigningAndVerification(t *testing.T) {
	seed := make([]byte, 32)
	if _, err := rand.Read(seed); err != nil {
		t.Fatalf("rand.Read failed: %v", err)
	}

	canonicalManifest := []byte(`{"version":2,"nickname":"Alice","fingerprint":"fp123"}`)

	sigB64, pubB64, err := SignBackupManifest(seed, canonicalManifest)
	if err != nil {
		t.Fatalf("SignBackupManifest failed: %v", err)
	}

	if sigB64 == "" || pubB64 == "" {
		t.Fatalf("SignBackupManifest returned empty signature or public key")
	}

	// Valid verification
	if !VerifyBackupManifest(pubB64, canonicalManifest, sigB64) {
		t.Fatalf("VerifyBackupManifest failed for valid signature")
	}

	// Tampered manifest verification failure
	tamperedManifest := []byte(`{"version":2,"nickname":"Mallory","fingerprint":"fp123"}`)
	if VerifyBackupManifest(pubB64, tamperedManifest, sigB64) {
		t.Fatalf("VerifyBackupManifest should have rejected tampered manifest")
	}

	// Tampered signature
	if VerifyBackupManifest(pubB64, canonicalManifest, "invalidSig==") {
		t.Fatalf("VerifyBackupManifest should have rejected invalid signature")
	}
}

func TestBackupV2InvalidInputs(t *testing.T) {
	// Short password
	_, err := EncryptBackup("12345", []byte("payload"), "fp")
	if err == nil {
		t.Fatalf("Expected error for short password")
	}

	// Empty payload
	_, err = EncryptBackup("validPassword12", []byte{}, "fp")
	if err == nil {
		t.Fatalf("Expected error for empty payload")
	}

	// Inspect corrupted data
	_, err = InspectBackupHeader([]byte("GARBAGE_HEADER_DATA"))
	if err == nil {
		t.Fatalf("Expected error for garbage header")
	}
}
