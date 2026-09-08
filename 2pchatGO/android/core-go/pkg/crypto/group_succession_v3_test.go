package crypto

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"testing"
	"time"
)

// Helper to generate a test keypair
func generateTestKeyPair(t *testing.T) (ed25519.PublicKey, ed25519.PrivateKey, string, string) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate ed25519 key: %v", err)
	}
	pubB64 := base64.StdEncoding.EncodeToString(pub)
	fingerprint := hex.EncodeToString(pub) // lowercase hex
	return pub, priv, pubB64, fingerprint
}

// ============================================================================
// R-01: IMMUTABLE GENESIS ANCHOR A_0 (Wire Spec §4.1)
// ============================================================================

func TestV3_Genesis_CreationValid(t *testing.T) {
	_, priv, pubB64, fp := generateTestKeyPair(t)
	groupID := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	initialRosterHash := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	nowMs := time.Now().UnixMilli()

	genesis := &GenesisBlockV3{
		Type:               "group_succession_genesis_v3",
		GroupID:            groupID,
		CreatorFingerprint: fp,
		CreatorSigningKey:  pubB64,
		InitialRosterHash:  initialRosterHash,
		CreatedAtMs:        nowMs,
	}

	if err := genesis.Sign(priv); err != nil {
		t.Fatalf("genesis.Sign failed: %v", err)
	}

	if err := genesis.Verify(); err != nil {
		t.Fatalf("genesis.Verify failed: %v", err)
	}

	anchor := genesis.GenesisAnchor()
	if len(anchor) != 64 {
		t.Fatalf("expected 64-hex char anchor, got len %d: %s", len(anchor), anchor)
	}

	// JSON roundtrip
	jsonStr, err := genesis.ToJSON()
	if err != nil {
		t.Fatalf("genesis.ToJSON failed: %v", err)
	}
	parsed, err := ParseGenesisBlockV3(jsonStr)
	if err != nil {
		t.Fatalf("ParseGenesisBlockV3 failed: %v", err)
	}
	if parsed.GenesisAnchor() != anchor {
		t.Fatalf("anchor mismatch after JSON deserialization: %s != %s", parsed.GenesisAnchor(), anchor)
	}
}

func TestV3_Genesis_TamperedSig(t *testing.T) {
	_, priv, pubB64, fp := generateTestKeyPair(t)
	genesis := &GenesisBlockV3{
		Type:               "group_succession_genesis_v3",
		GroupID:            "group123",
		CreatorFingerprint: fp,
		CreatorSigningKey:  pubB64,
		InitialRosterHash:  "roster123",
		CreatedAtMs:        1773000000000,
	}
	if err := genesis.Sign(priv); err != nil {
		t.Fatalf("Sign failed: %v", err)
	}

	// Mutate signature
	sigBytes, _ := base64.StdEncoding.DecodeString(genesis.Signature)
	sigBytes[0] ^= 0xFF
	genesis.Signature = base64.StdEncoding.EncodeToString(sigBytes)

	if err := genesis.Verify(); err != ErrV3InvalidGenesisSignature {
		t.Fatalf("expected ErrV3InvalidGenesisSignature for tampered signature, got %v", err)
	}
}

func TestV3_Adv_FakeGenesisSubstitution(t *testing.T) {
	// Honest genesis
	_, honestPriv, honestPub, honestFp := generateTestKeyPair(t)
	honestGenesis := &GenesisBlockV3{
		Type:               "group_succession_genesis_v3",
		GroupID:            "group-secure-v3",
		CreatorFingerprint: honestFp,
		CreatorSigningKey:  honestPub,
		InitialRosterHash:  "roster-hash-1",
		CreatedAtMs:        1773000000000,
	}
	_ = honestGenesis.Sign(honestPriv)
	honestAnchor := honestGenesis.GenesisAnchor()

	// Mallory creates fake genesis for the same group ID
	_, malPriv, malPub, malFp := generateTestKeyPair(t)
	malGenesis := &GenesisBlockV3{
		Type:               "group_succession_genesis_v3",
		GroupID:            "group-secure-v3",
		CreatorFingerprint: malFp,
		CreatorSigningKey:  malPub,
		InitialRosterHash:  "roster-hash-mal",
		CreatedAtMs:        1773000000000,
	}
	_ = malGenesis.Sign(malPriv)
	malAnchor := malGenesis.GenesisAnchor()

	if honestAnchor == malAnchor {
		t.Fatalf("honest and malicious anchors must not collide")
	}

	// Cert anchored to Mallory's genesis is rejected by honest nodes bound to honestAnchor
	cert := &SuccessionCertificateV3{
		Type:                    "group_succession_cert_v3",
		GroupID:                 "group-secure-v3",
		TenureEpoch:             1,
		LineageSequence:         1,
		PreviousCertificateHash: malAnchor, // Attacker attempts substitution
		RosterEpoch:             1,
		RosterHash:              "roster-hash-1",
		CurrentOwner:            honestFp,
		CurrentOwnerSigningKey:  honestPub,
		Successor:               "bob",
		SuccessorSigningKey:     "key-bob",
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1773000000000,
		ExpiresAt:               1804536000000,
	}
	if err := cert.VerifyLineagePredecessor(honestAnchor); err != ErrV3PredecessorMismatch {
		t.Fatalf("expected ErrV3PredecessorMismatch when predecessor differs from honest genesis anchor, got %v", err)
	}
}

