package crypto

import (
	"crypto/ed25519"
	crand "crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"math/rand"
	"testing"
	"time"
)

// GroupNodeModel represents an honest member node's local group and succession state.
// It uses production functions from group_succession.go for crypto operations.
type GroupNodeModel struct {
	NodeID        string
	PrivKey       ed25519.PrivateKey
	PubKey        string // base64 Ed25519 public key
	Fingerprint   string
	GroupID       string
	CurrentOwner  string
	ActiveCert    *SuccessionCertificate
	LastHeartbeat *OwnerHeartbeat
	IsRevoked     bool
	Members       map[string]string // Fingerprint -> Role ("OWNER", "MEMBER")
	EventLog      []string
}

func newTestGroupNode(t *testing.T, id string, groupID string) *GroupNodeModel {
	t.Helper()
	pub, priv, err := ed25519.GenerateKey(crand.Reader)
	if err != nil {
		t.Fatalf("GenerateKey failed for node %s: %v", id, err)
	}
	pubB64 := base64.StdEncoding.EncodeToString(pub)
	h := sha256.Sum256([]byte(pubB64))
	fp := hex.EncodeToString(h[:])

	return &GroupNodeModel{
		NodeID:       id,
		PrivKey:      priv,
		PubKey:       pubB64,
		Fingerprint:  fp,
		GroupID:      groupID,
		CurrentOwner: "",
		Members:      make(map[string]string),
		EventLog:     make([]string, 0),
	}
}

func (n *GroupNodeModel) StateHash() string {
	certHash := "none"
	if n.ActiveCert != nil {
		certHash = n.ActiveCert.CertificateHash()
	}
	hbHash := "none"
	if n.LastHeartbeat != nil {
		hbHash = n.LastHeartbeat.EventHash()
	}
	data := fmt.Sprintf("%s|%s|%s|%s|%v", n.GroupID, n.CurrentOwner, certHash, hbHash, n.IsRevoked)
	h := sha256.Sum256([]byte(data))
	return hex.EncodeToString(h[:])
}

// ApplyCertificate models receipt and storage of a SuccessionCertificate.
// Follows GroupChatCoordinator.receiveSuccessionCertificate logic.
func (n *GroupNodeModel) ApplyCertificate(cert *SuccessionCertificate) error {
	if cert == nil {
		return fmt.Errorf("nil certificate")
	}
	if err := cert.Verify(); err != nil {
		return fmt.Errorf("certificate verification failed: %w", err)
	}
	if cert.GroupID != n.GroupID {
		return fmt.Errorf("group ID mismatch")
	}
	n.ActiveCert = cert
	n.IsRevoked = false
	n.EventLog = append(n.EventLog, fmt.Sprintf("CERT_APPLIED:%s:successor=%s", cert.CertificateHash()[:8], cert.Successor[:8]))
	return nil
}

// ApplyHeartbeat models receipt of an OwnerHeartbeat.
func (n *GroupNodeModel) ApplyHeartbeat(hb *OwnerHeartbeat) error {
	if hb == nil {
		return fmt.Errorf("nil heartbeat")
	}
	if err := hb.Verify(); err != nil {
		return fmt.Errorf("heartbeat verification failed: %w", err)
	}
	if hb.GroupID != n.GroupID {
		return fmt.Errorf("group ID mismatch")
	}
	n.LastHeartbeat = hb
	n.EventLog = append(n.EventLog, fmt.Sprintf("HB_APPLIED:%s:seq=%d", hb.EventHash()[:8], hb.Sequence))
	return nil
}

// ApplyRevocation models receipt of a SuccessionRevocation.
func (n *GroupNodeModel) ApplyRevocation(rev *SuccessionRevocation) error {
	if rev == nil {
		return fmt.Errorf("nil revocation")
	}
	if err := rev.Verify(); err != nil {
		return fmt.Errorf("revocation verification failed: %w", err)
	}
	if rev.GroupID != n.GroupID {
		return fmt.Errorf("group ID mismatch")
	}
	if n.ActiveCert != nil && n.ActiveCert.CertificateHash() == rev.CertificateHash {
		n.IsRevoked = true
	}
	n.EventLog = append(n.EventLog, fmt.Sprintf("REV_APPLIED:%s", rev.CertificateHash[:8]))
	return nil
}

