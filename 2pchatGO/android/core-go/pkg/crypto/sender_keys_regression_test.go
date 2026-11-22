package crypto

import (
	"bytes"
	"testing"
)

func TestSenderSessionStateBoundsAccumulatedSkippedKeys(t *testing.T) {
	seed := bytes.Repeat([]byte{0x42}, KeySize)
	state, err := NewSenderSessionState(seed)
	if err != nil {
		t.Fatal(err)
	}
	// Each request is within the per-jump limit, but together would exceed the
	// bounded skipped-key cache if accumulation were not enforced.
	if _, err := state.RatchetKeyForIteration(2000); err != nil {
		t.Fatal(err)
	}
	if len(state.SkippedKeys) != 2000 {
		t.Fatalf("expected 2000 skipped keys, got %d", len(state.SkippedKeys))
	}
	if _, err := state.RatchetKeyForIteration(4000); err == nil {
		t.Fatal("expected accumulated skipped-key limit to reject second jump")
	}
}

func TestSenderSessionStateNilChainKeyRejected(t *testing.T) {
	state := &SenderSessionState{SkippedKeys: make(map[uint32]*SenderMessageKey)}
	if _, err := state.RatchetKeyForIteration(0); err == nil {
		t.Fatal("expected nil chain key to be rejected")
	}
}
