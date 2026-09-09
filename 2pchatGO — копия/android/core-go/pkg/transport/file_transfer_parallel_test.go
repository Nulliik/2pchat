package transport

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestFileTransfer_ParallelAndOutOfOrder(t *testing.T) {
	tmpDir := t.TempDir()
	srcFile := filepath.Join(tmpDir, "source.bin")
	downloadsDir := filepath.Join(tmpDir, "downloads")

	// Create 2 MB test file with random data (8 chunks of 256 KiB)
	fileData := make([]byte, 2*1024*1024)
	if _, err := rand.Read(fileData); err != nil {
		t.Fatalf("failed to generate random data: %v", err)
	}
	if err := os.WriteFile(srcFile, fileData, 0600); err != nil {
		t.Fatalf("failed to write source file: %v", err)
	}
	expectedHash := sha256.Sum256(fileData)

	var receivedFrames [][]byte
	var framesMu sync.Mutex

	ftmSender := NewFileTransferManager(nil)
	ftmReceiver := NewFileTransferManager(nil)

	messageID := "msg_parallel_test_1"
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// 1. Send file parallel with 4 workers
	err := ftmSender.SendFileStreamParallel(
		ctx,
		"peer_alice",
		messageID,
		srcFile,
		"parallel_test.bin",
		"Test Caption",
		"🚀",
		4,
		func(payload []byte) error {
			framesMu.Lock()
			receivedFrames = append(receivedFrames, payload)
			framesMu.Unlock()
			return nil
		},
	)
	if err != nil {
		t.Fatalf("SendFileStreamParallel failed: %v", err)
	}

	framesMu.Lock()
	totalFrames := len(receivedFrames)
	framesMu.Unlock()

	if totalFrames < 9 { // 1 metadata frame + 8 chunks
		t.Fatalf("expected at least 9 frames, got %d", totalFrames)
	}

	// 2. Feed frames to receiver OUT OF ORDER (simulate network jitter / reordering)
	// Metadata frame is frame 0
	metaFrame := receivedFrames[0]
	chunkFrames := receivedFrames[1:]

	// First send metadata
	assembled, err := ftmReceiver.ReceiveChunk("peer_alice", messageID, base64.StdEncoding.EncodeToString(metaFrame), downloadsDir)
	if err != nil {
		t.Fatalf("failed to receive metadata frame: %v", err)
	}
	if assembled != nil {
		t.Fatalf("assembled should be nil after metadata")
	}

	// Shuffle chunks: send odd indices first, then even indices
	var reordered [][]byte
	for i := 1; i < len(chunkFrames); i += 2 {
		reordered = append(reordered, chunkFrames[i])
	}
	for i := 0; i < len(chunkFrames); i += 2 {
		reordered = append(reordered, chunkFrames[i])
	}

	var finalAssembled *AssembledFile
	for _, chunk := range reordered {
		res, err := ftmReceiver.ReceiveChunk("peer_alice", messageID, base64.StdEncoding.EncodeToString(chunk), downloadsDir)
		if err != nil {
			t.Fatalf("ReceiveChunk failed on out-of-order chunk: %v", err)
		}
		if res != nil {
			finalAssembled = res
		}
	}

	if finalAssembled == nil {
		t.Fatalf("file should have been assembled after all chunks delivered")
	}

	// Verify assembled file content
	savedData, err := os.ReadFile(finalAssembled.FilePath)
	if err != nil {
		t.Fatalf("failed to read assembled file: %v", err)
	}

	if len(savedData) != len(fileData) {
		t.Fatalf("file size mismatch: expected %d, got %d", len(fileData), len(savedData))
	}

	actualHash := sha256.Sum256(savedData)
	if !bytes.Equal(actualHash[:], expectedHash[:]) {
		t.Fatalf("SHA-256 mismatch on assembled file")
	}
}

func TestFileTransfer_BitmaskPersistenceAndResume(t *testing.T) {
	tmpDir := t.TempDir()
	srcFile := filepath.Join(tmpDir, "source_resume.bin")
	downloadsDir := filepath.Join(tmpDir, "downloads_resume")

	fileData := make([]byte, 1024*1024) // 1 MB = 4 chunks
	_, _ = rand.Read(fileData)
	_ = os.WriteFile(srcFile, fileData, 0600)
	expectedHash := sha256.Sum256(fileData)

	var frames [][]byte
	ftmSender := NewFileTransferManager(nil)
	messageID := "msg_resume_test_2"

	_ = ftmSender.SendFileStream(
		context.Background(),
		"peer_bob",
		messageID,
		srcFile,
		"resume.bin",
		"",
		"",
		func(payload []byte) error {
			frames = append(frames, payload)
			return nil
		},
	)

	// Receiver 1 receives metadata and only chunk 0 and 2
	ftmReceiver1 := NewFileTransferManager(nil)
	_, _ = ftmReceiver1.ReceiveChunk("peer_bob", messageID, base64.StdEncoding.EncodeToString(frames[0]), downloadsDir)
	_, _ = ftmReceiver1.ReceiveChunk("peer_bob", messageID, base64.StdEncoding.EncodeToString(frames[1]), downloadsDir) // chunk 0
	_, _ = ftmReceiver1.ReceiveChunk("peer_bob", messageID, base64.StdEncoding.EncodeToString(frames[3]), downloadsDir) // chunk 2

	bm1 := ftmReceiver1.GetInboundBitmask(messageID)
	if bm1 == nil || bm1.Count() != 2 {
		t.Fatalf("expected 2 received chunks in bitmask")
	}
	missing := ftmReceiver1.GetInboundMissingChunks(messageID)
	if len(missing) != 2 || missing[0] != 1 || missing[1] != 3 {
		t.Fatalf("expected missing chunks [1, 3], got %v", missing)
	}

	// Receiver restarts (new instance of manager), loads bitmask from disk
	ftmReceiver2 := NewFileTransferManager(nil)
	// Receive remaining missing chunk 1 and 3
	_, _ = ftmReceiver2.ReceiveChunk("peer_bob", messageID, base64.StdEncoding.EncodeToString(frames[0]), downloadsDir) // meta
	_, _ = ftmReceiver2.ReceiveChunk("peer_bob", messageID, base64.StdEncoding.EncodeToString(frames[2]), downloadsDir) // chunk 1
	finalAssembled, err := ftmReceiver2.ReceiveChunk("peer_bob", messageID, base64.StdEncoding.EncodeToString(frames[4]), downloadsDir) // chunk 3

	if err != nil {
		t.Fatalf("failed on resumed chunks: %v", err)
	}
	if finalAssembled == nil {
		t.Fatalf("expected completed file after receiving missing chunks")
	}

	savedData, _ := os.ReadFile(finalAssembled.FilePath)
	actualHash := sha256.Sum256(savedData)
	if !bytes.Equal(actualHash[:], expectedHash[:]) {
		t.Fatalf("SHA-256 mismatch on resumed file")
	}
}
