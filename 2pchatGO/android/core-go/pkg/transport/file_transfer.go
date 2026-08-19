package transport

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// FileProgressCallback is invoked periodically as bytes are transmitted or received.
type FileProgressCallback func(peerFP string, messageID string, transferred int64, total int64, speedKbps float64)

// InboundFileTransfer holds the state of an incoming chunked file transfer.
type InboundFileTransfer struct {
	MessageID string
	PeerFP    string
	Meta      *FileMetadata
	Chunks    map[int][]byte
	Received  int64
	StartTime time.Time
}

// AssembledFile represents a completed and decrypted inbound file.
type AssembledFile struct {
	MessageID string
	FilePath  string
	FileName  string
	Caption   string
	FileSize  int64
}

// FileTransferManager manages active outbound and inbound chunked file transfers.
type FileTransferManager struct {
	mu           sync.RWMutex
	cancelTokens map[string]context.CancelFunc
	inbound      map[string]*InboundFileTransfer
	onProgress   FileProgressCallback
}

// NewFileTransferManager creates a new FileTransferManager.
func NewFileTransferManager(progressCb FileProgressCallback) *FileTransferManager {
	return &FileTransferManager{
		cancelTokens: make(map[string]context.CancelFunc),
		inbound:      make(map[string]*InboundFileTransfer),
		onProgress:   progressCb,
	}
}

// CancelTransfer cancels an ongoing file transfer task by messageId.
func (m *FileTransferManager) CancelTransfer(messageID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	if cancel, exists := m.cancelTokens[messageID]; exists {
		cancel()
		delete(m.cancelTokens, messageID)
		return true
	}
	return false
}

// SendFileStream reads a file from disk, encrypts it in 64KB chunks, and dispatches frames.
func (m *FileTransferManager) SendFileStream(
	ctx context.Context,
	peerFP string,
	messageID string,
	filePath string,
	fileName string,
	caption string,
	sendFrame func(payload []byte) error,
) error {
	fileInfo, err := os.Stat(filePath)
	if err != nil {
		return fmt.Errorf("failed to stat file %s: %w", filePath, err)
	}

	fileSize := fileInfo.Size()
	if fileName == "" {
		fileName = filepath.Base(filePath)
	}

	file, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("failed to open file %s: %w", filePath, err)
	}
	defer file.Close()

	// Register cancelable context for this transfer
	transferCtx, cancel := context.WithCancel(ctx)
	m.mu.Lock()
	m.cancelTokens[messageID] = cancel
	m.mu.Unlock()

	defer func() {
		m.mu.Lock()
		delete(m.cancelTokens, messageID)
		m.mu.Unlock()
	}()

	meta, chunkChan, err := EncryptFileStream(file, fileSize, fileName, caption, DefaultChunkSize)
	if err != nil {
		return fmt.Errorf("failed to initialize file stream encryption: %w", err)
	}

	// Send metadata frame first
	metaJSON, err := meta.EncodeMetadataJSON()
	if err != nil {
		return fmt.Errorf("failed to encode file metadata: %w", err)
	}

	if err := sendFrame(metaJSON); err != nil {
		return fmt.Errorf("failed to send file metadata frame: %w", err)
	}

	startTime := time.Now()
	var transferredBytes int64

	for {
		select {
		case <-transferCtx.Done():
			return errors.New("file transfer cancelled by user")
		case chunk, ok := <-chunkChan:
			if !ok {
				// All chunks sent successfully
				if m.onProgress != nil {
					elapsed := time.Since(startTime).Seconds()
					speed := 0.0
					if elapsed > 0 {
						speed = (float64(fileSize) * 8 / 1024) / elapsed
					}
					m.onProgress(peerFP, messageID, fileSize, fileSize, speed)
				}
				return nil
			}

			if chunk.Error != nil {
				return fmt.Errorf("stream chunk error at index %d: %w", chunk.Index, chunk.Error)
			}

			if err := sendFrame(chunk.Payload); err != nil {
				return fmt.Errorf("failed to transmit chunk %d: %w", chunk.Index, err)
			}

			transferredBytes += int64(len(chunk.Payload))
			if transferredBytes > fileSize {
				transferredBytes = fileSize
			}

			if m.onProgress != nil {
				elapsed := time.Since(startTime).Seconds()
				speed := 0.0
				if elapsed > 0 {
					speed = (float64(transferredBytes) * 8 / 1024) / elapsed
				}
				m.onProgress(peerFP, messageID, transferredBytes, fileSize, speed)
			}
		}
	}
}

