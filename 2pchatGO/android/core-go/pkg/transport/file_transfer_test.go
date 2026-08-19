package transport

import (
	"bytes"
	"context"
	"crypto/rand"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestFileTransferManagerSendAndCancel(t *testing.T) {
	// Create a temporary 256KB test file
	tmpDir := t.TempDir()
	testFilePath := filepath.Join(tmpDir, "testfile.bin")
	testData := make([]byte, 256*1024)
	rand.Read(testData)

	if err := os.WriteFile(testFilePath, testData, 0600); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	var progressEvents int32
	var lastTransferred int64
	var lastTotal int64

	mgr := NewFileTransferManager(func(peerFP string, msgID string, transferred int64, total int64, speed float64) {
		atomic.AddInt32(&progressEvents, 1)
		atomic.StoreInt64(&lastTransferred, transferred)
		atomic.StoreInt64(&lastTotal, total)
	})

	var framesReceived [][]byte
	var decodedMeta *FileMetadata
	receivedChunks := make(map[int][]byte)

	err := mgr.SendFileStream(
		context.Background(),
		"peer_fp_123",
		"msg_id_456",
		testFilePath,
		"testfile.bin",
		func(payload []byte) error {
			if decodedMeta == nil {
				meta, err := DecodeMetadataJSON(payload)
				if err != nil {
					return err
				}
				decodedMeta = meta
			} else {
				idx := len(receivedChunks)
				receivedChunks[idx] = payload
			}
			framesReceived = append(framesReceived, payload)
			return nil
		},
	)

	if err != nil {
		t.Fatalf("SendFileStream failed: %v", err)
	}

	if decodedMeta == nil {
		t.Fatal("expected metadata frame to be received")
	}

	if decodedMeta.FileSize != int64(len(testData)) {
		t.Fatalf("expected file size %d, got %d", len(testData), decodedMeta.FileSize)
	}

	// Decrypt received chunks and verify full byte equality
	reassembled, err := DecryptFileChunks(decodedMeta, receivedChunks)
	if err != nil {
		t.Fatalf("DecryptFileChunks failed: %v", err)
	}

	if !bytes.Equal(reassembled, testData) {
		t.Fatal("reassembled file data does not match original plaintext")
	}

	if atomic.LoadInt32(&progressEvents) == 0 {
		t.Fatal("expected progress events to be emitted")
	}

	if atomic.LoadInt64(&lastTransferred) != int64(len(testData)) {
		t.Fatalf("expected final transferred %d, got %d", len(testData), lastTransferred)
	}
}

func TestFileTransferManagerCancellation(t *testing.T) {
	tmpDir := t.TempDir()
	testFilePath := filepath.Join(tmpDir, "largefile.bin")
	testData := make([]byte, 1024*1024) // 1 MB
	rand.Read(testData)

	if err := os.WriteFile(testFilePath, testData, 0600); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	mgr := NewFileTransferManager(nil)

	go func() {
		time.Sleep(5 * time.Millisecond)
		mgr.CancelTransfer("cancel_msg_id")
	}()

	err := mgr.SendFileStream(
		context.Background(),
		"peer_fp_123",
		"cancel_msg_id",
		testFilePath,
		"largefile.bin",
		func(payload []byte) error {
			time.Sleep(10 * time.Millisecond)
			return nil
		},
	)

	if err == nil {
		t.Fatal("expected error due to transfer cancellation, got nil")
	}
}
