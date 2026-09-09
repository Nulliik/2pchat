package transport

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
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

func TestDefaultChunkSizeIs256KiB(t *testing.T) {
	if DefaultChunkSize != 256*1024 {
		t.Fatalf("DefaultChunkSize = %d, want %d", DefaultChunkSize, 256*1024)
	}
}

func TestBinaryV2FileChunkFrameMatchesPythonLayout(t *testing.T) {
	fileID := []byte("abcdefghijkl")
	payload := []byte{0xde, 0xad, 0xbe, 0xef}
	encoded, err := EncodeFileChunkFrame(fileID, 15, payload)
	if err != nil {
		t.Fatal(err)
	}
	const wantHex = "036162636465666768696a6b6c0000000f00000004deadbeef"
	if got := fmt.Sprintf("%x", encoded); got != wantHex {
		t.Fatalf("binary-v2 frame = %s, want %s", got, wantHex)
	}

	decoded, err := DecodeFileChunkFrame(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if decoded.ChunkIndex != 15 || !bytes.Equal(decoded.FileID, fileID) || !bytes.Equal(decoded.Payload, payload) {
		t.Fatalf("decoded frame mismatch: %#v", decoded)
	}
	ackID, err := FileChunkAckID(fileID, 15)
	if err != nil {
		t.Fatal(err)
	}
	if ackID != "file:YWJjZGVmZ2hpamts:15" {
		t.Fatalf("ack ID = %q", ackID)
	}
}

func TestChunkBufferPoolZeroization(t *testing.T) {
	bufPtr := getChunkBuffer(DefaultChunkSize)
	for i := range *bufPtr {
		(*bufPtr)[i] = 0xAA
	}

	putChunkBuffer(bufPtr, DefaultChunkSize)

	// Fetch buffer again from pool and verify it was sanitized
	newBufPtr := getChunkBuffer(DefaultChunkSize)
	defer putChunkBuffer(newBufPtr, DefaultChunkSize)

	for i, b := range *newBufPtr {
		if b != 0 {
			t.Fatalf("Buffer not zeroized at byte index %d (got 0x%02x)", i, b)
		}
	}
}

func BenchmarkEncryptFileStream(b *testing.B) {
	fileSize := 1024 * 1024 // 1 MB
	data := make([]byte, fileSize)
	rand.Read(data)

	b.ResetTimer()
	b.ReportAllocs()

	for b.Loop() {
		r := bytes.NewReader(data)
		_, chunkChan, err := EncryptFileStream(r, int64(fileSize), "bench.bin", "", "", DefaultChunkSize)
		if err != nil {
			b.Fatalf("EncryptFileStream failed: %v", err)
		}
		for chunk := range chunkChan {
			if chunk.Error != nil {
				b.Fatalf("Chunk error: %v", chunk.Error)
			}
		}
	}
}
