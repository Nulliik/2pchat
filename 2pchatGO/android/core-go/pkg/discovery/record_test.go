package discovery

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func generateTestEdKey(t *testing.T) (ed25519.PrivateKey, ed25519.PublicKey) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate test Ed25519 key: %v", err)
	}
	return priv, pub
}

func TestDiscoveryRecordSignAndVerify(t *testing.T) {
	priv, pub := generateTestEdKey(t)
	fp := "test-fingerprint-base64=="
	endpoints := []Endpoint{
		{Address: "192.168.1.100:50001", Class: "lan"},
		{Address: "abcxyz.onion:50001", Class: "tor"},
	}

	record, err := NewDiscoveryRecord(fp, priv, endpoints, 10, 30*time.Minute, 1)
	if err != nil {
		t.Fatalf("NewDiscoveryRecord failed: %v", err)
	}

	if err := record.Verify(pub); err != nil {
		t.Fatalf("Verify with correct pubkey failed: %v", err)
	}

	// Verify using record's embedded identity key
	if err := record.Verify(nil); err != nil {
		t.Fatalf("Verify with nil expected pubkey failed: %v", err)
	}

	// Verify with wrong pubkey should fail
	_, wrongPub := generateTestEdKey(t)
	if err := record.Verify(wrongPub); err != ErrIdentityKeyMismatch {
		t.Fatalf("Expected ErrIdentityKeyMismatch, got %v", err)
	}
}

func TestDiscoveryRecordTamperDetection(t *testing.T) {
	priv, pub := generateTestEdKey(t)
	fp := "alice-fingerprint"
	endpoints := []Endpoint{{Address: "10.0.0.1:50001", Class: "lan"}}

	record, err := NewDiscoveryRecord(fp, priv, endpoints, 1, 15*time.Minute, 0)
	if err != nil {
		t.Fatalf("NewDiscoveryRecord failed: %v", err)
	}

	// Tamper with fingerprint
	recordTampered := *record
	recordTampered.Fingerprint = "eve-fingerprint"
	if err := recordTampered.Verify(pub); err != ErrInvalidSignature {
		t.Fatalf("Tampered fingerprint should fail verification, got: %v", err)
	}

	// Tamper with sequence number
	recordTampered = *record
	recordTampered.Seq = 999
	if err := recordTampered.Verify(pub); err != ErrInvalidSignature {
		t.Fatalf("Tampered sequence number should fail verification, got: %v", err)
	}

	// Tamper with endpoints
	recordTampered = *record
	recordTampered.Endpoints = []Endpoint{{Address: "10.0.0.2:50001", Class: "lan"}}
	if err := recordTampered.Verify(pub); err != ErrInvalidSignature {
		t.Fatalf("Tampered endpoints should fail verification, got: %v", err)
	}

	// Tamper with timestamps
	recordTampered = *record
	recordTampered.IssuedAt += 1000
	if err := recordTampered.Verify(pub); err != ErrInvalidSignature {
		t.Fatalf("Tampered IssuedAt should fail verification, got: %v", err)
	}

	// Tamper with transport policy
	recordTampered = *record
	recordTampered.TransportPolicy = 2
	if err := recordTampered.Verify(pub); err != ErrInvalidSignature {
		t.Fatalf("Tampered TransportPolicy should fail verification, got: %v", err)
	}
}

func TestDiscoveryRecordClockSkewTolerance(t *testing.T) {
	priv, _ := generateTestEdKey(t)
	fp := "bob-fingerprint"
	endpoints := []Endpoint{{Address: "1.2.3.4:50001"}}

	now := time.Now()

	// Record issued now, expires in 20 minutes
	record, err := NewDiscoveryRecord(fp, priv, endpoints, 1, 20*time.Minute, 0)
	if err != nil {
		t.Fatalf("NewDiscoveryRecord failed: %v", err)
	}

	// 1. Valid right now
	if err := record.IsValidAt(now, DefaultClockSkewAllowance); err != nil {
		t.Fatalf("Expected valid right now, got %v", err)
	}

	// 2. Future-dated within 5-min clock skew tolerance (e.g. verified on device whose clock is 3 min slow)
	slowVerifierTime := now.Add(-3 * time.Minute)
	if err := record.IsValidAt(slowVerifierTime, DefaultClockSkewAllowance); err != nil {
		t.Fatalf("Expected valid within future clock skew tolerance, got %v", err)
	}

	// 3. Future-dated beyond 5-min tolerance (e.g. 6 min slow)
	tooSlowVerifierTime := now.Add(-6 * time.Minute)
	if err := record.IsValidAt(tooSlowVerifierTime, DefaultClockSkewAllowance); err != ErrRecordNotYetValid {
		t.Fatalf("Expected ErrRecordNotYetValid, got %v", err)
	}

	// 4. Expired within 5-min clock lag tolerance (e.g. 22 minutes after issue, expires at 20 min)
	laggedVerifierTime := now.Add(22 * time.Minute)
	if err := record.IsValidAt(laggedVerifierTime, DefaultClockSkewAllowance); err != nil {
		t.Fatalf("Expected valid within expiration clock lag tolerance, got %v", err)
	}

	// 5. Expired beyond 5-min clock lag tolerance (e.g. 26 minutes after issue)
	tooLaggedVerifierTime := now.Add(26 * time.Minute)
	if err := record.IsValidAt(tooLaggedVerifierTime, DefaultClockSkewAllowance); err != ErrRecordExpired {
		t.Fatalf("Expected ErrRecordExpired, got %v", err)
	}
}