// ============================================================================
// R-02: NETSTRING CANONICAL ENCODING & DELIMITER INJECTION (Wire Spec §3)
// ============================================================================

func TestV3_Netstring_Canonical(t *testing.T) {
	var buf []byte
	appendCanonical(&buf, "test")
	expected := "4:test\n"
	if string(buf) != expected {
		t.Fatalf("expected %q, got %q", expected, string(buf))
	}

	buf = nil
	appendCanonical(&buf, "")
	expected = "0:\n"
	if string(buf) != expected {
		t.Fatalf("expected %q, got %q", expected, string(buf))
	}
}

func TestV3_Adv_DelimiterInjection(t *testing.T) {
	// Attacker injects delimiters: colons and newlines inside field
	maliciousSuccessor := "bob\n10:mallory\n"
	var buf []byte
	appendCanonical(&buf, maliciousSuccessor)
	// Output must be exact length of string + colon + string + \n
	expected := fmt.Sprintf("%d:%s\n", len(maliciousSuccessor), maliciousSuccessor)
	if string(buf) != expected {
		t.Fatalf("Netstring failed to escape delimiter injection: expected %q, got %q", expected, string(buf))
	}
}

// ============================================================================
// R-03: DOMAIN SEPARATION STRINGS (Wire Spec §2)
// ============================================================================

func TestV3_Domain_Verification(t *testing.T) {
	if DomainSuccessionGenesisV3 != "2pchat-group-genesis-v3\n" {
		t.Fatalf("wrong genesis domain: %s", DomainSuccessionGenesisV3)
	}
	if DomainSuccessionCertV3 != "2pchat-group-succession-cert-v3\n" {
		t.Fatalf("wrong cert domain: %s", DomainSuccessionCertV3)
	}
	if DomainSuccessionTransitionV3 != "2pchat-group-owner-transition-v3\n" {
		t.Fatalf("wrong transition domain: %s", DomainSuccessionTransitionV3)
	}
	if DomainSuccessionClaimV3 != "2pchat-group-succession-claim-v3\n" {
		t.Fatalf("wrong claim domain: %s", DomainSuccessionClaimV3)
	}
	if DomainLivenessAttestationV3 != "2pchat-group-liveness-attestation-v3\n" {
		t.Fatalf("wrong liveness attestation domain: %s", DomainLivenessAttestationV3)
	}
	if DomainSuccessionRevocationV3 != "2pchat-group-succession-revocation-v3\n" {
		t.Fatalf("wrong revocation domain: %s", DomainSuccessionRevocationV3)
	}
	if DomainOwnerHeartbeatV3 != "2pchat-group-owner-heartbeat-v3\n" {
		t.Fatalf("wrong heartbeat domain: %s", DomainOwnerHeartbeatV3)
	}
	if DomainRosterDigestV3 != "2pchat-roster-digest-v3\n" {
		t.Fatalf("wrong roster digest domain: %s", DomainRosterDigestV3)
	}
	if DomainRosterEpochRecordV3 != "2pchat-roster-epoch-record-v3\n" {
		t.Fatalf("wrong roster epoch domain: %s", DomainRosterEpochRecordV3)
	}
}

func TestV3_Adv_SignatureSubstitution(t *testing.T) {
	// Sign a message using DomainSuccessionTransitionV3, then try to verify it as SuccessionCertificateV3
	_, priv, pubB64, fp := generateTestKeyPair(t)

	transition := &GroupOwnerTransitionCertificateV3{
		Type:                     "group_succession_transition_v3",
		GroupID:                  "group-shared",
		PriorTenureEpoch:         1,
		NewTenureEpoch:           2,
		PriorTenureFinalCertHash: "prev-hash",
		OldOwnerFingerprint:      fp,
		OldOwnerSigningKey:       pubB64,
		NewOwnerFingerprint:      "new-fp",
		NewOwnerSigningKey:       "new-key",
		RosterEpoch:              1,
		RosterHash:               "roster-hash",
		TransferredAtMs:          1773000000000,
	}
	if err := transition.Sign(priv); err != nil {
		t.Fatalf("transition.Sign failed: %v", err)
	}

	// Attempt to graft transition.OldOwnerSignature onto a SuccessionCertificateV3
	cert := &SuccessionCertificateV3{
		Type:                    "group_succession_cert_v3",
		GroupID:                 "group-shared",
		TenureEpoch:             1,
		LineageSequence:         1,
		PreviousCertificateHash: "prev-hash",
		RosterEpoch:             1,
		RosterHash:              "roster-hash",
		CurrentOwner:            fp,
		CurrentOwnerSigningKey:  pubB64,
		Successor:               "new-fp",
		SuccessorSigningKey:     "new-key",
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1773000000000,
		ExpiresAt:               1804536000000,
		Signature:               transition.OldOwnerSignature, // Replayed signature
	}

	if err := cert.Verify(); err != ErrV3InvalidCertificateSignature {
		t.Fatalf("expected ErrV3InvalidCertificateSignature on cross-domain signature replay, got %v", err)
	}
}

