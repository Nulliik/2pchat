package crypto

import (
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base32"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"strings"

	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/sha3"
)

const (
	// TorOnionSaltV1 is the salt used for HKDF derivation of Tor onion seed.
	TorOnionSaltV1 = "2pchat-onion-salt-v1"
	// TorOnionInfoPrefixV1 is the domain separation prefix for Tor onion key derivation.
	TorOnionInfoPrefixV1 = "2pchat-tor-v3-onion-key-v1"
	// TorV3SecretKeyHeader is the 32-byte header expected by Tor daemon for hs_ed25519_secret_key.
	TorV3SecretKeyHeader = "== ed25519v1-secret: type0 ==\x00\x00\x00"
	// TorV3ChecksumPrefix is the constant prefix used in Tor v3 onion address checksum.
	TorV3ChecksumPrefix = ".onion checksum"
	// TorV3VersionByte is the 1-byte version identifier for Tor v3 hidden services.
	TorV3VersionByte = 0x03
	// TorV3SecretKeySize is the total size in bytes of the hs_ed25519_secret_key file.
	TorV3SecretKeySize = 96
)

// DeriveTorOnionKey derives a standard 96-byte Tor v3 hs_ed25519_secret_key and corresponding
// .onion hostname deterministically from identitySeed and an integer index using HKDF domain separation.
func DeriveTorOnionKey(identitySeed []byte, index uint32) (onionAddress string, secretKeyBytes []byte, err error) {
	if len(identitySeed) != 32 {
		return "", nil, errors.New("invalid identity seed length, expected 32 bytes")
	}

	info := make([]byte, len(TorOnionInfoPrefixV1)+4)
	copy(info[0:len(TorOnionInfoPrefixV1)], TorOnionInfoPrefixV1)
	binary.BigEndian.PutUint32(info[len(TorOnionInfoPrefixV1):], index)

	prk := hkdf.Extract(sha256.New, identitySeed, []byte(TorOnionSaltV1))
	r := hkdf.Expand(sha256.New, prk, info)

	seed := make([]byte, 32)
	if _, err := io.ReadFull(r, seed); err != nil {
		return "", nil, fmt.Errorf("failed to derive onion seed: %w", err)
	}
	defer Zeroize(seed)

	h := sha512.Sum512(seed)
	defer Zeroize(h[:])

	scalar := make([]byte, 32)
	copy(scalar, h[:32])
	defer Zeroize(scalar)

	// Clamp Ed25519 scalar
	scalar[0] &= 248
	scalar[31] &= 127
	scalar[31] |= 64

	prfKey := make([]byte, 32)
	copy(prfKey, h[32:64])
	defer Zeroize(prfKey)

	// 96-byte Tor v3 secret key file content:
	// 32B header + 32B clamped scalar + 32B PRF key
	secretKey := make([]byte, TorV3SecretKeySize)
	copy(secretKey[0:32], TorV3SecretKeyHeader)
	copy(secretKey[32:64], scalar)
	copy(secretKey[64:96], prfKey)

	// Derive Ed25519 public key
	privKey := ed25519.NewKeyFromSeed(seed)
	defer Zeroize(privKey)
	pubKey := privKey.Public().(ed25519.PublicKey)

	// Compute Tor v3 onion address:
	// checksum = SHA3-256(".onion checksum" || pubKey || 0x03)[:2]
	// onion = base32(pubKey || checksum || 0x03) + ".onion"
	hInput := make([]byte, len(TorV3ChecksumPrefix)+len(pubKey)+1)
	copy(hInput[0:], TorV3ChecksumPrefix)
	copy(hInput[len(TorV3ChecksumPrefix):], pubKey)
	hInput[len(hInput)-1] = TorV3VersionByte

	checksumHash := sha3.Sum256(hInput)
	checksum := checksumHash[:2]

	onionRaw := make([]byte, 32+2+1)
	copy(onionRaw[0:32], pubKey)
	copy(onionRaw[32:34], checksum)
	onionRaw[34] = TorV3VersionByte

	onionAddress = strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(onionRaw)) + ".onion"
	return onionAddress, secretKey, nil
}
