package session

import (
	"fmt"
	"net"
	"sync"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
)

func TestIPRateLimiter(t *testing.T) {
	limiter := newIPRateLimiter()
	ip := "198.51.100.42"

	// Should allow up to maxHandshakesPerWindow within 5s
	for i := 0; i < maxHandshakesPerWindow; i++ {
		if !limiter.allow(ip) {
			t.Fatalf("expected request %d to be allowed", i+1)
		}
	}

	// Next request must be blocked
	if limiter.allow(ip) {
		t.Fatalf("expected request %d to be blocked by rate limiter", maxHandshakesPerWindow+1)
	}

	// Different IP should still be allowed
	if !limiter.allow("198.51.100.43") {
		t.Fatal("expected different IP to be allowed")
	}

	// Loopback / onion local should always be allowed
	for i := 0; i < 20; i++ {
		if !limiter.allow("127.0.0.1") {
			t.Fatal("expected localhost to always be allowed")
		}
	}
}

func TestManagerIncomingHandshakeConcurrencyGuard(t *testing.T) {
	id, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("failed to generate identity: %v", err)
	}
	prekeyPriv, prekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("failed to generate prekey: %v", err)
	}

	var errCount int
	var errMu sync.Mutex
	mgr := NewManager(id, prekeyPriv, prekeyPub, "", false, EventCallbacks{
		OnError: func(code int, msg string) {
			errMu.Lock()
			errCount++
			errMu.Unlock()
		},
	})

	if err := mgr.StartListener(0); err != nil {
		t.Fatalf("failed to start listener: %v", err)
	}
	defer func() { _ = mgr.StopListener() }()

	port := mgr.Port()
	addr := fmt.Sprintf("127.0.0.1:%d", port)

	// Rapidly open connections to test listener stability under load
	var wg sync.WaitGroup
	for i := 0; i < 25; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, err := net.DialTimeout("tcp", addr, 2*time.Second)
			if err == nil {
				time.Sleep(20 * time.Millisecond)
				_ = conn.Close()
			}
		}()
	}
	wg.Wait()

	// Wait briefly for all connections to be reaped
	time.Sleep(100 * time.Millisecond)

	// Ensure manager is still operational after burst
	testConn, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		t.Fatalf("manager failed to accept connection after burst: %v", err)
	}
	_ = testConn.Close()
}
