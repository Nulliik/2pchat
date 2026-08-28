package transport

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestTransferBitmask_BasicOperations(t *testing.T) {
	bm := NewTransferBitmask(10)
	if bm.NumChunks() != 10 {
		t.Fatalf("expected 10 chunks, got %d", bm.NumChunks())
	}
	if bm.Count() != 0 {
		t.Fatalf("expected count 0, got %d", bm.Count())
	}
	if bm.IsComplete() {
		t.Fatalf("expected not complete")
	}

	// Set chunk 0, 3, 9
	if !bm.Set(0) {
		t.Errorf("failed to set chunk 0")
	}
	if bm.Set(0) {
		t.Errorf("setting already set chunk 0 should return false")
	}
	if !bm.Set(3) {
		t.Errorf("failed to set chunk 3")
	}
	if !bm.Set(9) {
		t.Errorf("failed to set chunk 9")
	}

	if bm.Count() != 3 {
		t.Fatalf("expected count 3, got %d", bm.Count())
	}

	if !bm.IsSet(0) || !bm.IsSet(3) || !bm.IsSet(9) {
		t.Errorf("expected 0, 3, 9 to be set")
	}
	if bm.IsSet(1) || bm.IsSet(2) || bm.IsSet(8) {
		t.Errorf("unexpected chunk set")
	}

	missing := bm.MissingIndices()
	expectedMissing := []int{1, 2, 4, 5, 6, 7, 8}
	if !reflect.DeepEqual(missing, expectedMissing) {
		t.Fatalf("expected missing %v, got %v", expectedMissing, missing)
	}

	// Complete the rest
	for _, idx := range expectedMissing {
		bm.Set(idx)
	}

	if !bm.IsComplete() {
		t.Fatalf("expected complete bitmask")
	}
	if len(bm.MissingIndices()) != 0 {
		t.Fatalf("expected 0 missing indices")
	}
}

func TestTransferBitmask_SerializationAndFile(t *testing.T) {
	bm := NewTransferBitmask(35)
	bm.Set(0)
	bm.Set(7)
	bm.Set(8)
	bm.Set(34)

	raw := bm.ToBytes()
	restored, err := FromBytes(raw)
	if err != nil {
		t.Fatalf("FromBytes failed: %v", err)
	}

	if restored.NumChunks() != 35 || restored.Count() != 4 {
		t.Fatalf("restored bitmask mismatch: num=%d, count=%d", restored.NumChunks(), restored.Count())
	}
	if !restored.IsSet(0) || !restored.IsSet(7) || !restored.IsSet(8) || !restored.IsSet(34) {
		t.Fatalf("restored bits incorrect")
	}

	// Test File Persistence
	tmpDir := t.TempDir()
	path := filepath.Join(tmpDir, "test.bitmask")

	if err := bm.SaveToFile(path); err != nil {
		t.Fatalf("SaveToFile failed: %v", err)
	}

	loaded, err := LoadBitmaskFromFile(path)
	if err != nil {
		t.Fatalf("LoadBitmaskFromFile failed: %v", err)
	}

	if loaded.NumChunks() != 35 || loaded.Count() != 4 {
		t.Fatalf("loaded bitmask mismatch")
	}

	// Corrupt file test
	_ = os.WriteFile(path, []byte{1, 2}, 0600)
	if _, err := LoadBitmaskFromFile(path); err == nil {
		t.Fatalf("expected error on corrupt bitmask file")
	}
}
