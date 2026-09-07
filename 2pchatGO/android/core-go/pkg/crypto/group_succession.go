package crypto

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"
)

const (
	MinHeartbeatTimeoutDays        = 7
	MaxHeartbeatTimeoutDays        = 180
	DefaultHeartbeatTimeoutDays    = 30
	SuccessionCertValidityDuration = 365 * 24 * time.Hour

	GenesisPreviousEventHash = ""

	DomainSuccessionCert       = "2pchat-group-succession-cert-v1\n"
	DomainOwnerHeartbeat       = "2pchat-group-owner-heartbeat-v1\n"
	DomainSuccessionRevocation = "2pchat-group-succession-revocation-v1\n"
	DomainSuccessionClaim      = "2pchat-group-succession-claim-v1\n"
)

var (
	ErrSuccessionTimeoutOutOfBounds  = errors.New("heartbeat timeout days must be between 7 and 180")
	ErrSuccessionCertExpired         = errors.New("succession certificate has expired")
	ErrSuccessionCertRevoked         = errors.New("succession certificate has been revoked")
	ErrSuccessionClaimantMismatch    = errors.New("claimant does not match designated successor")
	ErrSuccessionPrematureClaim      = errors.New("succession claim is premature: required heartbeat timeout has not elapsed")
	ErrSuccessionLogMismatchDeferred = errors.New("claim references an unverified or mismatched heartbeat event hash; deferring claim")
	ErrSuccessionInvalidSignature    = errors.New("invalid cryptographic signature")
	ErrSuccessionInvalidCertHash     = errors.New("claim certificate hash does not match certificate")
	ErrSuccessionFutureTimestamp     = errors.New("timestamp is too far in the future")
)

// SuccessionCertificate represents a cryptographically bound delegation from the current owner
// to a designated successor with an agreed-upon heartbeat timeout.
type SuccessionCertificate struct {
	Type                   string `json:"type"` // "succession_certificate_v1"
	GroupID                string `json:"group_id"`
	CurrentOwner           string `json:"current_owner"`             // hex fingerprint
	CurrentOwnerSigningKey string `json:"current_owner_signing_key"` // base64 Ed25519 public key
	Successor              string `json:"successor"`                // hex fingerprint
	SuccessorSigningKey    string `json:"successor_signing_key"`     // base64 Ed25519 public key
	HeartbeatTimeoutDays   uint32 `json:"heartbeat_timeout_days"`
	IssuedAt               int64  `json:"issued_at"`  // Unix millisecond timestamp
	ExpiresAt              int64  `json:"expires_at"` // Unix millisecond timestamp
	Signature              string `json:"signature"`  // base64 Ed25519 signature
}

