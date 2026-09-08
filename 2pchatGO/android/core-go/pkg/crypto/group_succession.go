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
	"strings"
	"time"
)

const (
	MinHeartbeatTimeoutDays        = 7
	MaxHeartbeatTimeoutDays        = 180
	DefaultHeartbeatTimeoutDays    = 30
	SuccessionCertValidityDuration = 365 * 24 * time.Hour

	GenesisPreviousEventHash = ""

	DomainSuccessionCert       = "2pchat-group-succession-cert-v1\n"
	DomainSuccessionCertV2     = "2pchat-group-succession-cert-v2\n"
	DomainOwnerHeartbeat       = "2pchat-group-owner-heartbeat-v1\n"
	DomainSuccessionRevocation = "2pchat-group-succession-revocation-v1\n"
	DomainSuccessionClaim      = "2pchat-group-succession-claim-v1\n"

	// V3 Domain Separation Strings (FROZEN V3.2 WIRE SPEC)
	DomainSuccessionGenesisV3    = "2pchat-group-genesis-v3\n"
	DomainSuccessionCertV3       = "2pchat-group-succession-cert-v3\n"
	DomainSuccessionTransitionV3 = "2pchat-group-owner-transition-v3\n"
	DomainSuccessionClaimV3      = "2pchat-group-succession-claim-v3\n"
	DomainLivenessAttestationV3  = "2pchat-group-liveness-attestation-v3\n"
	DomainSuccessionRevocationV3 = "2pchat-group-succession-revocation-v3\n"
	DomainOwnerHeartbeatV3       = "2pchat-group-owner-heartbeat-v3\n"
	DomainRosterDigestV3         = "2pchat-roster-digest-v3\n"
	DomainRosterEpochRecordV3    = "2pchat-roster-epoch-record-v3\n"
)

