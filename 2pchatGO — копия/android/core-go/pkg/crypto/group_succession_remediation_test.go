package crypto

import (
	"crypto/ed25519"
	crand "crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"testing"
	"time"
)

// GroupNodeModelV2 represents an honest member node's local group state using V2 succession primitives.
type GroupNodeModelV2 struct {
	NodeID        string
	PrivKey       ed25519.PrivateKey
	PubKey        string // base64 Ed25519 public key
	Fingerprint   string
	GroupID       string
	CurrentOwner  string
	TenureEpoch   uint64
	ActiveCert    *SuccessionCertificateV2
	LastHeartbeat *OwnerHeartbeat
	IsRevoked     bool
	Members       map[string]string // Fingerprint -> Role ("OWNER", "MEMBER")
	EventLog      []string
}

func newTestGroupNodeV2(t *testing.T, id string, groupID string) *GroupNodeModelV2 {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(crand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey failed for node %s: %v", id, err)
	}
	pubB64 := base64.StdEncoding.EncodeToString(pub)
	h := sha256.Sum256([]byte(pubB64))
	fp := hex.EncodeToString(h[:])

	return &GroupNodeModelV2{
		NodeID:       id,
		PrivKey:      priv,
		PubKey:       pubB64,
		Fingerprint:  fp,
		GroupID:      groupID,
		CurrentOwner: "",
		TenureEpoch:  0,
		Members:      make(map[string]string),
		EventLog:     make([]string, 0),
	}
}

func (n *GroupNodeModelV2) StateHash() string {
	certHash := "none"
	if n.ActiveCert != nil {
		certHash = n.ActiveCert.CertificateHash()
	}
	hbHash := "none"
	if n.LastHeartbeat != nil {
		hbHash = n.LastHeartbeat.EventHash()
	}
	data := fmt.Sprintf("%s|%s|%d|%s|%s|%v", n.GroupID, n.CurrentOwner, n.TenureEpoch, certHash, hbHash, n.IsRevoked)
	h := sha256.Sum256([]byte(data))
	return hex.EncodeToString(h[:])
}

// ReceiveCertificate applies incoming V2 certificates using deterministic conflict resolution.
func (n *GroupNodeModelV2) ReceiveCertificate(cert *SuccessionCertificateV2) error {
	if cert == nil {
		return fmt.Errorf("nil certificate")
	}
	if err := cert.Verify(); err != nil {
		return fmt.Errorf("certificate verification failed: %w", err)
	}
	if cert.GroupID != n.GroupID {
		return fmt.Errorf("group ID mismatch")
	}

	if n.ActiveCert == nil {
		n.ActiveCert = cert
		n.IsRevoked = false
		n.EventLog = append(n.EventLog, fmt.Sprintf("CERT_INITIAL:%s:seq=%d", cert.CertificateHash()[:8], cert.LineageSequence))
		return nil
	}

	// Apply canonical conflict resolution
	winner := ResolveSuccessionCertificateConflict(n.ActiveCert, cert)
	if winner == cert && winner != n.ActiveCert {
		n.ActiveCert = cert
		n.IsRevoked = false
		n.EventLog = append(n.EventLog, fmt.Sprintf("CERT_SUPERSEDED:%s:seq=%d", cert.CertificateHash()[:8], cert.LineageSequence))
	}
	return nil
}

// ApplyHeartbeat applies an OwnerHeartbeat with tenure fencing.
func (n *GroupNodeModelV2) ApplyHeartbeat(hb *OwnerHeartbeat) error {
	if hb == nil {
		return fmt.Errorf("nil heartbeat")
	}
	if err := hb.Verify(); err != nil {
		return fmt.Errorf("heartbeat verification failed: %w", err)
	}
	if hb.GroupID != n.GroupID {
		return fmt.Errorf("group ID mismatch")
	}

	// Tenure Fencing: reject heartbeats from deposed owners once tenure has advanced
	if n.CurrentOwner != "" && hb.Owner != n.CurrentOwner {
		return ErrDeposedOwnerHeartbeat
	}

	n.LastHeartbeat = hb
	n.EventLog = append(n.EventLog, fmt.Sprintf("HB_APPLIED:%s:seq=%d", hb.EventHash()[:8], hb.Sequence))
	return nil
}