// CanonicalForSignature produces a deterministic Netstring byte representation for signing.
func (c *SuccessionCertificate) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainSuccessionCert)...)
	appendCanonical(&buf, c.GroupID)
	appendCanonical(&buf, c.CurrentOwner)
	appendCanonical(&buf, c.CurrentOwnerSigningKey)
	appendCanonical(&buf, c.Successor)
	appendCanonical(&buf, c.SuccessorSigningKey)
	buf = append(buf, []byte(strconv.FormatUint(uint64(c.HeartbeatTimeoutDays), 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(c.IssuedAt, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(c.ExpiresAt, 10)+"\n")...)
	return string(buf)
}

// CertificateHash computes the unique SHA-256 digest of the signed certificate.
func (c *SuccessionCertificate) CertificateHash() string {
	canonical := c.CanonicalForSignature()
	raw := "2pchat-group-succession-cert-hash-v1\x00" + canonical + "\x00" + c.Signature
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

// Sign signs the canonical certificate with the current owner's Ed25519 private key.
func (c *SuccessionCertificate) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := c.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	c.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify validates the timeout bounds, expiration, and cryptographic signature of the certificate.
func (c *SuccessionCertificate) Verify() error {
	if c.HeartbeatTimeoutDays < MinHeartbeatTimeoutDays || c.HeartbeatTimeoutDays > MaxHeartbeatTimeoutDays {
		return ErrSuccessionTimeoutOutOfBounds
	}
	if c.ExpiresAt <= c.IssuedAt {
		return errors.New("expires_at must be strictly greater than issued_at")
	}
	if c.CurrentOwnerSigningKey == "" || c.Signature == "" {
		return ErrSuccessionInvalidSignature
	}
	pubBytes, err := base64.StdEncoding.DecodeString(c.CurrentOwnerSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrSuccessionInvalidSignature
	}
	sigBytes, err := base64.StdEncoding.DecodeString(c.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrSuccessionInvalidSignature
	}
	canonical := c.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrSuccessionInvalidSignature
	}
	return nil
}

// ToJSON serializes the certificate to a JSON string.
func (c *SuccessionCertificate) ToJSON() (string, error) {
	bytes, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseSuccessionCertificate deserializes and validates a SuccessionCertificate from JSON.
func ParseSuccessionCertificate(jsonStr string) (*SuccessionCertificate, error) {
	var cert SuccessionCertificate
	if err := json.Unmarshal([]byte(jsonStr), &cert); err != nil {
		return nil, fmt.Errorf("failed to parse succession certificate JSON: %w", err)
	}
	if cert.Type != "succession_certificate_v1" {
		return nil, errors.New("invalid succession certificate type")
	}
	return &cert, nil
}

// OwnerHeartbeat represents a replicated control event in the group log proving owner activity.
type OwnerHeartbeat struct {
	Type              string `json:"type"` // "owner_heartbeat"
	GroupID           string `json:"group_id"`
	Owner             string `json:"owner"`               // hex fingerprint
	OwnerSigningKey   string `json:"owner_signing_key"`   // base64 Ed25519 public key
	Sequence          uint64 `json:"sequence"`            // monotonic sequence (>= 1)
	Timestamp         int64  `json:"timestamp"`           // Unix ms
	PreviousEventHash string `json:"previous_event_hash"` // hash of previous heartbeat or "" for genesis
	Signature         string `json:"signature"`           // base64 Ed25519 signature
}

// CanonicalForSignature produces a deterministic Netstring byte representation for signing.
func (h *OwnerHeartbeat) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainOwnerHeartbeat)...)
	appendCanonical(&buf, h.GroupID)
	appendCanonical(&buf, h.Owner)
	appendCanonical(&buf, h.OwnerSigningKey)
	buf = append(buf, []byte(strconv.FormatUint(h.Sequence, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(h.Timestamp, 10)+"\n")...)
	appendCanonical(&buf, h.PreviousEventHash)
	return string(buf)
}

// EventHash computes the unique SHA-256 digest of this heartbeat event.
func (h *OwnerHeartbeat) EventHash() string {
	canonical := h.CanonicalForSignature()
	raw := "2pchat-group-owner-heartbeat-hash-v1\x00" + canonical + "\x00" + h.Signature
	digest := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(digest[:])
}

// Sign signs the canonical heartbeat with the owner's Ed25519 private key.
func (h *OwnerHeartbeat) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := h.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	h.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify validates the sequence and signature of the heartbeat.
func (h *OwnerHeartbeat) Verify() error {
	if h.Sequence == 0 {
		return errors.New("heartbeat sequence must be >= 1")
	}
	if h.OwnerSigningKey == "" || h.Signature == "" {
		return ErrSuccessionInvalidSignature
	}
	pubBytes, err := base64.StdEncoding.DecodeString(h.OwnerSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrSuccessionInvalidSignature
	}
	sigBytes, err := base64.StdEncoding.DecodeString(h.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrSuccessionInvalidSignature
	}
	canonical := h.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrSuccessionInvalidSignature
	}
	return nil
}