var (
	ErrSuccessionTimeoutOutOfBounds      = errors.New("heartbeat timeout days must be between 7 and 180")
	ErrSuccessionCertExpired             = errors.New("succession certificate has expired")
	ErrSuccessionCertRevoked             = errors.New("succession certificate has been revoked")
	ErrSuccessionClaimantMismatch        = errors.New("claimant does not match designated successor")
	ErrSuccessionPrematureClaim          = errors.New("succession claim is premature: required heartbeat timeout has not elapsed")
	ErrSuccessionLogMismatchDeferred     = errors.New("claim references an unverified or mismatched heartbeat event hash; deferring claim")
	ErrSuccessionInvalidSignature        = errors.New("invalid cryptographic signature")
	ErrSuccessionInvalidCertHash         = errors.New("claim certificate hash does not match certificate")
	ErrSuccessionFutureTimestamp         = errors.New("timestamp is too far in the future")
	ErrSuccessionLineageSequenceZero    = errors.New("succession certificate lineage sequence must be >= 1")
	ErrSuccessionStaleLineage           = errors.New("succession certificate lineage sequence is older than active certificate")
	ErrSuccessionPredecessorHashMismatch = errors.New("succession certificate previous certificate hash does not match predecessor")
	ErrSuccessionSuccessorNotMember     = errors.New("designated successor is not an active participating member of the group")
	ErrDeposedOwnerHeartbeat            = errors.New("heartbeat emitted by deposed group owner")

	// V3 Protocol Error Declarations
	ErrV3InvalidGenesisSignature     = errors.New("invalid genesis signature")
	ErrV3InvalidCertificateSignature = errors.New("invalid succession certificate signature")
	ErrV3TenureEpochZero             = errors.New("tenure epoch must be >= 1")
	ErrV3LineageSequenceZero         = errors.New("lineage sequence must be >= 1")
	ErrV3PredecessorMismatch         = errors.New("predecessor certificate hash mismatch")
	ErrV3TimeoutOutOfBounds          = errors.New("heartbeat timeout days out of bounds [7, 180]")
	ErrV3ExpirationInvalid           = errors.New("expires_at must be strictly greater than issued_at")
	ErrV3TransitionInvalidSignature  = errors.New("invalid transition signature")
	ErrV3TransitionEpochStepInvalid  = errors.New("new tenure epoch must be strictly prior_tenure_epoch + 1")
	ErrV3TransitionOwnerMismatch     = errors.New("transition old owner key does not match prior tenure owner")
	ErrV3ClaimInsufficientWitnesses  = errors.New("witness attestation count does not meet required quorum")
	ErrV3ClaimWitnessNotMember       = errors.New("witness is not an active member in group roster")
	ErrV3ClaimDuplicateWitness       = errors.New("duplicate witness attestation in claim")
	ErrV3ClaimInvalidWitnessSig      = errors.New("invalid witness attestation signature")
	ErrV3ClaimInvalidClaimantSig     = errors.New("invalid claimant signature")
	ErrV3ClaimCertHashMismatch       = errors.New("claim certificate hash mismatch")
	ErrV3ClaimTimeoutNotElapsed      = errors.New("heartbeat timeout has not elapsed for succession claim")
	ErrV3RosterLineageBroken         = errors.New("roster epoch record hash chain broken")
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

// SuccessionCertificateV2 represents a cryptographically bound delegation from the current owner
// to a designated successor with an agreed-upon heartbeat timeout, strictly monotonic lineage sequence,
// predecessor certificate chaining, and roster hash binding.
type SuccessionCertificateV2 struct {
	Type                    string `json:"type"`                      // "succession_certificate_v2"
	GroupID                 string `json:"group_id"`
	LineageSequence         uint64 `json:"lineage_sequence"`          // Monotonic sequence (>= 1)
	PreviousCertificateHash string `json:"previous_certificate_hash"` // SHA-256 hash of predecessor cert, or "" for genesis
	GroupEpoch              uint64 `json:"group_epoch"`               // Group epoch at issuance
	RosterHash              string `json:"roster_hash"`               // SHA-256 roster digest at issuance
	CurrentOwner            string `json:"current_owner"`             // hex fingerprint
	CurrentOwnerSigningKey  string `json:"current_owner_signing_key"` // base64 Ed25519 public key
	Successor               string `json:"successor"`                 // hex fingerprint
	SuccessorSigningKey     string `json:"successor_signing_key"`     // base64 Ed25519 public key
	HeartbeatTimeoutDays    uint32 `json:"heartbeat_timeout_days"`
	IssuedAt                int64  `json:"issued_at"`                 // Unix millisecond timestamp
	ExpiresAt               int64  `json:"expires_at"`                // Unix millisecond timestamp
	Signature               string `json:"signature"`                 // base64 Ed25519 signature
}

// CanonicalForSignature produces a deterministic Netstring byte representation for signing.
func (c *SuccessionCertificateV2) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainSuccessionCertV2)...)
	appendCanonical(&buf, c.GroupID)
	buf = append(buf, []byte(strconv.FormatUint(c.LineageSequence, 10)+"\n")...)
	appendCanonical(&buf, c.PreviousCertificateHash)
	buf = append(buf, []byte(strconv.FormatUint(c.GroupEpoch, 10)+"\n")...)
	appendCanonical(&buf, c.RosterHash)
	appendCanonical(&buf, c.CurrentOwner)
	appendCanonical(&buf, c.CurrentOwnerSigningKey)
	appendCanonical(&buf, c.Successor)
	appendCanonical(&buf, c.SuccessorSigningKey)
	buf = append(buf, []byte(strconv.FormatUint(uint64(c.HeartbeatTimeoutDays), 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(c.IssuedAt, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(c.ExpiresAt, 10)+"\n")...)
	return string(buf)
}

// CertificateHash computes the unique SHA-256 digest of the signed V2 certificate.
func (c *SuccessionCertificateV2) CertificateHash() string {
	canonical := c.CanonicalForSignature()
	raw := "2pchat-group-succession-cert-hash-v2\x00" + canonical + "\x00" + c.Signature
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

// Sign signs the canonical certificate with the current owner's Ed25519 private key.
func (c *SuccessionCertificateV2) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := c.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	c.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify validates the lineage sequence, timeout bounds, expiration, and cryptographic signature.
func (c *SuccessionCertificateV2) Verify() error {
	if c.LineageSequence == 0 {
		return ErrSuccessionLineageSequenceZero
	}
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

// ToJSON serializes the V2 certificate to a JSON string.
func (c *SuccessionCertificateV2) ToJSON() (string, error) {
	bytes, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseSuccessionCertificateV2 deserializes and validates a SuccessionCertificateV2 from JSON.
func ParseSuccessionCertificateV2(jsonStr string) (*SuccessionCertificateV2, error) {
	var cert SuccessionCertificateV2
	if err := json.Unmarshal([]byte(jsonStr), &cert); err != nil {
		return nil, fmt.Errorf("failed to parse succession certificate V2 JSON: %w", err)
	}
	if cert.Type != "succession_certificate_v2" {
		return nil, errors.New("invalid succession certificate V2 type")
	}
	return &cert, nil
}

// ResolveSuccessionCertificateConflict deterministically selects the canonical winning certificate
// between two valid succession certificates for the same group.
// Axioms:
// 1. LineageSequence: higher sequence strictly wins.
// 2. Equivocation tie-breaker: if LineageSequence is identical:
//    a) Later IssuedAt timestamp wins.
//    b) If IssuedAt is equal, lexicographically smaller CertificateHash wins:
//       min(certA.CertificateHash(), certB.CertificateHash()).
func ResolveSuccessionCertificateConflict(certA, certB *SuccessionCertificateV2) *SuccessionCertificateV2 {
	if certA == nil {
		return certB
	}
	if certB == nil {
		return certA
	}
	// Rule 1: Monotonic lineage sequence
	if certA.LineageSequence > certB.LineageSequence {
		return certA
	}
	if certB.LineageSequence > certA.LineageSequence {
		return certB
	}
	// Rule 2a: Later timestamp if sequence equal (equivocation)
	if certA.IssuedAt > certB.IssuedAt {
		return certA
	}
	if certB.IssuedAt > certA.IssuedAt {
		return certB
	}
	// Rule 2b: Deterministic lexicographical tie-breaker
	if certA.CertificateHash() <= certB.CertificateHash() {
		return certA
	}
	return certB
}

// VerifySuccessionClaimV2 validates all conditions required for a V2 succession claim to be accepted:
// 1. Certificate is valid, unexpired, and unrevoked.
// 2. Claimant matches the successor designated in the certificate.
// 3. Claim signature is cryptographically valid.
// 4. Certificate hash matches.
// 5. Last heartbeat event hash matches the verified replicated log.
// 6. Required heartbeat timeout duration has elapsed without owner activity.
func VerifySuccessionClaimV2(
	cert *SuccessionCertificateV2,
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

// ============================================================================
// PROTOCOL V3.2 HARDENED CRYPTOGRAPHIC PRIMITIVES (FROZEN IMPLEMENTATION CONTRACT)
// ============================================================================

// GenesisBlockV3 represents the immutable founding block of a group under Succession Protocol V3.2.
type GenesisBlockV3 struct {
	Type               string `json:"type"`                // "group_succession_genesis_v3"
	GroupID            string `json:"group_id"`            // hex string
	CreatorFingerprint string `json:"creator_fingerprint"` // hex string
	CreatorSigningKey  string `json:"creator_signing_key"`  // base64 ed25519 public key
	InitialRosterHash  string `json:"initial_roster_hash"`  // hex SHA-256
	CreatedAtMs        int64  `json:"created_at_ms"`
	Signature          string `json:"signature"` // base64 ed25519 signature
}

// CanonicalForSignature formats the deterministic Netstring representation for Genesis signing.
func (g *GenesisBlockV3) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainSuccessionGenesisV3)...)
	appendCanonical(&buf, g.GroupID)
	appendCanonical(&buf, g.CreatorFingerprint)
	appendCanonical(&buf, g.CreatorSigningKey)
	appendCanonical(&buf, g.InitialRosterHash)
	buf = append(buf, []byte(strconv.FormatInt(g.CreatedAtMs, 10)+"\n")...)
	return string(buf)
}

// GenesisAnchor derives the immutable anchor digest A_0 for the group.
// A_0 = Hex(SHA256("2pchat-genesis-anchor-v3\n" || CanonicalBytes || "\x00" || signature))
func (g *GenesisBlockV3) GenesisAnchor() string {
	canonical := g.CanonicalForSignature()
	raw := "2pchat-genesis-anchor-v3\n" + canonical + "\x00" + g.Signature
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

// Sign signs the canonical genesis block using the creator's Ed25519 private key.
func (g *GenesisBlockV3) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := g.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	g.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify cryptographically validates the genesis block signature against CreatorSigningKey.
func (g *GenesisBlockV3) Verify() error {
	if g.CreatorSigningKey == "" || g.Signature == "" {
		return ErrV3InvalidGenesisSignature
	}
	pubBytes, err := base64.StdEncoding.DecodeString(g.CreatorSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrV3InvalidGenesisSignature
	}
	sigBytes, err := base64.StdEncoding.DecodeString(g.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrV3InvalidGenesisSignature
	}
	canonical := g.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrV3InvalidGenesisSignature
	}
	return nil
}

// ToJSON serializes GenesisBlockV3 to JSON.
func (g *GenesisBlockV3) ToJSON() (string, error) {
	bytes, err := json.Marshal(g)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseGenesisBlockV3 parses a GenesisBlockV3 from JSON.
func ParseGenesisBlockV3(jsonStr string) (*GenesisBlockV3, error) {
	var g GenesisBlockV3
	if err := json.Unmarshal([]byte(jsonStr), &g); err != nil {
		return nil, fmt.Errorf("failed to parse genesis block V3 JSON: %w", err)
	}
	if g.Type != "group_succession_genesis_v3" {
		return nil, errors.New("invalid genesis block V3 type")
	}
	return &g, nil
}

// SuccessionCertificateV3 represents an owner delegation under Protocol V3.2.
type SuccessionCertificateV3 struct {
	Type                    string `json:"type"`                      // "group_succession_cert_v3"
	GroupID                 string `json:"group_id"`
	TenureEpoch             uint64 `json:"tenure_epoch"`              // Monotonic tenure (1, 2, ...)
	LineageSequence         uint64 `json:"lineage_sequence"`          // Monotonic sequence within tenure (1, 2, ...)
	PreviousCertificateHash string `json:"previous_certificate_hash"` // Hash of predecessor cert or genesis_anchor
	RosterEpoch             uint64 `json:"roster_epoch"`              // Monotonic roster epoch
	RosterHash              string `json:"roster_hash"`               // Canonical SHA-256 roster digest
	CurrentOwner            string `json:"current_owner"`             // Hex fingerprint
	CurrentOwnerSigningKey  string `json:"current_owner_signing_key"` // Base64 Ed25519 public key
	Successor               string `json:"successor"`                 // Hex fingerprint
	SuccessorSigningKey     string `json:"successor_signing_key"`     // Base64 Ed25519 public key
	HeartbeatTimeoutDays    uint32 `json:"heartbeat_timeout_days"`    // 7 to 180 days
	IssuedAt                int64  `json:"issued_at"`                 // Unix ms
	ExpiresAt               int64  `json:"expires_at"`                // Unix ms
	Signature               string `json:"signature"`                 // Base64 Ed25519 signature
}

// CanonicalForSignature produces the deterministic Netstring byte representation for V3 certificate signing.
func (c *SuccessionCertificateV3) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainSuccessionCertV3)...)
	appendCanonical(&buf, c.GroupID)
	buf = append(buf, []byte(strconv.FormatUint(c.TenureEpoch, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatUint(c.LineageSequence, 10)+"\n")...)
	appendCanonical(&buf, c.PreviousCertificateHash)
	buf = append(buf, []byte(strconv.FormatUint(c.RosterEpoch, 10)+"\n")...)
	appendCanonical(&buf, c.RosterHash)
	appendCanonical(&buf, c.CurrentOwner)
	appendCanonical(&buf, c.CurrentOwnerSigningKey)
	appendCanonical(&buf, c.Successor)
	appendCanonical(&buf, c.SuccessorSigningKey)
	buf = append(buf, []byte(strconv.FormatUint(uint64(c.HeartbeatTimeoutDays), 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(c.IssuedAt, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(c.ExpiresAt, 10)+"\n")...)
	return string(buf)
}

// CertificateHash computes the unique SHA-256 digest of the signed V3 certificate.
// CertHash = Hex(SHA256("2pchat-succession-cert-hash-v3\x00" || CanonicalBytes || "\x00" || signature))
func (c *SuccessionCertificateV3) CertificateHash() string {
	canonical := c.CanonicalForSignature()
	raw := "2pchat-succession-cert-hash-v3\x00" + canonical + "\x00" + c.Signature
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

// Sign signs the canonical V3 certificate with the current owner's Ed25519 private key.
func (c *SuccessionCertificateV3) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := c.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	c.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify validates structural constraints and cryptographic signature.
func (c *SuccessionCertificateV3) Verify() error {
	if c.TenureEpoch == 0 {
		return ErrV3TenureEpochZero
	}
	if c.LineageSequence == 0 {
		return ErrV3LineageSequenceZero
	}
	if c.HeartbeatTimeoutDays < MinHeartbeatTimeoutDays || c.HeartbeatTimeoutDays > MaxHeartbeatTimeoutDays {
		return ErrV3TimeoutOutOfBounds
	}
	if c.ExpiresAt <= c.IssuedAt {
		return ErrV3ExpirationInvalid
	}
	if c.CurrentOwnerSigningKey == "" || c.Signature == "" {
		return ErrV3InvalidCertificateSignature
	}
	pubBytes, err := base64.StdEncoding.DecodeString(c.CurrentOwnerSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrV3InvalidCertificateSignature
	}
	sigBytes, err := base64.StdEncoding.DecodeString(c.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrV3InvalidCertificateSignature
	}
	canonical := c.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrV3InvalidCertificateSignature
	}
	return nil
}

// VerifyLineagePredecessor verifies that this certificate's PreviousCertificateHash matches the predecessor's hash.
func (c *SuccessionCertificateV3) VerifyLineagePredecessor(expectedPredecessorHash string) error {
	if c.PreviousCertificateHash != expectedPredecessorHash {
		return ErrV3PredecessorMismatch
	}
	return nil
}

// ToJSON serializes the V3 certificate to JSON.
func (c *SuccessionCertificateV3) ToJSON() (string, error) {
	bytes, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseSuccessionCertificateV3 deserializes a SuccessionCertificateV3 from JSON.
func ParseSuccessionCertificateV3(jsonStr string) (*SuccessionCertificateV3, error) {
	var cert SuccessionCertificateV3
	if err := json.Unmarshal([]byte(jsonStr), &cert); err != nil {
		return nil, fmt.Errorf("failed to parse succession certificate V3 JSON: %w", err)
	}
	if cert.Type != "group_succession_cert_v3" {
		return nil, errors.New("invalid succession certificate V3 type")
	}
	return &cert, nil
}

// ResolveSuccessionConflictV3 deterministically chooses the canonical winning certificate.
// Total ordering hierarchy (FROZEN IMPLEMENTATION CONTRACT):
// 1. Tenure Dominance: Higher TenureEpoch strictly wins (deposed owner sequence inflation defeated).
// 2. Lineage Monotonicity: Higher LineageSequence within the same tenure strictly wins.
// 3. Deterministic Equivocation Tie-Breaker: min(CertificateHash(A), CertificateHash(B)) in lexicographical order.
// ZERO dependency on wall-clock IssuedAt timestamp. Commutative and associative.
func ResolveSuccessionConflictV3(certA, certB *SuccessionCertificateV3) *SuccessionCertificateV3 {
	if certA == nil {
		return certB
	}
	if certB == nil {
		return certA
	}

	// 1. Tenure Dominance
	if certA.TenureEpoch > certB.TenureEpoch {
		return certA
	}
	if certB.TenureEpoch > certA.TenureEpoch {
		return certB
	}

	// 2. Lineage Sequence Monotonicity
	if certA.LineageSequence > certB.LineageSequence {
		return certA
	}
	if certB.LineageSequence > certA.LineageSequence {
		return certB
	}

	// 3. Deterministic Tie-Breaker: Lexicographically smaller CertificateHash wins
	if certA.CertificateHash() <= certB.CertificateHash() {
		return certA
	}
	return certB
}

// GroupOwnerTransitionCertificateV3 represents a voluntary handover from outgoing to incoming owner (Mode A).
type GroupOwnerTransitionCertificateV3 struct {
	Type                     string `json:"type"` // "group_succession_transition_v3"
	GroupID                  string `json:"group_id"`
	PriorTenureEpoch         uint64 `json:"prior_tenure_epoch"`
	NewTenureEpoch           uint64 `json:"new_tenure_epoch"` // MUST equal PriorTenureEpoch + 1
	PriorTenureFinalCertHash string `json:"prior_tenure_final_cert_hash"`
	OldOwnerFingerprint      string `json:"old_owner_fingerprint"`
	OldOwnerSigningKey       string `json:"old_owner_signing_key"`
	NewOwnerFingerprint      string `json:"new_owner_fingerprint"`
	NewOwnerSigningKey       string `json:"new_owner_signing_key"`
	RosterEpoch              uint64 `json:"roster_epoch"`
	RosterHash               string `json:"roster_hash"`
	TransferredAtMs          int64  `json:"transferred_at_ms"`
	OldOwnerSignature        string `json:"old_owner_signature"` // base64 ed25519
}

// CanonicalForSignature formats the canonical Netstring payload for transition signing.
func (t *GroupOwnerTransitionCertificateV3) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainSuccessionTransitionV3)...)
	appendCanonical(&buf, t.GroupID)
	buf = append(buf, []byte(strconv.FormatUint(t.PriorTenureEpoch, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatUint(t.NewTenureEpoch, 10)+"\n")...)
	appendCanonical(&buf, t.PriorTenureFinalCertHash)
	appendCanonical(&buf, t.OldOwnerFingerprint)
	appendCanonical(&buf, t.OldOwnerSigningKey)
	appendCanonical(&buf, t.NewOwnerFingerprint)
	appendCanonical(&buf, t.NewOwnerSigningKey)
	buf = append(buf, []byte(strconv.FormatUint(t.RosterEpoch, 10)+"\n")...)
	appendCanonical(&buf, t.RosterHash)
	buf = append(buf, []byte(strconv.FormatInt(t.TransferredAtMs, 10)+"\n")...)
	return string(buf)
}

// TransitionHash computes the SHA-256 digest of the signed transition envelope.
func (t *GroupOwnerTransitionCertificateV3) TransitionHash() string {
	canonical := t.CanonicalForSignature()
	raw := "2pchat-succession-transition-hash-v3\x00" + canonical + "\x00" + t.OldOwnerSignature
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

// Sign signs the transition envelope using the outgoing owner's Ed25519 private key.
func (t *GroupOwnerTransitionCertificateV3) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := t.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	t.OldOwnerSignature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify validates structural invariants and cryptographic signature of the transition envelope.
func (t *GroupOwnerTransitionCertificateV3) Verify() error {
	if t.PriorTenureEpoch == 0 {
		return ErrV3TenureEpochZero
	}
	if t.NewTenureEpoch != t.PriorTenureEpoch+1 {
		return ErrV3TransitionEpochStepInvalid
	}
	if t.OldOwnerSigningKey == "" || t.OldOwnerSignature == "" {
		return ErrV3TransitionInvalidSignature
	}
	pubBytes, err := base64.StdEncoding.DecodeString(t.OldOwnerSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrV3TransitionInvalidSignature
	}
	sigBytes, err := base64.StdEncoding.DecodeString(t.OldOwnerSignature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrV3TransitionInvalidSignature
	}
	canonical := t.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrV3TransitionInvalidSignature
	}
	return nil
}

// VerifyTenureTransitionV3 verifies that a Mode A transition envelope is cryptographically linked to the prior tenure head.
func VerifyTenureTransitionV3(priorCert *SuccessionCertificateV3, transition *GroupOwnerTransitionCertificateV3) error {
	if priorCert == nil || transition == nil {
		return errors.New("prior certificate and transition envelope cannot be nil")
	}
	if err := priorCert.Verify(); err != nil {
		return fmt.Errorf("prior certificate invalid: %w", err)
	}
	if err := transition.Verify(); err != nil {
		return fmt.Errorf("transition envelope invalid: %w", err)
	}
	if transition.GroupID != priorCert.GroupID {
		return errors.New("group_id mismatch between prior cert and transition")
	}
	if transition.PriorTenureEpoch != priorCert.TenureEpoch {
		return errors.New("transition prior tenure does not match prior cert tenure")
	}
	if transition.PriorTenureFinalCertHash != priorCert.CertificateHash() {
		return ErrV3PredecessorMismatch
	}
	if transition.OldOwnerSigningKey != priorCert.CurrentOwnerSigningKey {
		return ErrV3TransitionOwnerMismatch
	}
	return nil
}

// ToJSON serializes GroupOwnerTransitionCertificateV3 to JSON.
func (t *GroupOwnerTransitionCertificateV3) ToJSON() (string, error) {
	bytes, err := json.Marshal(t)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseGroupOwnerTransitionCertificateV3 deserializes a GroupOwnerTransitionCertificateV3 from JSON.
func ParseGroupOwnerTransitionCertificateV3(jsonStr string) (*GroupOwnerTransitionCertificateV3, error) {
	var tr GroupOwnerTransitionCertificateV3
	if err := json.Unmarshal([]byte(jsonStr), &tr); err != nil {
		return nil, fmt.Errorf("failed to parse transition certificate V3 JSON: %w", err)
	}
	if tr.Type != "group_succession_transition_v3" {
		return nil, errors.New("invalid transition certificate V3 type")
	}
	return &tr, nil
}

// WitnessAttestationV3 represents a co-signed liveness witness statement for an involuntary claim.
type WitnessAttestationV3 struct {
	WitnessFingerprint string `json:"witness_fingerprint"` // hex string
	WitnessSigningKey  string `json:"witness_signing_key"`  // base64 ed25519 public key
	AttestedAtMs       int64  `json:"attested_at_ms"`
	Signature          string `json:"signature"` // base64 ed25519 signature
}

// CanonicalForSignature formats the canonical representation for a witness attestation.
func (w *WitnessAttestationV3) CanonicalForSignature(groupID string, targetTenure uint64, certHash, claimant, lastHeartbeatHash string) string {
	var buf []byte
	buf = append(buf, []byte(DomainLivenessAttestationV3)...)
	appendCanonical(&buf, groupID)
	buf = append(buf, []byte(strconv.FormatUint(targetTenure, 10)+"\n")...)
	appendCanonical(&buf, certHash)
	appendCanonical(&buf, claimant)
	appendCanonical(&buf, w.WitnessFingerprint)
	appendCanonical(&buf, w.WitnessSigningKey)
	appendCanonical(&buf, lastHeartbeatHash)
	buf = append(buf, []byte(strconv.FormatInt(w.AttestedAtMs, 10)+"\n")...)
	return string(buf)
}

// Sign signs the witness attestation.
func (w *WitnessAttestationV3) Sign(privKey ed25519.PrivateKey, groupID string, targetTenure uint64, certHash, claimant, lastHeartbeatHash string) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := w.CanonicalForSignature(groupID, targetTenure, certHash, claimant, lastHeartbeatHash)
	sig := ed25519.Sign(privKey, []byte(canonical))
	w.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify verifies the witness attestation signature.
func (w *WitnessAttestationV3) Verify(groupID string, targetTenure uint64, certHash, claimant, lastHeartbeatHash string) error {
	if w.WitnessSigningKey == "" || w.Signature == "" {
		return ErrV3ClaimInvalidWitnessSig
	}
	pubBytes, err := base64.StdEncoding.DecodeString(w.WitnessSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrV3ClaimInvalidWitnessSig
	}
	sigBytes, err := base64.StdEncoding.DecodeString(w.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrV3ClaimInvalidWitnessSig
	}
	canonical := w.CanonicalForSignature(groupID, targetTenure, certHash, claimant, lastHeartbeatHash)
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrV3ClaimInvalidWitnessSig
	}
	return nil
}

// SuccessionClaimV3 represents an involuntary succession claim (Mode B).
type SuccessionClaimV3 struct {
	Type                string                 `json:"type"` // "group_succession_claim_v3"
	GroupID             string                 `json:"group_id"`
	TargetTenureEpoch   uint64                 `json:"target_tenure_epoch"`
	CertificateHash     string                 `json:"certificate_hash"`
	ClaimantFingerprint string                 `json:"claimant_fingerprint"`
	ClaimantSigningKey  string                 `json:"claimant_signing_key"`
	LastHeartbeatHash   string                 `json:"last_heartbeat_hash"`
	ClaimedAtMs         int64                  `json:"claimed_at_ms"`
	WitnessAttestations []WitnessAttestationV3 `json:"witness_attestations"`
	Signature           string                 `json:"signature"` // claimant base64 signature
}

// CanonicalForSignature formats the canonical representation for the claimant's signature on ClaimV3.
func (c *SuccessionClaimV3) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainSuccessionClaimV3)...)
	appendCanonical(&buf, c.GroupID)
	buf = append(buf, []byte(strconv.FormatUint(c.TargetTenureEpoch, 10)+"\n")...)
	appendCanonical(&buf, c.CertificateHash)
	appendCanonical(&buf, c.ClaimantFingerprint)
	appendCanonical(&buf, c.ClaimantSigningKey)
	appendCanonical(&buf, c.LastHeartbeatHash)
	buf = append(buf, []byte(strconv.FormatInt(c.ClaimedAtMs, 10)+"\n")...)
	return string(buf)
}