// CreateClaim generates a SuccessionClaim referencing the node's active V2 certificate.
func (n *GroupNodeModelV2) CreateClaim(nowMs int64) (*SuccessionClaim, error) {
	if n.ActiveCert == nil {
		return nil, fmt.Errorf("no active succession certificate")
	}
	if n.LastHeartbeat == nil {
		return nil, fmt.Errorf("no last heartbeat recorded")
	}
	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                n.GroupID,
		Claimant:               n.Fingerprint,
		ClaimantSigningKey:     n.PubKey,
		CertificateHash:        n.ActiveCert.CertificateHash(),
		LastHeartbeatEventHash: n.LastHeartbeat.EventHash(),
		ClaimedAt:              nowMs,
	}
	if err := claim.Sign(n.PrivKey); err != nil {
		return nil, fmt.Errorf("failed to sign claim: %w", err)
	}
	return claim, nil
}

// ReceiveSuccessionClaim applies a succession claim under V2 rules with tenure ratchet.
func (n *GroupNodeModelV2) ReceiveSuccessionClaim(claim *SuccessionClaim, nowMs int64) error {
	if claim == nil {
		return fmt.Errorf("nil claim")
	}
	if n.ActiveCert == nil {
		return fmt.Errorf("cannot verify claim without active certificate")
	}

	// 1. Verify claim against active V2 certificate
	err := VerifySuccessionClaimV2(n.ActiveCert, claim, n.LastHeartbeat, n.IsRevoked, nowMs)
	if err != nil {
		return err
	}

	// 2. Roster check: verify claimant is participating member
	if role, exists := n.Members[claim.Claimant]; !exists || role == "BANNED" || role == "LEFT" {
		return ErrSuccessionSuccessorNotMember
	}

	// 3. Commit ownership transition & Tenure Epoch Ratchet
	oldOwner := n.CurrentOwner
	n.CurrentOwner = claim.Claimant
	n.TenureEpoch = n.ActiveCert.GroupEpoch + 1
	if oldOwner != "" && oldOwner != claim.Claimant {
		n.Members[oldOwner] = "MEMBER"
	}
	n.Members[claim.Claimant] = "OWNER"
	n.EventLog = append(n.EventLog, fmt.Sprintf("CLAIM_APPLIED:%s:new_owner=%s:tenure=%d",
		claim.CertificateHash[:8], claim.Claimant[:8], n.TenureEpoch))
	return nil
}

// -----------------------------------------------------------------------------
// Tests for SuccessionCertificateV2
// -----------------------------------------------------------------------------

func TestSuccessionV2_SignVerifyAndTamper(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, successorPub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()
	cert := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "test-group-v2-tamper",
		LineageSequence:         1,
		PreviousCertificateHash: GenesisPreviousEventHash,
		GroupEpoch:              0,
		RosterHash:              "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
		CurrentOwner:            "owner-fp-1",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "successor-fp-2",
		SuccessorSigningKey:     successorPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}

	if err := cert.Sign(ownerPriv); err != nil {
		t.Fatalf("cert.Sign failed: %v", err)
	}

	if err := cert.Verify(); err != nil {
		t.Fatalf("cert.Verify failed: %v", err)
	}

	// Tamper LineageSequence
	tampered := *cert
	tampered.LineageSequence = 0
	if err := tampered.Verify(); err != ErrSuccessionLineageSequenceZero {
		t.Fatalf("expected ErrSuccessionLineageSequenceZero on sequence 0, got: %v", err)
	}

	// Tamper Signature verification on modified sequence
	tampered.LineageSequence = 99
	if err := tampered.Verify(); err != ErrSuccessionInvalidSignature {
		t.Fatalf("expected ErrSuccessionInvalidSignature on modified sequence, got: %v", err)
	}

	// Tamper RosterHash
	tampered = *cert
	tampered.RosterHash = "tampered-roster-digest"
	if err := tampered.Verify(); err != ErrSuccessionInvalidSignature {
		t.Fatalf("expected signature error on tampered RosterHash, got: %v", err)
	}

	// Tamper GroupEpoch
	tampered = *cert
	tampered.GroupEpoch = 999
	if err := tampered.Verify(); err != ErrSuccessionInvalidSignature {
		t.Fatalf("expected signature error on tampered GroupEpoch, got: %v", err)
	}
}

