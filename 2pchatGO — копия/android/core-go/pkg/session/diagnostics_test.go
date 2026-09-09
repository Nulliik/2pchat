package session

import (
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"testing"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/diagnostics"
)

func diagnosticTestManager(t *testing.T) *Manager {
	t.Helper()
	id, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	priv, pub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	m := NewManager(id, priv, pub, "", false, EventCallbacks{})
	t.Cleanup(func() { _ = m.Close(); id.Zeroize(); crypto.Zeroize(priv[:]) })
	return m
}

func TestDiagnosticsObserveCompletedOutboundHandshakeOnly(t *testing.T) {
	alice, bob := diagnosticTestManager(t), diagnosticTestManager(t)
	alice.SetDiagnosticsEnabled(true)
	bob.SetDiagnosticsEnabled(true)
	if err := bob.StartListener(0); err != nil {
		t.Fatal(err)
	}
	endpoint := fmt.Sprintf("127.0.0.1:%d", bob.Port())
	if _, err := alice.ConnectPeer(endpoint, bob.Fingerprint()); err != nil {
		t.Fatal(err)
	}
	if _, err := alice.ConnectPeer(endpoint, bob.Fingerprint()); err != nil {
		t.Fatal(err)
	}
	if got := alice.DiagnosticsSnapshot().Outbound[0].Outcomes.Success; got != "1" {
		t.Fatalf("cached session was counted as another attempt: %s", got)
	}
	if bob.DiagnosticsSnapshot().Outbound[0].Outcomes.Success != "0" {
		t.Fatal("inbound connection was mislabeled outbound")
	}
	encoded, err := json.Marshal(alice.DiagnosticsSnapshot())
	if err != nil {
		t.Fatal(err)
	}
	for _, secret := range []string{endpoint, bob.Fingerprint(), alice.Fingerprint(), "127.0.0.1"} {
		if strings.Contains(string(encoded), secret) {
			t.Fatalf("private value reached report: %q", secret)
		}
	}
}

func TestDiagnosticsRejectAndHandshakeFailureAreDistinct(t *testing.T) {
	m := diagnosticTestManager(t)
	m.SetDiagnosticsEnabled(true)
	if _, err := m.ConnectPeer("0.0.0.0:50001", ""); err == nil {
		t.Fatal("expected endpoint rejection")
	}
	if got := m.DiagnosticsSnapshot().Outbound[4].Outcomes.Rejected; got != "1" {
		t.Fatalf("missing rejected outcome: %s", got)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	done := make(chan struct{})
	go func() {
		defer close(done)
		if conn, acceptErr := listener.Accept(); acceptErr == nil {
			_ = conn.Close()
		}
	}()
	if _, err := m.ConnectPeer(listener.Addr().String(), ""); err == nil {
		t.Fatal("expected handshake failure")
	}
	_ = listener.Close()
	<-done
	if got := m.DiagnosticsSnapshot().Outbound[0].Outcomes.HandshakeFailed; got != "1" {
		t.Fatalf("missing handshake failure: %s", got)
	}
}

func TestDiagnosticsTransportClassificationIsFinite(t *testing.T) {
	m := diagnosticTestManager(t)
	for _, tc := range []struct {
		endpoints []string
		want      diagnostics.Kind
	}{
		{[]string{"127.0.0.1:1234"}, diagnostics.Direct},
		{[]string{"[200:1::1]:1234"}, diagnostics.Yggdrasil},
		{[]string{strings.Repeat("a", 56) + ".onion:1234"}, diagnostics.Tor},
		{[]string{"127.0.0.1:1234", "[200:1::1]:1234"}, diagnostics.Mixed},
		{[]string{"private-user@invalid"}, diagnostics.Unknown},
	} {
		if got := m.diagnosticCandidateKind(tc.endpoints); got != tc.want {
			t.Errorf("got %v, want %v", got, tc.want)
		}
	}
}
