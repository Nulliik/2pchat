package discovery

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	// DiscoveryRecordDomain is the domain separation context prepended to canonical bytes before signing.
	DiscoveryRecordDomain = "2pchat-discovery-record-v1\n"

	// DefaultClockSkewAllowance accounts for network jitter and out-of-sync system clocks.
	// Records are accepted up to 5 minutes before IssuedAt (future-dating tolerance)
	// and up to 5 minutes after ExpiresAt (clock lag tolerance).
	DefaultClockSkewAllowance = 5 * time.Minute

	// MaxDiscoveryRecordTTL limits the validity window to prevent long-lived replay windows.
	MaxDiscoveryRecordTTL = 1 * time.Hour

	// MaxDiscoveryRecordBytes limits the maximum serialized JSON payload to avoid memory/CPU exhaustion.
	MaxDiscoveryRecordBytes = 4096

	// MaxEndpointsPerRecord caps the maximum candidate endpoints per record.
	MaxEndpointsPerRecord = 16

	// MaxEndpointAddressLength limits the string length of each endpoint address.
	MaxEndpointAddressLength = 256

	// MaxSequenceGap limits the forward jump in sequence numbers to mitigate Sequence Exhaustion DoS.
	MaxSequenceGap = 1000
)

var (
	ErrRecordTooLarge         = fmt.Errorf("discovery record exceeds maximum size of %d bytes", MaxDiscoveryRecordBytes)
	ErrTooManyEndpoints       = fmt.Errorf("discovery record exceeds maximum of %d endpoints", MaxEndpointsPerRecord)
	ErrEndpointAddressTooLong = fmt.Errorf("endpoint address exceeds %d characters", MaxEndpointAddressLength)
	ErrRecordNotYetValid      = errors.New("discovery record not yet valid (issued in future beyond clock skew allowance)")
	ErrRecordExpired          = errors.New("discovery record expired beyond clock skew allowance")
	ErrTTLTooLong             = errors.New("discovery record TTL exceeds maximum allowed 1 hour")
	ErrInvalidTTL             = errors.New("discovery record expires_at must be strictly after issued_at")
	ErrInvalidSignature       = errors.New("invalid Ed25519 signature on discovery record")
	ErrInvalidIdentityKey     = errors.New("invalid Ed25519 identity key length (must be 32 bytes)")
	ErrFingerprintMismatch    = errors.New("discovery record fingerprint does not match expected peer fingerprint")
	ErrIdentityKeyMismatch    = errors.New("discovery record identity key does not match known peer signing key")
	ErrSequenceStale          = errors.New("discovery record sequence number is stale or replayed")
	ErrSequenceGapTooLarge    = errors.New("discovery record sequence gap exceeds maximum allowed forward jump (DoS protection)")
)

// Endpoint represents a network discovery candidate address and its transport classification.
type Endpoint struct {
	Address string `json:"address"`         // e.g. "192.168.1.50:50001", "xyz.onion:50001", "[200:1234::1]:50001"
	Class   string `json:"class,omitempty"` // e.g. "lan", "wan", "tor", "yggdrasil"
}

// UnmarshalJSON supports unmarshaling an Endpoint from either a simple string or a structured object.
func (e *Endpoint) UnmarshalJSON(data []byte) error {
	if len(data) > 0 && data[0] == '"' {
		var s string
		if err := json.Unmarshal(data, &s); err != nil {
			return err
		}
		e.Address = strings.TrimSpace(s)
		e.Class = ""
		return nil
	}
	type Alias Endpoint
	var aux Alias
	if err := json.Unmarshal(data, &aux); err != nil {
		return err
	}
	e.Address = strings.TrimSpace(aux.Address)
	e.Class = strings.TrimSpace(aux.Class)
	return nil
}

func (e Endpoint) String() string {
	return e.Address
}

