package session

import (
	"fmt"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
)

func endpointTestManager(t *testing.T, callback func(string, string, bool)) *Manager {
	t.Helper()
	id, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	priv, pub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	m := NewManager(id, priv, pub, "", false, EventCallbacks{OnEndpointResult: callback})
	t.Cleanup(func() { _ = m.Close() })
	return m
}

func TestEndpointSuccessOnlyAfterAuthenticatedOutboundHandshake(t *testing.T) {
	type result struct {
		fp, endpoint string
		success      bool
	}
	outbound := make(chan result, 8)
	inbound := make(chan result, 8)
	alice := endpointTestManager(t, func(fp, ep string, ok bool) { outbound <- result{fp, ep, ok} })
	bob := endpointTestManager(t, func(fp, ep string, ok bool) { inbound <- result{fp, ep, ok} })
	if err := bob.StartListener(0); err != nil {
		t.Fatal(err)
	}
	address := fmt.Sprintf("127.0.0.1:%d", bob.Port())
	sess, err := alice.ConnectPeer(address, bob.Fingerprint())
	if err != nil {
		t.Fatal(err)
	}
	if sess.verifiedDialEndpoint != address {
		t.Fatal("winning route not retained")
	}
	select {
	case got := <-outbound:
		if !got.success || got.fp != bob.Fingerprint() || got.endpoint != address {
			t.Fatalf("wrong route result: %+v", got)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("missing authenticated route result")
	}
	select {
	case got := <-inbound:
		t.Fatalf("incoming ephemeral source port promoted: %+v", got)
	default:
	}
}

func TestWrongFingerprintNeverPromotesEndpoint(t *testing.T) {
	results := make(chan bool, 8)
	alice := endpointTestManager(t, func(_, _ string, success bool) { results <- success })
	bob := endpointTestManager(t, nil)
	if err := bob.StartListener(0); err != nil {
		t.Fatal(err)
	}
	_, err := alice.ConnectPeer(fmt.Sprintf("127.0.0.1:%d", bob.Port()), alice.Fingerprint())
	if err == nil {
		t.Fatal("accepted wrong fingerprint")
	}
	close(results)
	for success := range results {
		if success {
			t.Fatal("failed identity check counted as success")
		}
	}
}
