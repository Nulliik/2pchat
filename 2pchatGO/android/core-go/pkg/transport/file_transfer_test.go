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
		"test caption",
		"🔥",
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
		"",
		"",
		func(payload []byte) error {
			time.Sleep(10 * time.Millisecond)
			return nil
		},
	)

	if err == nil {
		t.Fatal("expected error due to transfer cancellation, got nil")
	}
}

func TestReceiveChunkOutOfOrderAndDuplicate(t *testing.T) {
	tmpDir := t.TempDir()
	testFilePath := filepath.Join(tmpDir, "sample.bin")
	testData := make([]byte, 256*1024) // 4 chunks of 64KB
	rand.Read(testData)

	if err := os.WriteFile(testFilePath, testData, 0600); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	mgr := NewFileTransferManager(nil)

	var metaPayload string
	var chunkPayloads []string

	err := mgr.SendFileStream(
		context.Background(),
		"peer_fp_abc",
		"msg_scramble",
		testFilePath,
		"sample.bin",
		"scrambled caption",
		"🚀",
		func(payload []byte) error {
			if metaPayload == "" {
				metaPayload = EncodeMetadataB64(payload)
			} else {
				chunkPayloads = append(chunkPayloads, EncodeMetadataB64(payload))
			}
			return nil
		},
	)
	if err != nil {
		t.Fatalf("SendFileStream failed: %v", err)
	}

	downloadsDir := filepath.Join(tmpDir, "downloads")
	receiver := NewFileTransferManager(nil)

	// Deliver metadata first
	res, err := receiver.ReceiveChunk("peer_fp_abc", "msg_scramble", metaPayload, downloadsDir)
	if err != nil || res != nil {
		t.Fatalf("unexpected metadata result: res=%v, err=%v", res, err)
	}

	// Deliver chunks scrambled: [2, 0, 1, 1 (duplicate), 3]
	scrambledIndices := []int{2, 0, 1, 1, 3}
	var assembled *AssembledFile

	for _, idx := range scrambledIndices {
		payloadB64 := chunkPayloads[idx]
		res, err := receiver.ReceiveChunk("peer_fp_abc", "msg_scramble", payloadB64, downloadsDir)
		if err != nil {
			t.Fatalf("ReceiveChunk failed on chunk %d: %v", idx, err)
		}
		if res != nil {
			assembled = res
		}
	}

	if assembled == nil {
		t.Fatal("expected file to be fully assembled and decrypted")
	}

	savedBytes, err := os.ReadFile(assembled.FilePath)
	if err != nil {
		t.Fatalf("failed to read assembled file: %v", err)
	}

	if !bytes.Equal(savedBytes, testData) {
		t.Fatal("assembled file plaintext does not match original data")
	}
}

func TestReapIncompleteTransfers(t *testing.T) {
	mgr := NewFileTransferManager(nil)

	mgr.inbound["stale_1"] = &InboundFileTransfer{
		MessageID: "stale_1",
		StartTime: time.Now().Add(-20 * time.Minute),
	}
	mgr.inbound["fresh_1"] = &InboundFileTransfer{
		MessageID: "fresh_1",
		StartTime: time.Now().Add(-2 * time.Minute),
	}

	reaped := mgr.ReapIncompleteTransfers(15 * time.Minute)
	if reaped != 1 {
		t.Fatalf("expected 1 reaped transfer, got %d", reaped)
	}

	if _, exists := mgr.inbound["stale_1"]; exists {
		t.Fatal("expected stale_1 to be reaped")
	}
	if _, exists := mgr.inbound["fresh_1"]; !exists {
		t.Fatal("expected fresh_1 to remain")
	}
}
