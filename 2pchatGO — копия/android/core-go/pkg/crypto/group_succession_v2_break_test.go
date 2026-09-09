package crypto

import (
	"testing"
	"time"
)

// TestBreakV2_EquivocationForkWithSkewedTimestamp demonstrates that because ResolveSuccessionCertificateConflict
// uses IssuedAt as the first tie-breaker for same-sequence certificates, a creator can easily bypass the
// hash tie-breaker and arbitrarily dictate the winner across partitions by forging a future IssuedAt timestamp.
func TestBreakV2_EquivocationForkWithSkewedTimestamp(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, bobPub := genTestEd25519Key(t)
	_, charliePub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()

	// Legitimate certificate issued to Bob at real time
	certBob := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "group-break-equivocation",
		LineageSequence:         2,
		PreviousCertificateHash: "genesis-hash",
		GroupEpoch:              1,
		RosterHash:              "roster-hash-1",
		CurrentOwner:            "owner-fp",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "bob-fp",
		SuccessorSigningKey:     bobPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certBob.Sign(ownerPriv)

	// Malicious/Equivocated certificate issued to Charlie with forged +1 hour IssuedAt
	certCharlie := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "group-break-equivocation",
		LineageSequence:         2,
		PreviousCertificateHash: "genesis-hash",
		GroupEpoch:              1,
		RosterHash:              "roster-hash-1",
		CurrentOwner:            "owner-fp",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "charlie-fp",
		SuccessorSigningKey:     charliePub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now + 3600*1000, // Forged future timestamp
		ExpiresAt:               now + 3600*1000 + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certCharlie.Sign(ownerPriv)

	winner := ResolveSuccessionCertificateConflict(certBob, certCharlie)

	// Charlie wins simply because IssuedAt is higher, completely bypassing deterministic hash tie-breaking
	if winner != certCharlie {
		t.Fatalf("expected certCharlie to win based on skewed IssuedAt")
	}
	t.Logf("Adversarial finding confirmed: IssuedAt tie-breaker allows timestamp manipulation to dominate conflict resolution")
}

// TestBreakV2_UnvalidatedPreviousCertificateHash proves that VerifySuccessionClaimV2
// completely fails to verify PreviousCertificateHash against any prior certificate.
func TestBreakV2_UnvalidatedPreviousCertificateHash(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	successorPriv, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()

	hb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "group-break-prevhash",
		Owner:             "owner-fp",
		OwnerSigningKey:   ownerPub,
		Sequence:          1,
		Timestamp:         now - 35*24*60*60*1000,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = hb.Sign(ownerPriv)

	// Bogus certificate claiming Sequence 50, but with a completely fake non-existent PreviousCertificateHash
	certBogus := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "group-break-prevhash",
		LineageSequence:         50,
		PreviousCertificateHash: "totally-bogus-non-existent-parent-hash-12345",
		GroupEpoch:              5,
		RosterHash:              "arbitrary-roster",
		CurrentOwner:            "owner-fp",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "successor-fp",
		SuccessorSigningKey:     successorPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                hb.Timestamp,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certBogus.Sign(ownerPriv)

	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                "group-break-prevhash",
		Claimant:               "successor-fp",
		ClaimantSigningKey:     successorPub,
		CertificateHash:        certBogus.CertificateHash(),
		LastHeartbeatEventHash: hb.EventHash(),
		ClaimedAt:              now,
	}
	_ = claim.Sign(successorPriv)

	// VerifySuccessionClaimV2 succeeds despite the predecessor certificate never existing!
	err := VerifySuccessionClaimV2(certBogus, claim, hb, false, now)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	t.Logf("Adversarial finding confirmed: VerifySuccessionClaimV2 does NOT validate PreviousCertificateHash against actual history")
}

// TestBreakV2_UnvalidatedRosterHash proves that VerifySuccessionClaimV2
// does not check RosterHash against any membership roster.
func TestBreakV2_UnvalidatedRosterHash(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	successorPriv, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()

	hb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           "group-break-roster",
		Owner:             "owner-fp",
		OwnerSigningKey:   ownerPub,
		Sequence:          1,
		Timestamp:         now - 35*24*60*60*1000,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = hb.Sign(ownerPriv)

	// Certificate with fraudulent RosterHash
	certFraudRoster := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "group-break-roster",
		LineageSequence:         1,
		PreviousCertificateHash: GenesisPreviousEventHash,
		GroupEpoch:              0,
		RosterHash:              "fraudulent-roster-hash-666",
		CurrentOwner:            "owner-fp",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "successor-fp",
		SuccessorSigningKey:     successorPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                hb.Timestamp,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certFraudRoster.Sign(ownerPriv)

	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                "group-break-roster",
		Claimant:               "successor-fp",
		ClaimantSigningKey:     successorPub,
		CertificateHash:        certFraudRoster.CertificateHash(),
		LastHeartbeatEventHash: hb.EventHash(),
		ClaimedAt:              now,
	}
	_ = claim.Sign(successorPriv)

	// VerifySuccessionClaimV2 succeeds because RosterHash is never evaluated against current members!
	err := VerifySuccessionClaimV2(certFraudRoster, claim, hb, false, now)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	t.Logf("Adversarial finding confirmed: RosterHash in SuccessionCertificateV2 is not verified by VerifySuccessionClaimV2")
}

