package discovery

import (
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

const RendezvousContext = "2pchat-rendezvous-v1"

// NormalizeNickname normalizes whitespace and lowercases the nickname to match Python.
func NormalizeNickname(value string) string {
	return strings.Join(strings.Fields(strings.ToLower(strings.TrimSpace(value))), " ")
}

// NormalizeSharedCode trims whitespace from the shared discovery code.
func NormalizeSharedCode(value string) string {
	return strings.TrimSpace(value)
}

// DeriveRendezvousKey computes the standard 20-byte SHA-1 BitTorrent info_hash for discovery.
// Must strictly match Python: sha1("2pchat-rendezvous-v1:" + normalize(nick) + ":" + normalize(code)).
func DeriveRendezvousKey(nickname, sharedCode string) [20]byte {
	normNick := NormalizeNickname(nickname)
	normCode := NormalizeSharedCode(sharedCode)
	payload := RendezvousContext + ":" + normNick + ":" + normCode
	return sha1.Sum([]byte(payload))
}

// DeriveRendezvousKeyHex returns the 40-character lowercase hex representation of the rendezvous key.
func DeriveRendezvousKeyHex(nickname, sharedCode string) string {
	sum := DeriveRendezvousKey(nickname, sharedCode)
	return hex.EncodeToString(sum[:])
}

// DeriveDiscoveryToken computes a deterministic 16-char discovery token from a peer fingerprint.
func DeriveDiscoveryToken(fingerprint string) string {
	sum := sha256.Sum256([]byte("2pchat-discovery-token-v1:" + strings.TrimSpace(fingerprint)))
	return hex.EncodeToString(sum[:8])
}

// MatchesDiscoveryToken checks if a token matches the expected peer fingerprint.
func MatchesDiscoveryToken(token, fingerprint string) bool {
	expected := DeriveDiscoveryToken(fingerprint)
	return strings.EqualFold(strings.TrimSpace(token), expected)
}

// DeriveRendezvousCode computes the 8-char rendezvous code from a fingerprint.
func DeriveRendezvousCode(fingerprint string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(fingerprint)))
	return hex.EncodeToString(sum[:4])
}