func TestSuccessionV2_MonotonicLineage(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, successor1Pub := genTestEd25519Key(t)
	_, successor2Pub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()

	// Certificate 1: Sequence 1
	cert1 := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "test-group-monotonic",
		LineageSequence:         1,
		PreviousCertificateHash: GenesisPreviousEventHash,
		GroupEpoch:              0,
		RosterHash:              "roster-hash-v1",
		CurrentOwner:            "owner-fp-1",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "successor-fp-1",
		SuccessorSigningKey:     successor1Pub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert1.Sign(ownerPriv)

	// Certificate 2: Sequence 2, chains to cert1
	cert2 := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "test-group-monotonic",
		LineageSequence:         2,
		PreviousCertificateHash: cert1.CertificateHash(),
		GroupEpoch:              1,
		RosterHash:              "roster-hash-v2",
		CurrentOwner:            "owner-fp-1",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "successor-fp-2",
		SuccessorSigningKey:     successor2Pub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now + 1000,
		ExpiresAt:               now + 1000 + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert2.Sign(ownerPriv)

	// Resolve conflict between cert1 and cert2
	winnerA := ResolveSuccessionCertificateConflict(cert1, cert2)
	winnerB := ResolveSuccessionCertificateConflict(cert2, cert1)

	if winnerA != cert2 || winnerB != cert2 {
		t.Fatalf("expected cert2 (sequence 2) to strictly supersede cert1 (sequence 1)")
	}
}

func TestSuccessionV2_EquivocationTieBreaker(t *testing.T) {
	ownerPriv, ownerPub := genTestEd25519Key(t)
	_, successor1Pub := genTestEd25519Key(t)
	_, successor2Pub := genTestEd25519Key(t)

	now := time.Now().UnixMilli()

	// Byzantine / Faulty owner creates two certificates with the exact same sequence 2
	certA := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "test-group-equivocation",
		LineageSequence:         2,
		PreviousCertificateHash: "prev-hash",
		GroupEpoch:              1,
		RosterHash:              "roster-hash-1",
		CurrentOwner:            "owner-fp-1",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "successor-fp-bob",
		SuccessorSigningKey:     successor1Pub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now,
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certA.Sign(ownerPriv)

	certB := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 "test-group-equivocation",
		LineageSequence:         2,
		PreviousCertificateHash: "prev-hash",
		GroupEpoch:              1,
		RosterHash:              "roster-hash-1",
		CurrentOwner:            "owner-fp-1",
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               "successor-fp-charlie",
		SuccessorSigningKey:     successor2Pub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                now, // Identical IssuedAt
		ExpiresAt:               now + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = certB.Sign(ownerPriv)

	winner1 := ResolveSuccessionCertificateConflict(certA, certB)
	winner2 := ResolveSuccessionCertificateConflict(certB, certA)

	if winner1 != winner2 {
		t.Fatalf("equivocation tie-breaker is non-commutative: winner1=%v, winner2=%v", winner1, winner2)
	}

	var expectedWinner *SuccessionCertificateV2
	if certA.CertificateHash() <= certB.CertificateHash() {
		expectedWinner = certA
	} else {
		expectedWinner = certB
	}

	if winner1 != expectedWinner {
		t.Fatalf("expected lexicographical minimum hash to win equivocation tie-breaker")
	}
}