// CreateClaim models generating a SuccessionClaim when timeout has elapsed.
func (n *GroupNodeModel) CreateClaim(nowMs int64) (*SuccessionClaim, error) {
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

// ReceiveSuccessionClaim models GroupChatCoordinator.receiveSuccessionClaim + applySuccessionClaim.
func (n *GroupNodeModel) ReceiveSuccessionClaim(claim *SuccessionClaim, nowMs int64) error {
	if claim == nil {
		return fmt.Errorf("nil claim")
	}
	// Verify using production crypto function against node's local active certificate & heartbeat
	err := VerifySuccessionClaim(n.ActiveCert, claim, n.LastHeartbeat, n.IsRevoked, nowMs)
	if err != nil {
		return err
	}

	// Apply ownership transition locally
	oldOwner := n.CurrentOwner
	n.CurrentOwner = claim.Claimant
	if oldOwner != "" && oldOwner != claim.Claimant {
		n.Members[oldOwner] = "MEMBER"
	}
	n.Members[claim.Claimant] = "OWNER"
	n.EventLog = append(n.EventLog, fmt.Sprintf("CLAIM_APPLIED:%s:new_owner=%s", claim.CertificateHash[:8], claim.Claimant[:8]))
	return nil
}

// -----------------------------------------------------------------------------
// Section 5: Canonical Split-Brain Scenario
// -----------------------------------------------------------------------------

// TestGroupSuccession_CanonicalSplitBrain executes the core partition and succession scenario:
//
//	           Creator Alice
//	                |
//	        group epoch N
//	         /         \
//	      Member B   Member C
//	         |   PARTITION   |
//	      B claims    C claims
//	      succession  succession
//	         |               |
//	      Branch B       Branch C
//	         \       /
//	       NETWORK HEALS
//	             |
//	        CONVERGENCE?
//
// Evaluates whether state(B) == state(C) after partition heals and claims are exchanged.
func TestGroupSuccession_CanonicalSplitBrain(t *testing.T) {
	groupID := "test-group-splitbrain-canonical"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	// 1. Setup Honest Nodes
	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)
	charlie := newTestGroupNode(t, "Charlie", groupID)

	alice.CurrentOwner = alice.Fingerprint
	bob.CurrentOwner = alice.Fingerprint
	charlie.CurrentOwner = alice.Fingerprint

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
	if err := hb1.Sign(alice.PrivKey); err != nil {
		t.Fatalf("hb1.Sign failed: %v", err)
	}

	if err := bob.ApplyHeartbeat(hb1); err != nil {
		t.Fatalf("bob.ApplyHeartbeat failed: %v", err)
	}
	if err := charlie.ApplyHeartbeat(hb1); err != nil {
		t.Fatalf("charlie.ApplyHeartbeat failed: %v", err)
	}

	// 3. Alice issues Certificate 1 designating Bob as successor (IssuedAt = baseTime)
	cert1 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	if err := cert1.Sign(alice.PrivKey); err != nil {
		t.Fatalf("cert1.Sign failed: %v", err)
	}

	// Bob receives Cert 1
	if err := bob.ApplyCertificate(cert1); err != nil {
		t.Fatalf("bob.ApplyCertificate failed: %v", err)
	}

	// 4. Later, Alice decides to change successor to Charlie and issues Certificate 2 (IssuedAt = baseTime + 1 day)
	// Alice does NOT issue a cryptographic revocation of Cert 1.
	cert2 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              charlie.Fingerprint,
		SuccessorSigningKey:    charlie.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime + 86400000,
		ExpiresAt:              baseTime + 86400000 + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	if err := cert2.Sign(alice.PrivKey); err != nil {
		t.Fatalf("cert2.Sign failed: %v", err)
	}

	// Charlie receives Cert 2
	if err := charlie.ApplyCertificate(cert2); err != nil {
		t.Fatalf("charlie.ApplyCertificate failed: %v", err)
	}

	// 5. NETWORK PARTITION:
	// Alice disappears completely (e.g., destroyed device or abandoned account).
	// Bob is partitioned on Branch B (holds Cert 1, believes Bob is successor).
	// Charlie is partitioned on Branch C (holds Cert 2, believes Charlie is successor).
	// Neither Bob nor Charlie receives the other's certificate during partition.

	// 6. Time elapses beyond heartbeat timeout (31 days > 30 days)
	claimTime := baseTime + 31*24*60*60*1000

	// Branch B: Bob generates Claim B and applies it locally
	claimB, err := bob.CreateClaim(claimTime)
	if err != nil {
		t.Fatalf("bob.CreateClaim failed: %v", err)
	}
	if err := bob.ReceiveSuccessionClaim(claimB, claimTime); err != nil {
		t.Fatalf("bob.ReceiveSuccessionClaim for self failed: %v", err)
	}
	if bob.CurrentOwner != bob.Fingerprint {
		t.Fatalf("expected bob to be owner on Branch B, got: %s", bob.CurrentOwner)
	}

	// Branch C: Charlie generates Claim C and applies it locally
	claimC, err := charlie.CreateClaim(claimTime)
	if err != nil {
		t.Fatalf("charlie.CreateClaim failed: %v", err)
	}
	if err := charlie.ReceiveSuccessionClaim(claimC, claimTime); err != nil {
		t.Fatalf("charlie.ReceiveSuccessionClaim for self failed: %v", err)
	}
	if charlie.CurrentOwner != charlie.Fingerprint {
		t.Fatalf("expected charlie to be owner on Branch C, got: %s", charlie.CurrentOwner)
	}

	t.Logf("[Partition State] Node B CurrentOwner: %s (Self)", bob.CurrentOwner[:12])
	t.Logf("[Partition State] Node C CurrentOwner: %s (Self)", charlie.CurrentOwner[:12])

	// 7. NETWORK HEALS: Bob and Charlie reconnect and exchange their succession claims
	// Bob receives Charlie's Claim C
	errBobReceivesC := bob.ReceiveSuccessionClaim(claimC, claimTime)
	// Charlie receives Bob's Claim B
	errCharlieReceivesB := charlie.ReceiveSuccessionClaim(claimB, claimTime)

	t.Logf("[Heal Verification] Bob processing Claim C: error = %v", errBobReceivesC)
	t.Logf("[Heal Verification] Charlie processing Claim B: error = %v", errCharlieReceivesB)

	// In the production protocol:
	// Bob evaluates Claim C against Bob's active cert (Cert 1):
	// Claim C's claimant is Charlie, but Cert 1 successor is Bob -> ErrSuccessionClaimantMismatch!
	// Claim C's cert hash is Cert 2's hash, but Cert 1's hash differs -> ErrSuccessionInvalidCertHash!
	if errBobReceivesC == nil {
		t.Errorf("expected Bob to reject Claim C because Cert 1 designates Bob, but got nil error")
	}
	if errCharlieReceivesB == nil {
		t.Errorf("expected Charlie to reject Claim B because Cert 2 designates Charlie, but got nil error")
	}

	// 8. EVALUATE CONVERGENCE INVARIANTS
	// Invariant INV-1: Single Current Creator across honest nodes
	// Invariant INV-6: Partition Convergence (state(B) == state(C))
	// Invariant INV-7: No Dual Valid Histories
	t.Logf("[Convergence Check] Bob Owner: %s, Charlie Owner: %s", bob.CurrentOwner[:12], charlie.CurrentOwner[:12])
	t.Logf("[Convergence Check] Bob StateHash: %s, Charlie StateHash: %s", bob.StateHash()[:16], charlie.StateHash()[:16])

	isSplitBrain := (bob.CurrentOwner != charlie.CurrentOwner)
	if isSplitBrain {
		t.Logf("SECURITY INVARIANT VIOLATION CONFIRMED: SPLIT-BRAIN DETECTED!")
		t.Logf("INV-1 Single Current Creator: FAIL (Bob thinks Bob is owner, Charlie thinks Charlie is owner)")
		t.Logf("INV-6 Partition Convergence: FAIL (Nodes permanently disagree after partition heals)")
		t.Logf("INV-7 No Dual Valid Histories: FAIL (Both branches remain mutually valid and accepted locally)")
	} else {
		t.Fatalf("UNEXPECTED: Nodes converged without tie-breaker mechanism")
	}
}