// ReceiveChunk processes an incoming file chunk, reassembles when complete, and decrypts to disk.
func (m *FileTransferManager) ReceiveChunk(
	peerFP string,
	messageID string,
	payloadB64 string,
	downloadsDir string,
) (*AssembledFile, error) {
	if messageID == "" || payloadB64 == "" {
		return nil, errors.New("empty messageID or payload")
	}

	payload, err := base64.StdEncoding.DecodeString(payloadB64)
	if err != nil {
		return nil, fmt.Errorf("failed to decode base64 chunk payload: %w", err)
	}

	m.mu.Lock()
	transfer, exists := m.inbound[messageID]
	if !exists {
		transfer = &InboundFileTransfer{
			MessageID: messageID,
			PeerFP:    peerFP,
			Chunks:    make(map[int][]byte),
			StartTime: time.Now(),
		}
		m.inbound[messageID] = transfer
	}
	m.mu.Unlock()

	// If metadata is not set yet, check if this payload is the metadata frame
	if transfer.Meta == nil {
		meta, err := DecodeMetadataJSON(payload)
		if err == nil && meta != nil && meta.NumChunks > 0 {
			m.mu.Lock()
			transfer.Meta = meta
			m.mu.Unlock()
			if m.onProgress != nil {
				m.onProgress(peerFP, messageID, 0, meta.FileSize, 0)
			}
			return nil, nil
		}
	}

	if transfer.Meta == nil {
		return nil, errors.New("chunk arrived before file metadata")
	}

	m.mu.Lock()
	chunkIdx := len(transfer.Chunks)
	transfer.Chunks[chunkIdx] = payload
	transfer.Received += int64(len(payload))
	isComplete := len(transfer.Chunks) >= transfer.Meta.NumChunks
	m.mu.Unlock()

	if m.onProgress != nil {
		elapsed := time.Since(transfer.StartTime).Seconds()
		speed := 0.0
		if elapsed > 0 {
			speed = (float64(transfer.Received) * 8 / 1024) / elapsed
		}
		m.onProgress(peerFP, messageID, transfer.Received, transfer.Meta.FileSize, speed)
	}

	if isComplete {
		m.mu.Lock()
		delete(m.inbound, messageID)
		m.mu.Unlock()

		plaintext, err := DecryptFileChunks(transfer.Meta, transfer.Chunks)
		if err != nil {
			return nil, fmt.Errorf("failed to decrypt file chunks: %w", err)
		}

		if err := os.MkdirAll(downloadsDir, 0755); err != nil {
			return nil, fmt.Errorf("failed to create downloads directory: %w", err)
		}

		fileName := transfer.Meta.FileName
		if fileName == "" {
			fileName = fmt.Sprintf("file_%s", messageID)
		}
		fileName = filepath.Base(fileName)
		targetPath := filepath.Join(downloadsDir, fileName)

		// Handle name collisions cleanly
		if _, err := os.Stat(targetPath); err == nil {
			ext := filepath.Ext(fileName)
			stem := strings.TrimSuffix(fileName, ext)
			targetPath = filepath.Join(downloadsDir, fmt.Sprintf("%s_%d%s", stem, time.Now().UnixMilli(), ext))
		}

		if err := os.WriteFile(targetPath, plaintext, 0644); err != nil {
			return nil, fmt.Errorf("failed to write decrypted file to %s: %w", targetPath, err)
		}

		return &AssembledFile{
			MessageID: messageID,
			FilePath:  targetPath,
			FileName:  filepath.Base(targetPath),
			Caption:   transfer.Meta.Caption,
			FileSize:  int64(len(plaintext)),
		}, nil
	}

	return nil, nil
}