// TestSuccessionV2_PartitionConvergenceRemediated executes the canonical split-brain scenario
// under V2 rules and proves that both nodes converge to the canonical creator.
func TestSuccessionV2_PartitionConvergenceRemediated(t *testing.T) {
	groupID := "test-group-remediated-convergence"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	// 1. Setup Honest Nodes
	alice := newTestGroupNodeV2(t, "Alice", groupID)
	bob := newTestGroupNodeV2(t, "Bob", groupID)
	charlie := newTestGroupNodeV2(t, "Charlie", groupID)

	// Set initial membership
	for _, n := range []*GroupNodeModelV2{alice, bob, charlie} {
		n.Members[alice.Fingerprint] = "OWNER"
		n.Members[bob.Fingerprint] = "MEMBER"
		n.Members[charlie.Fingerprint] = "MEMBER"
		n.CurrentOwner = alice.Fingerprint
	}

	// 2. Alice emits genesis heartbeat
	hb1 := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           groupID,
		Owner:             alice.Fingerprint,
		OwnerSigningKey:   alice.PubKey,
		Sequence:          1,
		Timestamp:         baseTime,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = hb1.Sign(alice.PrivKey)

	_ = bob.ApplyHeartbeat(hb1)
	_ = charlie.ApplyHeartbeat(hb1)

	// 3. Alice issues Certificate 1: Sequence 1, designating Bob
	cert1 := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 groupID,
		LineageSequence:         1,
		PreviousCertificateHash: GenesisPreviousEventHash,
		GroupEpoch:              0,
		RosterHash:              "roster-hash-0",
		CurrentOwner:            alice.Fingerprint,
		CurrentOwnerSigningKey:  alice.PubKey,
		Successor:               bob.Fingerprint,
		SuccessorSigningKey:     bob.PubKey,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                baseTime,
		ExpiresAt:               baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert1.Sign(alice.PrivKey)
	_ = bob.ReceiveCertificate(cert1)

	// 4. Alice later updates succession to Charlie: Sequence 2, chained to cert1
	cert2 := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 groupID,
		LineageSequence:         2,
		PreviousCertificateHash: cert1.CertificateHash(),
		GroupEpoch:              1,
		RosterHash:              "roster-hash-1",
		CurrentOwner:            alice.Fingerprint,
		CurrentOwnerSigningKey:  alice.PubKey,
		Successor:               charlie.Fingerprint,
		SuccessorSigningKey:     charlie.PubKey,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                baseTime + 86400000,
		ExpiresAt:               baseTime + 86400000 + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert2.Sign(alice.PrivKey)
	_ = charlie.ReceiveCertificate(cert2)

	// 5. PARTITION: Alice goes offline.
	// Bob is in Partition B (holds Cert 1). Charlie is in Partition C (holds Cert 2).
	claimTime := baseTime + 31*24*60*60*1000

	claimB, _ := bob.CreateClaim(claimTime)
	_ = bob.ReceiveSuccessionClaim(claimB, claimTime)

	claimC, _ := charlie.CreateClaim(claimTime)
	_ = charlie.ReceiveSuccessionClaim(claimC, claimTime)

	t.Logf("[Partition State] Bob Owner: %s, Charlie Owner: %s", bob.CurrentOwner[:12], charlie.CurrentOwner[:12])

	// 6. NETWORK HEALS: Nodes exchange certificates and claims
	// Step A: Exchange certificates
	_ = bob.ReceiveCertificate(cert2) // Bob receives Cert 2 with Sequence 2 > 1 -> Cert 2 supersedes Cert 1!
	_ = charlie.ReceiveCertificate(cert1) // Charlie receives older Cert 1 -> Cert 2 remains active!

	if bob.ActiveCert.CertificateHash() != cert2.CertificateHash() {
		t.Fatalf("expected bob to adopt cert2 with higher sequence")
	}
	if charlie.ActiveCert.CertificateHash() != cert2.CertificateHash() {
		t.Fatalf("expected charlie to retain cert2 with higher sequence")
	}

	// Step B: Exchange claims
	// Bob receives Charlie's claim (valid under Cert 2)
	errBobReceivesC := bob.ReceiveSuccessionClaim(claimC, claimTime)
	if errBobReceivesC != nil {
		t.Fatalf("expected Bob to accept Charlie's claim under superseding cert2, got: %v", errBobReceivesC)
	}

	// Charlie receives Bob's claim (references obsolete cert1 and claimant is Bob instead of Charlie)
	errCharlieReceivesB := charlie.ReceiveSuccessionClaim(claimB, claimTime)
	if errCharlieReceivesB != ErrSuccessionInvalidCertHash && errCharlieReceivesB != ErrSuccessionClaimantMismatch {
		t.Fatalf("expected Charlie to reject Bob's claim, got: %v", errCharlieReceivesB)
	}

	// 7. Verify Invariants on Convergence
	t.Logf("[Converged State] Bob Owner: %s, Charlie Owner: %s", bob.CurrentOwner[:12], charlie.CurrentOwner[:12])
	t.Logf("[Converged State] Bob StateHash: %s, Charlie StateHash: %s", bob.StateHash()[:16], charlie.StateHash()[:16])

	if bob.CurrentOwner != charlie.CurrentOwner {
		t.Fatalf("CONVERGENCE FAILED: Bob owner (%s) != Charlie owner (%s)", bob.CurrentOwner, charlie.CurrentOwner)
	}
	if bob.CurrentOwner != charlie.Fingerprint {
		t.Fatalf("expected canonical winner Charlie to be owner, got: %s", bob.CurrentOwner)
	}
	if bob.StateHash() != charlie.StateHash() {
		t.Fatalf("state hash mismatch after convergence: %s vs %s", bob.StateHash(), charlie.StateHash())
	}

	t.Logf("INV-1 Single Current Creator: PASS (Both agree on Charlie)")
	t.Logf("INV-2 Deterministic Conflict Resolution: PASS (Lineage Sequence resolved conflict)")
	t.Logf("INV-3 Monotonic Group State: PASS (Cert 2 superseded Cert 1)")
	t.Logf("INV-6 Partition Convergence: PASS (state(B) == state(C))")
	t.Logf("INV-7 No Dual Valid Histories: PASS (Cert 1 retired; Claim B rejected)")
}