// ============================================================================
// R-04: DEPOSED OWNER IMPOTENCE & TENURE DOMINANCE (Wire Spec §4.2)
// ============================================================================

func TestV3_TenureDominance_Success(t *testing.T) {
	_, priv1, pub1, fp1 := generateTestKeyPair(t)
	_, priv2, pub2, fp2 := generateTestKeyPair(t)

	certTenure1 := &SuccessionCertificateV3{
		GroupID:                 "group-1",
		TenureEpoch:             1,
		LineageSequence:         100, // Very high sequence
		PreviousCertificateHash: "h1",
		RosterEpoch:             1,
		RosterHash:              "r1",
		CurrentOwner:            fp1,
		CurrentOwnerSigningKey:  pub1,
		Successor:               fp2,
		SuccessorSigningKey:     pub2,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               2000,
	}
	_ = certTenure1.Sign(priv1)

	certTenure2 := &SuccessionCertificateV3{
		GroupID:                 "group-1",
		TenureEpoch:             2,
		LineageSequence:         1, // Low sequence in new tenure
		PreviousCertificateHash: "h2",
		RosterEpoch:             2,
		RosterHash:              "r2",
		CurrentOwner:            fp2,
		CurrentOwnerSigningKey:  pub2,
		Successor:               fp1,
		SuccessorSigningKey:     pub1,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1500,
		ExpiresAt:               2500,
	}
	_ = certTenure2.Sign(priv2)

	// Tenure 2 strictly dominates Tenure 1
	winner := ResolveSuccessionConflictV3(certTenure1, certTenure2)
	if winner != certTenure2 {
		t.Fatalf("expected certTenure2 (Tenure 2) to win, got Tenure %d", winner.TenureEpoch)
	}

	// Symmetry / Commutativity
	winnerRev := ResolveSuccessionConflictV3(certTenure2, certTenure1)
	if winnerRev != certTenure2 {
		t.Fatalf("commutative property violated: expected certTenure2, got Tenure %d", winnerRev.TenureEpoch)
	}
}

func TestV3_Adv_DeposedOwnerSequenceInflation(t *testing.T) {
	// Deposed owner Alice tries to attack new tenure owner Bob by signing a cert with sequence 999,999,999
	_, alicePriv, alicePub, aliceFp := generateTestKeyPair(t)
	_, bobPriv, bobPub, bobFp := generateTestKeyPair(t)

	aliceInflatedCert := &SuccessionCertificateV3{
		GroupID:                 "group-inflation",
		TenureEpoch:             1,
		LineageSequence:         999999999, // Massive sequence
		PreviousCertificateHash: "alice-pred",
		RosterEpoch:             1,
		RosterHash:              "roster",
		CurrentOwner:            aliceFp,
		CurrentOwnerSigningKey:  alicePub,
		Successor:               "carol",
		SuccessorSigningKey:     "carol-key",
		HeartbeatTimeoutDays:    30,
		IssuedAt:                9999999999999,
		ExpiresAt:               9999999999999 + 86400000,
	}
	_ = aliceInflatedCert.Sign(alicePriv)

	bobLegitimateCert := &SuccessionCertificateV3{
		GroupID:                 "group-inflation",
		TenureEpoch:             2,
		LineageSequence:         1, // Modest initial sequence
		PreviousCertificateHash: "transition-hash",
		RosterEpoch:             2,
		RosterHash:              "roster-2",
		CurrentOwner:            bobFp,
		CurrentOwnerSigningKey:  bobPub,
		Successor:               "dave",
		SuccessorSigningKey:     "dave-key",
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               2000,
	}
	_ = bobLegitimateCert.Sign(bobPriv)

	winner := ResolveSuccessionConflictV3(aliceInflatedCert, bobLegitimateCert)
	if winner != bobLegitimateCert {
		t.Fatalf("CRITICAL SECURITY FAILURE: Deposed owner sequence inflation defeated legitimate tenure owner!")
	}
}

