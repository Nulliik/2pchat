package discovery

import (
	"net"
	"testing"
	"twopchat/core/pkg/transport"
)

func TestFilterCandidates_LANPort80Rejected(t *testing.T) {
	_, lanNet, _ := net.ParseCIDR("192.168.1.0/24")
	localIfaces := []*net.IPNet{lanNet}

	policy := transport.PolicySpeed

	// Candidates targeting internal infrastructure and admin ports
	badCandidates := []string{
		"192.168.1.1:80",    // Web router admin
		"192.168.1.1:443",   // Router HTTPS
		"192.168.1.50:8080", // Proxy / alt-HTTP
		"192.168.1.10:22",   // SSH
		"192.168.1.100:9050",// Tor SOCKS
		"192.168.1.100:9051",// Tor control
	}

	filtered, err := FilterCandidates(policy, badCandidates, localIfaces)
	if err == nil && len(filtered) > 0 {
		t.Fatalf("Anti-SSRF failure: Sensitive ports on LAN were permitted: %v", filtered)
	}

	// Valid candidates on default (50001) and custom unprivileged ports (50002, 52123) must be accepted
	validCandidates := []string{
		"192.168.1.50:50001",
		"192.168.1.51:50002",
		"192.168.1.52:52123",
	}
	filteredValid, errValid := FilterCandidates(policy, validCandidates, localIfaces)
	if errValid != nil || len(filteredValid) != 3 {
		t.Fatalf("Expected all 3 valid unprivileged LAN candidates to be accepted, got: %v (err: %v)", filteredValid, errValid)
	}
}
