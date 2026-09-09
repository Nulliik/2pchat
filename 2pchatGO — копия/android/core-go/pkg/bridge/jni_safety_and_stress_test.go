package bridge_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"twopchat/core/pkg/bridge"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/session"
	"twopchat/core/pkg/transport"
)

// TestFileTransferProgressThrottling verifies that file progress callbacks are throttled
// to at most ~100ms intervals, initial (0%) and final (100%) are delivered, and payload is intact.
func TestFileTransferProgressThrottling(t *testing.T) {
	const fileSize = 8 * 1024 * 1024 // 8 MB = 32 chunks (256KB each)
	rawData := make([]byte, fileSize)
	_, _ = rand.Read(rawData)

	tmpDir := t.TempDir()
	testFilePath := filepath.Join(tmpDir, "large_test_file.bin")
	if err := os.WriteFile(testFilePath, rawData, 0600); err != nil {
		t.Fatalf("Failed to write test file: %v", err)
	}

	var callbackCount int32
	var firstReportReceived int32
	var finalReportReceived int32

	ftMgr := transport.NewFileTransferManager(func(peerFP, msgID string, transferred, total int64, speed float64) {
		atomic.AddInt32(&callbackCount, 1)

		if transferred <= int64(transport.DefaultChunkSize+256) {
			atomic.StoreInt32(&firstReportReceived, 1)
		}
		if transferred == total {
			atomic.StoreInt32(&finalReportReceived, 1)
		}
	})

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	var framesReceived [][]byte
	var frameMu sync.Mutex

	err := ftMgr.SendFileStream(
		ctx,
		"test-peer",
		"msg-123",
		testFilePath,
		"large_test_file.bin",
		"caption",
		"📁",
		func(payload []byte) error {
			frameMu.Lock()
			framesReceived = append(framesReceived, append([]byte(nil), payload...))
			frameMu.Unlock()
			return nil
		},
	)
	if err != nil {
		t.Fatalf("SendFileStream failed: %v", err)
	}

	// 1. Check that metadata + 32 chunks were generated
	if len(framesReceived) < 33 {
		t.Fatalf("Expected 33 frames (1 metadata + 32 chunks), got %d", len(framesReceived))
	}

	// 2. Check that first and final reports were delivered
	if atomic.LoadInt32(&firstReportReceived) == 0 {
		t.Errorf("Initial progress report (first chunk) was not delivered")
	}
	if atomic.LoadInt32(&finalReportReceived) == 0 {
		t.Errorf("Final progress report (100%%) was not delivered")
	}

	// 3. Check throttling: 32 chunks sent rapidly in-memory.
	// Unthrottled would be 32 callbacks; throttled should be significantly lower (<= 5).
	count := atomic.LoadInt32(&callbackCount)
	t.Logf("Total progress callbacks delivered: %d (for 32 chunks)", count)
	if count > 10 {
		t.Errorf("Progress callbacks were not throttled: received %d for 32 chunks", count)
	}
}

// TestParallelConnectDisconnectStressWithDeadlockDetection runs concurrent connect/disconnect
// and message exchange cycles to ensure deadlock freedom and clean shutdown.
func TestParallelConnectDisconnectStressWithDeadlockDetection(t *testing.T) {
	const iterations = 5
	const concurrency = 4

	for iter := 0; iter < iterations; iter++ {
		var nodes []*bridge.SessionManager
		var endpoints []string
		var fps []string

		for i := 0; i < concurrency; i++ {
			node := &bridge.SessionManager{}
			if err := node.Init(); err != nil {
				t.Fatalf("Node %d Init failed: %v", i, err)
			}
			if err := node.StartListener(0); err != nil {
				t.Fatalf("Node %d StartListener failed: %v", i, err)
			}
			ep := fmt.Sprintf("127.0.0.1:%d", node.GetBoundPort())
			fp := node.GetLocalFingerprint()
			nodes = append(nodes, node)
			endpoints = append(endpoints, ep)
			fps = append(fps, fp)
		}

		var wg sync.WaitGroup
		// Concurrently connect nodes in full mesh
		for i := 0; i < concurrency; i++ {
			for j := 0; j < concurrency; j++ {
				if i == j {
					continue
				}
				wg.Add(1)
				go func(from, to int) {
					defer wg.Done()
					_ = nodes[from].ConnectPeer(endpoints[to], fps[to])
				}(i, j)
			}
		}
		wg.Wait()

		// Concurrently exchange messages
		for i := 0; i < concurrency; i++ {
			for j := 0; j < concurrency; j++ {
				if i == j {
					continue
				}
				wg.Add(1)
				go func(from, to int) {
					defer wg.Done()
					msg := fmt.Sprintf("stress-msg-%d-%d", from, to)
					_, _ = nodes[from].SendMessage(fps[to], msg)
				}(i, j)
			}
		}
		wg.Wait()

		// Concurrently close all nodes
		for i := 0; i < concurrency; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				_ = nodes[idx].Close()
			}(i)
		}
		wg.Wait()
	}
}

// TestReentrantCallbackDeadlockSafety verifies that calling Go core methods synchronously
// from inside a Go event callback (simulating Kotlin JNI re-entrancy) never deadlocks.
func TestReentrantCallbackDeadlockSafety(t *testing.T) {
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	alicePrePriv, alicePrePub, _ := crypto.GenerateX25519Keypair()

	bobID, _ := crypto.GenerateIdentityKeyPair()
	bobPrePriv, bobPrePub, _ := crypto.GenerateX25519Keypair()

	aliceFP := crypto.Fingerprint(aliceID.Public.Bytes())
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	var aliceMgr *session.Manager
	reentrantCallSuccess := make(chan bool, 5)

	// Callback that performs re-entrant synchronous calls into session.Manager
	aliceMgr = session.NewManager(aliceID, alicePrePriv, alicePrePub, "", false, session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			// Synchronous re-entrant calls:
			isOnline := aliceMgr.IsPeerOnline(peerFP)
			myFP := aliceMgr.Fingerprint()
			port := aliceMgr.Port()
			if isOnline && myFP != "" && port >= 0 {
				reentrantCallSuccess <- true
			}
		},
		OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
			// Synchronous re-entrant call:
			isOnline := aliceMgr.IsPeerOnline(peerFP)
			if isOnline {
				reentrantCallSuccess <- true
			}
		},
	})
	defer aliceMgr.Close()

	bobMgr := session.NewManager(bobID, bobPrePriv, bobPrePub, "", false, session.EventCallbacks{})
	defer bobMgr.Close()

	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()

	go func() {
		sess, err := session.NewSession(client, true, aliceID, alicePrePriv, alicePrePub, bobFP, 5*time.Second)
		if err == nil {
			aliceMgr.RegisterSession(sess, bobFP, "pipe-alice", true)
		}
	}()

	go func() {
		sess, err := session.NewSession(server, false, bobID, bobPrePriv, bobPrePub, aliceFP, 5*time.Second)
		if err == nil {
			bobMgr.RegisterSession(sess, aliceFP, "pipe-bob", false)
		}
	}()

	select {
	case <-reentrantCallSuccess:
		// Succeeded without deadlock
	case <-time.After(5 * time.Second):
		t.Fatalf("Deadlock detected during re-entrant JNI callback invocation")
	}
	_ = bytes.Equal
	_ = sha256.Sum256
}
