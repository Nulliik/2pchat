package discovery

import (
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"strings"
)

const RendezvousContext = "2pchat-rendezvous-v1"

// DeriveRendezvousKey returns the shared 20-byte tracker/DHT namespace used by
// both the Python and Go clients. SHA-1 is used as a fixed-width namespace
// function because BitTorrent info_hash values are exactly 20 bytes; the
// secret rendezvous code supplies the anti-enumeration entropy.
func DeriveRendezvousKey(nickname, sharedCode string) ([sha1.Size]byte, error) {
	var zero [sha1.Size]byte
	normalizedNickname := strings.Join(strings.Fields(strings.ToLower(strings.TrimSpace(nickname))), " ")
	normalizedCode := strings.TrimSpace(sharedCode)
	if normalizedNickname == "" || normalizedCode == "" {
		return zero, errors.New("nickname and shared code must not be empty")
	}
	payload := RendezvousContext + ":" + normalizedNickname + ":" + normalizedCode
	return sha1.Sum([]byte(payload)), nil
}

func DeriveRendezvousKeyHex(nickname, sharedCode string) (string, error) {
	key, err := DeriveRendezvousKey(nickname, sharedCode)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(key[:]), nil
}
