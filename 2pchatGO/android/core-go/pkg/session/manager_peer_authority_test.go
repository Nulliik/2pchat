package session

import (
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

func TestPeerAuthority_InternalReconnect_HonorsPeerPolicy(t *testing.T) {
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()
	aliceMgr := NewManager(aliceID, alicePrekeyPriv, alicePrekeyPub, "127.0.0.1:9050", false, EventCallbacks{})

	bobID, _ := crypto.GenerateIdentityKeyPair()
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	// Store TOR_ONLY policy for Bob
	aliceMgr.SetPeerPolicy(bobFP, transport.PolicyTorStrict)

	// Attempt internal reconnect using plain ConnectPeer with clearnet endpoint
	clearnetEndpoint := "127.0.0.1:50001"
	_, err := aliceMgr.ConnectPeer(clearnetEndpoint, bobFP)
	if err == nil {
		t.Fatalf("Expected ConnectPeer to fail closed using stored peer policy, but it succeeded")
	}
}

func TestPeerAuthority_TorOnlyPeer_InboundClearnet_Rejected(t *testing.T) {
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()

	var rejectedCount int32
	callbacks := EventCallbacks{
		OnError: func(code int, msg string) {
			atomic.AddInt32(&rejectedCount, 1)
		},
	}
	aliceMgr := NewManager(aliceID, alicePrekeyPriv, alicePrekeyPub, "127.0.0.1:9050", false, callbacks)

	// Start Alice's listener in Speed mode (dual-stack / accepts clearnet)
	if err := aliceMgr.StartListener(0); err != nil {
		t.Fatalf("StartListener failed: %v", err)
	}
	defer func() { _ = aliceMgr.StopListener() }()

	alicePort := aliceMgr.Port()
	aliceAddr := fmt.Sprintf("127.0.0.1:%d", alicePort)

	// Bob is configured
	bobID, _ := crypto.GenerateIdentityKeyPair()
	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	// Alice designates Bob as TOR_ONLY in her database / manager
	aliceMgr.SetPeerPolicy(bobFP, transport.PolicyTorStrict)

	// Bob dials Alice over clearnet TCP
	conn, err := net.DialTimeout("tcp", aliceAddr, 2*time.Second)
	if err != nil {
		t.Fatalf("Bob failed to connect to Alice's listener: %v", err)
	}
	defer conn.Close()

	// Bob performs X3DH initiator handshake over clearnet
	bobSess, err := NewSession(
		conn,
		true, // initiator
		bobID,
		bobPrekeyPriv,
		bobPrekeyPub,
		aliceMgr.Fingerprint(),
		5*time.Second,
	)
	if err != nil {
		t.Fatalf("Bob NewSession handshake error: %v", err)
	}
	_ = bobSess

	// Wait for Alice to process handshake and verify policy
	time.Sleep(300 * time.Millisecond)

	// Alice must NOT have registered a session for Bob
	if aliceMgr.IsPeerOnline(bobFP) {
		t.Fatalf("SECURITY VIOLATION: Alice registered an online session with Bob over clearnet despite Bob having TOR_ONLY policy")
	}

	if atomic.LoadInt32(&rejectedCount) == 0 {
		t.Fatalf("Expected Alice's OnError callback to fire with rejection, but it did not")
	}
}

func TestPeerAuthority_SwitchToStrict_PreservesTorSessions(t *testing.T) {
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()
	aliceMgr := NewManager(aliceID, alicePrekeyPriv, alicePrekeyPub, "127.0.0.1:9050", false, EventCallbacks{})

	// Simulate an active Tor session
	c1, c2 := net.Pipe()
	defer c2.Close()

	torID, _ := crypto.GenerateIdentityKeyPair()
	torPrekeyPriv, torPrekeyPub, _ := crypto.GenerateX25519Keypair()
	torFP := crypto.Fingerprint(torID.Public.Bytes())

	var sess *Session
	var sessErr error
	var responderSess *Session
	var respErr error
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		sess, sessErr = NewSession(c1, true, torID, torPrekeyPriv, torPrekeyPub, crypto.Fingerprint(aliceID.Public.Bytes()), 2*time.Second)
	}()
	go func() {
		defer wg.Done()
		responderSess, respErr = NewSession(c2, false, aliceID, alicePrekeyPriv, alicePrekeyPub, "", 2*time.Second)
	}()
	wg.Wait()
	if sessErr != nil {
		t.Fatalf("NewSession failed: %v", sessErr)
	}
	defer sess.Close()
	if respErr != nil {
		t.Fatalf("responder NewSession failed: %v", respErr)
	}
	defer responderSess.Close()
	sess.SetTorTransport(true)

	aliceMgr.RegisterSession(sess, torFP, "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion:50001", false)

	// Verify session is online
	if !aliceMgr.IsPeerOnline(torFP) {
		t.Fatalf("Tor session should be online")
	}

	// Switch policy to Tor Strict
	aliceMgr.ApplyPolicy(transport.PolicyTorStrict)

	// Tor session must remain online!
	if !aliceMgr.IsPeerOnline(torFP) {
		t.Fatalf("Tor session was incorrectly terminated when switching to Tor Strict")
	}
}