// ClaimHash produces the unique SHA-256 digest of the succession claim.
func (c *SuccessionClaimV3) ClaimHash() string {
	canonical := c.CanonicalForSignature()
	raw := "2pchat-succession-claim-hash-v3\x00" + canonical + "\x00" + c.Signature
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

// Sign signs the succession claim with the claimant's private key.
func (c *SuccessionClaimV3) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := c.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	c.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify validates the claimant's signature on the claim.
func (c *SuccessionClaimV3) Verify() error {
	if c.ClaimantSigningKey == "" || c.Signature == "" {
		return ErrV3ClaimInvalidClaimantSig
	}
	pubBytes, err := base64.StdEncoding.DecodeString(c.ClaimantSigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return ErrV3ClaimInvalidClaimantSig
	}
	sigBytes, err := base64.StdEncoding.DecodeString(c.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return ErrV3ClaimInvalidClaimantSig
	}
	canonical := c.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return ErrV3ClaimInvalidClaimantSig
	}
	return nil
}

// VerifySuccessionClaimV3 verifies all cryptographic, authorization, timeout, and witness quorum constraints for Mode B.
// Enforces frozen contract R-08 quorum formula: W = min(3, ceil(N/2)) where N = active roster members.
func VerifySuccessionClaimV3(
	cert *SuccessionCertificateV3,
	claim *SuccessionClaimV3,
	roster []RosterMemberEntryV3,
	expectedLastHeartbeatHash string,
	elapsedDays uint32,
) error {
	if cert == nil || claim == nil {
		return errors.New("certificate and claim cannot be nil")
	}
	if err := cert.Verify(); err != nil {
		return fmt.Errorf("certificate verification failed: %w", err)
	}
	if claim.GroupID != cert.GroupID {
		return errors.New("group_id mismatch between certificate and claim")
	}
	if claim.TargetTenureEpoch != cert.TenureEpoch+1 {
		return errors.New("claim target tenure epoch must be cert.TenureEpoch + 1")
	}
	if claim.CertificateHash != cert.CertificateHash() {
		return ErrV3ClaimCertHashMismatch
	}
	if claim.ClaimantFingerprint != cert.Successor || claim.ClaimantSigningKey != cert.SuccessorSigningKey {
		return ErrSuccessionClaimantMismatch
	}
	if claim.LastHeartbeatHash != expectedLastHeartbeatHash {
		return ErrSuccessionLogMismatchDeferred
	}
	if elapsedDays < cert.HeartbeatTimeoutDays {
		return ErrV3ClaimTimeoutNotElapsed
	}
	if err := claim.Verify(); err != nil {
		return err
	}

	// Active roster members index
	activeMembers := make(map[string]string) // deviceId (lowercase) -> signingKey
	for _, m := range roster {
		if m.Status == "ACTIVE" {
			activeMembers[strings.ToLower(m.DeviceID)] = m.SigningKey
		}
	}

	// Quorum formula: W = min(3, ceil(N/2))
	n := len(activeMembers)
	requiredQuorum := (n + 1) / 2
	if requiredQuorum > 3 {
		requiredQuorum = 3
	}
	if len(claim.WitnessAttestations) < requiredQuorum {
		return ErrV3ClaimInsufficientWitnesses
	}

	// Deduplication and witness verification
	seen := make(map[string]bool)
	for _, w := range claim.WitnessAttestations {
		wID := strings.ToLower(w.WitnessFingerprint)
		if seen[wID] {
			return ErrV3ClaimDuplicateWitness
		}
		seen[wID] = true

		key, exists := activeMembers[wID]
		if !exists || key != w.WitnessSigningKey {
			return ErrV3ClaimWitnessNotMember
		}

		if err := w.Verify(claim.GroupID, claim.TargetTenureEpoch, claim.CertificateHash, claim.ClaimantFingerprint, claim.LastHeartbeatHash); err != nil {
			return fmt.Errorf("witness %s signature verification failed: %w", w.WitnessFingerprint, err)
		}
	}

	return nil
}