// ToJSON serializes the heartbeat to a JSON string.
func (h *OwnerHeartbeat) ToJSON() (string, error) {
	bytes, err := json.Marshal(h)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseOwnerHeartbeat deserializes an OwnerHeartbeat from JSON.
func ParseOwnerHeartbeat(jsonStr string) (*OwnerHeartbeat, error) {
	var hb OwnerHeartbeat
	if err := json.Unmarshal([]byte(jsonStr), &hb); err != nil {
		return nil, fmt.Errorf("failed to parse owner heartbeat JSON: %w", err)
	}
	if hb.Type != "owner_heartbeat" {
		return nil, errors.New("invalid owner heartbeat type")
	}
	return &hb, nil
}

// SuccessionRevocation represents a signed invalidation of a previously issued succession certificate.
type SuccessionRevocation struct {
	Type            string `json:"type"`              // "succession_revocation"
	GroupID         string `json:"group_id"`
	Owner           string `json:"owner"`             // hex fingerprint
	OwnerSigningKey string `json:"owner_signing_key"` // base64 Ed25519 public key
	CertificateHash string `json:"certificate_hash"`  // SHA-256 of revoked certificate
	RevokedAt       int64  `json:"revoked_at"`        // Unix ms
	Signature       string `json:"signature"`         // base64 Ed25519 signature
}

// CanonicalForSignature produces a deterministic Netstring byte representation for signing.
func (r *SuccessionRevocation) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainSuccessionRevocation)...)
	appendCanonical(&buf, r.GroupID)
	appendCanonical(&buf, r.Owner)
	appendCanonical(&buf, r.OwnerSigningKey)
	appendCanonical(&buf, r.CertificateHash)
	buf = append(buf, []byte(strconv.FormatInt(r.RevokedAt, 10)+"\n")...)
	return string(buf)
}

// Sign signs the canonical revocation with the owner's Ed25519 private key.
func (r *SuccessionRevocation) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := r.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	r.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify validates the signature of the revocation event.
func (r *SuccessionRevocation) Verify() error {
	if r.OwnerSigningKey == "" || r.Signature == "" {
		return ErrSuccessionInvalidSignature
	}
	pubBytes, err := base64.StdEncoding.DecodeString(r.OwnerSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrSuccessionInvalidSignature
	}
	sigBytes, err := base64.StdEncoding.DecodeString(r.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrSuccessionInvalidSignature
	}
	canonical := r.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrSuccessionInvalidSignature
	}
	return nil
}

// ToJSON serializes the revocation to a JSON string.
func (r *SuccessionRevocation) ToJSON() (string, error) {
	bytes, err := json.Marshal(r)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseSuccessionRevocation deserializes a SuccessionRevocation from JSON.
func ParseSuccessionRevocation(jsonStr string) (*SuccessionRevocation, error) {
	var rev SuccessionRevocation
	if err := json.Unmarshal([]byte(jsonStr), &rev); err != nil {
		return nil, fmt.Errorf("failed to parse succession revocation JSON: %w", err)
	}
	if rev.Type != "succession_revocation" {
		return nil, errors.New("invalid succession revocation type")
	}
	return &rev, nil
}

// SuccessionClaim represents an assertion by the designated successor to claim group ownership
// after the required heartbeat timeout has expired.
type SuccessionClaim struct {
	Type                   string `json:"type"`                      // "succession_claim_v1"
	GroupID                string `json:"group_id"`
	Claimant               string `json:"claimant"`                  // hex fingerprint
	ClaimantSigningKey     string `json:"claimant_signing_key"`      // base64 Ed25519 public key
	CertificateHash        string `json:"certificate_hash"`          // SHA-256 of certificate
	LastHeartbeatEventHash string `json:"last_heartbeat_event_hash"` // SHA-256 of last heartbeat
	ClaimedAt              int64  `json:"claimed_at"`                // Unix ms
	Signature              string `json:"signature"`                 // base64 Ed25519 signature
}

// CanonicalForSignature produces a deterministic Netstring byte representation for signing.
func (c *SuccessionClaim) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainSuccessionClaim)...)
	appendCanonical(&buf, c.GroupID)
	appendCanonical(&buf, c.Claimant)
	appendCanonical(&buf, c.ClaimantSigningKey)
	appendCanonical(&buf, c.CertificateHash)
	appendCanonical(&buf, c.LastHeartbeatEventHash)
	buf = append(buf, []byte(strconv.FormatInt(c.ClaimedAt, 10)+"\n")...)
	return string(buf)
}

