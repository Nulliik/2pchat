package crypto

import (
	"bytes"
	"crypto/rand"
	"strings"
	"testing"
)

func TestMnemonicRoundtrip(t *testing.T) {
	for i := 0; i < 20; i++ {
		seed := make([]byte, 32)
		if _, err := rand.Read(seed); err != nil {
			t.Fatalf("rand.Read failed: %v", err)
		}

		mnemonic, err := MnemonicFromSeed(seed)
		if err != nil {
			t.Fatalf("MnemonicFromSeed failed: %v", err)
		}

		words := strings.Fields(mnemonic)
		if len(words) != 24 {
			t.Fatalf("Expected 24 words, got %d", len(words))
		}

		recoveredSeed, err := SeedFromMnemonic(mnemonic)
		if err != nil {
			t.Fatalf("SeedFromMnemonic failed: %v", err)
		}

		if !bytes.Equal(seed, recoveredSeed) {
			t.Fatalf("Recovered seed does not match original seed!\nOriginal:  %x\nRecovered: %x", seed, recoveredSeed)
		}
	}
}

func TestMnemonicChecksumRejection(t *testing.T) {
	seed := make([]byte, 32)
	_, _ = rand.Read(seed)

	mnemonic, err := MnemonicFromSeed(seed)
	if err != nil {
		t.Fatalf("MnemonicFromSeed failed: %v", err)
	}

	words := strings.Fields(mnemonic)
	// Corrupt the last word
	if words[23] == "zoo" {
		words[23] = "abandon"
	} else {
		words[23] = "zoo"
	}

	corrupted := strings.Join(words, " ")
	_, err = SeedFromMnemonic(corrupted)
	if err == nil {
		t.Fatalf("Expected checksum error for corrupted mnemonic, but got nil")
	}
}

func TestMnemonicInvalidWordCount(t *testing.T) {
	_, err := SeedFromMnemonic("abandon ability able")
	if err == nil {
		t.Fatalf("Expected error for 3 words, got nil")
	}
}

func TestMnemonicKeyDerivationDeterministic(t *testing.T) {
	seed := make([]byte, 32)
	_, _ = rand.Read(seed)

	mnemonic, err := MnemonicFromSeed(seed)
	if err != nil {
		t.Fatalf("MnemonicFromSeed failed: %v", err)
	}

	recoveredSeed, err := SeedFromMnemonic(mnemonic)
	if err != nil {
		t.Fatalf("SeedFromMnemonic failed: %v", err)
	}

	keypair1, err := IdentityKeyPairFromSeed(seed)
	if err != nil {
		t.Fatalf("IdentityKeyPairFromSeed(seed) failed: %v", err)
	}

	keypair2, err := IdentityKeyPairFromSeed(recoveredSeed)
	if err != nil {
		t.Fatalf("IdentityKeyPairFromSeed(recoveredSeed) failed: %v", err)
	}

	if keypair1.Fingerprint() != keypair2.Fingerprint() {
		t.Fatalf("Fingerprints do not match: %s vs %s", keypair1.Fingerprint(), keypair2.Fingerprint())
	}
}
