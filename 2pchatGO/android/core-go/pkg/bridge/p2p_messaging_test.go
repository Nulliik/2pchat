package bridge_test

import (
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"twopchat/core/pkg/bridge"
	"twopchat/core/pkg/session"
)

// TestP2PConnectionAndMessaging verifies the full lifecycle of a P2P session:
// 1. Initialization of two independent SessionManagers (Alice & Bob).
// 2. Bob starts listening on a dynamic port.
// 3. Alice connects to Bob using his endpoint and fingerprint.
// 4. X3DH Handshake completes automatically.
// 5. Alice sends a message to Bob.
// 6. Bob receives and decrypts the message.
// 7. Bob replies to Alice.
// 8. Alice receives and decrypts the reply.
func TestP2PConnectionAndMessaging(t *testing.T) {
	// --- Setup Channels for Async Events ---
	aliceConnected := make(chan string, 1)
	bobConnected := make(chan string, 1)
	aliceReceived := make(chan string, 1)
	bobReceived := make(chan string, 1)

	var mu sync.Mutex
	var aliceLastError string
	var bobLastError string

	// --- Initialize Alice ---
	alice := &bridge.SessionManager{}
	alice.SetStorageDir(t.TempDir())
	alice.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			t.Logf("[Alice] Connected to peer %s at %s", peerFP, endpoint)
			aliceConnected <- peerFP
		},
		OnPeerDisconnected: func(peerFP, reason string) {
			t.Logf("[Alice] Disconnected from %s: %s", peerFP, reason)
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			t.Logf("[Alice] Received message from %s (ID: %s): %s", peerFP, msgID, string(payload))
			aliceReceived <- string(payload)
		},
		OnError: func(code int, message string) {
			mu.Lock()
			aliceLastError = fmt.Sprintf("Code %d: %s", code, message)
			mu.Unlock()
			t.Errorf("[Alice] Error: %s", message)
		},
	}, nil)

	if err := alice.Init(); err != nil {
		t.Fatalf("Failed to initialize Alice: %v", err)
	}
	defer alice.Close() // Cleanup resources

	// --- Initialize Bob ---
	bob := &bridge.SessionManager{}
	bob.SetStorageDir(t.TempDir())
	bob.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			t.Logf("[Bob] Connected to peer %s at %s", peerFP, endpoint)
			bobConnected <- peerFP
		},
		OnPeerDisconnected: func(peerFP, reason string) {
			t.Logf("[Bob] Disconnected from %s: %s", peerFP, reason)
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			t.Logf("[Bob] Received message from %s (ID: %s): %s", peerFP, msgID, string(payload))
			bobReceived <- string(payload)
		},
		OnError: func(code int, message string) {
			mu.Lock()
			bobLastError = fmt.Sprintf("Code %d: %s", code, message)
			mu.Unlock()
			t.Errorf("[Bob] Error: %s", message)
		},
	}, nil)

	if err := bob.Init(); err != nil {
		t.Fatalf("Failed to initialize Bob: %v", err)
	}
	defer bob.Close() // Cleanup resources

	// --- Step 1: Bob Starts Listening ---
	// Using port 0 lets the OS assign a random available port
	if err := bob.StartListener(0); err != nil {
		t.Fatalf("Bob failed to start listener: %v", err)
	}
	bobPort := bob.GetBoundPort()
	if bobPort == 0 {
		t.Fatal("Bob listener port is 0, expected a valid port")
	}
	bobEndpoint := fmt.Sprintf("127.0.0.1:%d", bobPort)
	bobFP := bob.GetLocalFingerprint()
	t.Logf("[Setup] Bob listening on %s (Fingerprint: %s)", bobEndpoint, bobFP)

	// --- Step 2: Alice Connects to Bob ---
	// We pass Bob's expected fingerprint to ensure we are connecting to the right peer
	if err := alice.ConnectPeer(bobEndpoint, bobFP); err != nil {
		t.Fatalf("Alice failed to initiate connection to Bob: %v", err)
	}

	// --- Step 3: Verify Connection Establishment (Handshake) ---
	select {
	case connectedFP := <-aliceConnected:
		if connectedFP != bobFP {
			t.Fatalf("Alice connected to unexpected peer: got %s, want %s", connectedFP, bobFP)
		}
		t.Log("[Success] Alice successfully completed handshake with Bob")
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Alice to connect to Bob")
	}

	select {
	case connectedFP := <-bobConnected:
		aliceFP := alice.GetLocalFingerprint()
		if connectedFP != aliceFP {
			t.Fatalf("Bob connected to unexpected peer: got %s, want %s", connectedFP, aliceFP)
		}
		t.Log("[Success] Bob successfully accepted connection from Alice")
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Bob to accept connection from Alice")
	}

	// --- Step 4: Alice Sends Message to Bob ---
	testMessage := "Hello Bob! This is a secure message from Alice via Go Core."
	msgID, err := alice.SendMessage(bobFP, testMessage)
	if err != nil {
		t.Fatalf("Alice failed to send message: %v", err)
	}
	if msgID == "" {
		t.Fatal("Expected a non-empty message ID from Alice")
	}
	t.Logf("[Alice] Sent message ID: %s", msgID)

	// --- Step 5: Bob Receives Message ---
	select {
	case receivedPayload := <-bobReceived:
		// The payload comes as a JSON string from the manager, check if it contains the body
		if !strings.Contains(receivedPayload, testMessage) {
			t.Fatalf("Bob received unexpected message content: %s", receivedPayload)
		}
		t.Log("[Success] Bob received and decrypted Alice's message")
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Bob to receive message")
	}

	// --- Step 6: Bob Replies to Alice ---
	replyMessage := "Hi Alice! Message received loud and clear. Replying now."
	aliceFP := alice.GetLocalFingerprint()
	replyID, err := bob.SendMessage(aliceFP, replyMessage)
	if err != nil {
		t.Fatalf("Bob failed to send reply: %v", err)
	}
	t.Logf("[Bob] Sent reply ID: %s", replyID)

	// --- Step 7: Alice Receives Reply ---
	select {
	case receivedPayload := <-aliceReceived:
		if !strings.Contains(receivedPayload, replyMessage) {
			t.Fatalf("Alice received unexpected reply content: %s", receivedPayload)
		}
		t.Log("[Success] Alice received and decrypted Bob's reply")
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Alice to receive reply")
	}

	// --- Final Verification ---
	mu.Lock()
	defer mu.Unlock()
	if aliceLastError != "" {
		t.Errorf("Alice encountered errors during test: %s", aliceLastError)
	}
	if bobLastError != "" {
		t.Errorf("Bob encountered errors during test: %s", bobLastError)
	}

	t.Log("✅ TestP2PConnectionAndMessaging: PASS")
}