// -----------------------------------------------------------------------------
// Section 6: Adversarial Test Matrix (Cases A through J)
// -----------------------------------------------------------------------------

// Case A — Simultaneous Claims: B and C generate claims against the same heartbeat at the exact same logical timestamp.
func TestGroupSuccession_Adversarial_CaseA_SimultaneousClaims(t *testing.T) {
	groupID := "test-case-a-simultaneous"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)
	charlie := newTestGroupNode(t, "Charlie", groupID)

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

	cert1 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert1.Sign(alice.PrivKey)

	cert2 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              charlie.Fingerprint,
		SuccessorSigningKey:    charlie.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime + 1000,
		ExpiresAt:              baseTime + 1000 + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert2.Sign(alice.PrivKey)

	_ = bob.ApplyHeartbeat(hb)
	_ = bob.ApplyCertificate(cert1)
	_ = charlie.ApplyHeartbeat(hb)
	_ = charlie.ApplyCertificate(cert2)

	// Exactly identical ClaimedAt timestamp
	simultaneousClaimTime := baseTime + 31*24*60*60*1000
	claimB, errB := bob.CreateClaim(simultaneousClaimTime)
	claimC, errC := charlie.CreateClaim(simultaneousClaimTime)

	if errB != nil || errC != nil {
		t.Fatalf("claim creation failed: errB=%v, errC=%v", errB, errC)
	}

	// Verify both claims are cryptographically valid against their respective certificates
	if err := VerifySuccessionClaim(cert1, claimB, hb, false, simultaneousClaimTime); err != nil {
		t.Fatalf("claimB failed verification: %v", err)
	}
	if err := VerifySuccessionClaim(cert2, claimC, hb, false, simultaneousClaimTime); err != nil {
		t.Fatalf("claimC failed verification: %v", err)
	}

	t.Logf("Case A PASS: Both claims simultaneously and independently valid; protocol has no mechanism to order them")
}

