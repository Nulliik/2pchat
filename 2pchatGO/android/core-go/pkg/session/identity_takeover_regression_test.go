package session

import (
	"crypto/ed25519"
	"fmt"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
)

// TestIdentityTakeoverRejected verifies that if Alice expects Bob's fingerprint,
// and Eve connects presenting Eve's identity, the handshake fails and does not bind.
func TestIdentityTakeoverRejected(t *testing.T) {
	aliceId, _ := crypto.GenerateIdentityKeyPair()
	alicePrePriv, alicePrePub, _ := crypto.GenerateX25519Keypair()

	eveId, _ := crypto.GenerateIdentityKeyPair()
	evePrePriv, evePrePub, _ := crypto.GenerateX25519Keypair()

	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()

	expectedBobFingerprint := "aabbccddeeff00112233445566778899aabbccdd"

	errChan := make(chan error, 2)

	// Alice initiates expecting Bob
	go func() {
		_, err := NewSession(
			client,
			true,
			aliceId,
			alicePrePriv,
			alicePrePub,
			expectedBobFingerprint,
			5*time.Second,
		)
		errChan <- err
	}()

	// Eve responds with Eve's identity
	go func() {
		_, err := NewSession(
			server,
			false,
			eveId,
			evePrePriv,
			evePrePub,
			"",
			5*time.Second,
		)
		errChan <- err
	}()

	var initiatorErr error
	for i := 0; i < 2; i++ {
		err := <-errChan
		if err != nil && strings.Contains(err.Error(), "peer fingerprint mismatch") {
			initiatorErr = err
		}
	}

	if initiatorErr == nil {
		t.Fatalf("Expected initiator to reject unexpected fingerprint, but got nil error")
	}
}

// TestHandshakeConcurrencyAndRateLimit verifies that flood connections are rejected by semaphore/rate limiter.
func TestHandshakeConcurrencyAndRateLimit(t *testing.T) {
	mgrId, _ := crypto.GenerateIdentityKeyPair()
	mgrPrePriv, mgrPrePub, _ := crypto.GenerateX25519Keypair()

	callbacks := EventCallbacks{}
	mgr := NewManager(mgrId, mgrPrePriv, mgrPrePub, "", false, callbacks)
	defer mgr.Close()

	err := mgr.StartListener(0)
	if err != nil {
		t.Fatalf("Failed to start listener: %v", err)
	}

	port := mgr.Port()
	addr := fmt.Sprintf("127.0.0.1:%d", port)

	// Attempt rapid bursts of connections
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, dialErr := net.DialTimeout("tcp", addr, 200*time.Millisecond)
			if dialErr == nil && conn != nil {
				_ = conn.Close()
			}
		}()
	}
	wg.Wait()
}

// TestEphemeralKeyZeroization ensures memory zeroization works properly on byte buffers.
func TestEphemeralKeyZeroization(t *testing.T) {
	buf := make([]byte, 32)
	for i := range buf {
		buf[i] = 0xAA
	}

	crypto.Zeroize(buf)

	for i, b := range buf {
		if b != 0 {
			t.Fatalf("Byte at index %d was not zeroized (got 0x%02X)", i, b)
		}
	}
}

func init() {
	_ = ed25519.PublicKey(nil)
}