// ToJSON serializes SuccessionClaimV3 to JSON.
func (c *SuccessionClaimV3) ToJSON() (string, error) {
	bytes, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// ParseSuccessionClaimV3 deserializes SuccessionClaimV3 from JSON.
func ParseSuccessionClaimV3(jsonStr string) (*SuccessionClaimV3, error) {
	var cl SuccessionClaimV3
	if err := json.Unmarshal([]byte(jsonStr), &cl); err != nil {
		return nil, fmt.Errorf("failed to parse succession claim V3 JSON: %w", err)
	}
	if cl.Type != "group_succession_claim_v3" {
		return nil, errors.New("invalid succession claim V3 type")
	}
	return &cl, nil
}

// RosterEpochRecordV3 represents a cryptographically linked DAG node recording a roster mutation.
type RosterEpochRecordV3 struct {
	GroupID               string `json:"group_id"`
	RosterEpoch           uint64 `json:"roster_epoch"`
	PreviousRecordHash    string `json:"previous_record_hash"`
	RosterHash            string `json:"roster_hash"`
	ModifiedByFingerprint string `json:"modified_by_fingerprint"`
	ModifiedBySigningKey  string `json:"modified_by_signing_key"`
	TimestampMs           int64  `json:"timestamp_ms"`
	Signature             string `json:"signature"` // base64 ed25519
}

// CanonicalForSignature formats the canonical representation for signing the roster epoch record.
func (r *RosterEpochRecordV3) CanonicalForSignature() string {
	var buf []byte
	buf = append(buf, []byte(DomainRosterEpochRecordV3)...)
	appendCanonical(&buf, r.GroupID)
	buf = append(buf, []byte(strconv.FormatUint(r.RosterEpoch, 10)+"\n")...)
	appendCanonical(&buf, r.PreviousRecordHash)
	appendCanonical(&buf, r.RosterHash)
	appendCanonical(&buf, r.ModifiedByFingerprint)
	appendCanonical(&buf, r.ModifiedBySigningKey)
	buf = append(buf, []byte(strconv.FormatInt(r.TimestampMs, 10)+"\n")...)
	return string(buf)
}

// RecordHash derives the SHA-256 digest of the signed roster epoch record.
func (r *RosterEpochRecordV3) RecordHash() string {
	canonical := r.CanonicalForSignature()
	raw := "2pchat-roster-epoch-hash-v3\x00" + canonical + "\x00" + r.Signature
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

// Sign signs the record using the modifier's Ed25519 private key.
func (r *RosterEpochRecordV3) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid private key size")
	}
	canonical := r.CanonicalForSignature()
	sig := ed25519.Sign(privKey, []byte(canonical))
	r.Signature = base64.StdEncoding.EncodeToString(sig)
	return nil
}

