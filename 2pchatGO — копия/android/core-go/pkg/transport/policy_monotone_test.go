package transport

import (
	"testing"
)

func TestPolicy_Intersect_Monotone(t *testing.T) {
	// Exhaustively test all 32 x 32 bitmask combinations in [0..31]
	for a := 0; a < 32; a++ {
		pa := PolicyFromFlags(a)
		for b := 0; b < 32; b++ {
			pb := PolicyFromFlags(b)
			inter := pa.Intersect(pb)

			// Property: Intersect(a, b) must be a subset of a and subset of b
			if inter.AllowLAN && (!pa.AllowLAN || !pb.AllowLAN) {
				t.Fatalf("Intersect(%d, %d).AllowLAN violated monotonicity", a, b)
			}
			if inter.AllowWAN && (!pa.AllowWAN || !pb.AllowWAN) {
				t.Fatalf("Intersect(%d, %d).AllowWAN violated monotonicity", a, b)
			}
			if inter.AllowYggdrasil && (!pa.AllowYggdrasil || !pb.AllowYggdrasil) {
				t.Fatalf("Intersect(%d, %d).AllowYggdrasil violated monotonicity", a, b)
			}
			if inter.AllowOnion && (!pa.AllowOnion || !pb.AllowOnion) {
				t.Fatalf("Intersect(%d, %d).AllowOnion violated monotonicity", a, b)
			}
			if inter.AllowLocalDNS && (!pa.AllowLocalDNS || !pb.AllowLocalDNS) {
				t.Fatalf("Intersect(%d, %d).AllowLocalDNS violated monotonicity", a, b)
			}

			// Property: Intersect is commutative: a.Intersect(b) == b.Intersect(a)
			interRev := pb.Intersect(pa)
			if inter != interRev {
				t.Fatalf("Intersect(%d, %d) not commutative: %v != %v", a, b, inter, interRev)
			}

			// Property: Intersect is idempotent: a.Intersect(a) == a
			interSelf := pa.Intersect(pa)
			if interSelf != pa {
				t.Fatalf("Intersect(%d, %d) not idempotent: %v != %v", a, a, interSelf, pa)
			}
		}
	}
}

func TestPolicyFlags_UnknownOrInvalidComboRejected(t *testing.T) {
	// Zero flags (inherit) is valid for contacts
	if err := ValidateFlags(0); err != nil {
		t.Fatalf("Expected flags=0 to be valid, got: %v", err)
	}

	// Preset Speed (31) must be valid
	if err := ValidateFlags(PolicySpeed.ToFlags()); err != nil {
		t.Fatalf("Expected PolicySpeed to be valid, got: %v", err)
	}

	// Preset TorStrict (8) must be valid
	if err := ValidateFlags(PolicyTorStrict.ToFlags()); err != nil {
		t.Fatalf("Expected PolicyTorStrict to be valid, got: %v", err)
	}

	// Contact DirectOnly: AllowLAN | AllowWAN (3) or AllowLAN | AllowWAN | AllowLocalDNS (19) must be valid
	if err := ValidateFlags(PolicyFlagAllowLAN | PolicyFlagAllowWAN); err != nil {
		t.Fatalf("Expected DirectOnly to be valid, got: %v", err)
	}
	if err := ValidateFlags(PolicyFlagAllowLAN | PolicyFlagAllowWAN | PolicyFlagAllowLocalDNS); err != nil {
		t.Fatalf("Expected DirectOnly with LocalDNS to be valid, got: %v", err)
	}

	// Contact YggdrasilOnly: AllowYggdrasil with overlay/local or onion
	// But standalone Yggdrasil without LAN, WAN, or Onion is rejected because listener cannot accept incoming Ygg on loopback without TUN
	if err := ValidateFlags(PolicyFlagAllowYggdrasil); err == nil {
		t.Fatalf("Expected standalone Yggdrasil without LAN/WAN/Onion to be rejected")
	}

	// Unknown bit (e.g. 1 << 6 = 64) must be rejected
	if err := ValidateFlags(1 << 6); err == nil {
		t.Fatalf("Expected unknown flag bit 64 to be rejected")
	}

	// AllowLocalDNS without clearnet (e.g. AllowLocalDNS=16 alone, or AllowOnion | AllowLocalDNS = 24) must be rejected
	if err := ValidateFlags(PolicyFlagAllowLocalDNS); err == nil {
		t.Fatalf("Expected AllowLocalDNS without clearnet to be rejected")
	}
	if err := ValidateFlags(PolicyFlagAllowOnion | PolicyFlagAllowLocalDNS); err == nil {
		t.Fatalf("Expected AllowOnion + AllowLocalDNS without clearnet to be rejected")
	}

	// AllowWAN without AllowLAN (e.g. AllowWAN=2 alone) must be rejected
	if err := ValidateFlags(PolicyFlagAllowWAN); err == nil {
		t.Fatalf("Expected AllowWAN without AllowLAN to be rejected")
	}
}
