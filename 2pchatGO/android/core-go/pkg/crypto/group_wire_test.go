package crypto

import (
	"crypto/ed25519"
	"encoding/base64"
	"testing"
)

func TestGroupOwnerTransitionCertificateCanonicalForm(t *testing.T) {
	head := "control-head-7"
	cert := &GroupOwnerTransitionCertificate{
		GroupID:             "group-1",
		PreviousOwnerAnchor: "root-anchor",
		LineageSequence:     1,
		PreviousControlHead: &head,
		OldOwnerFingerprint: "old-fingerprint",
		OldOwnerDeviceId:    "old-device",
		OldOwnerSigningKey:  "old-signing-key",
		NewOwnerFingerprint: "new-fingerprint",
		NewOwnerDeviceId:    "new-device",
		NewOwnerSigningKey:  "new-signing-key",
		CreatedAtMs:         1784000000000,
		Nonce:               "nonce-1",
	}

	expected := "2pchat-group-owner-transition-v1\n" +
		"1\n" +
		"7:group-1\n" +
		"11:root-anchor\n" +
		"1\n" +
		"14:control-head-7\n" +
		"15:old-fingerprint\n" +
		"10:old-device\n" +
		"15:old-signing-key\n" +
		"15:new-fingerprint\n" +
		"10:new-device\n" +
		"15:new-signing-key\n" +
		"1784000000000\n" +
		"7:nonce-1\n"

	actual := cert.CanonicalForSignature()
	if actual != expected {
		t.Errorf("Canonical mismatch:\nExpected:\n%s\nGot:\n%s", expected, actual)
	}
}

func TestGroupOwnerTransitionSigningAndVerification(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("GenerateKey failed: %v", err)
	}

	head := "control-head-7"
	cert := &GroupOwnerTransitionCertificate{
		GroupID:             "group-xyz",
		PreviousOwnerAnchor: "anchor-001",
		LineageSequence:     1,
		PreviousControlHead: &head,
		OldOwnerFingerprint: "old-fp",
		OldOwnerDeviceId:    "old-dev",
		OldOwnerSigningKey:  base64.StdEncoding.EncodeToString(pub),
		NewOwnerFingerprint: "new-fp",
		NewOwnerDeviceId:    "new-dev",
		NewOwnerSigningKey:  "new-key",
		CreatedAtMs:         1784000000000,
		Nonce:               "nonce-random",
	}

	canonical := cert.CanonicalForSignature()
	sig := ed25519.Sign(priv, []byte(canonical))
	cert.SignatureBase64 = base64.StdEncoding.EncodeToString(sig)

	if !cert.Verify() {
		t.Fatalf("Certificate verification failed with valid signature")
	}

	// Tamper group ID
	certTampered := *cert
	certTampered.GroupID = "group-xyz-tampered"
	if certTampered.Verify() {
		t.Fatalf("Tampered certificate unexpectedly verified")
	}

	// Tamper signature
	certTamperedSig := *cert
	certTamperedSig.SignatureBase64 = base64.StdEncoding.EncodeToString([]byte("invalid-64-byte-sig-random-padding-12345678901234567890123456789012"))
	if certTamperedSig.Verify() {
		t.Fatalf("Invalid signature unexpectedly verified")
	}
}

func TestGroupTransitionIDDeterministic(t *testing.T) {
	cert := &GroupOwnerTransitionCertificate{
		GroupID:             "group-1",
		PreviousOwnerAnchor: "root-anchor",
		LineageSequence:     1,
		OldOwnerFingerprint: "old-fp",
		OldOwnerDeviceId:    "old-dev",
		OldOwnerSigningKey:  "key1",
		NewOwnerFingerprint: "new-fp",
		NewOwnerDeviceId:    "new-dev",
		NewOwnerSigningKey:  "key2",
		CreatedAtMs:         1000,
		Nonce:               "nonce-1",
		SignatureBase64:     "sig-base64",
	}

	id1 := cert.TransitionID()
	id2 := cert.TransitionID()

	if id1 != id2 || len(id1) != 64 {
		t.Fatalf("Expected 64-char hex deterministic ID, got %s != %s", id1, id2)
	}
}