// Verify checks the cryptographic signature of the record.
func (r *RosterEpochRecordV3) Verify() error {
	if r.ModifiedBySigningKey == "" || r.Signature == "" {
		return errors.New("missing signing key or signature")
	}
	pubBytes, err := base64.StdEncoding.DecodeString(r.ModifiedBySigningKey)
	if err != nil || len(pubBytes) != ed25519.PublicKeySize {
		return errors.New("invalid signing key")
	}
	sigBytes, err := base64.StdEncoding.DecodeString(r.Signature)
	if err != nil || len(sigBytes) != ed25519.SignatureSize {
		return errors.New("invalid signature")
	}
	canonical := r.CanonicalForSignature()
	if !ed25519.Verify(pubBytes, []byte(canonical), sigBytes) {
		return errors.New("signature verification failed")
	}
	return nil
}

// VerifyRosterLineageV3 verifies that a chain of roster epoch records forms an unbroken cryptographic DAG from genesis.
func VerifyRosterLineageV3(chain []*RosterEpochRecordV3, genesisRosterHash string) error {
	if len(chain) == 0 {
		return errors.New("empty roster lineage chain")
	}

	for i, record := range chain {
		if err := record.Verify(); err != nil {
			return fmt.Errorf("record at epoch %d failed signature verification: %w", record.RosterEpoch, err)
		}

		if i == 0 {
			if record.RosterEpoch != 1 {
				return errors.New("genesis roster epoch must be 1")
			}
			if record.PreviousRecordHash != "" {
				return errors.New("genesis roster record previous hash must be empty")
			}
			if record.RosterHash != genesisRosterHash {
				return ErrV3RosterLineageBroken
			}
		} else {
			prev := chain[i-1]
			if record.RosterEpoch != prev.RosterEpoch+1 {
				return ErrV3RosterLineageBroken
			}
			if record.PreviousRecordHash != prev.RecordHash() {
				return ErrV3RosterLineageBroken
			}
		}
	}
	return nil
}

