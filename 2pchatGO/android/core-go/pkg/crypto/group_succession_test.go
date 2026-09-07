package crypto

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"testing"
	"time"
)

func genTestEd25519Key(t *testing.T) (ed25519.PrivateKey, string) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("ed25519.GenerateKey failed: %v", err)
	}
	return priv, base64.StdEncoding.EncodeToString(pub)
}

func TestSuccessionCertSignVerify(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()
	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                "test-group-123",
		CurrentOwner:           "owner-fingerprint-001",
		CurrentOwnerSigningKey: ownerPub,
		Successor:              "successor-fingerprint-002",
		SuccessorSigningKey:    successorPub,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               now,
		ExpiresAt:              now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}

	if err := cert.Sign(ownerPriv); err != nil {
		t.Fatalf("cert.Sign failed: %v", err)
	}

	if err := cert.Verify(); err != nil {
		t.Fatalf("cert.Verify failed: %v", err)
	}

	hash := cert.CertificateHash()
	if len(hash) != 64 {
		t.Fatalf("unexpected certificate hash length: %d", len(hash))
	}
}

func TestSuccessionCertTamperRejection(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()
	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                "test-group-123",
		CurrentOwner:           "owner-fingerprint-001",
		CurrentOwnerSigningKey: ownerPub,
		Successor:              "successor-fingerprint-002",
		SuccessorSigningKey:    successorPub,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               now,
		ExpiresAt:              now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(ownerPriv)

	// Tamper timeout days
	tampered := *cert
	tampered.HeartbeatTimeoutDays = 7
	if err := tampered.Verify(); err == nil {
		t.Fatalf("expected verification failure on tampered HeartbeatTimeoutDays")
	}

	// Tamper successor
	tampered = *cert
	tampered.Successor = "attacker-fingerprint-666"
	if err := tampered.Verify(); err == nil {
		t.Fatalf("expected verification failure on tampered Successor")
	}

	// Tamper group id
	tampered = *cert
	tampered.GroupID = "evil-group"
	if err := tampered.Verify(); err == nil {
		t.Fatalf("expected verification failure on tampered GroupID")
	}
}

func TestSuccessionTimeoutBoundaries(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, successorPub := genTestEd25519Key(t)
	now := time.Now().UnixMilli()

	makeCert := func(days uint32) *SuccessionCertificate {
		c := &SuccessionCertificate{
			Type:                   "succession_certificate_v1",
			GroupID:                "test-group-bounds",
			CurrentOwner:           "owner-fingerprint-001",
			CurrentOwnerSigningKey: ownerPub,
			Successor:              "successor-fingerprint-002",
			SuccessorSigningKey:    successorPub,
			HeartbeatTimeoutDays:   days,
			IssuedAt:               now,
			ExpiresAt:              now + int64(SuccessionCertValidityDuration/time.Millisecond),
		}
		_ = c.Sign(ownerPriv)
		return c
	}

	// Underflow: 6 days
	if err := makeCert(6).Verify(); err != ErrSuccessionTimeoutOutOfBounds {
		t.Fatalf("expected ErrSuccessionTimeoutOutOfBounds for 6 days, got: %v", err)
	}

	// Lower boundary: 7 days
	if err := makeCert(7).Verify(); err != nil {
		t.Fatalf("expected 7 days to be valid, got: %v", err)
	}

	// Presets: 30, 90 days
	if err := makeCert(30).Verify(); err != nil {
		t.Fatalf("expected 30 days to be valid, got: %v", err)
	}
	if err := makeCert(90).Verify(); err != nil {
		t.Fatalf("expected 90 days to be valid, got: %v", err)
	}

	// Upper boundary: 180 days
	if err := makeCert(180).Verify(); err != nil {
		t.Fatalf("expected 180 days to be valid, got: %v", err)
	}

	// Overflow: 181 days
	if err := makeCert(181).Verify(); err != ErrSuccessionTimeoutOutOfBounds {
		t.Fatalf("expected ErrSuccessionTimeoutOutOfBounds for 181 days, got: %v", err)
	}
}

func TestOwnerHeartbeatChainingAndGenesis(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	now := time.Now().UnixMilli()

	// 1. Genesis heartbeat (PreviousEventHash = "")
	hbGenesis := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "test-group-chain",
		Owner:             "owner-fp-1",
		OwnerSigningKey:   ownerPub,
		Sequence:          1,
		Timestamp:         now,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	if err := hbGenesis.Sign(ownerPriv); err != nil {
		t.Fatalf("hbGenesis.Sign failed: %v", err)
	}
	if err := hbGenesis.Verify(); err != nil {
		t.Fatalf("hbGenesis.Verify failed: %v", err)
	}
	genesisHash := hbGenesis.EventHash()
	if len(genesisHash) != 64 {
		t.Fatalf("unexpected genesis hash len: %d", len(genesisHash))
	}

	// 2. Chained second heartbeat
	hb2 := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "test-group-chain",
		Owner:             "owner-fp-1",
		OwnerSigningKey:   ownerPub,
		Sequence:          2,
		Timestamp:         now + 86400000,
		PreviousEventHash: genesisHash,
	}
	if err := hb2.Sign(ownerPriv); err != nil {
		t.Fatalf("hb2.Sign failed: %v", err)
	}
	if err := hb2.Verify(); err != nil {
		t.Fatalf("hb2.Verify failed: %v", err)
	}
	if hb2.PreviousEventHash != genesisHash {
		t.Fatalf("expected hb2 PreviousEventHash to match genesisHash")
	}

	// Tampering test
	tampered := *hb2
	tampered.Sequence = 99
	if err := tampered.Verify(); err == nil {
		t.Fatalf("expected verification failure on tampered sequence")
	}
}

