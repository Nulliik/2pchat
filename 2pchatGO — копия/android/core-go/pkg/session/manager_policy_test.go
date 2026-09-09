package session

import (
	"sync"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

// TestManager_PolicySwitchToStrict_ClosesActiveClearnetSessions verifies requirement Б1:
// Switching network policy to PolicyTorStrict in runtime immediately terminates any
// active clearnet (LAN, WAN, Yggdrasil) sessions.
func TestManager_PolicySwitchToStrict_ClosesActiveClearnetSessions(t *testing.T) {
	// 1. Setup Alice and Bob
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()

	bobID, _ := crypto.GenerateIdentityKeyPair()
	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()

	var disconnectMu sync.Mutex
	disconnectedPeer := ""
	disconnectedChan := make(chan struct{}, 1)

	aliceCallbacks := EventCallbacks{
		OnPeerDisconnected: func(peerFP, reason string) {
			disconnectMu.Lock()
			disconnectedPeer = peerFP
			disconnectMu.Unlock()
			select {
			case disconnectedChan <- struct{}{}:
			default:
			}
		},
	}

	aliceMgr := NewManager(aliceID, alicePrekeyPriv, alicePrekeyPub, "127.0.0.1:9050", false, aliceCallbacks)
	defer aliceMgr.Close()

	bobMgr := NewManager(bobID, bobPrekeyPriv, bobPrekeyPub, "127.0.0.1:9050", false, EventCallbacks{})
	defer bobMgr.Close()

	// 2. Start Bob's listener on an ephemeral port
	bobPort, err := transport.FindAvailablePort("127.0.0.1", 0)
	if err != nil {
		t.Fatalf("FindAvailablePort failed: %v", err)
	}
	if err := bobMgr.StartListener(bobPort); err != nil {
		t.Fatalf("Bob StartListener failed: %v", err)
	}
	defer bobMgr.StopListener()

	// 3. Connect Alice to Bob over direct clearnet
	bobEndpoint := transport.NormalizeEndpoint("127.0.0.1", bobPort)
	bobFP := bobMgr.Fingerprint()

	sess, err := aliceMgr.ConnectPeer(bobEndpoint, bobFP)
	if err != nil {
		t.Fatalf("Alice ConnectPeer failed: %v", err)
	}
	if sess == nil || !sess.IsOnline() {
		t.Fatalf("Alice session with Bob is not online")
	}

	if !aliceMgr.IsPeerOnline(bobFP) {
		t.Fatalf("Alice should see Bob online before policy switch")
	}

	// 4. Runtime switch to PolicyTorStrict (Б1)
	aliceMgr.ApplyPolicy(transport.PolicyTorStrict)

	// 5. Session must be terminated immediately
	select {
	case <-disconnectedChan:
		// Success: disconnect callback triggered
	case <-time.After(2 * time.Second):
		t.Fatalf("Timeout waiting for OnPeerDisconnected after switching to TorStrict")
	}

	disconnectMu.Lock()
	if disconnectedPeer != bobFP {
		t.Errorf("Expected disconnected peer %s, got %s", bobFP, disconnectedPeer)
	}
	disconnectMu.Unlock()

	if aliceMgr.IsPeerOnline(bobFP) {
		t.Fatalf("Alice must NOT see Bob online after switching to TorStrict (session must be closed)")
	}
}
