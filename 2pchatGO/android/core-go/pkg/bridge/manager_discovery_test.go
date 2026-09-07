package bridge

import (
	"crypto/ed25519"
	"encoding/json"
	"testing"
	"time"
	"twopchat/core/pkg/discovery"
)

func TestSessionManagerDiscoveryRecordLifecycle(t *testing.T) {
	mgr := GetManager()
	if err := mgr.Init(); err != nil {
		t.Fatalf("Init failed: %v", err)
	}

	mgr.SetDiscoverySeqCounter(100)

	endpointsJSON := `["93.184.216.34:50001"]`
	recordJSON, err := mgr.CreateSignedDiscoveryRecord(endpointsJSON, 1800, 0)
	if err != nil {
		t.Fatalf("CreateSignedDiscoveryRecord failed: %v", err)
	}

	var rec discovery.DiscoveryRecord
	if err := json.Unmarshal([]byte(recordJSON), &rec); err != nil {
		t.Fatalf("Unmarshal recordJSON failed: %v", err)
	}

	if rec.Seq != 101 {
		t.Fatalf("Expected Seq 101, got %d", rec.Seq)
	}
	if rec.Fingerprint == "" {
		t.Fatalf("Expected non-empty fingerprint")
	}
	if len(rec.IdentityKey) != ed25519.PublicKeySize {
		t.Fatalf("Expected 32-byte identity key")
	}

	// Verify discovery record
	filtered, newSeq, err := mgr.VerifyDiscoveryRecord(recordJSON, rec.Fingerprint, true)
	if err != nil {
		t.Fatalf("VerifyDiscoveryRecord failed: %v", err)
	}
	if newSeq != 101 {
		t.Fatalf("Expected newSeq 101, got %d", newSeq)
	}
	if len(filtered) == 0 {
		t.Fatalf("Expected filtered endpoints, got none")
	}

	// Replay should fail
	_, _, err = mgr.VerifyDiscoveryRecord(recordJSON, rec.Fingerprint, true)
	if err != discovery.ErrSequenceStale {
		t.Fatalf("Expected ErrSequenceStale on replay, got: %v", err)
	}
}

func TestSessionManagerDiscoverySeqPersistenceCallback(t *testing.T) {
	mgr := GetManager()
	persisted := make(chan uint64, 5)
	mgr.SetDiscoverySeqPersistHook(func(seq uint64) {
		persisted <- seq
	})

	// Force counter to 99 so next increment hits 100 (which triggers throttle persist)
	mgr.SetDiscoverySeqCounter(99)
	seq := mgr.GetNextDiscoverySeq()
	if seq != 100 {
		t.Fatalf("Expected seq 100, got %d", seq)
	}

	select {
	case p := <-persisted:
		if p != 100 {
			t.Fatalf("Expected persisted seq 100, got %d", p)
		}
	case <-time.After(1 * time.Second):
		t.Fatalf("Timeout waiting for sequence persistence hook")
	}
}
