package bridge

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/discovery"
	"twopchat/core/pkg/session"
	"twopchat/core/pkg/transport"
)

func TestGoCoreE2EConnectivityAndDiscovery(t *testing.T) {
	var logs []string
	var logsMu sync.Mutex
	logf := func(format string, args ...any) {
		logsMu.Lock()
		defer logsMu.Unlock()
		msg := fmt.Sprintf(format, args...)
		logs = append(logs, msg)
		t.Log(msg)
	}

	// 1. Setup Node A (Alice) and Node B (Bob)
	aliceReceived := make(chan string, 10)
	bobReceived := make(chan string, 10)
	aliceConnected := make(chan string, 10)
	bobConnected := make(chan string, 10)

	aliceFPChan := make(chan string, 1)
	bobFPChan := make(chan string, 1)

	alice := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}
	alice.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			logf("[ALICE] OnPeerConnected: peer=%s endpoint=%s", peerFP, endpoint)
			aliceConnected <- peerFP
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			logf("[ALICE] OnMessageReceived: peer=%s msgID=%s payload=%s", peerFP, msgID, string(payload))
			aliceReceived <- string(payload)
		},
		OnError: func(code int, message string) {
			logf("[ALICE] OnError: code=%d msg=%s", code, message)
		},
	}, func(infoHashHex, endpoint, source string) {
		logf("[ALICE] Discovery callback: infoHash=%s ep=%s source=%s", infoHashHex, endpoint, source)
	})

	bob := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
		torProxy: "127.0.0.1:9050",
		dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second),
	}
	bob.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			logf("[BOB] OnPeerConnected: peer=%s endpoint=%s", peerFP, endpoint)
			bobConnected <- peerFP
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			logf("[BOB] OnMessageReceived: peer=%s msgID=%s payload=%s", peerFP, msgID, string(payload))
			bobReceived <- string(payload)
		},
		OnError: func(code int, message string) {
			logf("[BOB] OnError: code=%d msg=%s", code, message)
		},
	}, func(infoHashHex, endpoint, source string) {
		logf("[BOB] Discovery callback: infoHash=%s ep=%s source=%s", infoHashHex, endpoint, source)
	})

	if err := alice.Init(); err != nil {
		t.Fatalf("Alice init failed: %v", err)
	}
	if err := bob.Init(); err != nil {
		t.Fatalf("Bob init failed: %v", err)
	}

	aliceFP := alice.GetLocalFingerprint()
	bobFP := bob.GetLocalFingerprint()
	aliceFPChan <- aliceFP
	bobFPChan <- bobFP

	logf("[LIB2PCORE] Local identities initialized: Alice FP=%s, Bob FP=%s", aliceFP, bobFP)

	// 2. Start Listener on Bob on dynamic port to prevent port conflicts with running emulators
	if err := bob.StartListener(0); err != nil {
		t.Fatalf("Bob StartListener failed: %v", err)
	}
	bobPort := bob.GetBoundPort()
	logf("[LIB2PCORE] Listener successfully started on port %d", bobPort)
	defer bob.StopListener()

	// 3. Verify LAN Discovery serialization & beacon exchange
	beacon := discovery.LANBeacon{
		Service:     discovery.LANServiceName,
		Fingerprint: bobFP,
		Port:        bobPort,
		Timestamp:   time.Now().Unix(),
	}
	beaconData, err := json.Marshal(beacon)
	if err != nil {
		t.Fatalf("LANBeacon json.Marshal failed: %v", err)
	}
	var parsedBeacon discovery.LANBeacon
	if err := json.Unmarshal(beaconData, &parsedBeacon); err != nil {
		t.Fatalf("UnmarshalLANBeacon failed: %v", err)
	}
	if parsedBeacon.Fingerprint != bobFP {
		t.Fatalf("Beacon fingerprint mismatch: got %s, want %s", parsedBeacon.Fingerprint, bobFP)
	}
	logf("[LIB2PCORE] LAN discovery beacon verified for peer FP %s (port %d)", bobFP, bobPort)

	// 4. Alice establishes encrypted X3DH connection to Bob
	bobEndpoint := fmt.Sprintf("127.0.0.1:%d", bobPort)
	logf("[LIB2PCORE] Alice connecting to Bob endpoint: %s (expected FP: %s)", bobEndpoint, bobFP)

	if err := alice.ConnectPeer(bobEndpoint, bobFP); err != nil {
		t.Fatalf("Alice ConnectPeer failed: %v", err)
	}

	select {
	case connectedFP := <-aliceConnected:
		if connectedFP != bobFP {
			t.Fatalf("Alice connected to unexpected peer: %s", connectedFP)
		}
		logf("[LIB2PCORE] Alice successfully completed X3DH handshake with Bob (%s)", connectedFP)
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Alice OnPeerConnected")
	}

	select {
	case connectedFP := <-bobConnected:
		if connectedFP != aliceFP {
			t.Fatalf("Bob connected to unexpected peer: %s", connectedFP)
		}
		logf("[LIB2PCORE] Bob successfully completed X3DH handshake with Alice (%s)", connectedFP)
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Bob OnPeerConnected")
	}

	// 5. Send encrypted test message from Alice to Bob
	testMsg := "Hello Bob! Verified message from Go Core lib2pcore."
	logf("[LIB2PCORE] Alice sending encrypted test message: %s", testMsg)
	msgID, err := alice.SendMessage(bobFP, testMsg)
	if err != nil {
		t.Fatalf("Alice SendMessage failed: %v", err)
	}
	if msgID == "" {
		t.Fatal("Expected non-empty msgID from Alice")
	}
	logf("[LIB2PCORE] Message sent with msgID: %s", msgID)

	select {
	case received := <-bobReceived:
		if !strings.Contains(received, testMsg) {
			t.Fatalf("Bob received unexpected message content: %s", received)
		}
		logf("[LIB2PCORE] Bob successfully received and decrypted message: %s", received)
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Bob to receive message from Alice")
	}

	// 6. Bob replies back to Alice
	replyMsg := "Hi Alice! Confirmation received over encrypted Go Core P2P session."
	logf("[LIB2PCORE] Bob replying to Alice: %s", replyMsg)
	replyID, err := bob.SendMessage(aliceFP, replyMsg)
	if err != nil {
		t.Fatalf("Bob SendMessage failed: %v", err)
	}
	if replyID == "" {
		t.Fatal("Expected non-empty replyID from Bob")
	}

	select {
	case received := <-aliceReceived:
		if !strings.Contains(received, replyMsg) {
			t.Fatalf("Alice received unexpected reply: %s", received)
		}
		logf("[LIB2PCORE] Alice successfully received and decrypted reply: %s", received)
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Alice to receive reply from Bob")
	}

	// 7. Verify Tor routing in AdaptiveDialer
	dialer := transport.NewAdaptiveDialer("127.0.0.1:9050", false, 5*time.Second)

	// .onion addresses must always classify as TransportTor
	onionEp := "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"
	if dialer.ClassifyEndpoint(onionEp) != transport.TransportTor {
		t.Fatalf("Expected .onion endpoint to classify as TransportTor")
	}
	logf("[LIB2PCORE] AdaptiveDialer verified: .onion endpoint routed to Tor transport (%s)", onionEp)

	// LAN / Loopback must classify as TransportDirect
	localEp := "192.168.1.100:50001"
	if dialer.ClassifyEndpoint(localEp) != transport.TransportDirect {
		t.Fatalf("Expected local IP to classify as TransportDirect")
	}
	logf("[LIB2PCORE] AdaptiveDialer verified: LAN endpoint routed to Direct transport (%s)", localEp)

	// When Tor proxy is enabled, public IP addresses use Direct transport per RULES.md §11, domain names use Tor
	dialer.SetTorProxy(true, "127.0.0.1:9050")
	publicEp := "93.184.216.34:50001"
	if dialer.ClassifyEndpoint(publicEp) != transport.TransportDirect {
		t.Fatalf("Expected public IP to classify as TransportDirect per RULES.md §11")
	}
	domainEp := "tracker.customdomain.org:50001"
	if dialer.ClassifyEndpoint(domainEp) != transport.TransportTor {
		t.Fatalf("Expected domain name to classify as TransportTor when proxy is enabled")
	}
	logf("[LIB2PCORE] AdaptiveDialer verified: Public endpoint routed to Direct and domain routed to Tor SOCKS5")

	logf("[LIB2PCORE] ALL E2E CONNECTIVITY & PEER DISCOVERY TESTS PASSED!")
}

