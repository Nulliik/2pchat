package crypto

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/nacl/secretbox"
)

const (
	// SecretBoxKeySize is 32 bytes.
	SecretBoxKeySize = 32
	// SecretBoxNonceSize is 24 bytes (XSalsa20 nonce).
	SecretBoxNonceSize = 24
	// SecretBoxOverhead is 16 bytes (Poly1305 tag).
	SecretBoxOverhead = secretbox.Overhead
	// ChaChaKeySize is 32 bytes.
	ChaChaKeySize = chacha20poly1305.KeySize
	// ChaChaNonceSize is 12 bytes (Standard IETF nonce).
	ChaChaNonceSize = chacha20poly1305.NonceSize
)

// HKDFSHA256 derives a key of specified length from input key material, optional salt, and context info.
// 100% compliant with RFC 5869.
func HKDFSHA256(ikm, salt, info []byte, length int) ([]byte, error) {
	if length <= 0 {
		return nil, errors.New("HKDF output length must be positive")
	}
	h := hkdf.New(sha256.New, ikm, salt, info)
	out := make([]byte, length)
	if _, err := io.ReadFull(h, out); err != nil {
		return nil, fmt.Errorf("HKDF expansion failed: %w", err)
	}
	return out, nil
}

// HMACSHA256 returns HMAC-SHA256(key, data).
func HMACSHA256(key, data []byte) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write(data)
	return mac.Sum(nil)
}

// SecretBoxEncrypt encrypts plaintext with a 32-byte key using XSalsa20-Poly1305.
// The output matches PyNaCl SecretBox.encrypt: [24-byte Nonce] + [Ciphertext with 16-byte Poly1305 MAC].
func SecretBoxEncrypt(key []byte, plaintext []byte) ([]byte, error) {
	if len(key) != SecretBoxKeySize {
		return nil, fmt.Errorf("invalid SecretBox key size: expected 32, got %d", len(key))
	}
	var secretKey [SecretBoxKeySize]byte
	copy(secretKey[:], key)

	var nonce [SecretBoxNonceSize]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return nil, fmt.Errorf("failed to generate random nonce: %w", err)
	}

	// Allocate buffer starting with the 24-byte nonce
	out := make([]byte, SecretBoxNonceSize, SecretBoxNonceSize+len(plaintext)+SecretBoxOverhead)
	copy(out, nonce[:])

	// Seal appends ciphertext + poly1305 tag
	sealed := secretbox.Seal(out, plaintext, &nonce, &secretKey)
	return sealed, nil
}

// SecretBoxEncryptWithNonce encrypts plaintext with a specific 24-byte nonce.
func SecretBoxEncryptWithNonce(key []byte, nonce []byte, plaintext []byte) ([]byte, error) {
	if len(key) != SecretBoxKeySize {
		return nil, fmt.Errorf("invalid SecretBox key size: expected 32, got %d", len(key))
	}
	if len(nonce) != SecretBoxNonceSize {
		return nil, fmt.Errorf("invalid SecretBox nonce size: expected 24, got %d", len(nonce))
	}
	var secretKey [SecretBoxKeySize]byte
	copy(secretKey[:], key)

	var secretNonce [SecretBoxNonceSize]byte
	copy(secretNonce[:], nonce)

	out := make([]byte, SecretBoxNonceSize, SecretBoxNonceSize+len(plaintext)+SecretBoxOverhead)
	copy(out, secretNonce[:])

	sealed := secretbox.Seal(out, plaintext, &secretNonce, &secretKey)
	return sealed, nil
}

// SecretBoxDecrypt decrypts a payload produced by SecretBoxEncrypt.
// Expects payload format: [24-byte Nonce] + [Ciphertext with 16-byte Poly1305 MAC].
func SecretBoxDecrypt(key []byte, payload []byte) ([]byte, error) {
	if len(key) != SecretBoxKeySize {
		return nil, fmt.Errorf("invalid SecretBox key size: expected 32, got %d", len(key))
	}
	if len(payload) < SecretBoxNonceSize+SecretBoxOverhead {
		return nil, errors.New("SecretBox payload too short")
	}

	var secretKey [SecretBoxKeySize]byte
	copy(secretKey[:], key)

	var nonce [SecretBoxNonceSize]byte
	copy(nonce[:], payload[:SecretBoxNonceSize])

	ciphertext := payload[SecretBoxNonceSize:]
	out := make([]byte, 0, len(ciphertext)-SecretBoxOverhead)

	decrypted, ok := secretbox.Open(out, ciphertext, &nonce, &secretKey)
	if !ok {
		return nil, errors.New("SecretBox decryption failed: message authentication failure")
	}
	return decrypted, nil
}

// ChaCha20Poly1305Encrypt encrypts plaintext with ChaCha20-Poly1305 AEAD.
func ChaCha20Poly1305Encrypt(key, nonce, plaintext, additionalData []byte) ([]byte, error) {
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	return aead.Seal(nil, nonce, plaintext, additionalData), nil
}

// ChaCha20Poly1305Decrypt decrypts ciphertext with ChaCha20-Poly1305 AEAD.
func ChaCha20Poly1305Decrypt(key, nonce, ciphertext, additionalData []byte) ([]byte, error) {
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	return aead.Open(nil, nonce, ciphertext, additionalData)
}
