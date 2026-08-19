package transport

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"testing"
)

func TestFileStreamingRoundtrip(t *testing.T) {
	// Create 250 KB mock file data
	originalSize := 250 * 1024
	originalData := make([]byte, originalSize)
	rand.Read(originalData)

	r := bytes.NewReader(originalData)
	meta, chunkChan, err := EncryptFileStream(r, int64(originalSize), "test_doc.pdf", "test caption", "🚀", 64*1024)
	if err != nil {
		t.Fatalf("EncryptFileStream failed: %v", err)
	}

	chunks := make(map[int][]byte)
	for chunk := range chunkChan {
		if chunk.Error != nil {
			t.Fatalf("Error streaming chunk %d: %v", chunk.Index, chunk.Error)
		}
		chunks[chunk.Index] = chunk.Payload
	}

	// Update meta hash from recomputed data for validation
	h := sha256.Sum256(originalData)
	meta.FileHash = h[:]

	// Test metadata JSON serialization
	metaJSON, err := meta.EncodeMetadataJSON()
	if err != nil {
		t.Fatalf("EncodeMetadataJSON failed: %v", err)
	}

	decodedMeta, err := DecodeMetadataJSON(metaJSON)
	if err != nil {
		t.Fatalf("DecodeMetadataJSON failed: %v", err)
	}

	reassembled, err := DecryptFileChunks(decodedMeta, chunks)
	if err != nil {
		t.Fatalf("DecryptFileChunks failed: %v", err)
	}

	if !bytes.Equal(originalData, reassembled) {
		t.Fatal("Reassembled file data does not match original")
	}
}