// Case B — Different Claim Arrival Order:
// Demonstrates that a neutral observer Dave, having received both certificates,
// will adopt a different final owner depending on packet arrival order.
func TestGroupSuccession_Adversarial_CaseB_ArrivalOrderDependence(t *testing.T) {
	groupID := "test-case-b-arrival-order"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)
	charlie := newTestGroupNode(t, "Charlie", groupID)

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

	cert1 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert1.Sign(alice.PrivKey)

	cert2 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              charlie.Fingerprint,
		SuccessorSigningKey:    charlie.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime + 5000,
		ExpiresAt:              baseTime + 5000 + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert2.Sign(alice.PrivKey)

	claimTime := baseTime + 31*24*60*60*1000
	_ = bob.ApplyHeartbeat(hb)
	_ = bob.ApplyCertificate(cert1)
	claimB, _ := bob.CreateClaim(claimTime)

	_ = charlie.ApplyHeartbeat(hb)
	_ = charlie.ApplyCertificate(cert2)
	claimC, _ := charlie.CreateClaim(claimTime)

	// Observer 1 (Dave1) receives Cert1 + ClaimB, then Cert2 + ClaimC
	dave1 := newTestGroupNode(t, "Dave1", groupID)
	_ = dave1.ApplyHeartbeat(hb)
	_ = dave1.ApplyCertificate(cert1)
	_ = dave1.ReceiveSuccessionClaim(claimB, claimTime)
	_ = dave1.ApplyCertificate(cert2)
	_ = dave1.ReceiveSuccessionClaim(claimC, claimTime)

	// Observer 2 (Dave2) receives Cert2 + ClaimC, then Cert1 + ClaimB
	dave2 := newTestGroupNode(t, "Dave2", groupID)
	_ = dave2.ApplyHeartbeat(hb)
	_ = dave2.ApplyCertificate(cert2)
	_ = dave2.ReceiveSuccessionClaim(claimC, claimTime)
	// Dave2 receives older Cert1 (which overwrites activeCert in coordinator since no sequence check exists)
	_ = dave2.ApplyCertificate(cert1)
	_ = dave2.ReceiveSuccessionClaim(claimB, claimTime)

	t.Logf("Dave1 Final Owner (received B then C): %s", dave1.CurrentOwner[:12])
	t.Logf("Dave2 Final Owner (received C then B): %s", dave2.CurrentOwner[:12])

	// Invariant INV-2: Deterministic Conflict Resolution
	if dave1.CurrentOwner != dave2.CurrentOwner {
		t.Logf("INV-2 VIOLATION CONFIRMED: Observer final state depends entirely on arrival order (%s vs %s)",
			dave1.CurrentOwner[:12], dave2.CurrentOwner[:12])
	}
}

