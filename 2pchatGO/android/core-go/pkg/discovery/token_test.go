package discovery

import (
	"strings"
	"testing"
)

func TestDiscoveryTokenDerivation(t *testing.T) {
	fp := "zK8y9u2V1A0bC3dE4fG5hI6jK7lM8nO9pQ0rS1tU2vW="
	token1 := DeriveDiscoveryToken(fp)
	token2 := DeriveDiscoveryToken(fp)

	if len(token1) != 16 {
		t.Fatalf("Expected 16-character token, got length %d (%s)", len(token1), token1)
	}
	if token1 != token2 {
		t.Fatalf("Expected deterministic derivation, got %s != %s", token1, token2)
	}

	// Different fingerprint must yield different token
	differentFP := "differentFingerprint1234567890abcdef="
	tokenDiff := DeriveDiscoveryToken(differentFP)
	if token1 == tokenDiff {
		t.Fatalf("Collision detected between different fingerprints")
	}
}

func TestDiscoveryTokenMatching(t *testing.T) {
	fp := "mySecureLocalFingerprintBase64Value=="
	token := DeriveDiscoveryToken(fp)

	if !MatchesDiscoveryToken(token, fp) {
		t.Errorf("Expected token to match fingerprint")
	}
	if !MatchesDiscoveryToken(strings.ToUpper(token), fp) {
		t.Errorf("Expected case-insensitive match")
	}
	if !MatchesDiscoveryToken("  "+token+"  ", fp) {
		t.Errorf("Expected whitespace-trimmed match")
	}
	if MatchesDiscoveryToken("invalidToken1234", fp) {
		t.Errorf("Expected invalid token to be rejected")
	}
}

func TestRendezvousCodeCalculation(t *testing.T) {
	fp := "sample-identity-fingerprint"
	code := DeriveRendezvousCode(fp)

	if len(code) != 8 {
		t.Fatalf("Expected 8-character hex code, got %d (%s)", len(code), code)
	}

	// Ensure all characters are hex
	for _, c := range code {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			t.Fatalf("Invalid hex character in code: %c", c)
		}
	}
}

func TestRendezvousKeyDerivation(t *testing.T) {
	// Must match Python:
	// payload = b"2pchat-rendezvous-v1:null:36571c05" -> 4725456c9bc18c138f2066366fcf09bfe6ecdc34
	hexKey := DeriveRendezvousKeyHex("Null", "36571c05")
	expected := "4725456c9bc18c138f2066366fcf09bfe6ecdc34"
	if hexKey != expected {
		t.Fatalf("Rendezvous key hex mismatch: got %s, expected %s", hexKey, expected)
	}

	// Whitespace and case-insensitivity
	hexKey2 := DeriveRendezvousKeyHex("  NULL  ", "  36571c05  ")
	if hexKey2 != expected {
		t.Fatalf("Rendezvous key normalization failed: got %s, expected %s", hexKey2, expected)
	}
}
