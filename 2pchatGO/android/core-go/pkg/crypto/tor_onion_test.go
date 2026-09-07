package crypto

import (
	"bytes"
	"crypto/rand"
	"regexp"
	"strings"
	"testing"
)

func TestDeriveTorOnionKey_DeterminismAndFormat(t *testing.T) {
	seed := make([]byte, 32)
	for i := range seed {
		seed[i] = byte(i + 1)
	}

	addr1, key1, err := DeriveTorOnionKey(seed, 0)
	if err != nil {
		t.Fatalf("DeriveTorOnionKey failed: %v", err)
	}

	// 1. Length and format checks
	if len(key1) != 96 {
		t.Fatalf("Expected 96-byte secret key, got %d", len(key1))
	}
	if string(key1[0:32]) != TorV3SecretKeyHeader {
		t.Fatalf("Secret key header mismatch: got %q", key1[0:32])
	}

	onionRegex := regexp.MustCompile(`^[a-z2-7]{56}\.onion$`)
	if !onionRegex.MatchString(addr1) {
		t.Fatalf("Invalid onion address format: %s", addr1)
	}
	if !strings.HasSuffix(addr1, ".onion") {
		t.Fatalf("Address must end with .onion: %s", addr1)
	}
	if len(addr1) != 62 { // 56 base32 chars + 6 chars for ".onion"
		t.Fatalf("Expected 62 characters in onion address, got %d: %s", len(addr1), addr1)
	}

	// 2. Determinism check: same seed and index must yield exact same result
	addr2, key2, err := DeriveTorOnionKey(seed, 0)
	if err != nil {
		t.Fatalf("DeriveTorOnionKey 2 failed: %v", err)
	}
	if addr1 != addr2 {
		t.Fatalf("Addresses do not match across identical runs: %s vs %s", addr1, addr2)
	}
	if !bytes.Equal(key1, key2) {
		t.Fatalf("Secret keys do not match across identical runs")
	}
}

func TestDeriveTorOnionKey_RotationIndexIsolation(t *testing.T) {
	seed := make([]byte, 32)
	if _, err := rand.Read(seed); err != nil {
		t.Fatalf("rand.Read failed: %v", err)
	}

	addr0, key0, err := DeriveTorOnionKey(seed, 0)
	if err != nil {
		t.Fatalf("DeriveTorOnionKey index 0 failed: %v", err)
	}

	addr1, key1, err := DeriveTorOnionKey(seed, 1)
	if err != nil {
		t.Fatalf("DeriveTorOnionKey index 1 failed: %v", err)
	}

	addr2, key2, err := DeriveTorOnionKey(seed, 2)
	if err != nil {
		t.Fatalf("DeriveTorOnionKey index 2 failed: %v", err)
	}

	if addr0 == addr1 || addr0 == addr2 || addr1 == addr2 {
		t.Fatalf("Different indices must yield distinct addresses: 0=%s, 1=%s, 2=%s", addr0, addr1, addr2)
	}
	if bytes.Equal(key0, key1) || bytes.Equal(key0, key2) || bytes.Equal(key1, key2) {
		t.Fatalf("Different indices must yield distinct secret keys")
	}
}

func TestDeriveTorOnionKey_InvalidSeed(t *testing.T) {
	shortSeed := make([]byte, 16)
	_, _, err := DeriveTorOnionKey(shortSeed, 0)
	if err == nil {
		t.Fatalf("Expected error on 16-byte seed")
	}

	longSeed := make([]byte, 64)
	_, _, err = DeriveTorOnionKey(longSeed, 0)
	if err == nil {
		t.Fatalf("Expected error on 64-byte seed")
	}
}
