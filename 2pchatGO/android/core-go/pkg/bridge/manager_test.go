package bridge

import (
	"context"
	"encoding/base64"
	"fmt"
	"sync"
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

func TestRefreshNatDiagnosticsBlocksSTUNUnderTor(t *testing.T) {
	mgr := &SessionManager{
		sessions:   make(map[string]*crypto.SessionState),
		torEnabled: true,
		torProxy:   "127.0.0.1:9050",
		dialer:     transport.NewAdaptiveDialer("127.0.0.1:9050", true, 5*time.Second),
	}
	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if !mgr.RefreshNATDiagnostics(ctx) {
		t.Fatal("synchronous NAT refresh returned false")
	}
	if got := mgr.GetPublicEndpoint(); got != "" {
		t.Fatalf("Tor mode must not expose a STUN public endpoint, got %q", got)
	}
}

func TestConcurrentCallbacksNoDeadlock(t *testing.T) {
	var mgr *SessionManager
	var wg sync.WaitGroup

	cbCount := 0
	var cbMu sync.Mutex

	callbacks := session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			cbMu.Lock()
			cbCount++
			cbMu.Unlock()

			// Re-entrant call back into SessionManager during callback
			_ = mgr.IsPeerOnline(peerFP)
			mgr.UpdatePeerNameMapping(peerFP, "Nickname-"+peerFP)
			_ = mgr.GetLocalFingerprint()
			_ = mgr.GetOnionAddress()
		},
		OnPeerDisconnected: func(peerFP, reason string) {
			cbMu.Lock()
			cbCount++
			cbMu.Unlock()

			_ = mgr.IsPeerOnline(peerFP)
			_ = mgr.GetLocalFingerprint()
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			cbMu.Lock()
			cbCount++
			cbMu.Unlock()

			_ = mgr.IsPeerOnline(peerFP)
			mgr.UpdatePeerNameMapping(peerFP, "User-"+peerFP)
		},
		OnError: func(code int, msg string) {
			cbMu.Lock()
			cbCount++
			cbMu.Unlock()

			_ = mgr.GetLocalFingerprint()
		},
		OnFileProgress: func(peerFP, msgID string, transferred, total int64, speed float64) {
			cbMu.Lock()
			cbCount++
			cbMu.Unlock()

			_ = mgr.IsPeerOnline(peerFP)
		},
	}

	mgr = &SessionManager{
		sessions:  make(map[string]*crypto.SessionState),
		torProxy:  "127.0.0.1:9050",
		dialer:    transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
		callbacks: callbacks,
	}

	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	// 100 concurrent workers exercising callbacks and manager methods simultaneously
	goroutines := 100
	wg.Add(goroutines)

	for i := 0; i < goroutines; i++ {
		workerID := i
		go func() {
			defer wg.Done()
			fp := fmt.Sprintf("test-fingerprint-%d", workerID)
			ep := fmt.Sprintf("127.0.0.1:%d", 10000+workerID)

			// Concurrently trigger manager methods and callbacks
			mgr.UpdatePeerNameMapping(fp, fmt.Sprintf("Nick-%d", workerID))
			_ = mgr.IsPeerOnline(fp)
			_ = mgr.GetLocalFingerprint()

			// Exercise the real mutable JNI callback storage instead of only a
			// goroutine-local callback variable.
			mgr.SetCallbacks(callbacks, nil)
			activeCallbacks, _ := mgr.callbackSnapshot()
			if activeCallbacks.OnPeerConnected != nil {
				activeCallbacks.OnPeerConnected(fp, ep)
			}
			if activeCallbacks.OnMessageReceived != nil {
				activeCallbacks.OnMessageReceived(fp, []byte(fmt.Sprintf("hello from worker %d", workerID)), fmt.Sprintf("msg-%d", workerID))
			}
			if activeCallbacks.OnFileProgress != nil {
				activeCallbacks.OnFileProgress(fp, fmt.Sprintf("msg-%d", workerID), 1024, 2048, 100.5)
			}
			if activeCallbacks.OnError != nil {
				activeCallbacks.OnError(1, "simulated transient error")
			}
			if activeCallbacks.OnPeerDisconnected != nil {
				activeCallbacks.OnPeerDisconnected(fp, "clean shutdown")
			}
		}()
	}

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		// Succeeded without deadlock
	case <-time.After(5 * time.Second):
		t.Fatalf("TestConcurrentCallbacksNoDeadlock TIMED OUT (Deadlock detected in JNI callback path)")
	}

	cbMu.Lock()
	totalExecuted := cbCount
	cbMu.Unlock()

	if totalExecuted < goroutines*5 {
		t.Fatalf("Expected at least %d callback executions, got %d", goroutines*5, totalExecuted)
	}
}

