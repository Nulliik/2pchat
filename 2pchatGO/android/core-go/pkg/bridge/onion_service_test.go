package bridge

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/discovery"
	"twopchat/core/pkg/session"
	"twopchat/core/pkg/transport"
)

func TestTorOnionServiceIntegration(t *testing.T) {
	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}

	testOnion := "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion"

	// 1. Test SetOnionAddress and GetOnionAddress before Init
	mgr.SetOnionAddress(testOnion)
	if got := mgr.GetOnionAddress(); got != testOnion {
		t.Fatalf("Expected onion address %s, got %s", testOnion, got)
	}

	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	// 2. Verify onion address propagated to netManager and discoverySvc
	if got := mgr.netManager.GetOnionAddress(); got != testOnion {
		t.Errorf("Expected netManager onion address %s, got %s", testOnion, got)
	}
	if got := mgr.discoverySvc.GetOnionAddress(); got != testOnion {
		t.Errorf("Expected discoverySvc onion address %s, got %s", testOnion, got)
	}

	// 3. Test dynamic update of onion address
	newOnion := "2pchatv3hiddennodeabcdefghijklmnopqrstuvwxyz1234567890.onion"
	mgr.SetOnionAddress(newOnion)
	if got := mgr.GetOnionAddress(); got != newOnion {
		t.Errorf("Expected updated onion address %s, got %s", newOnion, got)
	}
	if got := mgr.netManager.GetOnionAddress(); got != newOnion {
		t.Errorf("Expected updated netManager onion address %s, got %s", newOnion, got)
	}

	// 4. Verify endpoint classification for .onion
	tier := discovery.ClassifyTier(newOnion + ":50001")
	if tier != discovery.TierTor {
		t.Errorf("Expected .onion endpoint to classify as TierTor, got %v", tier)
	}

	trans := mgr.dialer.ClassifyEndpoint(newOnion + ":50001")
	if trans != transport.TransportTor {
		t.Errorf("Expected .onion endpoint to classify as TransportTor, got %v", trans)
	}

	t.Log("✅ Tor Onion Service integration verified successfully")
}

func TestInboundTorHiddenServiceHandshake(t *testing.T) {
	// Bob acts as Hidden Service receiver on 127.0.0.1 (forwarded by Tor)
	bobReceived := make(chan string, 1)
	bobConnected := make(chan string, 1)

	bobMgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}
	bobMgr.SetOnionAddress("bobnodev3hiddenaddress1234567890abcdefghijklmnopqrstu.onion")
	bobMgr.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			bobConnected <- peerFP
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			bobReceived <- string(payload)
		},
	}, nil)

	if err := bobMgr.Init(); err != nil {
		t.Fatalf("Bob Init failed: %v", err)
	}
	if err := bobMgr.StartListener(0); err != nil {
		t.Fatalf("Bob StartListener failed: %v", err)
	}
	defer bobMgr.StopListener()
	bobPort := bobMgr.GetBoundPort()

	// Alice dials Bob on 127.0.0.1 (simulating Tor inbound circuit to local listener)
	aliceMgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}
	aliceConnected := make(chan string, 1)
	aliceMgr.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			aliceConnected <- peerFP
		},
	}, nil)
	if err := aliceMgr.Init(); err != nil {
		t.Fatalf("Alice Init failed: %v", err)
	}

	bobFP := bobMgr.GetLocalFingerprint()
	aliceFP := aliceMgr.GetLocalFingerprint()

	targetEp := fmt.Sprintf("127.0.0.1:%d", bobPort)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, err := aliceMgr.dialer.DialContext(ctx, "tcp", targetEp)
	if err != nil {
		t.Fatalf("Alice dial failed: %v", err)
	}

	aliceSess, err := session.NewSession(
		conn,
		true,
		aliceMgr.identity,
		aliceMgr.prekeyPriv,
		aliceMgr.prekeyPub,
		bobFP,
		5*time.Second,
	)
	if err != nil {
		t.Fatalf("Alice NewSession handshake failed: %v", err)
	}
	defer aliceSess.Close()

	select {
	case connectedFP := <-bobConnected:
		if connectedFP != aliceFP {
			t.Fatalf("Bob connected to unexpected peer: %s", connectedFP)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Bob OnPeerConnected")
	}

	// Send encrypted test message
	testMsg := map[string]any{
		"type": "chat",
		"id":   "tor-test-1",
		"body": "Hello over Inbound Tor Hidden Service!",
	}
	msgID, err := aliceSess.SendReliable(testMsg)
	if err != nil {
		t.Fatalf("Alice SendReliable failed: %v", err)
	}

	select {
	case received := <-bobReceived:
		if !strings.Contains(received, "Hello over Inbound Tor Hidden Service!") {
			t.Fatalf("Bob received unexpected payload: %s", received)
		}
		t.Logf("✅ Inbound Tor Hidden Service received and decrypted message (ID: %s): %s", msgID, received)
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Bob to receive message")
	}
}
