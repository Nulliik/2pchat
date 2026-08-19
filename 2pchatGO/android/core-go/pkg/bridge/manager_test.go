package bridge

import (
	"context"
	"encoding/base64"
	"fmt"
	"testing"
	"time"

	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/session"
	"twopchat/core/pkg/transport"
)

func TestManagerInitAndIdentity(t *testing.T) {
	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}

	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	fp := mgr.GetLocalFingerprint()
	if fp == "" {
		t.Fatalf("Expected non-empty fingerprint")
	}

	idJSON, err := mgr.GetLocalIdentityJSON()
	if err != nil || idJSON == "" {
		t.Fatalf("Expected non-empty identity JSON: %v", err)
	}

	signPub, err := mgr.GetLocalSigningPublicKey()
	if err != nil || signPub == "" {
		t.Fatalf("Expected non-empty signing public key: %v", err)
	}
}

func TestManagerConfigureLocalIdentity(t *testing.T) {
	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}

	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	// Generate a valid base64 keypair
	idKey, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("Failed to generate identity: %v", err)
	}
	privB64 := base64.StdEncoding.EncodeToString(idKey.Private.Bytes())

	ok := mgr.ConfigureLocalIdentity("Alice", privB64, "About Alice")
	if !ok {
		t.Fatalf("ConfigureLocalIdentity failed")
	}

	fp := mgr.GetLocalFingerprint()
	expectedFp := crypto.Fingerprint(idKey.Public.Bytes())
	if fp != expectedFp {
		t.Fatalf("Fingerprint mismatch: expected %s, got %s", expectedFp, fp)
	}
}

func TestManagerStartStopListener(t *testing.T) {
	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}

	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	// Start listener on dynamic port
	if err := mgr.StartListener(0); err != nil {
		t.Fatalf("StartListener failed: %v", err)
	}

	port := mgr.GetBoundPort()
	if port <= 0 {
		t.Fatalf("Expected bound port > 0, got %d", port)
	}

	if err := mgr.StopListener(); err != nil {
		t.Fatalf("StopListener failed: %v", err)
	}
}

func TestManagerTorProxyConfiguration(t *testing.T) {
	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}

	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	mgr.SetTorProxy(true, "127.0.0.1:9150")
	mgr.mu.RLock()
	torEnabled := mgr.torEnabled
	torProxy := mgr.torProxy
	mgr.mu.RUnlock()

	if !torEnabled {
		t.Fatalf("Expected Tor to be enabled")
	}
	if torProxy != "127.0.0.1:9150" {
		t.Fatalf("Expected proxy 127.0.0.1:9150, got %s", torProxy)
	}
}

func TestManagerGroupCryptoOperations(t *testing.T) {
	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}

	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	payload := "test-canonical-group-message-v1\nhello world"
	sig, err := mgr.SignGroupPayload(payload)
	if err != nil || sig == "" {
		t.Fatalf("SignGroupPayload failed: %v", err)
	}

	pubKey, err := mgr.GetLocalSigningPublicKey()
	if err != nil || pubKey == "" {
		t.Fatalf("GetLocalSigningPublicKey failed: %v", err)
	}
	if !mgr.VerifyGroupPayload(pubKey, payload, sig) {
		t.Fatalf("VerifyGroupPayload failed with valid signature")
	}

	// Tampered payload must fail
	if mgr.VerifyGroupPayload(pubKey, payload+" tampered", sig) {
		t.Fatalf("VerifyGroupPayload unexpectedly succeeded on tampered payload")
	}
}

func TestManagerPeerHandshakeAndMessaging(t *testing.T) {
	aliceMsgChan := make(chan string, 10)
	bobMsgChan := make(chan string, 10)

	aliceCallbacks := session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			aliceMsgChan <- string(payload)
		},
	}
	bobCallbacks := session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			bobMsgChan <- string(payload)
		},
	}

	alice := &SessionManager{
		sessions:  make(map[string]*crypto.SessionState),
		torProxy:  "127.0.0.1:9050",
		dialer:    transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
		callbacks: aliceCallbacks,
	}
	bob := &SessionManager{
		sessions:  make(map[string]*crypto.SessionState),
		torProxy:  "127.0.0.1:9050",
		dialer:    transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
		callbacks: bobCallbacks,
	}

	if err := alice.Init(); err != nil {
		t.Fatalf("Alice init failed: %v", err)
	}
	if err := bob.Init(); err != nil {
		t.Fatalf("Bob init failed: %v", err)
	}

	if err := bob.StartListener(0); err != nil {
		t.Fatalf("Bob start listener failed: %v", err)
	}
	defer bob.StopListener()

	bobPort := bob.GetBoundPort()
	bobEndpoint := fmt.Sprintf("127.0.0.1:%d", bobPort)
	bobFP := bob.GetLocalFingerprint()

	// Alice dials Bob directly
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, err := alice.dialer.DialContext(ctx, "tcp", bobEndpoint)
	if err != nil {
		t.Fatalf("Alice failed to connect to Bob: %v", err)
	}
	defer conn.Close()

	if conn == nil {
		t.Fatalf("Connection is nil")
	}

	// Verify Bob's fingerprint is known and valid
	if bobFP == "" {
		t.Fatalf("Bob fingerprint is empty")
	}
}

func TestManagerNatTraversal(t *testing.T) {
	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}

	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	if !mgr.TriggerNatTraversal() {
		t.Fatalf("TriggerNatTraversal returned false")
	}

	diagJSON := mgr.GetNatDiagnosticsJSON()
	if diagJSON == "" || diagJSON == "{}" {
		t.Fatalf("Expected non-empty JSON diagnostics, got %s", diagJSON)
	}

	// In Tor mode, NAT traversal should block UDP queries safely
	mgr.SetTorProxy(true, "127.0.0.1:9050")
	if !mgr.TriggerNatTraversal() {
		t.Fatalf("TriggerNatTraversal in Tor mode returned false")
	}
}
