package crypto

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/hkdf"
)

const (
	// BackupMagic is the 4-byte file header magic for 2PChat backups.
	BackupMagic = "2PBK"
	// BackupVersionV2 is format version 2 (authenticated & encrypted).
	BackupVersionV2 = 2
	// KDFTypeArgon2id indicates Argon2id key derivation.
	KDFTypeArgon2id = 1

	// Default Argon2id parameters (RFC 9106 recommended for mobile sensitive storage).
	DefaultArgon2Time    = uint32(3)
	DefaultArgon2Memory  = uint32(64 * 1024) // 64 MB
	DefaultArgon2Threads = uint8(4)
	BackupArgon2KeySize  = 32

	// BackupSaltSize is the length of the random Argon2id salt.
	BackupSaltSize = 32
	// BackupNonceSize is the length of the random XChaCha20-Poly1305 nonce (24 bytes).
	BackupNonceSize = chacha20poly1305.NonceSizeX

	// MaxFingerprintSize bounds the fingerprint length in the header.
	MaxFingerprintSize = 256
	// MaxBackupPayloadSize protects against OOM attacks (15 MB).
	MaxBackupPayloadSize = 15 * 1024 * 1024

	// HKDF domain separation strings for backup manifest signing.
	BackupSigningInfo = "2pchat-backup-sign-v1"
	BackupSaltDomain  = "2pchat-salt-v1"
)

// BackupHeader contains the authenticated metadata fields from the 2PBK header.
type BackupHeader struct {
	Version     uint8
	KDFType     uint8
	Time        uint32
	Memory      uint32
	Threads     uint8
	Salt        [BackupSaltSize]byte
	Nonce       [BackupNonceSize]byte
	Fingerprint string
	HeaderBytes []byte
}

// DeriveBackupSigningKey derives a domain-separated Ed25519 signing keypair from the identity seed.
func DeriveBackupSigningKey(identitySeed []byte) (ed25519.PrivateKey, ed25519.PublicKey, error) {
	if len(identitySeed) != 32 {
		return nil, nil, fmt.Errorf("invalid identity seed size: expected 32, got %d", len(identitySeed))
	}
	kdf := hkdf.New(sha256.New, identitySeed, []byte(BackupSaltDomain), []byte(BackupSigningInfo))
	keyBytes := make([]byte, 32)
	if _, err := io.ReadFull(kdf, keyBytes); err != nil {
		return nil, nil, fmt.Errorf("failed to derive backup signing key: %w", err)
	}
	defer Zeroize(keyBytes)

	priv := ed25519.NewKeyFromSeed(keyBytes)
	pub := priv.Public().(ed25519.PublicKey)
	return priv, pub, nil
}

// SignBackupManifest signs a canonical manifest byte slice using a domain-separated key derived from identitySeed.
func SignBackupManifest(identitySeed []byte, canonicalManifest []byte) (string, string, error) {
	priv, pub, err := DeriveBackupSigningKey(identitySeed)
	if err != nil {
		return "", "", err
	}
	defer Zeroize(priv)

	sig := ed25519.Sign(priv, canonicalManifest)
	return base64.StdEncoding.EncodeToString(sig), base64.StdEncoding.EncodeToString(pub), nil
}

// VerifyBackupManifest verifies an Ed25519 signature over a canonical manifest byte slice.
func VerifyBackupManifest(verifyPubB64 string, canonicalManifest []byte, signatureB64 string) bool {
	pubBytes, err := base64.StdEncoding.DecodeString(verifyPubB64)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return false
	}
	sigBytes, err := base64.StdEncoding.DecodeString(signatureB64)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(pubBytes, canonicalManifest, sigBytes)
}