// Sign signs the canonical claim with the claimant's Ed25519 private key.
func (c *SuccessionClaim) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := c.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	c.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify validates the claimant's signature on the claim assertion.
func (c *SuccessionClaim) Verify() error {
	if c.ClaimantSigningKey == "" || c.Signature == "" {
		return ErrSuccessionInvalidSignature
	}
	pubBytes, err := base64.StdEncoding.DecodeString(c.ClaimantSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrSuccessionInvalidSignature
	}
	sigBytes, err := base64.StdEncoding.DecodeString(c.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrSuccessionInvalidSignature
	}
	canonical := c.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrSuccessionInvalidSignature
	}
	return nil
}

// ToJSON serializes the claim to a JSON string.
func (c *SuccessionClaim) ToJSON() (string, error) {
	bytes, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseSuccessionClaim deserializes a SuccessionClaim from JSON.
func ParseSuccessionClaim(jsonStr string) (*SuccessionClaim, error) {
	var claim SuccessionClaim
	if err := json.Unmarshal([]byte(jsonStr), &claim); err != nil {
		return nil, fmt.Errorf("failed to parse succession claim JSON: %w", err)
	}
	if claim.Type != "succession_claim_v1" {
		return nil, errors.New("invalid succession claim type")
	}
	return &claim, nil
}

// VerifySuccessionClaim validates all conditions required for a succession claim to be accepted:
// 1. Certificate is valid, unexpired, and unrevoked.
// 2. Claimant matches the successor designated in the certificate.
// 3. Claim signature is cryptographically valid.
// 4. Certificate hash matches.
// 5. Last heartbeat event hash matches the verified replicated log (split-brain guard).
// 6. Required heartbeat timeout duration has elapsed without owner activity.
func VerifySuccessionClaim(
	cert *SuccessionCertificate,
	claim *SuccessionClaim,
	lastHeartbeat *OwnerHeartbeat,
	isRevoked bool,
	nowMs int64,
) error {
	if cert == nil || claim == nil {
		return errors.New("certificate and claim cannot be nil")
	}

	// 1. Verify certificate validity & signature
	if err := cert.Verify(); err != nil {
		return fmt.Errorf("certificate verification failed: %w", err)
	}

	// 2. Check revocation status
	if isRevoked {
		return ErrSuccessionCertRevoked
	}

	// 3. Check expiration
	if nowMs > cert.ExpiresAt {
		return ErrSuccessionCertExpired
	}

	// 4. Verify claim signature
	if err := claim.Verify(); err != nil {
		return fmt.Errorf("claim signature verification failed: %w", err)
	}

	// 5. Verify claimant identity matches successor in certificate
	if claim.Claimant != cert.Successor {
		return ErrSuccessionClaimantMismatch
	}
	if claim.GroupID != cert.GroupID {
		return errors.New("group_id mismatch between claim and certificate")
	}

	// 6. Verify certificate hash linkage
	if claim.CertificateHash != cert.CertificateHash() {
		return ErrSuccessionInvalidCertHash
	}

	// 7. Verify control log synchronization (Split-Brain / Partition Guard)
	if lastHeartbeat == nil || claim.LastHeartbeatEventHash != lastHeartbeat.EventHash() {
		return ErrSuccessionLogMismatchDeferred
	}

	// 8. Verify lastHeartbeat authenticity and binding
	if err := lastHeartbeat.Verify(); err != nil {
		return fmt.Errorf("last heartbeat invalid: %w", err)
	}
	if lastHeartbeat.GroupID != cert.GroupID || lastHeartbeat.Owner != cert.CurrentOwner {
		return errors.New("heartbeat group or owner mismatch")
	}

	// 9. Verify timeout elapsed:
	// Allowed clock skew: 5 minutes (300_000 ms)
	const clockSkewAllowanceMs = 5 * 60 * 1000
	if claim.ClaimedAt > nowMs+clockSkewAllowanceMs {
		return ErrSuccessionFutureTimestamp
	}

	requiredDurationMs := int64(cert.HeartbeatTimeoutDays) * 24 * 60 * 60 * 1000
	elapsedMs := claim.ClaimedAt - lastHeartbeat.Timestamp

	if elapsedMs < requiredDurationMs {
		return ErrSuccessionPrematureClaim
	}

	return nil
}
