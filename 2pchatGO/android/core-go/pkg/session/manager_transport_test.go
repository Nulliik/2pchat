package session

import (
	"encoding/json"
	"testing"
)

func TestTransportClassForSessionRelayProvenance(t *testing.T) {

	for _, tt := range []struct {
		name string
		opts []SessionOption
		want string
	}{
		{name: "default is direct", want: "direct"},
		{name: "tor remains tor", opts: []SessionOption{WithTorTransport(true)}, want: "tor"},
		{name: "yggdrasil remains direct classification", opts: []SessionOption{WithYggdrasilTransport(true)}, want: "direct"},
		{name: "relay explicit", opts: []SessionOption{WithRelayTransport(true)}, want: "relay"},
		{name: "relay takes precedence over tor", opts: []SessionOption{WithTorTransport(true), WithRelayTransport(true)}, want: "relay"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			s := &Session{}
			for _, opt := range tt.opts {
				opt(s)
			}
			if got := transportClassForSession(s); got != tt.want {
				t.Fatalf("transportClassForSession() = %q, want %q", got, tt.want)
			}
		})
	}

	relaySession := &Session{}
	WithRelayTransport(true)(relaySession)
	manager := &Manager{sessions: map[string]*Session{"peer": relaySession}, peerEndp: map[string]string{"peer": "relay://example"}}
	var states []struct {
		Transport string `json:"transport"`
	}
	if err := json.Unmarshal([]byte(manager.PeerStatesJSON()), &states); err != nil {
		t.Fatalf("PeerStatesJSON() invalid JSON: %v", err)
	}
	if len(states) != 1 || states[0].Transport != "relay" {
		t.Fatalf("PeerStatesJSON() transport = %#v, want one relay state", states)
	}
}