func TestSuccessionV2_CreatorReturnDefense(t *testing.T) {
	groupID := "test-group-creator-return-defense"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNodeV2(t, "Alice", groupID)
	bob := newTestGroupNodeV2(t, "Bob", groupID)

	bob.Members[alice.Fingerprint] = "OWNER"
	bob.Members[bob.Fingerprint] = "MEMBER"
	bob.CurrentOwner = alice.Fingerprint

	hb1 := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           groupID,
		Owner:             alice.Fingerprint,
		OwnerSigningKey:   alice.PubKey,
		Sequence:          1,
		Timestamp:         baseTime,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = hb1.Sign(alice.PrivKey)
	_ = bob.ApplyHeartbeat(hb1)

	cert := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 groupID,
		LineageSequence:         1,
		PreviousCertificateHash: GenesisPreviousEventHash,
		GroupEpoch:              0,
		RosterHash:              "roster-hash-0",
		CurrentOwner:            alice.Fingerprint,
		CurrentOwnerSigningKey:  alice.PubKey,
		Successor:               bob.Fingerprint,
		SuccessorSigningKey:     bob.PubKey,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                baseTime,
		ExpiresAt:               baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(alice.PrivKey)
	_ = bob.ReceiveCertificate(cert)

	// Bob claims succession after 31 days
	claimTime := baseTime + 31*24*60*60*1000
	claim, _ := bob.CreateClaim(claimTime)
	if err := bob.ReceiveSuccessionClaim(claim, claimTime); err != nil {
		t.Fatalf("bob failed to claim succession: %v", err)
	}

	if bob.CurrentOwner != bob.Fingerprint {
		t.Fatalf("bob should be current owner")
	}

	// Alice comes back online and sends Heartbeat 2
	hb2 := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           groupID,
		Owner:             alice.Fingerprint,
		OwnerSigningKey:   alice.PubKey,
		Sequence:          2,
		Timestamp:         baseTime + 40*24*60*60*1000,
		PreviousEventHash: hb1.EventHash(),
	}
	_ = hb2.Sign(alice.PrivKey)

	// Bob's group rejects Alice's heartbeat because Alice is no longer recorded owner
	err := bob.ApplyHeartbeat(hb2)
	if err != ErrDeposedOwnerHeartbeat {
		t.Fatalf("expected ErrDeposedOwnerHeartbeat when deposed creator sends heartbeat, got: %v", err)
	}

	if bob.CurrentOwner != bob.Fingerprint {
		t.Fatalf("deposed creator corrupted owner state")
	}
	t.Logf("Creator return defense PASS: Deposed creator cannot reset heartbeat or reclaim authority")
}

func TestSuccessionV2_RosterBinding(t *testing.T) {
	groupID := "test-group-roster-binding"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNodeV2(t, "Alice", groupID)
	mallory := newTestGroupNodeV2(t, "Mallory", groupID)

	// Mallory was banned or left the group
	alice.Members[mallory.Fingerprint] = "BANNED"

	hb := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           groupID,
		Owner:             alice.Fingerprint,
		OwnerSigningKey:   alice.PubKey,
		Sequence:          1,
		Timestamp:         baseTime,
		PreviousEventHash: GenesisPreviousEventHash,
	}
	_ = hb.Sign(alice.PrivKey)
	_ = alice.ApplyHeartbeat(hb)

	cert := &SuccessionCertificateV2{
		Type:                    "succession_certificate_v2",
		GroupID:                 groupID,
		LineageSequence:         1,
		PreviousCertificateHash: GenesisPreviousEventHash,
		GroupEpoch:              0,
		RosterHash:              "roster-hash-0",
		CurrentOwner:            alice.Fingerprint,
		CurrentOwnerSigningKey:  alice.PubKey,
		Successor:               mallory.Fingerprint,
		SuccessorSigningKey:     mallory.PubKey,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                baseTime,
		ExpiresAt:               baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(alice.PrivKey)
	_ = alice.ReceiveCertificate(cert)

	claimTime := baseTime + 31*24*60*60*1000
	claim := &SuccessionClaim{
		Type:                   "succession_claim_v1",
		GroupID:                groupID,
		Claimant:               mallory.Fingerprint,
		ClaimantSigningKey:     mallory.PubKey,
		CertificateHash:        cert.CertificateHash(),
		LastHeartbeatEventHash: hb.EventHash(),
		ClaimedAt:              claimTime,
	}
	_ = claim.Sign(mallory.PrivKey)

	// Attempt to apply claim for banned member
	err := alice.ReceiveSuccessionClaim(claim, claimTime)
	if err != ErrSuccessionSuccessorNotMember {
		t.Fatalf("expected ErrSuccessionSuccessorNotMember on banned claimant, got: %v", err)
	}
	t.Logf("Roster binding PASS: Banned/non-participating member rejected from succession")
}