// ============================================================================
// R-05: ARRIVAL-ORDER INDEPENDENCE & DETERMINISTIC TIE-BREAKER (INV-2)
// ============================================================================

func TestV3_TieBreaker_MinHash(t *testing.T) {
	_, priv, pub, fp := generateTestKeyPair(t)

	certA := &SuccessionCertificateV3{
		GroupID:                 "group-fork",
		TenureEpoch:             1,
		LineageSequence:         1,
		PreviousCertificateHash: "genesis",
		RosterEpoch:             1,
		RosterHash:              "roster",
		CurrentOwner:            fp,
		CurrentOwnerSigningKey:  pub,
		Successor:               "bob-fingerprint",
		SuccessorSigningKey:     "bob-signing-key",
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               2000,
	}
	_ = certA.Sign(priv)

	certB := &SuccessionCertificateV3{
		GroupID:                 "group-fork",
		TenureEpoch:             1,
		LineageSequence:         1,
		PreviousCertificateHash: "genesis",
		RosterEpoch:             1,
		RosterHash:              "roster",
		CurrentOwner:            fp,
		CurrentOwnerSigningKey:  pub,
		Successor:               "charlie-fingerprint",
		SuccessorSigningKey:     "charlie-signing-key",
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               2000,
	}
	_ = certB.Sign(priv)

	hashA := certA.CertificateHash()
	hashB := certB.CertificateHash()

	var expectedWinner *SuccessionCertificateV3
	if hashA <= hashB {
		expectedWinner = certA
	} else {
		expectedWinner = certB
	}

	winner1 := ResolveSuccessionConflictV3(certA, certB)
	winner2 := ResolveSuccessionConflictV3(certB, certA)

	if winner1 != expectedWinner || winner2 != expectedWinner {
		t.Fatalf("Tie-breaker did not select min(CertHash): w1=%s, w2=%s, exp=%s",
			winner1.CertificateHash(), winner2.CertificateHash(), expectedWinner.CertificateHash())
	}
}

func TestV3_Adv_ArrivalOrder120Permutations(t *testing.T) {
	_, priv, pub, fp := generateTestKeyPair(t)

	// Create 5 conflicting certificates at (T=1, S=1)
	certs := make([]*SuccessionCertificateV3, 5)
	for i := 0; i < 5; i++ {
		c := &SuccessionCertificateV3{
			GroupID:                 "group-perm-120",
			TenureEpoch:             1,
			LineageSequence:         1,
			PreviousCertificateHash: "genesis",
			RosterEpoch:             1,
			RosterHash:              "roster",
			CurrentOwner:            fp,
			CurrentOwnerSigningKey:  pub,
			Successor:               fmt.Sprintf("successor-%d", i),
			SuccessorSigningKey:     fmt.Sprintf("key-%d", i),
			HeartbeatTimeoutDays:    30,
			IssuedAt:                int64(1000 + i*10), // Deliberately varied timestamps!
			ExpiresAt:               3000,
		}
		_ = c.Sign(priv)
		certs[i] = c
	}

	// Find the true minimum hash among all 5
	minHash := certs[0].CertificateHash()
	var canonicalCert *SuccessionCertificateV3 = certs[0]
	for _, c := range certs[1:] {
		if c.CertificateHash() < minHash {
			minHash = c.CertificateHash()
			canonicalCert = c
		}
	}

	// Generate all 5! = 120 permutations
	var permutations [][]int
	var permute func([]int, int)
	permute = func(arr []int, k int) {
		if k == len(arr) {
			copied := make([]int, len(arr))
			copy(copied, arr)
			permutations = append(permutations, copied)
			return
		}
		for i := k; i < len(arr); i++ {
			arr[k], arr[i] = arr[i], arr[k]
			permute(arr, k+1)
			arr[k], arr[i] = arr[i], arr[k]
		}
	}
	permute([]int{0, 1, 2, 3, 4}, 0)

	if len(permutations) != 120 {
		t.Fatalf("expected 120 permutations, got %d", len(permutations))
	}

	// Verify that EVERY single permutation converges to canonicalCert
	for pIdx, p := range permutations {
		var head *SuccessionCertificateV3 = nil
		for _, itemIdx := range p {
			head = ResolveSuccessionConflictV3(head, certs[itemIdx])
		}
		if head.CertificateHash() != canonicalCert.CertificateHash() {
			t.Fatalf("Permutation %d failed to converge to canonical cert! Got hash %s, expected %s",
				pIdx, head.CertificateHash(), canonicalCert.CertificateHash())
		}
	}
}

// ============================================================================
// R-06: VOLUNTARY HANDOVER MODE A (Wire Spec §4.3)
// ============================================================================

