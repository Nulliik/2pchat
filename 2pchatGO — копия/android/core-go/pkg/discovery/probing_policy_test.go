package discovery

import (
	"fmt"
	"net"
	"testing"
	"twopchat/core/pkg/transport"
)

func parseTestCIDR(s string) *net.IPNet {
	_, ipnet, err := net.ParseCIDR(s)
	if err != nil {
		panic(err)
	}
	return ipnet
}

// TestFilterCandidates_AntiSSRF tests that SSRF attack vectors
// (loopback, link-local metadata, unspecified, bogons) are stripped out.
func TestFilterCandidates_AntiSSRF(t *testing.T) {
	localIfaces := []*net.IPNet{
		parseTestCIDR("192.168.1.0/24"),
	}

	attackCandidates := []string{
		"127.0.0.1:50001",
		"[::1]:50001",
		"169.254.169.254:80", // AWS/GCP cloud metadata
		"[fe80::1]:50001",    // IPv6 link-local
		"0.0.0.0:50001",
		"[::]:50001",
		"192.0.2.1:50001",    // TEST-NET-1 bogon
		"198.18.0.1:50001",   // benchmark bogon
		"224.0.0.1:50001",    // multicast
		"8.8.8.8:50001",      // valid WAN candidate
	}

	filtered, err := FilterCandidates(transport.PolicySpeed, attackCandidates, localIfaces)
	if err != nil {
		t.Fatalf("FilterCandidates failed: %v", err)
	}

	if len(filtered) != 1 || filtered[0] != "8.8.8.8:50001" {
		t.Fatalf("Expected only '8.8.8.8:50001' to survive Anti-SSRF filtering, got: %v", filtered)
	}
}

// TestFilterCandidates_LANOnlyIfSameSubnet verifies that peer-supplied LAN candidates
// are accepted ONLY if our device has an active network interface in the exact same subnet.
func TestFilterCandidates_LANOnlyIfSameSubnet(t *testing.T) {
	localIfaces := []*net.IPNet{
		parseTestCIDR("192.168.1.0/24"),
	}

	candidates := []string{
		"192.168.1.55:50001", // in local subnet -> must be kept
		"10.0.0.99:50001",     // private RFC1918, but not in local subnet -> must be dropped
		"192.168.2.50:50001", // different 192.168.x subnet -> must be dropped
		"172.16.5.10:50001",  // different 172.16.x subnet -> must be dropped
	}

	filtered, err := FilterCandidates(transport.PolicySpeed, candidates, localIfaces)
	if err != nil {
		t.Fatalf("FilterCandidates failed: %v", err)
	}

	if len(filtered) != 1 || filtered[0] != "192.168.1.55:50001" {
		t.Fatalf("Expected only '192.168.1.55:50001' to survive subnet matching, got: %v", filtered)
	}
}

// TestFilterCandidates_CapsAt16 verifies П3:
// Hard limit of 16 candidates to prevent DoS on goroutines/timers.
func TestFilterCandidates_CapsAt16(t *testing.T) {
	var candidates []string
	for i := 1; i <= 30; i++ {
		candidates = append(candidates, fmt.Sprintf("8.8.8.%d:50001", i))
	}

	filtered, err := FilterCandidates(transport.PolicySpeed, candidates, nil)
	if err != nil {
		t.Fatalf("FilterCandidates failed: %v", err)
	}

	if len(filtered) != MaxCandidateEndpoints {
		t.Fatalf("Expected exactly %d candidates, got %d", MaxCandidateEndpoints, len(filtered))
	}
}

// TestFilterCandidates_Deduplication verifies П3:
// Redundant candidates with identical normalized (host, port) are deduplicated.
func TestFilterCandidates_Deduplication(t *testing.T) {
	candidates := []string{
		"8.8.8.8:50001",
		"8.8.8.8:50001",
		" 8.8.8.8:50001 ",
		"1.1.1.1:50001",
		"1.1.1.1:50001",
	}

	filtered, err := FilterCandidates(transport.PolicySpeed, candidates, nil)
	if err != nil {
		t.Fatalf("FilterCandidates failed: %v", err)
	}

	if len(filtered) != 2 {
		t.Fatalf("Expected 2 deduplicated candidates, got: %v", filtered)
	}
}

// TestFilterCandidates_TorStrict_NoClearnetCandidates verifies SEC-07b:
// In Tor Strict mode, all LAN, WAN, and Yggdrasil candidates are stripped out
// before racing starts, leaving only .onion candidates.
func TestFilterCandidates_TorStrict_NoClearnetCandidates(t *testing.T) {
	v3Onion := "expyuz5wqlgah2inqqdu42q5755hkgy2ec2sp7z5bvhz2e6p3mndnxyd.onion:50001"

	candidates := []string{
		"192.168.1.50:50001",
		"8.8.8.8:50001",
		"[200:1234:5678::1]:50001",
		v3Onion,
	}

	filtered, err := FilterCandidates(transport.PolicyTorStrict, candidates, nil)
	if err != nil {
		t.Fatalf("FilterCandidates failed: %v", err)
	}

	if len(filtered) != 1 || filtered[0] != v3Onion {
		t.Fatalf("Expected only onion candidate under TorStrict, got: %v", filtered)
	}
}
