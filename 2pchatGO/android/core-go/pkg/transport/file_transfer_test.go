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
	testData := make([]byte, 1024*1024) // 4 chunks of 256 KiB
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

func TestResumableFileTransfer(t *testing.T) {
	tmpDir := t.TempDir()
	testFilePath := filepath.Join(tmpDir, "resume_video.mp4")
	testData := make([]byte, 2*1024*1024) // 8 chunks of 256 KiB
	rand.Read(testData)

	if err := os.WriteFile(testFilePath, testData, 0600); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	downloadsDir := filepath.Join(tmpDir, "downloads")
	receiver := NewFileTransferManager(nil)

	// Step 1: Sender sends first half [0, 1, 2, 3]
	sender1 := NewFileTransferManager(nil)
	var metaB64 string
	var firstHalfChunks []string

	_ = sender1.SendFileStream(
		context.Background(),
		"peer_sender",
		"msg_resume_1",
		testFilePath,
		"resume_video.mp4",
		"video caption",
		"🎥",
		func(payload []byte) error {
			if metaB64 == "" {
				metaB64 = EncodeMetadataB64(payload)
			} else if len(firstHalfChunks) < 4 {
				firstHalfChunks = append(firstHalfChunks, EncodeMetadataB64(payload))
			}
			return nil
		},
	)

	// Deliver metadata to receiver
	res1, err := receiver.ReceiveChunk("peer_sender", "msg_resume_1", metaB64, downloadsDir)
	if err != nil || res1 != nil {
		t.Fatalf("unexpected meta receive result: %v", err)
	}

	// Deliver first 4 chunks
	for idx, chunkB64 := range firstHalfChunks {
		res, err := receiver.ReceiveChunk("peer_sender", "msg_resume_1", chunkB64, downloadsDir)
		if err != nil || res != nil {
			t.Fatalf("unexpected chunk %d receive result: %v", idx, err)
		}
	}

	// Step 2: Resume sender from chunk 4
	var secondHalfChunks []string

	err = sender1.SendFileStreamWithResume(
		context.Background(),
		"peer_sender",
		"msg_resume_1",
		testFilePath,
		"resume_video.mp4",
		"video caption",
		"🎥",
		4, // resume from chunk 4
		func(payload []byte) error {
			// Skip metadata frame on second half collection
			if meta, _ := DecodeMetadataJSON(payload); meta == nil {
				secondHalfChunks = append(secondHalfChunks, EncodeMetadataB64(payload))
			}
			return nil
		},
	)
	if err != nil {
		t.Fatalf("SendFileStreamWithResume failed: %v", err)
	}

	if len(secondHalfChunks) != 4 {
		t.Fatalf("expected 4 resumed chunks, got %d", len(secondHalfChunks))
	}

	// Deliver remaining 4 chunks to the receiver
	var finalAssembled *AssembledFile
	for idx, chunkB64 := range secondHalfChunks {
		res, err := receiver.ReceiveChunk("peer_sender", "msg_resume_1", chunkB64, downloadsDir)
		if err != nil {
			t.Fatalf("ReceiveChunk error on chunk %d: %v", idx+4, err)
		}
		if res != nil {
			finalAssembled = res
		}
	}

	if finalAssembled == nil {
		t.Fatal("expected file to be completely assembled after resume")
	}

	savedBytes, err := os.ReadFile(finalAssembled.FilePath)
	if err != nil {
		t.Fatalf("failed to read saved file: %v", err)
	}

	if !bytes.Equal(savedBytes, testData) {
		t.Fatal("resumed file plaintext does not match original data")
	}
}

func TestDiskBackedLowMemoryStreaming(t *testing.T) {
	tmpDir := t.TempDir()
	testFilePath := filepath.Join(tmpDir, "stream_audio.ogg")
	testData := make([]byte, 1024*1024) // 4 chunks of 256 KiB
	rand.Read(testData)

	if err := os.WriteFile(testFilePath, testData, 0600); err != nil {
		t.Fatalf("failed to write test file: %v", err)
	}

	downloadsDir := filepath.Join(tmpDir, "downloads")
	sender := NewFileTransferManager(nil)
	receiver := NewFileTransferManager(nil)

	var metaB64 string
	var chunkB64s []string

	err := sender.SendFileStream(
		context.Background(),
		"peer_sender",
		"msg_stream_1",
		testFilePath,
		"stream_audio.ogg",
		"voice note",
		"🎤",
		func(payload []byte) error {
			if metaB64 == "" {
				metaB64 = EncodeMetadataB64(payload)
			} else {
				chunkB64s = append(chunkB64s, EncodeMetadataB64(payload))
			}
			return nil
		},
	)
	if err != nil {
		t.Fatalf("SendFileStream failed: %v", err)
	}

	// Deliver metadata
	_, err = receiver.ReceiveChunk("peer_sender", "msg_stream_1", metaB64, downloadsDir)
	if err != nil {
		t.Fatalf("ReceiveChunk metadata failed: %v", err)
	}

	// Verify that .part file was created on disk immediately
	partPath := filepath.Join(downloadsDir, ".part_msg_stream_1")
	if _, err := os.Stat(partPath); err != nil {
		t.Fatalf("expected .part file to exist on disk during streaming, got: %v", err)
	}

	// Stream all chunks
	var finalAssembled *AssembledFile
	for idx, chunk := range chunkB64s {
		res, err := receiver.ReceiveChunk("peer_sender", "msg_stream_1", chunk, downloadsDir)
		if err != nil {
			t.Fatalf("ReceiveChunk chunk %d failed: %v", idx, err)
		}
		if res != nil {
			finalAssembled = res
		}
	}

	if finalAssembled == nil {
		t.Fatal("expected file to be assembled")
	}

	// Verify that .part file was atomically renamed (no longer exists under .part name)
	if _, err := os.Stat(partPath); !os.IsNotExist(err) {
		t.Fatal("expected .part file to be removed after assembly")
	}

	// Verify final file on disk matches original
	saved, err := os.ReadFile(finalAssembled.FilePath)
	if err != nil {
		t.Fatalf("failed to read saved file: %v", err)
	}
	if !bytes.Equal(saved, testData) {
		t.Fatal("saved file does not match original data")
	}
}
