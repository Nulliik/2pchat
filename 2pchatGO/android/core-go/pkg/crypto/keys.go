package crypto

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"runtime"

	"golang.org/x/crypto/curve25519"
)

const (
	// KeySize is the standard 32-byte size for X25519 and Ed25519 public keys.
	KeySize = 32
	// Ed25519SignatureSize is 64 bytes.
	Ed25519SignatureSize = 64
	// SafetyNumberModulus is 10^60.
	SafetyNumberDigits = 60
)

// Zeroize wipes the memory of the given byte slice in constant time.
func Zeroize(buf []byte) {
	if buf == nil {
		return
	}
	for i := range buf {
		buf[i] = 0
	}
	runtime.KeepAlive(buf)
}

// X25519PrivateKey represents a 32-byte X25519 private key.
type X25519PrivateKey [KeySize]byte

// X25519PublicKey represents a 32-byte X25519 public key.
type X25519PublicKey [KeySize]byte

// Bytes returns a slice copy of the private key.
func (k *X25519PrivateKey) Bytes() []byte {
	b := make([]byte, KeySize)
	copy(b, k[:])
	return b
}

// Bytes returns a slice copy of the public key.
func (k *X25519PublicKey) Bytes() []byte {
	b := make([]byte, KeySize)
	copy(b, k[:])
	return b
}

// Public derives the X25519 public key from the private key.
func (k *X25519PrivateKey) Public() *X25519PublicKey {
	var pub X25519PublicKey
	curve25519.ScalarBaseMult((*[KeySize]byte)(&pub), (*[KeySize]byte)(k))
	return &pub
}

// GenerateX25519Keypair generates a fresh random X25519 keypair.
func GenerateX25519Keypair() (*X25519PrivateKey, *X25519PublicKey, error) {
	var priv X25519PrivateKey
	if _, err := rand.Read(priv[:]); err != nil {
		return nil, nil, fmt.Errorf("crypto/rand read failed: %w", err)
	}
	// Clamp private key according to RFC 7748
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64

	pub := priv.Public()
	return &priv, pub, nil
}

// X25519PrivateKeyFromBytes loads a 32-byte X25519 private key.
func X25519PrivateKeyFromBytes(data []byte) (*X25519PrivateKey, error) {
	if len(data) != KeySize {
		return nil, fmt.Errorf("invalid X25519 private key length: expected 32, got %d", len(data))
	}
	var priv X25519PrivateKey
	copy(priv[:], data)
	return &priv, nil
}

// X25519PublicKeyFromBytes loads a 32-byte X25519 public key.
func X25519PublicKeyFromBytes(data []byte) (*X25519PublicKey, error) {
	if len(data) != KeySize {
		return nil, fmt.Errorf("invalid X25519 public key length: expected 32, got %d", len(data))
	}
	var pub X25519PublicKey
	copy(pub[:], data)
	return &pub, nil
}

// DH computes the X25519 Diffie-Hellman scalar multiplication and guards against all-zero output.
func DH(priv *X25519PrivateKey, pub *X25519PublicKey) ([]byte, error) {
	shared, err := curve25519.X25519(priv[:], pub[:])
	if err != nil {
		return nil, fmt.Errorf("X25519 computation failed: %w", err)
	}
	var zeros [KeySize]byte
	if subtle.ConstantTimeCompare(shared, zeros[:]) == 1 {
		return nil, errors.New("invalid all-zero DH output")
	}
	return shared, nil
}

// GenerateEd25519Keypair generates an Ed25519 signing keypair.
func GenerateEd25519Keypair() (ed25519.PrivateKey, ed25519.PublicKey, error) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, nil, fmt.Errorf("ed25519.GenerateKey failed: %w", err)
	}
	return priv, pub, nil
}

// IdentityKeyPair contains both the long-term X25519 identity key and the companion Ed25519 signing key.
type IdentityKeyPair struct {
	Public  *X25519PublicKey
	Private *X25519PrivateKey
	Signing ed25519.PrivateKey
	Verify  ed25519.PublicKey
}

// GenerateIdentityKeyPair creates a complete identity pair.
func GenerateIdentityKeyPair() (*IdentityKeyPair, error) {
	xPriv, xPub, err := GenerateX25519Keypair()
	if err != nil {
		return nil, err
	}
	edPriv, edPub, err := GenerateEd25519Keypair()
	if err != nil {
		return nil, err
	}
	return &IdentityKeyPair{
		Public:  xPub,
		Private: xPriv,
		Signing: edPriv,
		Verify:  edPub,
	}, nil
}