func TestSuccessionRevocationSignVerify(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	now := time.Now().UnixMilli()

	rev := &SuccessionRevocation{
		Type:            "succession_revocation",
		GroupID:         "test-group-rev",
		Owner:           "owner-fp-rev",
		OwnerSigningKey: ownerPub,
		CertificateHash: "deadbeef0000111122223333444455556666777788889999aaaabbbbccccdddd",
		RevokedAt:       now,
	}

	if err := rev.Sign(ownerPriv); err != nil {
		t.Fatalf("rev.Sign failed: %v", err)
	}
	if err := rev.Verify(); err != nil {
		t.Fatalf("rev.Verify failed: %v", err)
	}

	// Tamper test
	tampered := *rev
	tampered.CertificateHash = "0000000000000000000000000000000000000000000000000000000000000000"
	if err := tampered.Verify(); err == nil {
		t.Fatalf("expected verification failure on tampered CertificateHash")
	}
}

func TestVerifySuccessionClaim_ValidFlow(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	successorPriv, successorPub := genTestEd25519Key(t)

	issuedAt := time.Now().UnixMilli() - 35*24*60*60*1000 // 35 days ago
	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                "group-succession-001",
		CurrentOwner:           "owner-fp-alpha",
		CurrentOwnerSigningKey: ownerPub,
		Successor:              "successor-fp-beta",
		SuccessorSigningKey:    successorPub,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               issuedAt,
		ExpiresAt:              issuedAt + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(ownerPriv)

	lastHb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "group-succession-001",
		Owner:             "owner-fp-alpha",
		OwnerSigningKey:   ownerPub,
		Sequence:          5,
		Timestamp:         issuedAt + 2*24*60*60*1000, // 33 days ago (owner became inactive here)
		PreviousEventHash: "some-prev-hash",
	}
	_ = lastHb.Sign(ownerPriv)

	now := time.Now().UnixMilli()
	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                "group-succession-001",
		Claimant:               "successor-fp-beta",
		ClaimantSigningKey:     successorPub,
		CertificateHash:        cert.CertificateHash(),
		LastHeartbeatEventHash: lastHb.EventHash(),
		ClaimedAt:              now,
	}
	if err := claim.Sign(successorPriv); err != nil {
		t.Fatalf("claim.Sign failed: %v", err)
	}

	// Verification must succeed (33 days elapsed >= 30 days required)
	if err := VerifySuccessionClaim(cert, claim, lastHb, false, now); err != nil {
		t.Fatalf("VerifySuccessionClaim failed for valid claim: %v", err)
	}
}

func TestVerifySuccessionClaim_PrematureRejected(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	successorPriv, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()
	// Last heartbeat was only 10 days ago, while timeout is 30 days
	hbTime := now - 10*24*60*60*1000

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                "group-premature",
		CurrentOwner:           "owner-fp-alpha",
		CurrentOwnerSigningKey: ownerPub,
		Successor:              "successor-fp-beta",
		SuccessorSigningKey:    successorPub,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               hbTime,
		ExpiresAt:              now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(ownerPriv)

	lastHb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "group-premature",
		Owner:             "owner-fp-alpha",
		OwnerSigningKey:   ownerPub,
		Sequence:          1,
		Timestamp:         hbTime,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = lastHb.Sign(ownerPriv)

	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                "group-premature",
		Claimant:               "successor-fp-beta",
		ClaimantSigningKey:     successorPub,
		CertificateHash:        cert.CertificateHash(),
		LastHeartbeatEventHash: lastHb.EventHash(),
		ClaimedAt:              now,
	}
	_ = claim.Sign(successorPriv)

	err := VerifySuccessionClaim(cert, claim, lastHb, false, now)
	if err != ErrSuccessionPrematureClaim {
		t.Fatalf("expected ErrSuccessionPrematureClaim, got: %v", err)
	}
}

