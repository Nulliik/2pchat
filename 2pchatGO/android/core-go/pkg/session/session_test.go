package session

import (
	"fmt"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
)

func TestSessionOverTCP(t *testing.T) {
	aliceId, _ := crypto.GenerateIdentityKeyPair()
	bobId, _ := crypto.GenerateIdentityKeyPair()

	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()
	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to start listener: %v", err)
	}
	defer listener.Close()

	port := listener.Addr().(*net.TCPAddr).Port

	var bobSession *Session
	var bobErr error
	var wg sync.WaitGroup
	wg.Add(1)

	go func() {
		defer wg.Done()
		conn, err := listener.Accept()
		if err != nil {
			bobErr = err
			return
		}
		bobSession, bobErr = NewSession(
			conn,
			false, // responder
			bobId,
			bobPrekeyPriv,
			bobPrekeyPub,
			"",
			5*time.Second,
		)
	}()

	clientConn, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		t.Fatalf("Failed to dial listener: %v", err)
	}

	aliceSession, err := NewSession(
		clientConn,
		true, // initiator
		aliceId,
		alicePrekeyPriv,
		alicePrekeyPub,
		crypto.Fingerprint(bobId.Public.Bytes()),
		5*time.Second,
	)
	if err != nil {
		t.Fatalf("Alice session creation failed: %v", err)
	}
	defer aliceSession.Close()

	wg.Wait()
	if bobErr != nil {
		t.Fatalf("Bob session creation failed: %v", bobErr)
	}
	defer bobSession.Close()

	// Step 1: Alice sends reliable chat to Bob
	msgID, err := aliceSession.SendChat("Hello Bob from Go TCP Session!", "Alice")
	if err != nil {
		t.Fatalf("Alice SendChat failed: %v", err)
	}
	if msgID == "" {
		t.Fatal("Expected non-empty msgID")
	}

	select {
	case msg := <-bobSession.Messages():
		if msg["type"] != string(TypeChat) {
			t.Fatalf("Expected type chat, got: %v", msg["type"])
		}
		if msg["body"] != "Hello Bob from Go TCP Session!" {
			t.Fatalf("Body mismatch: %v", msg["body"])
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for message on Bob")
	}

	// Step 2: Bob replies back to Alice
	replyID, err := bobSession.SendChat("Hi Alice! Got your message over encrypted Go TCP.", "Bob")
	if err != nil {
		t.Fatalf("Bob SendChat failed: %v", err)
	}
	if replyID == "" {
		t.Fatal("Expected non-empty replyID")
	}

	select {
	case msg := <-aliceSession.Messages():
		if msg["type"] != string(TypeChat) {
			t.Fatalf("Expected type chat, got: %v", msg["type"])
		}
		if msg["body"] != "Hi Alice! Got your message over encrypted Go TCP." {
			t.Fatalf("Body mismatch: %v", msg["body"])
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for message on Alice")
	}
}

func TestManagerConnectionAndMessaging(t *testing.T) {
	aliceId, _ := crypto.GenerateIdentityKeyPair()
	bobId, _ := crypto.GenerateIdentityKeyPair()

	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()
	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()

	var aliceReceivedMsg string
	var bobReceivedMsg string
	var bobConnectedEndpoint string

	var mu sync.Mutex
	aliceConnected := make(chan bool, 1)
	bobConnected := make(chan bool, 1)
	bobGotMsg := make(chan bool, 1)
	aliceGotMsg := make(chan bool, 1)

	aliceMgr := NewManager(
		aliceId,
		alicePrekeyPriv,
		alicePrekeyPub,
		"127.0.0.1:9050",
		false,
		EventCallbacks{
			OnPeerConnected: func(peerFP, endpoint string) {
				select {
				case aliceConnected <- true:
				default:
				}
			},
			OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
				m, _ := DecodeMessage(payload)
				mu.Lock()
				aliceReceivedMsg, _ = m["body"].(string)
				mu.Unlock()
				select {
				case aliceGotMsg <- true:
				default:
				}
			},
		},
	)
	defer aliceMgr.Close()
	aliceMgr.SetNickname("Alice")

	bobMgr := NewManager(
		bobId,
		bobPrekeyPriv,
		bobPrekeyPub,
		"127.0.0.1:9050",
		false,
		EventCallbacks{
			OnPeerConnected: func(peerFP, endpoint string) {
				mu.Lock()
				bobConnectedEndpoint = endpoint
				mu.Unlock()
				select {
				case bobConnected <- true:
				default:
				}
			},
			OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
				m, _ := DecodeMessage(payload)
				mu.Lock()
				bobReceivedMsg, _ = m["body"].(string)
				mu.Unlock()
				select {
				case bobGotMsg <- true:
				default:
				}
			},
		},
	)
	defer bobMgr.Close()
	bobMgr.SetNickname("Bob")

	// Bob starts listening on random available port
	if err := bobMgr.StartListener(0); err != nil {
		t.Fatalf("Bob StartListener failed: %v", err)
	}

	bobPort := bobMgr.listener.Port()
	bobEndpoint := fmt.Sprintf("127.0.0.1:%d", bobPort)
	bobFP := crypto.Fingerprint(bobId.Public.Bytes())

	// Alice connects to Bob
	_, err := aliceMgr.ConnectPeer(bobEndpoint, bobFP)
	if err != nil {
		t.Fatalf("Alice ConnectPeer failed: %v", err)
	}

	select {
	case <-aliceConnected:
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Alice connection event")
	}

	select {
	case <-bobConnected:
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Bob connection event")
	}

	// Alice sends message to Bob
	_, err = aliceMgr.SendMessage(bobFP, "Testing Manager P2P communication")
	if err != nil {
		t.Fatalf("Alice SendMessage failed: %v", err)
	}

	select {
	case <-bobGotMsg:
		mu.Lock()
		if bobReceivedMsg != "Testing Manager P2P communication" {
			t.Fatalf("Bob message content mismatch: %s", bobReceivedMsg)
		}
		mu.Unlock()
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Bob to receive message")
	}

	// Bob sends reply to Alice
	aliceFP := crypto.Fingerprint(aliceId.Public.Bytes())
	_, err = bobMgr.SendMessage(aliceFP, "Reply from Bob via Manager")
	if err != nil {
		t.Fatalf("Bob SendMessage failed: %v", err)
	}

	select {
	case <-aliceGotMsg:
		mu.Lock()
		if aliceReceivedMsg != "Reply from Bob via Manager" {
			t.Fatalf("Alice message content mismatch: %s", aliceReceivedMsg)
		}
		mu.Unlock()
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Alice to receive reply")
	}

	mu.Lock()
	_ = bobConnectedEndpoint
	mu.Unlock()
}

