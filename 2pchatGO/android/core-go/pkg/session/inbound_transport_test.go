package session

import (
	"testing"
	"twopchat/core/pkg/transport"
)

func TestClassifyInboundTransport_PreservesUserSpaceYggdrasilProvenance(t *testing.T) {
	tests := []struct {
		name     string
		endpoint string
		onion    string
		want     transport.TransportClass
		wantTor  bool
	}{
		{"ygg shim marker", "127.0.0.2:41382", "", transport.TransportYggdrasil, false},
		{"tor proxy loopback", "127.0.0.1:41382", "example.onion", transport.TransportTor, true},
		{"ordinary loopback", "127.0.0.1:41382", "", transport.TransportLAN, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, gotTor := classifyInboundTransport(tt.endpoint, tt.onion)
			if got != tt.want || gotTor != tt.wantTor {
				t.Fatalf("classifyInboundTransport(%q) = (%s, %t), want (%s, %t)", tt.endpoint, got, gotTor, tt.want, tt.wantTor)
			}
		})
	}
}
