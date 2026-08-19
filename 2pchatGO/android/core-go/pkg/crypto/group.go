package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
)

const (
	GroupAEADKeySize     = 32
	GroupAEADNonceSize   = 12
	gcmTagSize           = 16
	GroupSignatureDomain = "2pchat-group-event-signature-v1"
)

// SignGroupPayload signs a canonical string using Ed25519 and returns Base64 signature.
func SignGroupPayload(privKey ed25519.PrivateKey, canonicalPayload string) (string, error) {
	if len(privKey) != ed25519.PrivateKeySize {
		return "", errors.New("invalid Ed25519 private key size")
	}
	if canonicalPayload == "" {
		return "", errors.New("empty canonical payload")
	}

	sig := ed25519.Sign(privKey, []byte(canonicalPayload))
	return base64.StdEncoding.EncodeToString(sig), nil
}

// VerifyGroupPayload verifies an Ed25519 signature over a canonical string.
func VerifyGroupPayload(pubKey ed25519.PublicKey, canonicalPayload string, signatureBase64 string) bool {
	if len(pubKey) != ed25519.PublicKeySize || canonicalPayload == "" || signatureBase64 == "" {
		return false
	}

	sig, err := base64.StdEncoding.DecodeString(signatureBase64)
	if err != nil || len(sig) != ed25519.SignatureSize {
		return false
	}

	return ed25519.Verify(pubKey, []byte(canonicalPayload), sig)
}

// GroupEncrypt encrypts plaintext with AES-256-GCM using epochSecret and authenticatedData.
func GroupEncrypt(
	epochSecret []byte,
	authenticatedData []byte,
	plaintext []byte,
) (nonceBase64 string, ciphertextBase64 string, err error) {
	if len(epochSecret) != GroupAEADKeySize {
		return "", "", fmt.Errorf("epoch secret must be %d bytes, got %d", GroupAEADKeySize, len(epochSecret))
	}

	block, err := aes.NewCipher(epochSecret)
	if err != nil {
		return "", "", fmt.Errorf("aes.NewCipher failed: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", "", fmt.Errorf("cipher.NewGCM failed: %w", err)
	}

	nonce := make([]byte, GroupAEADNonceSize)
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", "", fmt.Errorf("failed to generate random nonce: %w", err)
	}

	ciphertext := gcm.Seal(nil, nonce, plaintext, authenticatedData)

	return base64.StdEncoding.EncodeToString(nonce),
		base64.StdEncoding.EncodeToString(ciphertext),
		nil
}

// GroupDecrypt decrypts AES-256-GCM ciphertext using epochSecret and authenticatedData.
func GroupDecrypt(
	epochSecret []byte,
	authenticatedData []byte,
	nonceBase64 string,
	ciphertextBase64 string,
) ([]byte, error) {
	if len(epochSecret) != GroupAEADKeySize {
		return nil, fmt.Errorf("epoch secret must be %d bytes, got %d", GroupAEADKeySize, len(epochSecret))
	}

	nonce, err := base64.StdEncoding.DecodeString(nonceBase64)
	if err != nil || len(nonce) != GroupAEADNonceSize {
		return nil, errors.New("invalid group nonce")
	}

	ciphertext, err := base64.StdEncoding.DecodeString(ciphertextBase64)
	if err != nil || len(ciphertext) < gcmTagSize {
		return nil, errors.New("invalid group ciphertext")
	}

	block, err := aes.NewCipher(epochSecret)
	if err != nil {
		return nil, fmt.Errorf("aes.NewCipher failed: %w", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("cipher.NewGCM failed: %w", err)
	}

	plaintext, err := gcm.Open(nil, nonce, ciphertext, authenticatedData)
	if err != nil {
		return nil, errors.New("group ciphertext authentication failed")
	}

	return plaintext, nil
}