// EncryptBackup encrypts a backup payload with a password and fingerprint using Argon2id + XChaCha20-Poly1305.
func EncryptBackup(password string, payload []byte, fingerprint string) ([]byte, error) {
	if len(password) < 6 {
		return nil, errors.New("backup password must be at least 6 characters")
	}
	if len(payload) == 0 {
		return nil, errors.New("backup payload cannot be empty")
	}
	if len(payload) > MaxBackupPayloadSize {
		return nil, fmt.Errorf("backup payload exceeds maximum allowed size (%d bytes)", MaxBackupPayloadSize)
	}
	fpBytes := []byte(fingerprint)
	if len(fpBytes) > MaxFingerprintSize {
		return nil, fmt.Errorf("fingerprint exceeds maximum allowed length (%d)", MaxFingerprintSize)
	}

	var salt [BackupSaltSize]byte
	if _, err := io.ReadFull(rand.Reader, salt[:]); err != nil {
		return nil, fmt.Errorf("failed to generate random salt: %w", err)
	}

	var nonce [BackupNonceSize]byte
	if _, err := io.ReadFull(rand.Reader, nonce[:]); err != nil {
		return nil, fmt.Errorf("failed to generate random nonce: %w", err)
	}

	passBytes := []byte(password)
	defer Zeroize(passBytes)

	key := argon2.IDKey(passBytes, salt[:], DefaultArgon2Time, DefaultArgon2Memory, DefaultArgon2Threads, BackupArgon2KeySize)
	defer Zeroize(key)

	// Build authenticated header (AAD)
	// Header format:
	// Magic (4) + Version (1) + KDFType (1) + Time (4) + Memory (4) + Threads (1) + Salt (32) + Nonce (24) + FP Len (2) + FP (N)
	headerLen := 4 + 1 + 1 + 4 + 4 + 1 + BackupSaltSize + BackupNonceSize + 2 + len(fpBytes)
	header := make([]byte, headerLen)

	copy(header[0:4], []byte(BackupMagic))
	header[4] = BackupVersionV2
	header[5] = KDFTypeArgon2id
	binary.BigEndian.PutUint32(header[6:10], DefaultArgon2Time)
	binary.BigEndian.PutUint32(header[10:14], DefaultArgon2Memory)
	header[14] = DefaultArgon2Threads
	copy(header[15:47], salt[:])
	copy(header[47:71], nonce[:])
	binary.BigEndian.PutUint16(header[71:73], uint16(len(fpBytes)))
	copy(header[73:], fpBytes)

	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, fmt.Errorf("failed to initialize XChaCha20-Poly1305: %w", err)
	}

	// Encrypt payload with header as Additional Authenticated Data (AAD)
	ciphertext := aead.Seal(nil, nonce[:], payload, header)

	result := make([]byte, len(header)+len(ciphertext))
	copy(result[:len(header)], header)
	copy(result[len(header):], ciphertext)

	return result, nil
}

// InspectBackupHeader parses the plaintext authenticated header without requiring password decryption.
func InspectBackupHeader(data []byte) (*BackupHeader, error) {
	// Min header length = 73 (header fixed fields) + 16 (Poly1305 tag)
	minSize := 73 + chacha20poly1305.Overhead
	if len(data) < minSize {
		return nil, errors.New("backup data too short for 2PBK format")
	}

	if string(data[0:4]) != BackupMagic {
		return nil, fmt.Errorf("invalid backup magic: expected '%s', got '%s'", BackupMagic, string(data[0:4]))
	}

	version := data[4]
	if version != BackupVersionV2 {
		return nil, fmt.Errorf("unsupported backup version: %d", version)
	}

	kdfType := data[5]
	if kdfType != KDFTypeArgon2id {
		return nil, fmt.Errorf("unsupported KDF type: %d", kdfType)
	}

	timeCost := binary.BigEndian.PutUint32 // for reference
	_ = timeCost
	timeVal := binary.BigEndian.Uint32(data[6:10])
	memVal := binary.BigEndian.Uint32(data[10:14])
	threadsVal := data[14]

	// Parameter sanity checks against DoS / resource exhaustion
	if memVal == 0 || memVal > 512*1024 { // max 512 MB
		return nil, fmt.Errorf("invalid Argon2 memory parameter: %d KiB", memVal)
	}
	if timeVal == 0 || timeVal > 20 {
		return nil, fmt.Errorf("invalid Argon2 time parameter: %d", timeVal)
	}
	if threadsVal == 0 || threadsVal > 64 {
		return nil, fmt.Errorf("invalid Argon2 threads parameter: %d", threadsVal)
	}

	var salt [BackupSaltSize]byte
	copy(salt[:], data[15:47])

	var nonce [BackupNonceSize]byte
	copy(nonce[:], data[47:71])

	fpLen := int(binary.BigEndian.Uint16(data[71:73]))
	if fpLen > MaxFingerprintSize {
		return nil, fmt.Errorf("fingerprint length exceeds limit: %d", fpLen)
	}

	headerEnd := 73 + fpLen
	if len(data) < headerEnd+chacha20poly1305.Overhead {
		return nil, errors.New("backup data truncated before ciphertext payload")
	}

	fp := string(data[73:headerEnd])

	return &BackupHeader{
		Version:     version,
		KDFType:     kdfType,
		Time:        timeVal,
		Memory:      memVal,
		Threads:     threadsVal,
		Salt:        salt,
		Nonce:       nonce,
		Fingerprint: fp,
		HeaderBytes: data[:headerEnd],
	}, nil
}

// DecryptBackup decrypts a 2PBK encrypted backup using the provided password.
func DecryptBackup(password string, data []byte) ([]byte, *BackupHeader, error) {
	header, err := InspectBackupHeader(data)
	if err != nil {
		return nil, nil, err
	}

	passBytes := []byte(password)
	defer Zeroize(passBytes)

	key := argon2.IDKey(passBytes, header.Salt[:], header.Time, header.Memory, header.Threads, BackupArgon2KeySize)
	defer Zeroize(key)

	aead, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to initialize XChaCha20-Poly1305: %w", err)
	}

	ciphertext := data[len(header.HeaderBytes):]

	// Decrypt with header as Additional Authenticated Data (AAD)
	plaintext, err := aead.Open(nil, header.Nonce[:], ciphertext, header.HeaderBytes)
	if err != nil {
		return nil, nil, errors.New("authentication failed: incorrect password or corrupted backup file")
	}

	return plaintext, header, nil
}
