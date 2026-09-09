package crypto

import (
	"bytes"
	"crypto/rand"
	"testing"
)

func TestXChaCha20Poly1305Roundtrip(t *testing.T) {
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		t.Fatalf("failed to generate random key: %v", err)
	}

	plaintext := []byte("confidential-private-key-data-payload-96-bytes-padded-material-1234567890abcdef")
	aad := []byte("identity_v1.key")

	encrypted, err := XChaCha20Poly1305Encrypt(key, plaintext, aad)
	if err != nil {
		t.Fatalf("XChaCha20Poly1305Encrypt failed: %v", err)
	}

	// Payload must be: 24-byte nonce + len(plaintext) + 16-byte Poly1305 overhead
	expectedLen := 24 + len(plaintext) + 16
	if len(encrypted) != expectedLen {
		t.Fatalf("unexpected ciphertext length: got %d, expected %d", len(encrypted), expectedLen)
	}

	// Decrypt
	decrypted, err := XChaCha20Poly1305Decrypt(key, encrypted, aad)
	if err != nil {
		t.Fatalf("XChaCha20Poly1305Decrypt failed: %v", err)
	}

	if !bytes.Equal(decrypted, plaintext) {
		t.Fatalf("decrypted data does not match original plaintext")
	}
}

func TestXChaCha20Poly1305RejectsWrongAAD(t *testing.T) {
	key := make([]byte, 32)
	_, _ = rand.Read(key)
	plaintext := []byte("sensitive-key-bytes")

	encrypted, err := XChaCha20Poly1305Encrypt(key, plaintext, []byte("identity_v1.key"))
	if err != nil {
		t.Fatalf("encryption failed: %v", err)
	}

	// Attempt decrypt with different AAD (e.g. trying to substitute prekey for identity)
	_, err = XChaCha20Poly1305Decrypt(key, encrypted, []byte("prekey_v1.key"))
	if err == nil {
		t.Fatalf("decryption should have failed when using wrong AAD")
	}
}

func TestXChaCha20Poly1305RejectsTamperedCiphertext(t *testing.T) {
	key := make([]byte, 32)
	_, _ = rand.Read(key)
	plaintext := []byte("sensitive-key-bytes")
	aad := []byte("identity_v1.key")

	encrypted, err := XChaCha20Poly1305Encrypt(key, plaintext, aad)
	if err != nil {
		t.Fatalf("encryption failed: %v", err)
	}

	// Tamper single bit in ciphertext
	tampered := make([]byte, len(encrypted))
	copy(tampered, encrypted)
	tampered[len(tampered)-1] ^= 0x01

	_, err = XChaCha20Poly1305Decrypt(key, tampered, aad)
	if err == nil {
		t.Fatalf("decryption should have failed for tampered ciphertext")
	}
}

func TestXChaCha20Poly1305RejectsInvalidKeySize(t *testing.T) {
	shortKey := make([]byte, 16)
	plaintext := []byte("data")
	aad := []byte("test")

	_, err := XChaCha20Poly1305Encrypt(shortKey, plaintext, aad)
	if err == nil {
		t.Fatalf("expected error for 16-byte key, got nil")
	}

	_, err = XChaCha20Poly1305Decrypt(shortKey, []byte("dummy-payload-exceeding-minimum-length-40-bytes"), aad)
	if err == nil {
		t.Fatalf("expected error for 16-byte key during decrypt, got nil")
	}
}