func TestV3_Transition_ModeA_Valid(t *testing.T) {
	_, oldPriv, oldPub, oldFp := generateTestKeyPair(t)
	_, newPriv, newPub, newFp := generateTestKeyPair(t)
	_ = newPriv

	priorCert := &SuccessionCertificateV3{
		GroupID:                 "group-mode-a",
		TenureEpoch:             1,
		LineageSequence:         2,
		PreviousCertificateHash: "h1",
		RosterEpoch:             2,
		RosterHash:              "roster-2",
		CurrentOwner:            oldFp,
		CurrentOwnerSigningKey:  oldPub,
		Successor:               newFp,
		SuccessorSigningKey:     newPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               2000,
	}
	_ = priorCert.Sign(oldPriv)

	transition := &GroupOwnerTransitionCertificateV3{
		Type:                     "group_succession_transition_v3",
		GroupID:                  "group-mode-a",
		PriorTenureEpoch:         1,
		NewTenureEpoch:           2,
		PriorTenureFinalCertHash: priorCert.CertificateHash(),
		OldOwnerFingerprint:      oldFp,
		OldOwnerSigningKey:       oldPub,
		NewOwnerFingerprint:      newFp,
		NewOwnerSigningKey:       newPub,
		RosterEpoch:              3,
		RosterHash:               "roster-3",
		TransferredAtMs:          1500,
	}
	if err := transition.Sign(oldPriv); err != nil {
		t.Fatalf("transition.Sign failed: %v", err)
	}

	if err := transition.Verify(); err != nil {
		t.Fatalf("transition.Verify failed: %v", err)
	}

	if err := VerifyTenureTransitionV3(priorCert, transition); err != nil {
		t.Fatalf("VerifyTenureTransitionV3 failed: %v", err)
	}
}

func TestV3_Transition_WrongPriorOwner(t *testing.T) {
	_, oldPriv, oldPub, oldFp := generateTestKeyPair(t)
	_, malPriv, malPub, _ := generateTestKeyPair(t)
	_, _, newPub, newFp := generateTestKeyPair(t)

	priorCert := &SuccessionCertificateV3{
		GroupID:                 "group-mode-a",
		TenureEpoch:             1,
		LineageSequence:         2,
		PreviousCertificateHash: "h1",
		RosterEpoch:             2,
		RosterHash:              "roster-2",
		CurrentOwner:            oldFp,
		CurrentOwnerSigningKey:  oldPub,
		Successor:               newFp,
		SuccessorSigningKey:     newPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               2000,
	}
	_ = priorCert.Sign(oldPriv)

	// Mallory tries to sign transition with her own key
	transition := &GroupOwnerTransitionCertificateV3{
		Type:                     "group_succession_transition_v3",
		GroupID:                  "group-mode-a",
		PriorTenureEpoch:         1,
		NewTenureEpoch:           2,
		PriorTenureFinalCertHash: priorCert.CertificateHash(),
		OldOwnerFingerprint:      oldFp,
		OldOwnerSigningKey:       malPub, // Key mismatch!
		NewOwnerFingerprint:      newFp,
		NewOwnerSigningKey:       newPub,
		RosterEpoch:              3,
		RosterHash:               "roster-3",
		TransferredAtMs:          1500,
	}
	_ = transition.Sign(malPriv)

	err := VerifyTenureTransitionV3(priorCert, transition)
	if err != ErrV3TransitionOwnerMismatch {
		t.Fatalf("expected ErrV3TransitionOwnerMismatch, got %v", err)
	}
}

func TestV3_Adv_UnsignedTenureJump(t *testing.T) {
	_, priv, pub, fp := generateTestKeyPair(t)

	// Attacker tries to skip tenure 2 directly to tenure 5
	transition := &GroupOwnerTransitionCertificateV3{
		Type:                     "group_succession_transition_v3",
		GroupID:                  "group-jump",
		PriorTenureEpoch:         1,
		NewTenureEpoch:           5, // Illegal jump!
		PriorTenureFinalCertHash: "hash",
		OldOwnerFingerprint:      fp,
		OldOwnerSigningKey:       pub,
		NewOwnerFingerprint:      "new-fp",
		NewOwnerSigningKey:       "new-pub",
		RosterEpoch:              1,
		RosterHash:               "roster",
		TransferredAtMs:          1000,
	}
	_ = transition.Sign(priv)

	if err := transition.Verify(); err != ErrV3TransitionEpochStepInvalid {
		t.Fatalf("expected ErrV3TransitionEpochStepInvalid on epoch jump, got %v", err)
	}
}

// ============================================================================
// R-07 & R-08: INVOLUNTARY CLAIM (MODE B) & WITNESS ATTESTATION QUORUM
// ============================================================================

