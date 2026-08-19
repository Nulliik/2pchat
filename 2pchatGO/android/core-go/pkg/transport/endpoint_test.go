package transport

import (
	"reflect"
	"strconv"
	"strings"
	"testing"
)

// IsValidEndpoint validates a single host:port endpoint string.
func IsValidEndpoint(endpoint string) bool {
	if len(endpoint) == 0 || len(endpoint) > 512 {
		return false
	}
	for i := 0; i < len(endpoint); i++ {
		if endpoint[i] < 32 || endpoint[i] == 127 {
			return false // Control characters
		}
	}

	var host, portText string
	if strings.HasPrefix(endpoint, "[") {
		closingBracket := strings.Index(endpoint, "]")
		if closingBracket <= 1 || closingBracket+1 >= len(endpoint) || endpoint[closingBracket+1] != ':' {
			return false
		}
		host = endpoint[1:closingBracket]
		portText = endpoint[closingBracket+2:]
	} else {
		if strings.Count(endpoint, ":") != 1 {
			return false
		}
		parts := strings.Split(endpoint, ":")
		host = parts[0]
		portText = parts[1]
	}

	if host == "" || strings.ContainsAny(host, " \t\r\n<>;&$\"'`") {
		return false
	}

	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return false
	}

	return true
}

// IsValidEndpointList validates a comma-separated list of endpoints.
func IsValidEndpointList(value string) bool {
	if strings.TrimSpace(value) == "" || len(value) > 4096 {
		return false
	}
	parts := strings.Split(value, ",")
	if len(parts) == 0 || len(parts) > 12 {
		return false
	}
	for _, p := range parts {
		if !IsValidEndpoint(strings.TrimSpace(p)) {
			return false
		}
	}
	return true
}

// OrderedDirectEndpoints sorts endpoints giving priority to private IPv4, then public IPv4, then IPv6, then onion.
func OrderedDirectEndpoints(endpoints []string) []string {
	type epRank struct {
		ep   string
		rank int
	}

	ranks := make([]epRank, 0, len(endpoints))
	seen := make(map[string]bool)

	for _, ep := range endpoints {
		ep = strings.TrimSpace(ep)
		if ep == "" || seen[ep] {
			continue
		}
		seen[ep] = true

		host := ep
		if strings.HasPrefix(ep, "[") {
			closing := strings.Index(ep, "]")
			if closing > 0 {
				host = ep[1:closing]
			}
		} else if strings.Contains(ep, ":") {
			host = strings.Split(ep, ":")[0]
		}

		rank := 3 // onion or other
		if strings.HasPrefix(host, "192.168.") || strings.HasPrefix(host, "10.") {
			rank = 0 // Private LAN IPv4
		} else if !strings.Contains(host, ":") && !strings.HasSuffix(host, ".onion") {
			rank = 1 // Public IPv4
		} else if strings.Contains(host, ":") || strings.HasPrefix(host, "200:") {
			rank = 2 // IPv6 / Yggdrasil
		}

		ranks = append(ranks, epRank{ep: ep, rank: rank})
	}

	// Stable sort by rank
	for i := 0; i < len(ranks); i++ {
		for j := i + 1; j < len(ranks); j++ {
			if ranks[j].rank < ranks[i].rank {
				ranks[i], ranks[j] = ranks[j], ranks[i]
			}
		}
	}

	result := make([]string, len(ranks))
	for i, r := range ranks {
		result[i] = r.ep
	}
	return result
}

// SelectExternalIPv4 returns the first candidate that is not loopback or local LAN.
func SelectExternalIPv4(localIPv4 string, candidates []string) string {
	for _, c := range candidates {
		c = strings.TrimSpace(c)
		if c == "" || strings.Contains(c, ":") || c == localIPv4 {
			continue
		}
		if strings.HasPrefix(c, "127.") || strings.HasPrefix(c, "10.") || strings.HasPrefix(c, "192.168.") {
			continue
		}
		return c
	}
	return ""
}

func TestValidPeerEndpoint(t *testing.T) {
	// Valid endpoints
	validCases := []string{
		"192.168.1.1:50001",
		"10.0.0.2:8080",
		"[::1]:50001",
		"[200:1234::abcd]:50001",
		"[200:f1d1:906:eabc:f83c:9899:db49:6177]:50001",
		"zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion:50001",
	}

	for _, ep := range validCases {
		if !IsValidEndpoint(ep) {
			t.Errorf("Expected valid endpoint: %s", ep)
		}
	}

	// Invalid endpoints
	invalidCases := []string{
		"",
		"192.168.1.1",
		"abc:def",
		"[::1]",
		"127.0.0.1:0",
		"127.0.0.1:70000",
		"192.168.1.1: 50001",
		"192.168.1.1:-1",
		"zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion",
		"zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion:0",
		"zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion:70000",
		"zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion:abc",
		"zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion; rm -rf /:50001",
		"192.168.1.1:50001\nSocksPort 9050",
		"192.168.1.1:50001\r\nControlPort 9051",
		"<script>192.168.1.1:50001</script>",
		strings.Repeat("a", 600),
	}

	for _, ep := range invalidCases {
		if IsValidEndpoint(ep) {
			t.Errorf("Expected invalid endpoint to be rejected: %s", ep)
		}
	}
}

func TestValidPeerEndpointList(t *testing.T) {
	validList := "192.168.1.50:50001, [200:1234::abcd]:50001, test.onion:8080"
	if !IsValidEndpointList(validList) {
		t.Errorf("Expected valid endpoint list: %s", validList)
	}

	invalidList := "[200:f1d1:906:eabc:f83c:9899:db49:6177]:50001, invalid_endpoint"
	if IsValidEndpointList(invalidList) {
		t.Errorf("Expected invalid endpoint in list to be rejected: %s", invalidList)
	}
}

func TestOrderedDirectEndpoints(t *testing.T) {
	input := []string{
		"[200:db8::20]:50001",
		"203.0.113.20:50001",
		"192.168.1.20:50001",
	}

	expected := []string{
		"192.168.1.20:50001",
		"203.0.113.20:50001",
		"[200:db8::20]:50001",
	}

	actual := OrderedDirectEndpoints(input)
	if !reflect.DeepEqual(actual, expected) {
		t.Errorf("OrderedDirectEndpoints mismatch:\nExpected: %v\nGot:      %v", expected, actual)
	}
}

func TestSelectExternalIPv4(t *testing.T) {
	candidates := []string{"200:db8::20", "192.168.1.20", "203.0.113.20"}
	selected := SelectExternalIPv4("192.168.1.20", candidates)
	if selected != "203.0.113.20" {
		t.Errorf("Expected 203.0.113.20, got %s", selected)
	}
}