// TestP2PBidirectionalMessagingByNickname verifies bidirectional messaging when peers address each other by nickname
func TestP2PBidirectionalMessagingByNickname(t *testing.T) {
	aliceConnected := make(chan string, 1)
	bobConnected := make(chan string, 1)
	aliceReceived := make(chan string, 10)
	bobReceived := make(chan string, 10)

	alice := &bridge.SessionManager{}
	alice.SetStorageDir(t.TempDir())
	alice.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			select {
			case aliceConnected <- peerFP:
			default:
			}
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			aliceReceived <- string(payload)
		},
	}, nil)
	if err := alice.Init(); err != nil {
		t.Fatalf("Failed to initialize Alice: %v", err)
	}
	defer alice.Close()
	alice.SetNickname("Alice")

	bob := &bridge.SessionManager{}
	bob.SetStorageDir(t.TempDir())
	bob.SetCallbacks(session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			select {
			case bobConnected <- peerFP:
			default:
			}
		},
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			bobReceived <- string(payload)
		},
	}, nil)
	if err := bob.Init(); err != nil {
		t.Fatalf("Failed to initialize Bob: %v", err)
	}
	defer bob.Close()
	bob.SetNickname("Bob")

	if err := bob.StartListener(0); err != nil {
		t.Fatalf("Bob start listener failed: %v", err)
	}
	bobPort := bob.GetBoundPort()
	bobEndpoint := fmt.Sprintf("127.0.0.1:%d", bobPort)
	bobFP := bob.GetLocalFingerprint()
	aliceFP := alice.GetLocalFingerprint()

	// Register nicknames
	alice.UpdatePeerNameMapping(bobFP, "Bob")
	bob.UpdatePeerNameMapping(aliceFP, "Alice")

	// Connect Alice -> Bob
	if err := alice.ConnectPeer(bobEndpoint, bobFP); err != nil {
		t.Fatalf("ConnectPeer failed: %v", err)
	}

	select {
	case <-aliceConnected:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Alice connection")
	}

	select {
	case <-bobConnected:
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Bob connection")
	}

	// 1. Alice sends to Bob by exact nickname "Bob"
	msg1 := "Hello Bob by nickname!"
	if _, err := alice.SendMessage("Bob", msg1); err != nil {
		t.Fatalf("Alice SendMessage by nickname 'Bob' failed: %v", err)
	}

	select {
	case rx := <-bobReceived:
		if !strings.Contains(rx, msg1) {
			t.Fatalf("Bob received unexpected payload: %s", rx)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Bob to receive message from Alice")
	}

	// 2. Bob replies to Alice by case-insensitive nickname "alice"
	msg2 := "Hello Alice by lowercase nickname!"
	if _, err := bob.SendMessage("alice", msg2); err != nil {
		t.Fatalf("Bob SendMessage by nickname 'alice' failed: %v", err)
	}

	select {
	case rx := <-aliceReceived:
		if !strings.Contains(rx, msg2) {
			t.Fatalf("Alice received unexpected payload: %s", rx)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for Alice to receive reply from Bob")
	}

	t.Log("✅ TestP2PBidirectionalMessagingByNickname: PASS")
}