func TestV3_Claim_ModeB_Valid(t *testing.T) {
	_, ownerPriv, ownerPub, ownerFp := generateTestKeyPair(t)
	_, bobPriv, bobPub, bobFp := generateTestKeyPair(t)
	_, w1Priv, w1Pub, w1Fp := generateTestKeyPair(t)
	_, w2Priv, w2Pub, w2Fp := generateTestKeyPair(t)

	cert := &SuccessionCertificateV3{
		GroupID:                 "group-mode-b",
		TenureEpoch:             1,
		LineageSequence:         1,
		PreviousCertificateHash: "genesis-anchor",
		RosterEpoch:             1,
		RosterHash:              "roster-1",
		CurrentOwner:            ownerFp,
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               bobFp,
		SuccessorSigningKey:     bobPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               100000,
	}
	_ = cert.Sign(ownerPriv)

	lastHeartbeatHash := "last-heartbeat-sha256"
	targetTenure := uint64(2)
	nowMs := int64(1000 + 31*24*3600*1000)

	// Build active roster of 4 members: Owner, Bob, Witness1, Witness2
	roster := []RosterMemberEntryV3{
		{DeviceID: ownerFp, SigningKey: ownerPub, Role: "OWNER", Status: "ACTIVE"},
		{DeviceID: bobFp, SigningKey: bobPub, Role: "MEMBER", Status: "ACTIVE"},
		{DeviceID: w1Fp, SigningKey: w1Pub, Role: "MEMBER", Status: "ACTIVE"},
		{DeviceID: w2Fp, SigningKey: w2Pub, Role: "MEMBER", Status: "ACTIVE"},
	}
	// N = 4 active members. Required quorum = min(3, ceil(4/2)) = 2.

	w1 := WitnessAttestationV3{
		WitnessFingerprint: w1Fp,
		WitnessSigningKey:  w1Pub,
		AttestedAtMs:       nowMs,
	}
	_ = w1.Sign(w1Priv, cert.GroupID, targetTenure, cert.CertificateHash(), bobFp, lastHeartbeatHash)

	w2 := WitnessAttestationV3{
		WitnessFingerprint: w2Fp,
		WitnessSigningKey:  w2Pub,
		AttestedAtMs:       nowMs,
	}
	_ = w2.Sign(w2Priv, cert.GroupID, targetTenure, cert.CertificateHash(), bobFp, lastHeartbeatHash)

	claim := &SuccessionClaimV3{
		Type:                "group_succession_claim_v3",
		GroupID:             cert.GroupID,
		TargetTenureEpoch:   targetTenure,
		CertificateHash:     cert.CertificateHash(),
		ClaimantFingerprint: bobFp,
		ClaimantSigningKey:  bobPub,
		LastHeartbeatHash:   lastHeartbeatHash,
		ClaimedAtMs:         nowMs,
		WitnessAttestations: []WitnessAttestationV3{w1, w2},
	}
	if err := claim.Sign(bobPriv); err != nil {
		t.Fatalf("claim.Sign failed: %v", err)
	}

	if err := VerifySuccessionClaimV3(cert, claim, roster, lastHeartbeatHash, 31); err != nil {
		t.Fatalf("VerifySuccessionClaimV3 failed: %v", err)
	}
}

func TestV3_Claim_PrematureLocalTimeout(t *testing.T) {
	_, ownerPriv, ownerPub, ownerFp := generateTestKeyPair(t)
	_, bobPriv, bobPub, bobFp := generateTestKeyPair(t)
	_, wPriv, wPub, wFp := generateTestKeyPair(t)

	cert := &SuccessionCertificateV3{
		GroupID:                 "group-mode-b",
		TenureEpoch:             1,
		LineageSequence:         1,
		PreviousCertificateHash: "genesis",
		RosterEpoch:             1,
		RosterHash:              "roster",
		CurrentOwner:            ownerFp,
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               bobFp,
		SuccessorSigningKey:     bobPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               100000,
	}
	_ = cert.Sign(ownerPriv)

	lastHb := "hb1"
	w := WitnessAttestationV3{WitnessFingerprint: wFp, WitnessSigningKey: wPub, AttestedAtMs: 2000}
	_ = w.Sign(wPriv, cert.GroupID, 2, cert.CertificateHash(), bobFp, lastHb)

	claim := &SuccessionClaimV3{
		GroupID:             cert.GroupID,
		TargetTenureEpoch:   2,
		CertificateHash:     cert.CertificateHash(),
		ClaimantFingerprint: bobFp,
		ClaimantSigningKey:  bobPub,
		LastHeartbeatHash:   lastHb,
		ClaimedAtMs:         2000,
		WitnessAttestations: []WitnessAttestationV3{w},
	}
	_ = claim.Sign(bobPriv)

	roster := []RosterMemberEntryV3{
		{DeviceID: bobFp, SigningKey: bobPub, Status: "ACTIVE"},
		{DeviceID: wFp, SigningKey: wPub, Status: "ACTIVE"},
	}

	// Claiming at 29 days (less than 30 required)
	err := VerifySuccessionClaimV3(cert, claim, roster, lastHb, 29)
	if err != ErrV3ClaimTimeoutNotElapsed {
		t.Fatalf("expected ErrV3ClaimTimeoutNotElapsed, got %v", err)
	}
}