// TestBreakV2_CrossTenureLineageInversion proves that ResolveSuccessionCertificateConflict
// causes cross-tenure inversion: if an old deposed owner had LineageSequence 5, and a new
// legitimate owner issues a certificate starting at LineageSequence 1, the old deposed owner's
// dead certificate will defeat the new legitimate owner's certificate!
func TestBreakV2_CrossTenureLineageInversion(t *testing.T) {
	alicePriv, alicePub := genTestEd25519Key(t)
	bobPriv, bobPub := genTestEd25519Key(t)
	_, charliePub := genTestEd25519Key(t)
	_, davePub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()

	// Alice (Tenure 1) had issued 5 certificates over her lifetime, reaching LineageSequence = 5
	certAlice := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "group-break-tenure-inversion",
		LineageSequence:         5,
		PreviousCertificateHash: "cert-4-hash",
		GroupEpoch:              10,
		RosterHash:              "roster-10",
		CurrentOwner:            "alice-fp",
		CurrentOwnerSigningKey:  alicePub,
		Successor:               "charlie-fp",
		SuccessorSigningKey:     charliePub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now - 100*24*60*60*1000,
		ExpiresAt:               now + 200*24*60*60*1000,
	}
	_ = certAlice.Sign(alicePriv)

	// Bob is now the legitimate owner (Tenure 2, GroupEpoch 11).
	// Bob designates Dave as successor with LineageSequence = 1 (local tenure sequence counter)
	certBob := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "group-break-tenure-inversion",
		LineageSequence:         1,
		PreviousCertificateHash: GenesisPreviousEventHash,
		GroupEpoch:              11,
		RosterHash:              "roster-11",
		CurrentOwner:            "bob-fp",
		CurrentOwnerSigningKey:  bobPub,
		Successor:               "dave-fp",
		SuccessorSigningKey:     davePub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certBob.Sign(bobPriv)

	// Conflict resolution compares LineageSequence (5 > 1) without checking CurrentOwner or GroupEpoch!
	winner := ResolveSuccessionCertificateConflict(certAlice, certBob)

	// Alice's old cert wins, defeating the CURRENT active owner Bob!
	if winner != certAlice {
		t.Fatalf("expected certAlice to win because 5 > 1")
	}

	t.Logf("Adversarial finding confirmed: Cross-tenure lineage inversion allows a deposed owner's higher sequence to defeat the current owner's new certificate")
}

// TestBreakV2_UnchainedSequenceJump demonstrates that an unverified sequence jump (e.g. jumping
// directly from Seq 1 to Seq 999999) will unconditionally wipe out all valid certificates
// without any node verifying whether intermediate certificates actually existed.
func TestBreakV2_UnchainedSequenceJump(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, bobPub := genTestEd25519Key(t)
	_, attackerPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()

	certLegitimate := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "group-break-jump",
		LineageSequence:         2,
		PreviousCertificateHash: "cert-1-hash",
		GroupEpoch:              1,
		RosterHash:              "roster-1",
		CurrentOwner:            "owner-fp",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "bob-fp",
		SuccessorSigningKey:     bobPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certLegitimate.Sign(ownerPriv)

	// A rogue jump to Seq 999999
	certJump := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "group-break-jump",
		LineageSequence:         999999,
		PreviousCertificateHash: "fake-hash",
		GroupEpoch:              1,
		RosterHash:              "roster-1",
		CurrentOwner:            "owner-fp",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "attacker-fp",
		SuccessorSigningKey:     attackerPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certJump.Sign(ownerPriv)

	winner := ResolveSuccessionCertificateConflict(certLegitimate, certJump)
	if winner != certJump {
		t.Fatalf("expected jump cert to win")
	}

	t.Logf("Adversarial finding confirmed: Unchained sequence jump allows arbitrary sequence inflation")
}