// TestMockTorSocks5ProxyRouting tests that AdaptiveDialer actually attempts to connect
// via the SOCKS5 proxy on 127.0.0.1:9050 for .onion destinations.
func TestMockTorSocks5ProxyRouting(t *testing.T) {
	// Start a mock SOCKS5 proxy listener
	proxyListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to start mock SOCKS5 proxy listener: %v", err)
	}
	defer proxyListener.Close()

	proxyAddr := proxyListener.Addr().String()
	t.Logf("[TOR] Mock SOCKS5 proxy listening on %s", proxyAddr)

	socks5ConnectionAccepted := make(chan bool, 1)
	go func() {
		conn, err := proxyListener.Accept()
		if err == nil {
			socks5ConnectionAccepted <- true
			// Send SOCKS5 method selection response (version 5, no auth: 0x05, 0x00)
			buf := make([]byte, 256)
			_, _ = conn.Read(buf)
			_, _ = conn.Write([]byte{0x05, 0x00})
			_ = conn.Close()
		}
	}()

	dialer := transport.NewAdaptiveDialer(proxyAddr, true, 2*time.Second)
	onionAddress := "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	// Dialing .onion through dialer must connect to our mock SOCKS5 proxy
	_, _ = dialer.DialContext(ctx, "tcp", onionAddress)

	select {
	case <-socks5ConnectionAccepted:
		t.Logf("[TOR] Successfully verified: AdaptiveDialer dialed mock SOCKS5 proxy on %s for %s", proxyAddr, onionAddress)
	case <-time.After(2 * time.Second):
		t.Fatal("[TOR] Failed: AdaptiveDialer did not connect to SOCKS5 proxy for .onion address")
	}
}