// TestUnconfirmedGroupPeerIncomingClearnetRejected verifies that a peer introduced via a group roster
// with PolicyTorStrict (POLICY_FLAG_ALLOW_ONION) cannot initiate an incoming session over clearnet.
//
// SEC-03 Residual Note:
// Rejection occurs post-handshake, meaning the initiator's endpoint and identity are disclosed
// during the handshake before connection termination. Protection is against session establishment,
// not pre-handshake identity leakage.
func TestPeerAuthority_UnconfirmedGroupPeerIncomingClearnetRejected(t *testing.T) {
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()

	var rejectedCount int32
	callbacks := EventCallbacks{
		OnError: func(code int, msg string) {
			atomic.AddInt32(&rejectedCount, 1)
		},
	}
	aliceMgr := NewManager(aliceID, alicePrekeyPriv, alicePrekeyPub, "127.0.0.1:9050", false, callbacks)

	if err := aliceMgr.StartListener(0); err != nil {
		t.Fatalf("StartListener failed: %v", err)
	}
	defer func() { _ = aliceMgr.StopListener() }()

	alicePort := aliceMgr.Port()
	aliceAddr := fmt.Sprintf("127.0.0.1:%d", alicePort)

	// Bob is a contact introduced via group roster (GROUP_INFERRED)
	bobID, _ := crypto.GenerateIdentityKeyPair()
	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	// Roster introduction set peer policy to Tor-only (PolicyFlagAllowOnion = 8)
	aliceMgr.SetPeerPolicy(bobFP, transport.PolicyTorStrict)

	// Bob dials Alice over clearnet TCP
	conn, err := net.DialTimeout("tcp", aliceAddr, 2*time.Second)
	if err != nil {
		t.Fatalf("Bob failed to connect to Alice's listener: %v", err)
	}
	defer conn.Close()

	// Bob completes handshake over clearnet
	bobSess, err := NewSession(
		conn,
		true, // initiator
		bobID,
		bobPrekeyPriv,
		bobPrekeyPub,
		aliceMgr.Fingerprint(),
		5*time.Second,
	)
	if err != nil {
		t.Fatalf("Bob NewSession handshake error: %v", err)
	}
	_ = bobSess

	time.Sleep(300 * time.Millisecond)

	// Alice must reject the unconfirmed group peer session over clearnet
	if aliceMgr.IsPeerOnline(bobFP) {
		t.Fatalf("SECURITY VIOLATION: Unconfirmed group peer established online session over clearnet")
	}

	if atomic.LoadInt32(&rejectedCount) == 0 {
		t.Fatalf("Expected OnError callback to fire with rejection for clearnet connection from group-inferred peer")
	}
}

