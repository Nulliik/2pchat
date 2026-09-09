package session

import (
	"encoding/json"
	"net"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/protocol"
)

func compatibilityPair(t *testing.T, a, b protocol.Declaration) (*Session, *Session) {
	t.Helper()
	ai, _ := crypto.GenerateIdentityKeyPair()
	bi, _ := crypto.GenerateIdentityKeyPair()
	ap, au, _ := crypto.GenerateX25519Keypair()
	bp, bu, _ := crypto.GenerateX25519Keypair()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	result := make(chan *Session, 1)
	errs := make(chan error, 1)
	go func() {
		c, err := l.Accept()
		if err != nil {
			errs <- err
			return
		}
		s, err := NewSession(c, false, bi, bp, bu, crypto.Fingerprint(ai.Public.Bytes()), time.Second*3, WithCapabilities(b))
		if err != nil {
			errs <- err
			return
		}
		result <- s
	}()
	c, err := net.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	as, err := NewSession(c, true, ai, ap, au, crypto.Fingerprint(bi.Public.Bytes()), time.Second*3, WithCapabilities(a))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = as.Close() })
	select {
	case bs := <-result:
		t.Cleanup(func() { _ = bs.Close() })
		return as, bs
	case err := <-errs:
		t.Fatal(err)
	case <-time.After(4 * time.Second):
		t.Fatal("handshake timeout")
	}
	return nil, nil
}

func TestEncryptedCompatibilityExchange(t *testing.T) {
	future := protocol.AndroidCapabilities()
	future.ProtocolVersion = 3
	a, b := compatibilityPair(t, protocol.LocalCapabilities(), future)
	if a.NegotiatedProtocol() != nil {
		t.Fatal("capabilities inferred before exchange")
	}
	if _, err := a.SendReliable(map[string]any{"type": "group_event_v1"}); err == nil {
		t.Fatal("sent pending group event")
	}
	for _, s := range []*Session{a, b} {
		if _, err := s.SendReliable(map[string]any{"type": "identity_info"}); err != nil {
			t.Fatal(err)
		}
	}
	if a.NegotiatedProtocol().ProtocolVersion != 1 || !b.NegotiatedProtocol().PeerIsOutdated {
		t.Fatal("wrong negotiated version")
	}
	if a.NegotiatedProtocol().Supports(protocol.GroupSuiteV1) {
		t.Fatal("enabled unavailable group runtime")
	}
	copy := a.NegotiatedProtocol()
	copy.ActiveCapabilities[0] = "fake_v1"
	if a.NegotiatedProtocol().Supports("fake_v1") {
		t.Fatal("snapshot aliases session state")
	}
	if _, err := a.SendChat("basic chat remains available", ""); err != nil {
		t.Fatal(err)
	}
	if _, err := a.SendReliable(map[string]any{"type": "group_succession_claim_v1"}); err == nil {
		t.Fatal("sent unsupported feature")
	}
}

func TestLegacyIdentityInfoDoesNotChangeWireHandshake(t *testing.T) {
	a, b := compatibilityPair(t, protocol.AndroidCapabilities(), protocol.LocalCapabilities())
	// Model a pre-negotiation client using its unchanged encrypted JSON format.
	raw := []byte(`{"type":"identity_info","id":"legacy"}`)
	if _, err := b.sendReliablePlaintext("legacy", raw); err != nil {
		t.Fatal(err)
	}
	if !a.NegotiatedProtocol().PeerIsLegacy {
		t.Fatal("missing legacy state")
	}
	if _, err := a.SendChat("hello old client", ""); err != nil {
		t.Fatal(err)
	}
}

func TestRaisedMinimumCannotUseUndeclaredBaseline(t *testing.T) {
	d := protocol.LocalCapabilities()
	d.ProtocolVersion, d.MinSupportedVersion = 2, 2
	a, b := compatibilityPair(t, d, protocol.LocalCapabilities())
	if _, err := a.SendChat("not permitted before negotiation", ""); err == nil {
		t.Fatal("bypassed raised version floor")
	}
	if err := b.sendEncryptedFrame([]byte(`{"type":"chat","body":"undeclared legacy"}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-a.closeChan:
	case <-time.After(time.Second * 2):
		t.Fatal("legacy traffic bypassed version floor")
	}
}

func TestRejectChangedMalformedAndIncompatibleDeclarations(t *testing.T) {
	for _, mode := range []string{"changed", "malformed", "incompatible", "identity"} {
		t.Run(mode, func(t *testing.T) {
			a, b := compatibilityPair(t, protocol.LocalCapabilities(), protocol.LocalCapabilities())
			if mode == "changed" {
				if _, err := b.SendReliable(map[string]any{"type": "identity_info"}); err != nil {
					t.Fatal(err)
				}
			}
			d := protocol.LocalCapabilities()
			d.ProtocolVersion = 2
			if mode == "incompatible" {
				d.MinSupportedVersion = 2
			}
			msg := map[string]any{"type": "identity_info", "protocol": d}
			if mode == "malformed" {
				msg["protocol"] = nil
			}
			if mode == "identity" {
				msg["fingerprint"] = "attacker"
			}
			raw, _ := json.Marshal(msg)
			if err := b.sendEncryptedFrame(raw); err != nil {
				t.Fatal(err)
			}
			select {
			case <-a.closeChan:
			case <-time.After(time.Second * 2):
				t.Fatal("bad declaration did not close session")
			}
			if a.NegotiatedProtocol() != nil {
				t.Fatal("closed session retained active capabilities")
			}
		})
	}
}