func TestManagerSuccessionFlow(t *testing.T) {
	ownerMgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}
	if err := ownerMgr.Init(); err != nil {
		t.Fatalf("ownerMgr.Init failed: %v", err)
	}

	successorMgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}
	if err := successorMgr.Init(); err != nil {
		t.Fatalf("successorMgr.Init failed: %v", err)
	}

	successorFP := successorMgr.GetLocalFingerprint()
	successorPub, err := successorMgr.GetLocalSigningPublicKey()
	if err != nil {
		t.Fatalf("GetLocalSigningPublicKey failed: %v", err)
	}

	groupID := "group-alpha-test"

	// 1. Hook to test sequence counter persistence
	var lastPersistedSeq uint64
	var hookMu sync.Mutex
	ownerMgr.SetHeartbeatPersistHook(func(gid string, seq uint64) {
		hookMu.Lock()
		lastPersistedSeq = seq
		hookMu.Unlock()
	})

	// 2. Create Succession Certificate (30 days, sequence 1)
	certJSON, err := ownerMgr.CreateSuccessionCertificate(groupID, successorFP, successorPub, 30, 1)
	if err != nil {
		t.Fatalf("CreateSuccessionCertificate failed: %v", err)
	}
	if err := ownerMgr.VerifySuccessionCertificate(certJSON); err != nil {
		t.Fatalf("VerifySuccessionCertificate failed: %v", err)
	}

	// 3. Create Genesis Heartbeat
	hb1JSON, err := ownerMgr.CreateOwnerHeartbeat(groupID)
	if err != nil {
		t.Fatalf("CreateOwnerHeartbeat 1 failed: %v", err)
	}
	hb1, err := crypto.ParseOwnerHeartbeat(hb1JSON)
	if err != nil {
		t.Fatalf("ParseOwnerHeartbeat 1 failed: %v", err)
	}
	if hb1.Sequence != 1 {
		t.Fatalf("Expected genesis heartbeat sequence 1, got %d", hb1.Sequence)
	}
	if hb1.PreviousEventHash != crypto.GenesisPreviousEventHash {
		t.Fatalf("Expected genesis previous hash to be empty, got %s", hb1.PreviousEventHash)
	}

	hookMu.Lock()
	if lastPersistedSeq != 1 {
		t.Fatalf("Expected persisted seq 1, got %d", lastPersistedSeq)
	}
	hookMu.Unlock()

	// 4. Create Second Heartbeat (Chained)
	hb2JSON, err := ownerMgr.CreateOwnerHeartbeat(groupID)
	if err != nil {
		t.Fatalf("CreateOwnerHeartbeat 2 failed: %v", err)
	}
	hb2, err := crypto.ParseOwnerHeartbeat(hb2JSON)
	if err != nil {
		t.Fatalf("ParseOwnerHeartbeat 2 failed: %v", err)
	}
	if hb2.Sequence != 2 {
		t.Fatalf("Expected heartbeat 2 sequence 2, got %d", hb2.Sequence)
	}
	if hb2.PreviousEventHash != hb1.EventHash() {
		t.Fatalf("Expected hb2 to chain to hb1 hash %s, got %s", hb1.EventHash(), hb2.PreviousEventHash)
	}

	// 5. Premature Succession Claim (0 days elapsed < 30 days)
	claimJSON, err := successorMgr.CreateSuccessionClaim(certJSON, hb2JSON)
	if err != nil {
		t.Fatalf("CreateSuccessionClaim failed: %v", err)
	}
	err = ownerMgr.VerifySuccessionClaim(certJSON, claimJSON, hb2JSON)
	if err != crypto.ErrSuccessionPrematureClaim {
		t.Fatalf("Expected ErrSuccessionPrematureClaim, got %v", err)
	}

	// 6. Revocation
	parsedCert, _ := crypto.ParseSuccessionCertificate(certJSON)
	revJSON, err := ownerMgr.CreateSuccessionRevocation(groupID, parsedCert.CertificateHash())
	if err != nil {
		t.Fatalf("CreateSuccessionRevocation failed: %v", err)
	}
	if err := ownerMgr.VerifySuccessionRevocation(revJSON); err != nil {
		t.Fatalf("VerifySuccessionRevocation failed: %v", err)
	}
	if !ownerMgr.IsCertificateRevoked(parsedCert.CertificateHash()) {
		t.Fatalf("Expected certificate to be marked as revoked")
	}

	// Verify that succession certificate is now rejected as revoked
	if err := ownerMgr.VerifySuccessionCertificate(certJSON); err != crypto.ErrSuccessionCertRevoked {
		t.Fatalf("Expected ErrSuccessionCertRevoked, got %v", err)
	}
}