// Zeroize wipes the private signing and X25519 keys of the identity from memory.
func (k *IdentityKeyPair) Zeroize() {
	if k == nil {
		return
	}
	if k.Private != nil {
		Zeroize(k.Private[:])
	}
	if k.Signing != nil {
		Zeroize(k.Signing)
	}
}

// IdentityKeyPairFromSeed derives an IdentityKeyPair deterministically from a 32-byte seed.
func IdentityKeyPairFromSeed(seed []byte) (*IdentityKeyPair, error) {
	if len(seed) != KeySize {
		return nil, fmt.Errorf("invalid seed length: expected %d, got %d", KeySize, len(seed))
	}
	var xPriv X25519PrivateKey
	copy(xPriv[:], seed)
	xPriv[0] &= 248
	xPriv[31] &= 127
	xPriv[31] |= 64
	xPub := xPriv.Public()

	edPriv := ed25519.NewKeyFromSeed(seed)
	edPub := edPriv.Public().(ed25519.PublicKey)

	return &IdentityKeyPair{
		Public:  xPub,
		Private: &xPriv,
		Signing: edPriv,
		Verify:  edPub,
	}, nil
}

// Fingerprint returns the Base64 fingerprint of the public X25519 identity key.
func (k *IdentityKeyPair) Fingerprint() string {
	if k == nil || k.Public == nil {
		return ""
	}
	return Fingerprint(k.Public[:])
}

// Seed returns the 32-byte seed representation of the private key.
func (k *IdentityKeyPair) Seed() []byte {
	if k == nil || k.Private == nil {
		return nil
	}
	return k.Private.Bytes()
}

// Fingerprint returns the Base64 representation of a 32-byte public key.
func Fingerprint(pub []byte) string {
	return base64.StdEncoding.EncodeToString(pub)
}

// FingerprintHex returns the hex representation of a 32-byte public key.
func FingerprintHex(pub []byte) string {
	return hex.EncodeToString(pub)
}

// FingerprintFromHex converts a hex fingerprint string to canonical Base64.
func FingerprintFromHex(hexStr string) (string, error) {
	b, err := hex.DecodeString(hexStr)
	if err != nil {
		return "", err
	}
	if len(b) != KeySize {
		return "", fmt.Errorf("invalid key size: expected %d bytes, got %d", KeySize, len(b))
	}
	return base64.StdEncoding.EncodeToString(b), nil
}

// SafetyNumber computes the 60-digit domain-separated safety number between two peers.
// 100% interoperable with the Python safety_number implementation.
func SafetyNumber(
	localIdentityPub, remoteIdentityPub []byte,
	localVerifyPub, remoteVerifyPub []byte,
) (string, error) {
	if len(localIdentityPub) != KeySize || len(remoteIdentityPub) != KeySize {
		return "", errors.New("invalid identity public key length")
	}

	var identities [][]byte
	if bytes.Compare(localIdentityPub, remoteIdentityPub) < 0 {
		identities = [][]byte{localIdentityPub, remoteIdentityPub}
	} else {
		identities = [][]byte{remoteIdentityPub, localIdentityPub}
	}

	var material []byte
	material = append(material, []byte("p2p-chat-safety-number-v2\x00")...)
	material = append(material, identities[0]...)
	material = append(material, identities[1]...)

	if len(localVerifyPub) == KeySize && len(remoteVerifyPub) == KeySize {
		var verifyKeys [][]byte
		if bytes.Compare(localVerifyPub, remoteVerifyPub) < 0 {
			verifyKeys = [][]byte{localVerifyPub, remoteVerifyPub}
		} else {
			verifyKeys = [][]byte{remoteVerifyPub, localVerifyPub}
		}
		material = append(material, 0x01)
		material = append(material, verifyKeys[0]...)
		material = append(material, verifyKeys[1]...)
	}

	h := sha256.Sum256(material)
	// int.from_bytes(digest[:30], "big") % (10 ** 60)
	num := new(big.Int).SetBytes(h[:30])
	modulus := new(big.Int).Exp(big.NewInt(10), big.NewInt(SafetyNumberDigits), nil)
	num.Mod(num, modulus)

	// format as 60-digit decimal string zero-padded
	return fmt.Sprintf("%060s", num.String()), nil
}