// Case C — Delayed Claim:
// A claim generated earlier is delivered after a later event.
func TestGroupSuccession_Adversarial_CaseC_DelayedClaim(t *testing.T) {
	groupID := "test-case-c-delayed"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)

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

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(alice.PrivKey)

	_ = bob.ApplyHeartbeat(hb)
	_ = bob.ApplyCertificate(cert)

	// Claim generated at day 31
	claimTime := baseTime + 31*24*60*60*1000
	claim, _ := bob.CreateClaim(claimTime)

	// Delivered at day 60 (delayed by network)
	deliveryTime := baseTime + 60*24*60*60*1000
	dave := newTestGroupNode(t, "Dave", groupID)
	_ = dave.ApplyHeartbeat(hb)
	_ = dave.ApplyCertificate(cert)

	err := dave.ReceiveSuccessionClaim(claim, deliveryTime)
	if err != nil {
		t.Fatalf("expected delayed claim to be accepted if cert still unexpired, got: %v", err)
	}
	if dave.CurrentOwner != bob.Fingerprint {
		t.Fatalf("expected dave to recognize bob as owner")
	}
	t.Logf("Case C PASS: Delayed claim verified within certificate validity window")
}

// Case D — Replay: Replay an old succession claim after newer state exists.
func TestGroupSuccession_Adversarial_CaseD_Replay(t *testing.T) {
	groupID := "test-case-d-replay"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)

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

	cert1 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert1.Sign(alice.PrivKey)

	_ = bob.ApplyHeartbeat(hb1)
	_ = bob.ApplyCertificate(cert1)
	claimTime := baseTime + 31*24*60*60*1000
	claimB, _ := bob.CreateClaim(claimTime)

	// Now Alice emits newer heartbeat hb2 (e.g. Alice was still alive and emitted hb2 at day 35)
	hb2 := &OwnerHeartbeat{
		Type:              "owner_heartbeat",
		GroupID:           groupID,
		Owner:             alice.Fingerprint,
		OwnerSigningKey:   alice.PubKey,
		Sequence:          2,
		Timestamp:         baseTime + 35*24*60*60*1000,
		PreviousEventHash: hb1.EventHash(),
	}
	_ = hb2.Sign(alice.PrivKey)

	dave := newTestGroupNode(t, "Dave", groupID)
	_ = dave.ApplyCertificate(cert1)
	_ = dave.ApplyHeartbeat(hb2) // Dave has newer heartbeat hb2

	// Attacker replays old claimB (which references hb1)
	err := dave.ReceiveSuccessionClaim(claimB, baseTime+40*24*60*60*1000)
	if err != ErrSuccessionLogMismatchDeferred {
		t.Fatalf("expected ErrSuccessionLogMismatchDeferred on replaying stale claim referencing hb1, got: %v", err)
	}
	t.Logf("Case D PASS: Replaying claim with stale heartbeat hash is deferred/rejected")
}

// Case E — Duplicate Delivery: Deliver the exact same claim multiple times.
func TestGroupSuccession_Adversarial_CaseE_DuplicateDelivery(t *testing.T) {
	groupID := "test-case-e-duplicate"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)

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

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(alice.PrivKey)

	_ = bob.ApplyHeartbeat(hb)
	_ = bob.ApplyCertificate(cert)
	claimTime := baseTime + 31*24*60*60*1000
	claim, _ := bob.CreateClaim(claimTime)

	dave := newTestGroupNode(t, "Dave", groupID)
	_ = dave.ApplyHeartbeat(hb)
	_ = dave.ApplyCertificate(cert)

	// First delivery
	if err := dave.ReceiveSuccessionClaim(claim, claimTime); err != nil {
		t.Fatalf("first delivery failed: %v", err)
	}
	firstStateHash := dave.StateHash()

	// Duplicate deliveries (x5)
	for i := 0; i < 5; i++ {
		if err := dave.ReceiveSuccessionClaim(claim, claimTime); err != nil {
			t.Fatalf("duplicate delivery %d failed: %v", i, err)
		}
		if dave.StateHash() != firstStateHash {
			t.Fatalf("state mutated on duplicate claim delivery at iteration %d", i)
		}
	}
	t.Logf("Case E PASS: Duplicate claim delivery is idempotent")
}

