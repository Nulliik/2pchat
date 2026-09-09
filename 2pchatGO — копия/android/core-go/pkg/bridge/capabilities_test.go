package bridge

import (
	"encoding/json"
	"fmt"
	"testing"
	"time"
	"twopchat/core/pkg/protocol"
)

func TestAutomaticCapabilitiesAndReconnectSnapshot(t *testing.T) {
	a, b := &SessionManager{}, &SessionManager{}
	for _, m := range []*SessionManager{a, b} {
		m.SetStorageDir(t.TempDir())
		if err := m.Init(); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = m.netManager.Close() })
	}
	if err := b.StartListener(0); err != nil {
		t.Fatal(err)
	}
	afp, bfp := a.GetLocalFingerprint(), b.GetLocalFingerprint()
	endpoint := fmt.Sprintf("127.0.0.1:%d", b.GetBoundPort())
	if a.PeerProtocolJSON(bfp) != "null" {
		t.Fatal("offline peer has capabilities")
	}
	for attempt := 0; attempt < 2; attempt++ {
		if err := a.ConnectPeer(endpoint, bfp); err != nil {
			t.Fatal(err)
		}
		deadline := time.Now().Add(3 * time.Second)
		for {
			var ar, br *protocol.NegotiatedSession
			if err := json.Unmarshal([]byte(a.PeerProtocolJSON(bfp)), &ar); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal([]byte(b.PeerProtocolJSON(afp)), &br); err != nil {
				t.Fatal(err)
			}
			if ar != nil && br != nil {
				if !ar.Supports(protocol.GroupSuccession) || !br.Supports(protocol.GroupSuiteV2) {
					t.Fatalf("wrong Android profile: %+v %+v", ar, br)
				}
				break
			}
			if time.Now().After(deadline) {
				t.Fatal("automatic negotiation timed out")
			}
			time.Sleep(time.Millisecond * 5)
		}
		as, bs := a.netManager.GetSession(bfp), b.netManager.GetSession(afp)
		if as == nil || bs == nil {
			t.Fatal("negotiated session disappeared unexpectedly")
		}
		_ = as.Close()
		_ = bs.Close()
		if a.PeerProtocolJSON(bfp) != "null" || b.PeerProtocolJSON(afp) != "null" {
			t.Fatal("disconnected session retained capabilities")
		}
	}
}