func TestVerifySuccessionClaim_LogMismatchDeferred(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	successorPriv, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()
	hbTime := now - 35*24*60*60*1000

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                "group-mismatch",
		CurrentOwner:           "owner-fp-alpha",
		CurrentOwnerSigningKey: ownerPub,
		Successor:              "successor-fp-beta",
		SuccessorSigningKey:    successorPub,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               hbTime,
		ExpiresAt:              now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(ownerPriv)

	lastHb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "group-mismatch",
		Owner:             "owner-fp-alpha",
		OwnerSigningKey:   ownerPub,
		Sequence:          1,
		Timestamp:         hbTime,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = lastHb.Sign(ownerPriv)

	// Claimant references a different hash (partition or out-of-sync control log)
	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                "group-mismatch",
		Claimant:               "successor-fp-beta",
		ClaimantSigningKey:     successorPub,
		CertificateHash:        cert.CertificateHash(),
		LastHeartbeatEventHash: "stale-or-future-event-hash-9999",
		ClaimedAt:              now,
	}
	_ = claim.Sign(successorPriv)

	err := VerifySuccessionClaim(cert, claim, lastHb, false, now)
	if err != ErrSuccessionLogMismatchDeferred {
		t.Fatalf("expected ErrSuccessionLogMismatchDeferred, got: %v", err)
	}
}

func TestVerifySuccessionClaim_RevokedRejected(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	successorPriv, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()
	hbTime := now - 35*24*60*60*1000

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                "group-revoked",
		CurrentOwner:           "owner-fp-alpha",
		CurrentOwnerSigningKey: ownerPub,
		Successor:              "successor-fp-beta",
		SuccessorSigningKey:    successorPub,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               hbTime,
		ExpiresAt:              now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(ownerPriv)

	lastHb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "group-revoked",
		Owner:             "owner-fp-alpha",
		OwnerSigningKey:   ownerPub,
		Sequence:          1,
		Timestamp:         hbTime,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = lastHb.Sign(ownerPriv)

	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                "group-revoked",
		Claimant:               "successor-fp-beta",
		ClaimantSigningKey:     successorPub,
		CertificateHash:        cert.CertificateHash(),
		LastHeartbeatEventHash: lastHb.EventHash(),
		ClaimedAt:              now,
	}
	_ = claim.Sign(successorPriv)

	// isRevoked is true
	err := VerifySuccessionClaim(cert, claim, lastHb, true, now)
	if err != ErrSuccessionCertRevoked {
		t.Fatalf("expected ErrSuccessionCertRevoked, got: %v", err)
	}
}

func TestVerifySuccessionClaim_ExpiredRejected(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	successorPriv, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()
	// Cert expired 1 day ago
	issuedAt := now - 366*24*60*60*1000
	expiresAt := now - 1*24*60*60*1000
	hbTime := issuedAt

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                "group-expired",
		CurrentOwner:           "owner-fp-alpha",
		CurrentOwnerSigningKey: ownerPub,
		Successor:              "successor-fp-beta",
		SuccessorSigningKey:    successorPub,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               issuedAt,
		ExpiresAt:              expiresAt,
	}
	_ = cert.Sign(ownerPriv)

	lastHb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "group-expired",
		Owner:             "owner-fp-alpha",
		OwnerSigningKey:   ownerPub,
		Sequence:          1,
		Timestamp:         hbTime,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = lastHb.Sign(ownerPriv)

	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                "group-expired",
		Claimant:               "successor-fp-beta",
		ClaimantSigningKey:     successorPub,
		CertificateHash:        cert.CertificateHash(),
		LastHeartbeatEventHash: lastHb.EventHash(),
		ClaimedAt:              now,
	}
	_ = claim.Sign(successorPriv)

	err := VerifySuccessionClaim(cert, claim, lastHb, false, now)
	if err != ErrSuccessionCertExpired {
		t.Fatalf("expected ErrSuccessionCertExpired, got: %v", err)
	}
}

func TestVerifySuccessionClaim_ClaimantMismatch(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, successorPub := genTestEd25519Key(t)
	attackerPriv, attackerPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()
	hbTime := now - 35*24*60*60*1000

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                "group-mismatch-claimant",
		CurrentOwner:           "owner-fp-alpha",
		CurrentOwnerSigningKey: ownerPub,
		Successor:              "successor-fp-beta",
		SuccessorSigningKey:    successorPub,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               hbTime,
		ExpiresAt:              now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(ownerPriv)

	lastHb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "group-mismatch-claimant",
		Owner:             "owner-fp-alpha",
		OwnerSigningKey:   ownerPub,
		Sequence:          1,
		Timestamp:         hbTime,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = lastHb.Sign(ownerPriv)

	// Attacker tries to submit a claim for themselves
	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                "group-mismatch-claimant",
		Claimant:               "attacker-fp-charlie",
		ClaimantSigningKey:     attackerPub,
		CertificateHash:        cert.CertificateHash(),
		LastHeartbeatEventHash: lastHb.EventHash(),
		ClaimedAt:              now,
	}
	_ = claim.Sign(attackerPriv)

	err := VerifySuccessionClaim(cert, claim, lastHb, false, now)
	if err != ErrSuccessionClaimantMismatch {
		t.Fatalf("expected ErrSuccessionClaimantMismatch, got: %v", err)
	}
}