// DiscoveryRecord represents an authenticated, replay-protected peer advertisement.
type DiscoveryRecord struct {
	Fingerprint     string     `json:"fingerprint"`      // Base64-encoded X25519 public key fingerprint
	IdentityKey     []byte     `json:"identity_key"`     // 32-byte Ed25519 verification public key
	Endpoints       []Endpoint `json:"endpoints"`        // Candidate endpoints (capped at 16)
	Seq             uint64     `json:"seq"`              // Monotonically increasing sequence number
	IssuedAt        int64      `json:"issued_at"`        // Epoch millisecond timestamp when record was created
	ExpiresAt       int64      `json:"expires_at"`       // Epoch millisecond timestamp when record expires
	TransportPolicy uint32     `json:"transport_policy"` // Allowed transport flags bitmask
	Signature       []byte     `json:"signature"`        // 64-byte Ed25519 signature over CanonicalBytes()
}

func appendCanonicalField(buf *[]byte, s string) {
	*buf = append(*buf, []byte(strconv.Itoa(len(s)))...)
	*buf = append(*buf, ':')
	*buf = append(*buf, []byte(s)...)
	*buf = append(*buf, '\n')
}

// CanonicalBytes constructs the deterministic length-prefixed representation for signature generation and verification.
// Endpoints are sorted lexicographically by Address to ensure canonical ordering.
func (r *DiscoveryRecord) CanonicalBytes() []byte {
	var buf []byte
	buf = append(buf, []byte(DiscoveryRecordDomain)...)

	appendCanonicalField(&buf, r.Fingerprint)
	appendCanonicalField(&buf, base64.StdEncoding.EncodeToString(r.IdentityKey))

	buf = append(buf, []byte(strconv.FormatUint(r.Seq, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(r.IssuedAt, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatInt(r.ExpiresAt, 10)+"\n")...)
	buf = append(buf, []byte(strconv.FormatUint(uint64(r.TransportPolicy), 10)+"\n")...)

	// Sort endpoints lexicographically by address (and class if present)
	sorted := make([]string, len(r.Endpoints))
	for i, ep := range r.Endpoints {
		if ep.Class != "" {
			sorted[i] = ep.Address + "|" + ep.Class
		} else {
			sorted[i] = ep.Address
		}
	}
	sort.Strings(sorted)

	buf = append(buf, []byte(strconv.Itoa(len(sorted))+"\n")...)
	for _, epStr := range sorted {
		appendCanonicalField(&buf, epStr)
	}

	return buf
}

// NewDiscoveryRecord creates and signs a new DiscoveryRecord using the local Ed25519 private key.
func NewDiscoveryRecord(
	fingerprint string,
	privKey ed25519.PrivateKey,
	endpoints []Endpoint,
	seq uint64,
	ttl time.Duration,
	policy uint32,
) (*DiscoveryRecord, error) {
	if len(privKey) != ed25519.PrivateKeySize {
		return nil, errors.New("invalid Ed25519 private key length")
	}
	if len(endpoints) > MaxEndpointsPerRecord {
		return nil, ErrTooManyEndpoints
	}
	for _, ep := range endpoints {
		if len(ep.Address) > MaxEndpointAddressLength {
			return nil, ErrEndpointAddressTooLong
		}
	}
	if ttl <= 0 {
		return nil, ErrInvalidTTL
	}
	if ttl > MaxDiscoveryRecordTTL {
		return nil, ErrTTLTooLong
	}

	pubKey := privKey.Public().(ed25519.PublicKey)
	now := time.Now()
	issuedAt := now.UnixMilli()
	expiresAt := now.Add(ttl).UnixMilli()

	r := &DiscoveryRecord{
		Fingerprint:     fingerprint,
		IdentityKey:     pubKey,
		Endpoints:       endpoints,
		Seq:             seq,
		IssuedAt:        issuedAt,
		ExpiresAt:       expiresAt,
		TransportPolicy: policy,
	}

	if err := r.Sign(privKey); err != nil {
		return nil, err
	}

	return r, nil
}

// Sign signs the canonical bytes using the given private key and populates Signature and IdentityKey.
func (r *DiscoveryRecord) Sign(privKey ed25519.PrivateKey) error {
	if len(privKey) != ed25519.PrivateKeySize {
		return errors.New("invalid Ed25519 private key length")
	}
	pubKey := privKey.Public().(ed25519.PublicKey)
	r.IdentityKey = pubKey
	canonical := r.CanonicalBytes()
	r.Signature = ed25519.Sign(privKey, canonical)
	return nil
}

// Verify checks the Ed25519 signature using r.IdentityKey or expectedPubKey if provided.
func (r *DiscoveryRecord) Verify(expectedPubKey ed25519.PublicKey) error {
	var pubKey ed25519.PublicKey
	if len(expectedPubKey) == ed25519.PublicKeySize {
		pubKey = expectedPubKey
		if len(r.IdentityKey) == ed25519.PublicKeySize && !bytes.Equal(r.IdentityKey, expectedPubKey) {
			return ErrIdentityKeyMismatch
		}
	} else if len(r.IdentityKey) == ed25519.PublicKeySize {
		pubKey = r.IdentityKey
	} else {
		return ErrInvalidIdentityKey
	}

	if len(r.Signature) != ed25519.SignatureSize {
		return ErrInvalidSignature
	}

	canonical := r.CanonicalBytes()
	if !ed25519.Verify(pubKey, canonical, r.Signature) {
		return ErrInvalidSignature
	}

	return nil
}

// IsValidAt checks whether the record is valid at time t within clockSkew allowance.
// Documented behavior:
// - Record is valid if t >= (IssuedAt - clockSkew) -> protects against future-dated records while allowing 5-min clock skew.
// - Record is valid if t <= (ExpiresAt + clockSkew) -> tolerates 5-min clock lag on the verifying device.
func (r *DiscoveryRecord) IsValidAt(t time.Time, clockSkew time.Duration) error {
	if r.ExpiresAt <= r.IssuedAt {
		return ErrInvalidTTL
	}
	if time.Duration(r.ExpiresAt-r.IssuedAt)*time.Millisecond > MaxDiscoveryRecordTTL {
		return ErrTTLTooLong
	}

	nowMs := t.UnixMilli()
	skewMs := clockSkew.Milliseconds()

	if nowMs < (r.IssuedAt - skewMs) {
		return ErrRecordNotYetValid
	}
	if nowMs > (r.ExpiresAt + skewMs) {
		return ErrRecordExpired
	}

	return nil
}

// ParseAndValidateRecord performs full, hardened verification of an incoming DiscoveryRecord JSON:
// 1. Enforces MaxDiscoveryRecordBytes limit before parsing.
// 2. Unmarshals JSON and enforces MaxEndpointsPerRecord and MaxEndpointAddressLength.
// 3. Verifies expected fingerprint if specified.
// 4. Verifies timestamp window with DefaultClockSkewAllowance.
// 5. Checks sequence monotonic progression and optionally guards against DoS gaps (> 1000).
// 6. Cryptographically verifies the Ed25519 signature.
func ParseAndValidateRecord(
	data []byte,
	expectedFp string,
	knownPubKey ed25519.PublicKey,
	lastSeenSeq uint64,
	now time.Time,
	checkSeqGap bool,
) (*DiscoveryRecord, error) {
	if len(data) > MaxDiscoveryRecordBytes {
		return nil, ErrRecordTooLarge
	}

	var record DiscoveryRecord
	if err := json.Unmarshal(data, &record); err != nil {
		return nil, fmt.Errorf("malformed discovery record JSON: %w", err)
	}

	if len(record.Endpoints) > MaxEndpointsPerRecord {
		return nil, ErrTooManyEndpoints
	}
	for _, ep := range record.Endpoints {
		if len(ep.Address) > MaxEndpointAddressLength {
			return nil, ErrEndpointAddressTooLong
		}
	}

	if expectedFp != "" && record.Fingerprint != expectedFp {
		return nil, ErrFingerprintMismatch
	}

	if err := record.IsValidAt(now, DefaultClockSkewAllowance); err != nil {
		return nil, err
	}

	// Sequence number replay protection
	if record.Seq <= lastSeenSeq && lastSeenSeq > 0 {
		return nil, ErrSequenceStale
	}

	// Sequence gap DoS protection: reject if gap exceeds MaxSequenceGap
	if checkSeqGap && lastSeenSeq > 0 && record.Seq > lastSeenSeq+MaxSequenceGap {
		return nil, ErrSequenceGapTooLarge
	}

	if err := record.Verify(knownPubKey); err != nil {
		return nil, err
	}

	return &record, nil
}