func TestV3_WitnessQuorum_Insufficient(t *testing.T) {
	_, ownerPriv, ownerPub, ownerFp := generateTestKeyPair(t)
	_, bobPriv, bobPub, bobFp := generateTestKeyPair(t)
	_, wPriv, wPub, wFp := generateTestKeyPair(t)

	cert := &SuccessionCertificateV3{
		GroupID:                 "group-quorum",
		TenureEpoch:             1,
		LineageSequence:         1,
		PreviousCertificateHash: "genesis",
		RosterEpoch:             1,
		RosterHash:              "roster",
		CurrentOwner:            ownerFp,
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               bobFp,
		SuccessorSigningKey:     bobPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               100000,
	}
	_ = cert.Sign(ownerPriv)

	lastHb := "hb1"
	w := WitnessAttestationV3{WitnessFingerprint: wFp, WitnessSigningKey: wPub, AttestedAtMs: 2000}
	_ = w.Sign(wPriv, cert.GroupID, 2, cert.CertificateHash(), bobFp, lastHb)

	claim := &SuccessionClaimV3{
		GroupID:             cert.GroupID,
		TargetTenureEpoch:   2,
		CertificateHash:     cert.CertificateHash(),
		ClaimantFingerprint: bobFp,
		ClaimantSigningKey:  bobPub,
		LastHeartbeatHash:   lastHb,
		ClaimedAtMs:         2000,
		WitnessAttestations: []WitnessAttestationV3{w}, // Only 1 witness!
	}
	_ = claim.Sign(bobPriv)

	// Roster of 5 active members -> ceil(5/2) = 3 witnesses required!
	roster := []RosterMemberEntryV3{
		{DeviceID: ownerFp, SigningKey: ownerPub, Status: "ACTIVE"},
		{DeviceID: bobFp, SigningKey: bobPub, Status: "ACTIVE"},
		{DeviceID: wFp, SigningKey: wPub, Status: "ACTIVE"},
		{DeviceID: "m4", SigningKey: "k4", Status: "ACTIVE"},
		{DeviceID: "m5", SigningKey: "k5", Status: "ACTIVE"},
	}

	err := VerifySuccessionClaimV3(cert, claim, roster, lastHb, 35)
	if err != ErrV3ClaimInsufficientWitnesses {
		t.Fatalf("expected ErrV3ClaimInsufficientWitnesses, got %v", err)
	}
}

func TestV3_Adv_SybilWitnessClaim(t *testing.T) {
	_, ownerPriv, ownerPub, ownerFp := generateTestKeyPair(t)
	_, bobPriv, bobPub, bobFp := generateTestKeyPair(t)
	_, wPriv, wPub, wFp := generateTestKeyPair(t)

	cert := &SuccessionCertificateV3{
		GroupID:                 "group-sybil",
		TenureEpoch:             1,
		LineageSequence:         1,
		PreviousCertificateHash: "genesis",
		RosterEpoch:             1,
		RosterHash:              "roster",
		CurrentOwner:            ownerFp,
		CurrentOwnerSigningKey:  ownerPub,
		Successor:               bobFp,
		SuccessorSigningKey:     bobPub,
		HeartbeatTimeoutDays:    30,
		IssuedAt:                1000,
		ExpiresAt:               100000,
	}
	_ = cert.Sign(ownerPriv)

	lastHb := "hb1"
	w := WitnessAttestationV3{WitnessFingerprint: wFp, WitnessSigningKey: wPub, AttestedAtMs: 2000}
	_ = w.Sign(wPriv, cert.GroupID, 2, cert.CertificateHash(), bobFp, lastHb)

	// Claimant duplicates the witness attestation to fake quorum count
	claim := &SuccessionClaimV3{
		GroupID:             cert.GroupID,
		TargetTenureEpoch:   2,
		CertificateHash:     cert.CertificateHash(),
		ClaimantFingerprint: bobFp,
		ClaimantSigningKey:  bobPub,
		LastHeartbeatHash:   lastHb,
		ClaimedAtMs:         2000,
		WitnessAttestations: []WitnessAttestationV3{w, w}, // Duplicate!
	}
	_ = claim.Sign(bobPriv)

	roster := []RosterMemberEntryV3{
		{DeviceID: bobFp, SigningKey: bobPub, Status: "ACTIVE"},
		{DeviceID: wFp, SigningKey: wPub, Status: "ACTIVE"},
	}

	err := VerifySuccessionClaimV3(cert, claim, roster, lastHb, 35)
	if err != ErrV3ClaimDuplicateWitness {
		t.Fatalf("expected ErrV3ClaimDuplicateWitness on duplicate witness replay, got %v", err)
	}
}