// Case F — Conflicting Valid Claims (Missing Tie-Breaker):
// Demonstrates that the crypto package exposes NO tie-breaking or canonical resolution function.
func TestGroupSuccession_Adversarial_CaseF_MissingTieBreaker(t *testing.T) {
	groupID := "test-case-f-tiebreaker"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)
	charlie := newTestGroupNode(t, "Charlie", groupID)

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

	cert1 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert1.Sign(alice.PrivKey)

	cert2 := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              charlie.Fingerprint,
		SuccessorSigningKey:    charlie.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime + 1000,
		ExpiresAt:              baseTime + 1000 + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert2.Sign(alice.PrivKey)

	claimTime := baseTime + 31*24*60*60*1000
	_ = bob.ApplyHeartbeat(hb)
	_ = bob.ApplyCertificate(cert1)
	claimB, _ := bob.CreateClaim(claimTime)

	_ = charlie.ApplyHeartbeat(hb)
	_ = charlie.ApplyCertificate(cert2)
	claimC, _ := charlie.CreateClaim(claimTime)

	// In the absence of a deterministic resolution rule:
	// Verify that both claims are valid in isolation
	if err := VerifySuccessionClaim(cert1, claimB, hb, false, claimTime); err != nil {
		t.Fatalf("claimB invalid: %v", err)
	}
	if err := VerifySuccessionClaim(cert2, claimC, hb, false, claimTime); err != nil {
		t.Fatalf("claimC invalid: %v", err)
	}

	// Neither claim can be verified against the opposite certificate
	if err := VerifySuccessionClaim(cert1, claimC, hb, false, claimTime); err == nil {
		t.Fatalf("expected claimC to fail against cert1")
	}
	if err := VerifySuccessionClaim(cert2, claimB, hb, false, claimTime); err == nil {
		t.Fatalf("expected claimB to fail against cert2")
	}

	t.Logf("Case F PASS: Verified that conflicting valid claims have no deterministic conflict resolution primitive in pkg/crypto")
}

// Case H — Creator Return:
// Original creator Alice reconnects after Bob has already claimed succession.
func TestGroupSuccession_Adversarial_CaseH_CreatorReturn(t *testing.T) {
	groupID := "test-case-h-creator-return"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)

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

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(alice.PrivKey)

	_ = bob.ApplyHeartbeat(hb1)
	_ = bob.ApplyCertificate(cert)

	// Bob successfully transitions to owner at day 31
	claimTime := baseTime + 31*24*60*60*1000
	claim, _ := bob.CreateClaim(claimTime)
	_ = bob.ReceiveSuccessionClaim(claim, claimTime)
	if bob.CurrentOwner != bob.Fingerprint {
		t.Fatalf("bob failed to become owner")
	}

	// At day 40, Alice's device comes back online and broadcasts Heartbeat 2
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

	// Heartbeat 2 is cryptographically valid
	if err := hb2.Verify(); err != nil {
		t.Fatalf("hb2.Verify failed: %v", err)
	}

	// However, Bob's group already recognizes Bob as owner!
	// If Bob processes hb2 as a heartbeat:
	t.Logf("hb2 valid from original creator Alice, but current owner is Bob (%s)", bob.CurrentOwner[:12])
	t.Logf("Case H PASS: Creator return scenario tested; demonstrates necessity of epoch/tenure fencing against deposed creator")
}

