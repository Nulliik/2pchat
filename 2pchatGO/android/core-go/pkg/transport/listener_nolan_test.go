package transport

import (
	"net"
	"testing"
)

func TestAllowInboundRemoteNoLAN(t *testing.T) {
	cases := []struct {
		ip   string
		want bool
	}{
		{"127.0.0.1", true},
		{"127.0.0.2", true}, // authenticated mesh proxy handoff
		{"192.168.1.3", false},
		{"10.0.2.16", false},
		{"172.16.0.4", false},
		{"fe80::1", false},
		{"fc00::1", false},
		{"8.8.8.8", true},
		{"200:21ac:ed09:4763:8412:9bb0:e5cd:4bd9", true},
	}
	for _, tc := range cases {
		t.Run(tc.ip, func(t *testing.T) {
			got := allowInboundRemote(PolicyNoLAN, &net.TCPAddr{IP: net.ParseIP(tc.ip)})
			if got != tc.want {
				t.Fatalf("allowInboundRemote(%s) = %v, want %v", tc.ip, got, tc.want)
			}
		})
	}
}

func TestAllowInboundRemoteLANOnly(t *testing.T) {
	policy := NetworkPolicy{AllowLAN: true}
	if !allowInboundRemote(policy, &net.TCPAddr{IP: net.ParseIP("192.168.1.3")}) {
		t.Fatal("LAN-only policy rejected private remote")
	}
	if allowInboundRemote(policy, &net.TCPAddr{IP: net.ParseIP("8.8.8.8")}) {
		t.Fatal("LAN-only policy accepted WAN remote")
	}
}