// ============================================================================
// R-09: CANONICAL ROSTER DIGEST (Wire Spec §4.2)
// ============================================================================

func TestV3_RosterHash_ExactMatch(t *testing.T) {
	// Mixed case IDs, different insertion orders
	m1 := RosterMemberEntryV3{DeviceID: "AAAA", SigningKey: "keyA", Role: "OWNER", Status: "ACTIVE"}
	m2 := RosterMemberEntryV3{DeviceID: "bbbb", SigningKey: "keyB", Role: "MEMBER", Status: "ACTIVE"}
	m3 := RosterMemberEntryV3{DeviceID: "CCCC", SigningKey: "keyC", Role: "ADMIN", Status: "ACTIVE"}

	list1 := []RosterMemberEntryV3{m1, m2, m3}
	list2 := []RosterMemberEntryV3{m3, m1, m2} // reordered

	hash1 := ComputeRosterHashV3(list1)
	hash2 := ComputeRosterHashV3(list2)

	if hash1 != hash2 {
		t.Fatalf("ComputeRosterHashV3 must be order-independent: %s != %s", hash1, hash2)
	}
}

func TestV3_RosterHash_MemberMismatch(t *testing.T) {
	m1 := RosterMemberEntryV3{DeviceID: "aaaa", SigningKey: "keyA", Role: "MEMBER", Status: "ACTIVE"}
	m2 := RosterMemberEntryV3{DeviceID: "aaaa", SigningKey: "keyA", Role: "ADMIN", Status: "ACTIVE"} // Role changed

	hash1 := ComputeRosterHashV3([]RosterMemberEntryV3{m1})
	hash2 := ComputeRosterHashV3([]RosterMemberEntryV3{m2})

	if hash1 == hash2 {
		t.Fatalf("ComputeRosterHashV3 must detect role changes")
	}
}

// ============================================================================
// R-10: ROSTER LINEAGE IN CONTROL DAG (Proof Challenge §5)
// ============================================================================

func TestV3_RosterDAG_LineageValid(t *testing.T) {
	_, priv, pub, fp := generateTestKeyPair(t)
	groupID := "group-dag"
	genesisRosterHash := "roster-hash-1"

	rec1 := &RosterEpochRecordV3{
		GroupID:               groupID,
		RosterEpoch:           1,
		PreviousRecordHash:    "",
		RosterHash:            genesisRosterHash,
		ModifiedByFingerprint: fp,
		ModifiedBySigningKey:  pub,
		TimestampMs:           1000,
	}
	_ = rec1.Sign(priv)

	rec2 := &RosterEpochRecordV3{
		GroupID:               groupID,
		RosterEpoch:           2,
		PreviousRecordHash:    rec1.RecordHash(),
		RosterHash:            "roster-hash-2",
		ModifiedByFingerprint: fp,
		ModifiedBySigningKey:  pub,
		TimestampMs:           2000,
	}
	_ = rec2.Sign(priv)

	chain := []*RosterEpochRecordV3{rec1, rec2}
	if err := VerifyRosterLineageV3(chain, genesisRosterHash); err != nil {
		t.Fatalf("VerifyRosterLineageV3 failed: %v", err)
	}
}

func TestV3_RosterDAG_UnlinkedEpoch(t *testing.T) {
	_, priv, pub, fp := generateTestKeyPair(t)
	groupID := "group-dag"
	genesisRosterHash := "roster-hash-1"

	rec1 := &RosterEpochRecordV3{
		GroupID:               groupID,
		RosterEpoch:           1,
		PreviousRecordHash:    "",
		RosterHash:            genesisRosterHash,
		ModifiedByFingerprint: fp,
		ModifiedBySigningKey:  pub,
		TimestampMs:           1000,
	}
	_ = rec1.Sign(priv)

	rec2 := &RosterEpochRecordV3{
		GroupID:               groupID,
		RosterEpoch:           2,
		PreviousRecordHash:    "wrong-hash", // Broken chain!
		RosterHash:            "roster-hash-2",
		ModifiedByFingerprint: fp,
		ModifiedBySigningKey:  pub,
		TimestampMs:           2000,
	}
	_ = rec2.Sign(priv)

	chain := []*RosterEpochRecordV3{rec1, rec2}
	if err := VerifyRosterLineageV3(chain, genesisRosterHash); err != ErrV3RosterLineageBroken {
		t.Fatalf("expected ErrV3RosterLineageBroken, got %v", err)
	}
}
