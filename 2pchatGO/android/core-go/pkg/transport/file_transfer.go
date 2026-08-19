package transport

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// FileProgressCallback is invoked periodically as bytes are transmitted or received.
type FileProgressCallback func(peerFP string, messageID string, transferred int64, total int64, speedKbps float64)

// FileTransferManager manages active outbound and inbound chunked file transfers.
type FileTransferManager struct {
	mu           sync.RWMutex
	cancelTokens map[string]context.CancelFunc
	onProgress   FileProgressCallback
}

// NewFileTransferManager creates a new FileTransferManager.
func NewFileTransferManager(progressCb FileProgressCallback) *FileTransferManager {
	return &FileTransferManager{
		cancelTokens: make(map[string]context.CancelFunc),
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

	meta, chunkChan, err := EncryptFileStream(file, fileSize, fileName, DefaultChunkSize)
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