func TestDiscoveryRecordTTLRestrictions(t *testing.T) {
	priv, _ := generateTestEdKey(t)
	fp := "test-fingerprint"
	endpoints := []Endpoint{{Address: "1.2.3.4:50001"}}

	// TTL <= 0 rejected
	_, err := NewDiscoveryRecord(fp, priv, endpoints, 1, 0, 0)
	if err != ErrInvalidTTL {
		t.Fatalf("Expected ErrInvalidTTL, got %v", err)
	}

	// TTL > 1 hour rejected
	_, err = NewDiscoveryRecord(fp, priv, endpoints, 1, 61*time.Minute, 0)
	if err != ErrTTLTooLong {
		t.Fatalf("Expected ErrTTLTooLong, got %v", err)
	}
}

func TestDiscoveryRecordSequenceReplayAndGapProtection(t *testing.T) {
	priv, pub := generateTestEdKey(t)
	fp := "charlie-fingerprint"
	endpoints := []Endpoint{{Address: "192.168.1.5:50001"}}

	// Record with Seq = 100
	record, err := NewDiscoveryRecord(fp, priv, endpoints, 100, 10*time.Minute, 0)
	if err != nil {
		t.Fatalf("NewDiscoveryRecord failed: %v", err)
	}
	recordBytes, err := json.Marshal(record)
	if err != nil {
		t.Fatalf("Marshal failed: %v", err)
	}

	now := time.Now()

	// 1. Happy path: lastSeenSeq = 50, record.Seq = 100
	parsed, err := ParseAndValidateRecord(recordBytes, fp, pub, 50, now, true)
	if err != nil {
		t.Fatalf("Expected valid record, got %v", err)
	}
	if parsed.Seq != 100 {
		t.Fatalf("Expected seq 100, got %d", parsed.Seq)
	}

	// 2. Replay attack: lastSeenSeq = 100, record.Seq = 100
	_, err = ParseAndValidateRecord(recordBytes, fp, pub, 100, now, true)
	if err != ErrSequenceStale {
		t.Fatalf("Expected ErrSequenceStale on equal seq, got %v", err)
	}

	// 3. Stale record: lastSeenSeq = 150, record.Seq = 100
	_, err = ParseAndValidateRecord(recordBytes, fp, pub, 150, now, true)
	if err != ErrSequenceStale {
		t.Fatalf("Expected ErrSequenceStale on older seq, got %v", err)
	}

	// 4. DoS gap attack: record with Seq = 2000 while lastSeenSeq = 10 (gap = 1990 > MaxSequenceGap 1000)
	recordGap, err := NewDiscoveryRecord(fp, priv, endpoints, 2000, 10*time.Minute, 0)
	if err != nil {
		t.Fatalf("NewDiscoveryRecord failed: %v", err)
	}
	recordGapBytes, _ := json.Marshal(recordGap)

	_, err = ParseAndValidateRecord(recordGapBytes, fp, pub, 10, now, true)
	if err != ErrSequenceGapTooLarge {
		t.Fatalf("Expected ErrSequenceGapTooLarge, got %v", err)
	}
}

func TestDiscoveryRecordSizeAndEndpointLimits(t *testing.T) {
	priv, pub := generateTestEdKey(t)
	fp := "limit-fingerprint"

	// 1. More than 16 endpoints
	var tooManyEndpoints []Endpoint
	for i := 0; i < 17; i++ {
		tooManyEndpoints = append(tooManyEndpoints, Endpoint{Address: "1.1.1.1:50001"})
	}
	_, err := NewDiscoveryRecord(fp, priv, tooManyEndpoints, 1, 10*time.Minute, 0)
	if err != ErrTooManyEndpoints {
		t.Fatalf("Expected ErrTooManyEndpoints, got %v", err)
	}

	// 2. Endpoint address too long (> 256 chars)
	longAddress := strings.Repeat("a", 257)
	_, err = NewDiscoveryRecord(fp, priv, []Endpoint{{Address: longAddress}}, 1, 10*time.Minute, 0)
	if err != ErrEndpointAddressTooLong {
		t.Fatalf("Expected ErrEndpointAddressTooLong, got %v", err)
	}

	// 3. Oversized serialized payload (> 4096 bytes)
	hugePayload := make([]byte, 4097)
	_, err = ParseAndValidateRecord(hugePayload, fp, pub, 0, time.Now(), false)
	if err != ErrRecordTooLarge {
		t.Fatalf("Expected ErrRecordTooLarge, got %v", err)
	}
}

func TestEndpointJSONPolymorphism(t *testing.T) {
	// JSON with simple string array
	rawJSON := `{"address":"192.168.1.1:50001"}`
	var ep1 Endpoint
	if err := json.Unmarshal([]byte(rawJSON), &ep1); err != nil {
		t.Fatalf("Unmarshal string endpoint failed: %v", err)
	}
	if ep1.Address != "192.168.1.1:50001" || ep1.Class != "" {
		t.Fatalf("Unexpected parsed endpoint: %+v", ep1)
	}

	// JSON with direct quoted string
	quotedJSON := `"10.0.0.1:50001"`
	var ep2 Endpoint
	if err := json.Unmarshal([]byte(quotedJSON), &ep2); err != nil {
		t.Fatalf("Unmarshal quoted string failed: %v", err)
	}
	if ep2.Address != "10.0.0.1:50001" {
		t.Fatalf("Unexpected parsed endpoint: %+v", ep2)
	}

	// JSON with structured object
	objJSON := `{"address":"tor.onion:50001","class":"tor"}`
	var ep3 Endpoint
	if err := json.Unmarshal([]byte(objJSON), &ep3); err != nil {
		t.Fatalf("Unmarshal structured object failed: %v", err)
	}
	if ep3.Address != "tor.onion:50001" || ep3.Class != "tor" {
		t.Fatalf("Unexpected parsed endpoint: %+v", ep3)
	}
}