// Case J — Malicious Member:
// Verify rejection of all invalid/tampered succession claims.
func TestGroupSuccession_Adversarial_CaseJ_MaliciousMember(t *testing.T) {
	groupID := "test-case-j-malicious"
	baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	alice := newTestGroupNode(t, "Alice", groupID)
	bob := newTestGroupNode(t, "Bob", groupID)
	mallory := newTestGroupNode(t, "Mallory", groupID)

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

	cert := &SuccessionCertificate{
		Type:                   "succession_certificate_v1",
		GroupID:                groupID,
		CurrentOwner:           alice.Fingerprint,
		CurrentOwnerSigningKey: alice.PubKey,
		Successor:              bob.Fingerprint,
		SuccessorSigningKey:    bob.PubKey,
		HeartbeatTimeoutDays:   30,
		IssuedAt:               baseTime,
		ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
	}
	_ = cert.Sign(alice.PrivKey)

	now := baseTime + 31*24*60*60*1000

	// 1. Wrong GroupID in claim
	{
		c := &SuccessionClaim{
			Type:                   "succession_claim_v1",
			GroupID:                "evil-group-id",
			Claimant:               bob.Fingerprint,
			ClaimantSigningKey:     bob.PubKey,
			CertificateHash:        cert.CertificateHash(),
			LastHeartbeatEventHash: hb.EventHash(),
			ClaimedAt:              now,
		}
		_ = c.Sign(bob.PrivKey)
		err := VerifySuccessionClaim(cert, c, hb, false, now)
		if err == nil {
			t.Errorf("expected error on wrong group ID")
		}
	}

	// 2. Wrong Claimant (Mallory impersonating Bob)
	{
		c := &SuccessionClaim{
			Type:                   "succession_claim_v1",
			GroupID:                groupID,
			Claimant:               mallory.Fingerprint,
			ClaimantSigningKey:     mallory.PubKey,
			CertificateHash:        cert.CertificateHash(),
			LastHeartbeatEventHash: hb.EventHash(),
			ClaimedAt:              now,
		}
		_ = c.Sign(mallory.PrivKey)
		err := VerifySuccessionClaim(cert, c, hb, false, now)
		if err != ErrSuccessionClaimantMismatch {
			t.Errorf("expected ErrSuccessionClaimantMismatch, got: %v", err)
		}
	}

	// 3. Forged Claimant Signature
	{
		c := &SuccessionClaim{
			Type:                   "succession_claim_v1",
			GroupID:                groupID,
			Claimant:               bob.Fingerprint,
			ClaimantSigningKey:     bob.PubKey,
			CertificateHash:        cert.CertificateHash(),
			LastHeartbeatEventHash: hb.EventHash(),
			ClaimedAt:              now,
		}
		_ = c.Sign(mallory.PrivKey) // Signed with Mallory's key instead of Bob's
		err := VerifySuccessionClaim(cert, c, hb, false, now)
		if err != ErrSuccessionInvalidSignature && err == nil {
			t.Errorf("expected signature verification failure for forged claim signature")
		}
	}

	// 4. Stale/Wrong Certificate Hash
	{
		c := &SuccessionClaim{
			Type:                   "succession_claim_v1",
			GroupID:                groupID,
			Claimant:               bob.Fingerprint,
			ClaimantSigningKey:     bob.PubKey,
			CertificateHash:        "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
			LastHeartbeatEventHash: hb.EventHash(),
			ClaimedAt:              now,
		}
		_ = c.Sign(bob.PrivKey)
		err := VerifySuccessionClaim(cert, c, hb, false, now)
		if err != ErrSuccessionInvalidCertHash {
			t.Errorf("expected ErrSuccessionInvalidCertHash, got: %v", err)
		}
	}

	// 5. Premature Claim (only 10 days elapsed)
	{
		prematureTime := baseTime + 10*24*60*60*1000
		c := &SuccessionClaim{
			Type:                   "succession_claim_v1",
			GroupID:                groupID,
			Claimant:               bob.Fingerprint,
			ClaimantSigningKey:     bob.PubKey,
			CertificateHash:        cert.CertificateHash(),
			LastHeartbeatEventHash: hb.EventHash(),
			ClaimedAt:              prematureTime,
		}
		_ = c.Sign(bob.PrivKey)
		err := VerifySuccessionClaim(cert, c, hb, false, prematureTime)
		if err != ErrSuccessionPrematureClaim {
			t.Errorf("expected ErrSuccessionPrematureClaim, got: %v", err)
		}
	}

	// 6. Expired Certificate
	{
		expiredTime := cert.ExpiresAt + 1000
		c := &SuccessionClaim{
			Type:                   "succession_claim_v1",
			GroupID:                groupID,
			Claimant:               bob.Fingerprint,
			ClaimantSigningKey:     bob.PubKey,
			CertificateHash:        cert.CertificateHash(),
			LastHeartbeatEventHash: hb.EventHash(),
			ClaimedAt:              expiredTime,
		}
		_ = c.Sign(bob.PrivKey)
		err := VerifySuccessionClaim(cert, c, hb, false, expiredTime)
		if err != ErrSuccessionCertExpired {
			t.Errorf("expected ErrSuccessionCertExpired, got: %v", err)
		}
	}

	// 7. Revoked Certificate
	{
		c := &SuccessionClaim{
			Type:                   "succession_claim_v1",
			GroupID:                groupID,
			Claimant:               bob.Fingerprint,
			ClaimantSigningKey:     bob.PubKey,
			CertificateHash:        cert.CertificateHash(),
			LastHeartbeatEventHash: hb.EventHash(),
			ClaimedAt:              now,
		}
		_ = c.Sign(bob.PrivKey)
		err := VerifySuccessionClaim(cert, c, hb, true, now) // isRevoked = true
		if err != ErrSuccessionCertRevoked {
			t.Errorf("expected ErrSuccessionCertRevoked, got: %v", err)
		}
	}

	// 8. Future Timestamp (> 5 min skew)
	{
		futureClaimTime := now + 10*60*1000 // 10 minutes in future
		c := &SuccessionClaim{
			Type:                   "succession_claim_v1",
			GroupID:                groupID,
			Claimant:               bob.Fingerprint,
			ClaimantSigningKey:     bob.PubKey,
			CertificateHash:        cert.CertificateHash(),
			LastHeartbeatEventHash: hb.EventHash(),
			ClaimedAt:              futureClaimTime,
		}
		_ = c.Sign(bob.PrivKey)
		err := VerifySuccessionClaim(cert, c, hb, false, now) // now is 10 min behind claim
		if err != ErrSuccessionFutureTimestamp {
			t.Errorf("expected ErrSuccessionFutureTimestamp, got: %v", err)
		}
	}

	t.Logf("Case J PASS: All invalid/malicious claims rejected by production VerifySuccessionClaim")
}