func TestSessionRapidBidirectionalExchange(t *testing.T) {
	aliceId, _ := crypto.GenerateIdentityKeyPair()
	bobId, _ := crypto.GenerateIdentityKeyPair()

	bobPrekeyPriv, bobPrekeyPub, _ := crypto.GenerateX25519Keypair()
	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to start listener: %v", err)
	}
	defer listener.Close()

	port := listener.Addr().(*net.TCPAddr).Port

	var bobSession *Session
	var wg sync.WaitGroup
	wg.Add(1)

	go func() {
		defer wg.Done()
		conn, err := listener.Accept()
		if err == nil {
			bobSession, _ = NewSession(
				conn,
				false,
				bobId,
				bobPrekeyPriv,
				bobPrekeyPub,
				"",
				5*time.Second,
			)
		}
	}()

	clientConn, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		t.Fatalf("Failed to dial listener: %v", err)
	}

	aliceSession, err := NewSession(
		clientConn,
		true,
		aliceId,
		alicePrekeyPriv,
		alicePrekeyPub,
		crypto.Fingerprint(bobId.Public.Bytes()),
		5*time.Second,
	)
	if err != nil {
		t.Fatalf("Alice session creation failed: %v", err)
	}
	defer aliceSession.Close()

	wg.Wait()
	if bobSession == nil {
		t.Fatalf("Bob session is nil")
	}
	defer bobSession.Close()

	// Exchange 30 messages in sequence
	numMessages := 30
	for i := 0; i < numMessages; i++ {
		text := fmt.Sprintf("Ping #%d from Alice", i)
		_, err := aliceSession.SendChat(text, "Alice")
		if err != nil {
			t.Fatalf("Alice SendChat %d failed: %v", i, err)
		}

		select {
		case msg := <-bobSession.Messages():
			if msg["body"] != text {
				t.Fatalf("Bob received unexpected body: got %v, expected %s", msg["body"], text)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("Bob timed out waiting for msg %d", i)
		}

		replyText := fmt.Sprintf("Pong #%d from Bob", i)
		_, err = bobSession.SendChat(replyText, "Bob")
		if err != nil {
			t.Fatalf("Bob SendChat %d failed: %v", i, err)
		}

		select {
		case msg := <-aliceSession.Messages():
			if msg["body"] != replyText {
				t.Fatalf("Alice received unexpected body: got %v, expected %s", msg["body"], replyText)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("Alice timed out waiting for reply %d", i)
		}
	}
}

func TestAdaptiveAckTimeoutForTor(t *testing.T) {
	if DefaultAckTimeout != 3*time.Second {
		t.Fatalf("Expected DefaultAckTimeout 3s, got %v", DefaultAckTimeout)
	}
	if TorAckTimeout != 8*time.Second {
		t.Fatalf("Expected TorAckTimeout 8s, got %v", TorAckTimeout)
	}

	s := &Session{
		ackTimeout: DefaultAckTimeout,
	}

	if s.AckTimeout() != 3*time.Second {
		t.Fatalf("Expected initial AckTimeout to be 3s, got %v", s.AckTimeout())
	}

	// Switch to Tor transport
	s.SetTorTransport(true)
	if s.AckTimeout() != TorAckTimeout {
		t.Fatalf("Expected AckTimeout after SetTorTransport(true) to be 8s, got %v", s.AckTimeout())
	}

	// Switch back to Direct
	s.SetTorTransport(false)
	if s.AckTimeout() != DefaultAckTimeout {
		t.Fatalf("Expected AckTimeout after SetTorTransport(false) to be 3s, got %v", s.AckTimeout())
	}

	// Custom timeout
	s.SetAckTimeout(6 * time.Second)
	if s.AckTimeout() != 6*time.Second {
		t.Fatalf("Expected custom AckTimeout to be 6s, got %v", s.AckTimeout())
	}
}

