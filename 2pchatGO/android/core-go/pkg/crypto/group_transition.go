package crypto

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"strconv"
)

// GroupOwnerTransitionCertificate represents an owner delegation in group chats.
type GroupOwnerTransitionCertificate struct {
	GroupID             string
	PreviousOwnerAnchor string
	LineageSequence     int
	PreviousControlHead *string
	OldOwnerFingerprint string
	OldOwnerDeviceId    string
	OldOwnerSigningKey  string
	NewOwnerFingerprint string
	NewOwnerDeviceId    string
	NewOwnerSigningKey  string
	CreatedAtMs         int64
	Nonce               string
	SignatureBase64     string
}

func appendCanonical(sb *[]byte, s string) {
	*sb = append(*sb, []byte(strconv.Itoa(len(s)))...)
	*sb = append(*sb, ':')
	*sb = append(*sb, []byte(s)...)
	*sb = append(*sb, '\n')
}

// CanonicalForSignature produces deterministic canonical byte representation.
func (c *GroupOwnerTransitionCertificate) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte("2pchat-group-owner-transition-v1\n1\n")...)
	appendCanonical(&buf, c.GroupID)
	appendCanonical(&buf, c.PreviousOwnerAnchor)
	buf = append(buf, []byte(strconv.Itoa(c.LineageSequence)+"\n")...)
	prevHead := ""
	if c.PreviousControlHead != nil {
		prevHead = *c.PreviousControlHead
	}
	appendCanonical(&buf, prevHead)
	appendCanonical(&buf, c.OldOwnerFingerprint)
	appendCanonical(&buf, c.OldOwnerDeviceId)
	appendCanonical(&buf, c.OldOwnerSigningKey)
	appendCanonical(&buf, c.NewOwnerFingerprint)
	appendCanonical(&buf, c.NewOwnerDeviceId)
	appendCanonical(&buf, c.NewOwnerSigningKey)
	buf = append(buf, []byte(strconv.FormatInt(c.CreatedAtMs, 10)+"\n")...)
	appendCanonical(&buf, c.Nonce)
	return string(buf)
}

// TransitionID produces the unique SHA-256 transition ID.
func (c *GroupOwnerTransitionCertificate) TransitionID() string {
	canonical := c.CanonicalForSignature()
	raw := "2pchat-group-owner-transition-id-v1\x00" + canonical + "\x00" + c.SignatureBase64
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

// Verify validates the Ed25519 digital signature of the transition certificate.
func (c *GroupOwnerTransitionCertificate) Verify() bool {
	if c.OldOwnerSigningKey == "" || c.SignatureBase64 == "" {
		return false
	}
	pubBytes, err := base64.StdEncoding.DecodeString(c.OldOwnerSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return false
	}
	sigBytes, err := base64.StdEncoding.DecodeString(c.SignatureBase64)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return false
	}
	canonical := c.CanonicalForSignature()
	return ed25519.Verify(pubBytes, []byte(canonical), sigBytes)
}