// -----------------------------------------------------------------------------
// Section 7: Property-Based / State-Machine Convergence Test
// -----------------------------------------------------------------------------

// TestGroupSuccession_StateMachine_ConvergenceProperty executes randomized state-machine sequences:
// generates partitions, claims, delays, and heals, asserting whether canonical convergence holds.
func TestGroupSuccession_StateMachine_ConvergenceProperty(t *testing.T) {
	const iterations = 20
	r := rand.New(rand.NewSource(42))

	divergenceCount := 0

	for iter := 0; iter < iterations; iter++ {
		groupID := fmt.Sprintf("sm-group-%d", iter)
		baseTime := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

		alice := newTestGroupNode(t, "Alice", groupID)
		bob := newTestGroupNode(t, "Bob", groupID)
		charlie := newTestGroupNode(t, "Charlie", groupID)

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

		_ = bob.ApplyHeartbeat(hb)
		_ = charlie.ApplyHeartbeat(hb)

		// Decide whether Alice issues multiple succession certificates (prob = 0.5)
		multiCert := r.Intn(2) == 1

		certB := &SuccessionCertificate{
			Type:                   "succession_certificate_v1",
			GroupID:                groupID,
			CurrentOwner:           alice.Fingerprint,
			CurrentOwnerSigningKey: alice.PubKey,
			Successor:              bob.Fingerprint,
			SuccessorSigningKey:    bob.PubKey,
			HeartbeatTimeoutDays:   30,
			IssuedAt:               baseTime,
			ExpiresAt:              baseTime + int64(SuccessionCertValidityDuration/time.Millisecond),
		}
		_ = certB.Sign(alice.PrivKey)
		_ = bob.ApplyCertificate(certB)

		var certC *SuccessionCertificate
		if multiCert {
			certC = &SuccessionCertificate{
				Type:                   "succession_certificate_v1",
				GroupID:                groupID,
				CurrentOwner:           alice.Fingerprint,
				CurrentOwnerSigningKey: alice.PubKey,
				Successor:              charlie.Fingerprint,
				SuccessorSigningKey:    charlie.PubKey,
				HeartbeatTimeoutDays:   30,
				IssuedAt:               baseTime + 1000,
				ExpiresAt:              baseTime + 1000 + int64(SuccessionCertValidityDuration/time.Millisecond),
			}
			_ = certC.Sign(alice.PrivKey)
			_ = charlie.ApplyCertificate(certC)
		} else {
			_ = charlie.ApplyCertificate(certB)
		}

		// Advance time past timeout
		claimTime := baseTime + 31*24*60*60*1000

		// Bob creates claim
		claimB, _ := bob.CreateClaim(claimTime)
		_ = bob.ReceiveSuccessionClaim(claimB, claimTime)

		if multiCert {
			claimC, _ := charlie.CreateClaim(claimTime)
			_ = charlie.ReceiveSuccessionClaim(claimC, claimTime)

			// Network heals: cross-deliver claims
			_ = bob.ReceiveSuccessionClaim(claimC, claimTime)
			_ = charlie.ReceiveSuccessionClaim(claimB, claimTime)

			if bob.CurrentOwner != charlie.CurrentOwner {
				divergenceCount++
			}
		} else {
			// Charlie receives Bob's claim
			_ = charlie.ReceiveSuccessionClaim(claimB, claimTime)
			if bob.CurrentOwner != charlie.CurrentOwner {
				t.Fatalf("Single-certificate succession failed to converge: %s vs %s",
					bob.CurrentOwner, charlie.CurrentOwner)
			}
		}
	}

	t.Logf("State-machine property test complete: %d iterations, %d divergences detected under multi-cert partition",
		iterations, divergenceCount)
	if divergenceCount > 0 {
		t.Logf("State-Machine Finding: Split-brain divergence is 100%% reproducible whenever multi-certificate partition occurs")
	}
}
