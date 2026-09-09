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

// TestSimultaneousDialingTieBreak verifies RULES.md §13:
// When two peers connect simultaneously, deterministic fingerprint comparison
// retains exactly one session and closes the duplicate without deadlocks or resource leaks.
func TestSimultaneousDialingTieBreak(t *testing.T) {
	aliceID, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("Generate Alice Identity: %v", err)
	}
	bobID, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("Generate Bob Identity: %v", err)
	}

	alicePrekeyPriv, alicePrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("Generate Alice Prekey: %v", err)
	}
	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("Generate Bob Prekey: %v", err)
	}

	aliceFP := crypto.Fingerprint(aliceID.Public.Bytes())
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	if aliceFP == bobFP {
		t.Fatalf("Alice and Bob generated identical fingerprints")
	}

	// 1. Start listeners for Alice and Bob
	aliceListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Alice listener failed: %v", err)
	}
	defer aliceListener.Close()
	alicePort := aliceListener.Addr().(*net.TCPAddr).Port

	bobListener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Bob listener failed: %v", err)
	}
	defer bobListener.Close()
	bobPort := bobListener.Addr().(*net.TCPAddr).Port

	var (
		sessionAtoB *Session
		sessionBtoA *Session
		inboundBob  *Session
		inboundAlice *Session
		errAtoB, errBtoA, errInBob, errInAlice error
	)

	var wg sync.WaitGroup
	wg.Add(4)

	// Inbound connection handlers
	go func() {
		defer wg.Done()
		conn, err := bobListener.Accept()
		if err != nil {
			errInBob = err
			return
		}
		inboundBob, errInBob = NewSession(
			conn,
			false,
			bobID,
			bobPrekeyPriv,
			bobPrekeyPub,
			aliceFP,
			5*time.Second,
		)
	}()

	go func() {
		defer wg.Done()
		conn, err := aliceListener.Accept()
		if err != nil {
			errInAlice = err
			return
		}
		inboundAlice, errInAlice = NewSession(
			conn,
			false,
			aliceID,
			alicePrekeyPriv,
			alicePrekeyPub,
			bobFP,
			5*time.Second,
		)
	}()

	// Outbound simultaneous connections
	go func() {
		defer wg.Done()
		conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(bobPort)), 5*time.Second)
		if err != nil {
			errAtoB = err
			return
		}
		sessionAtoB, errAtoB = NewSession(
			conn,
			true,
			aliceID,
			alicePrekeyPriv,
			alicePrekeyPub,
			bobFP,
			5*time.Second,
		)
	}()

	go func() {
		defer wg.Done()
		conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(alicePort)), 5*time.Second)
		if err != nil {
			errBtoA = err
			return
		}
		sessionBtoA, errBtoA = NewSession(
			conn,
			true,
			bobID,
			bobPrekeyPriv,
			bobPrekeyPub,
			aliceFP,
			5*time.Second,
		)
	}()

	wg.Wait()

	if errAtoB != nil || errInBob != nil {
		t.Fatalf("A -> B session setup failed: %v, %v", errAtoB, errInBob)
	}
	if errBtoA != nil || errInAlice != nil {
		t.Fatalf("B -> A session setup failed: %v, %v", errBtoA, errInAlice)
	}

	defer func() {
		if sessionAtoB != nil {
			sessionAtoB.Close()
		}
		if sessionBtoA != nil {
			sessionBtoA.Close()
		}
		if inboundBob != nil {
			inboundBob.Close()
		}
		if inboundAlice != nil {
			inboundAlice.Close()
		}
	}()

	// 2. Deterministic Tie-Breaking Verification:
	// The peer with smaller fingerprint is designated the canonical initiator.
	aliceIsInitiator := aliceFP < bobFP

	var activeAliceSession, activeBobSession *Session
	var redundantAliceSession, redundantBobSession *Session

	if aliceIsInitiator {
		activeAliceSession = sessionAtoB
		activeBobSession = inboundBob
		redundantAliceSession = inboundAlice
		redundantBobSession = sessionBtoA
	} else {
		activeAliceSession = inboundAlice
		activeBobSession = sessionBtoA
		redundantAliceSession = sessionAtoB
		redundantBobSession = inboundBob
	}

	// 3. Clean up the redundant session
	redundantAliceSession.Close()
	redundantBobSession.Close()

	// 4. Verify messaging on the retained canonical session
	testMsg := "Hello through tie-broken simultaneous session! 🛡️"
	msgID, err := activeAliceSession.SendChat(testMsg, "Alice")
	if err != nil {
		t.Fatalf("Failed to send chat on canonical session: %v", err)
	}

	select {
	case incoming := <-activeBobSession.Messages():
		if incoming["body"] != testMsg {
			t.Fatalf("Expected message %q, got %q", testMsg, incoming["body"])
		}
		if incoming["id"] != msgID {
			t.Fatalf("Expected msgID %q, got %q", msgID, incoming["id"])
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("Timeout waiting for message on canonical session")
	}

	// 5. Verify Bob can reply back on the same session
	replyMsg := fmt.Sprintf("Acknowledged message %s from Bob", msgID)
	_, err = activeBobSession.SendChat(replyMsg, "Bob")
	if err != nil {
		t.Fatalf("Bob reply failed: %v", err)
	}

	select {
	case incoming := <-activeAliceSession.Messages():
		if incoming["body"] != replyMsg {
			t.Fatalf("Expected reply %q, got %q", replyMsg, incoming["body"])
		}
	case <-time.After(3 * time.Second):
		t.Fatalf("Timeout waiting for reply on canonical session")
	}
}
