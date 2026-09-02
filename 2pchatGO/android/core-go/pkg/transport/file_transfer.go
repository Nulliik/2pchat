package transport

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"twopchat/core/pkg/crypto"
)

// FileProgressCallback is invoked periodically as bytes are transmitted or received.
type FileProgressCallback func(peerFP string, messageID string, transferred int64, total int64, speedKbps float64)

// InboundFileTransfer holds the state of an incoming chunked file transfer backed by a disk .part file.
type InboundFileTransfer struct {
	MessageID         string
	PeerFP            string
	Meta              *FileMetadata
	PartPath          string
	BitmaskPath       string
	PartFile          *os.File
	Bitmask           *TransferBitmask
	ReceivedBytes     int64
	StartTime         time.Time
	LastProgressTime  time.Time
	LastProgressBytes int64
}

// AssembledFile represents a completed and decrypted inbound file.
type AssembledFile struct {
	MessageID string
	FilePath  string
	FileName  string
	Caption   string
	Emoji     string
	FileSize  int64
}

// FileTransferManager manages active outbound and inbound chunked file transfers.
type FileTransferManager struct {
	mu           sync.RWMutex
	cancelTokens map[string]context.CancelFunc
	inbound      map[string]*InboundFileTransfer
	outbound     map[string]*FileMetadata
	onProgress   FileProgressCallback
}

// NewFileTransferManager creates a new FileTransferManager.
func NewFileTransferManager(progressCb FileProgressCallback) *FileTransferManager {
	return &FileTransferManager{
		cancelTokens: make(map[string]context.CancelFunc),
		inbound:      make(map[string]*InboundFileTransfer),
		outbound:     make(map[string]*FileMetadata),
		onProgress:   progressCb,
	}
}

// ReapIncompleteTransfers cleans up abandoned inbound transfers older than maxAge and removes stale .part and .bitmask files.
func (m *FileTransferManager) ReapIncompleteTransfers(maxAge time.Duration) int {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	reaped := 0
	for id, transfer := range m.inbound {
		if now.Sub(transfer.StartTime) > maxAge {
			if transfer.PartFile != nil {
				_ = transfer.PartFile.Close()
			}
			if transfer.PartPath != "" {
				_ = os.Remove(transfer.PartPath)
			}
			if transfer.BitmaskPath != "" {
				_ = os.Remove(transfer.BitmaskPath)
			}
			delete(m.inbound, id)
			delete(m.outbound, id)
			reaped++
		}
	}
	return reaped
}

// CancelTransfer cancels an ongoing file transfer task by messageId and frees associated .part and .bitmask files.
func (m *FileTransferManager) CancelTransfer(messageID string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	var found bool
	if cancel, exists := m.cancelTokens[messageID]; exists {
		cancel()
		delete(m.cancelTokens, messageID)
		found = true
	}
	if transfer, exists := m.inbound[messageID]; exists {
		if transfer.PartFile != nil {
			_ = transfer.PartFile.Close()
		}
		if transfer.PartPath != "" {
			_ = os.Remove(transfer.PartPath)
		}
		if transfer.BitmaskPath != "" {
			_ = os.Remove(transfer.BitmaskPath)
		}
		delete(m.inbound, messageID)
		found = true
	}
	delete(m.outbound, messageID)
	return found
}

// GetInboundBitmask returns the current TransferBitmask for an active or pending inbound transfer.
func (m *FileTransferManager) GetInboundBitmask(messageID string) *TransferBitmask {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if transfer, exists := m.inbound[messageID]; exists {
		return transfer.Bitmask
	}
	return nil
}

// GetInboundMissingChunks returns the list of missing chunk indices for an inbound transfer.
func (m *FileTransferManager) GetInboundMissingChunks(messageID string) []int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if transfer, exists := m.inbound[messageID]; exists && transfer.Bitmask != nil {
		return transfer.Bitmask.MissingIndices()
	}
	return nil
}

// SendFileStream reads a file from disk, encrypts it in 256 KiB chunks, and dispatches frames.
func (m *FileTransferManager) SendFileStream(
	ctx context.Context,
	peerFP string,
	messageID string,
	filePath string,
	fileName string,
	caption string,
	emoji string,
	sendFrame func(payload []byte) error,
) error {
	return m.SendFileStreamWithResume(ctx, peerFP, messageID, filePath, fileName, caption, emoji, 0, sendFrame)
}

// SendFileStreamWithResume reads a file from disk starting from startChunkIdx, encrypts in 256 KiB chunks, and dispatches frames.
func (m *FileTransferManager) SendFileStreamWithResume(
	ctx context.Context,
	peerFP string,
	messageID string,
	filePath string,
	fileName string,
	caption string,
	emoji string,
	startChunkIdx int,
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

	m.mu.RLock()
	cachedMeta, metaExists := m.outbound[messageID]
	m.mu.RUnlock()

	var meta *FileMetadata
	var chunkChan <-chan *EncryptedChunk

	if metaExists && cachedMeta != nil {
		meta = cachedMeta
		ch, err := EncryptFileStreamFromMeta(file, meta, DefaultChunkSize, startChunkIdx)
		if err != nil {
			return fmt.Errorf("failed to resume file stream encryption: %w", err)
		}
		chunkChan = ch
	} else {
		newMeta, ch, err := EncryptFileStreamWithResume(file, fileSize, fileName, caption, emoji, DefaultChunkSize, startChunkIdx)
		if err != nil {
			return fmt.Errorf("failed to initialize file stream encryption: %w", err)
		}
		meta = newMeta
		chunkChan = ch
		m.mu.Lock()
		m.outbound[messageID] = meta
		m.mu.Unlock()
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
	transferredBytes := int64(startChunkIdx) * int64(DefaultChunkSize)
	if transferredBytes > fileSize {
		transferredBytes = fileSize
	}
	lastReportTime := startTime
	lastReportBytes := transferredBytes

	for {
		if transferCtx.Err() != nil {
			return errors.New("file transfer cancelled by user")
		}
		select {
		case <-transferCtx.Done():
			return errors.New("file transfer cancelled by user")
		case chunk, ok := <-chunkChan:
			if transferCtx.Err() != nil {
				return errors.New("file transfer cancelled by user")
			}
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
				now := time.Now()
				isFirst := chunk.Index == 0
				isLast := transferredBytes >= fileSize
				timeDelta := now.Sub(lastReportTime)
				byteDelta := transferredBytes - lastReportBytes
				minByteDelta := fileSize / 100 // 1%
				if minByteDelta < int64(DefaultChunkSize) {
					minByteDelta = int64(DefaultChunkSize)
				}

				if isFirst || isLast || (timeDelta >= 100*time.Millisecond && (byteDelta >= minByteDelta || timeDelta >= 500*time.Millisecond)) {
					elapsed := now.Sub(startTime).Seconds()
					speed := 0.0
					if elapsed > 0 {
						speed = (float64(transferredBytes) * 8 / 1024) / elapsed
					}
					m.onProgress(peerFP, messageID, transferredBytes, fileSize, speed)
					lastReportTime = now
					lastReportBytes = transferredBytes
				}
			}
		}
	}
}

// SendFileStreamParallel reads a file and encrypts/dispatches chunks concurrently using a worker pool.
func (m *FileTransferManager) SendFileStreamParallel(
	ctx context.Context,
	peerFP string,
	messageID string,
	filePath string,
	fileName string,
	caption string,
	emoji string,
	numWorkers int,
	sendFrame func(payload []byte) error,
) error {
	if numWorkers <= 0 {
		numWorkers = 4
	}

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

	transferCtx, cancel := context.WithCancel(ctx)
	m.mu.Lock()
	m.cancelTokens[messageID] = cancel
	m.mu.Unlock()

	defer func() {
		m.mu.Lock()
		delete(m.cancelTokens, messageID)
		m.mu.Unlock()
	}()

	m.mu.RLock()
	cachedMeta, metaExists := m.outbound[messageID]
	m.mu.RUnlock()

	var meta *FileMetadata
	if metaExists && cachedMeta != nil {
		meta = cachedMeta
	} else {
		newMeta, _, err := EncryptFileStreamWithResume(file, fileSize, fileName, caption, emoji, DefaultChunkSize, 0)
		if err != nil {
			return fmt.Errorf("failed to initialize file metadata: %w", err)
		}
		meta = newMeta
		m.mu.Lock()
		m.outbound[messageID] = meta
		m.mu.Unlock()
	}

	metaJSON, err := meta.EncodeMetadataJSON()
	if err != nil {
		return fmt.Errorf("failed to encode file metadata: %w", err)
	}

	var sendMu sync.Mutex
	safeSend := func(payload []byte) error {
		sendMu.Lock()
		defer sendMu.Unlock()
		return sendFrame(payload)
	}

	if err := safeSend(metaJSON); err != nil {
		return fmt.Errorf("failed to send file metadata frame: %w", err)
	}

	startTime := time.Now()
	var transferredBytes int64
	var progressMu sync.Mutex
	lastReportTime := startTime
	lastReportBytes := int64(0)

	type chunkTask struct {
		index int
	}

	taskChan := make(chan chunkTask, meta.NumChunks)
	for i := 0; i < meta.NumChunks; i++ {
		taskChan <- chunkTask{index: i}
	}
	close(taskChan)

	var wg sync.WaitGroup
	errChan := make(chan error, numWorkers)

	for w := 0; w < numWorkers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			workerFile, openErr := os.Open(filePath)
			if openErr != nil {
				select {
				case errChan <- openErr:
				default:
				}
				return
			}
			defer workerFile.Close()

			bufPtr := getChunkBuffer(DefaultChunkSize)
			defer putChunkBuffer(bufPtr, DefaultChunkSize)
			buf := *bufPtr

			for task := range taskChan {
				if transferCtx.Err() != nil {
					return
				}

				offset := int64(task.index) * int64(DefaultChunkSize)
				n, readErr := workerFile.ReadAt(buf, offset)
				if n > 0 {
					var nonce [crypto.SecretBoxNonceSize]byte
					copy(nonce[:FileNoncePrefixSize], meta.FileNoncePrefix)
					binary.BigEndian.PutUint64(nonce[FileNoncePrefixSize:], uint64(task.index))

					encrypted, encErr := crypto.SecretBoxEncryptWithNonce(meta.FileKey, nonce[:], buf[:n])
					if encErr != nil {
						select {
						case errChan <- encErr:
						default:
						}
						cancel()
						return
					}

					if sendErr := safeSend(encrypted); sendErr != nil {
						select {
						case errChan <- sendErr:
						default:
						}
						cancel()
						return
					}

					progressMu.Lock()
					transferredBytes += int64(n)
					curTransferred := transferredBytes
					now := time.Now()
					timeDelta := now.Sub(lastReportTime)
					byteDelta := curTransferred - lastReportBytes
					isComplete := curTransferred >= fileSize
					isFirst := task.index == 0
					minByteDelta := fileSize / 100
					if minByteDelta < int64(DefaultChunkSize) {
						minByteDelta = int64(DefaultChunkSize)
					}

					shouldReport := m.onProgress != nil && (isFirst || isComplete || (timeDelta >= 100*time.Millisecond && (byteDelta >= minByteDelta || timeDelta >= 500*time.Millisecond)))
					if shouldReport {
						lastReportTime = now
						lastReportBytes = curTransferred
					}
					progressMu.Unlock()

					if shouldReport {
						elapsed := now.Sub(startTime).Seconds()
						speed := 0.0
						if elapsed > 0 {
							speed = (float64(curTransferred) * 8 / 1024) / elapsed
						}
						m.onProgress(peerFP, messageID, curTransferred, fileSize, speed)
					}
				}

				if readErr != nil && !errors.Is(readErr, io.EOF) {
					select {
					case errChan <- readErr:
					default:
					}
					cancel()
					return
				}
			}
		}()
	}

	wg.Wait()

	select {
	case err := <-errChan:
		return err
	default:
	}

	if transferCtx.Err() != nil {
		return errors.New("file transfer cancelled by user")
	}

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

// ReceiveChunk processes an incoming file chunk, streams decrypted slice to disk (.part file), and renames when complete.
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
			if err := os.MkdirAll(downloadsDir, 0755); err == nil {
				transfer.PartPath = filepath.Join(downloadsDir, fmt.Sprintf(".part_%s", messageID))
				transfer.BitmaskPath = filepath.Join(downloadsDir, fmt.Sprintf(".part_%s.bitmask", messageID))
				partFile, err := os.OpenFile(transfer.PartPath, os.O_CREATE|os.O_RDWR, 0600)
				if err == nil {
					transfer.PartFile = partFile
				}
				if loadedBM, loadErr := LoadBitmaskFromFile(transfer.BitmaskPath); loadErr == nil && loadedBM.NumChunks() == meta.NumChunks {
					transfer.Bitmask = loadedBM
				} else {
					transfer.Bitmask = NewTransferBitmask(meta.NumChunks)
				}
			}
			m.mu.Unlock()
			if m.onProgress != nil {
				targetMsgID := meta.MessageID
				if targetMsgID == "" {
					targetMsgID = meta.ID
				}
				if targetMsgID != "" && targetMsgID != messageID {
					m.onProgress(peerFP, targetMsgID, 0, meta.FileSize, 0)
				}
				m.onProgress(peerFP, messageID, 0, meta.FileSize, 0)
			}
			return nil, nil
		}
	}

	if transfer.Meta == nil {
		return nil, errors.New("chunk arrived before file metadata")
	}

	m.mu.Lock()
	if transfer.Bitmask == nil {
		transfer.Bitmask = NewTransferBitmask(transfer.Meta.NumChunks)
	}
	if transfer.PartFile == nil {
		if err := os.MkdirAll(downloadsDir, 0755); err == nil {
			transfer.PartPath = filepath.Join(downloadsDir, fmt.Sprintf(".part_%s", messageID))
			transfer.BitmaskPath = filepath.Join(downloadsDir, fmt.Sprintf(".part_%s.bitmask", messageID))
			partFile, err := os.OpenFile(transfer.PartPath, os.O_CREATE|os.O_RDWR, 0600)
			if err == nil {
				transfer.PartFile = partFile
			}
		}
	}

	var chunkIdx int
	if len(payload) >= crypto.SecretBoxNonceSize {
		chunkIdx = int(binary.BigEndian.Uint64(payload[FileNoncePrefixSize:crypto.SecretBoxNonceSize]))
	} else {
		chunkIdx = transfer.Bitmask.Count()
	}

	if !transfer.Bitmask.IsSet(chunkIdx) {
		// Decrypt chunk on the fly
		plaintext, err := crypto.SecretBoxDecrypt(transfer.Meta.FileKey, payload)
		if err != nil {
			m.mu.Unlock()
			return nil, fmt.Errorf("failed to decrypt chunk %d: %w", chunkIdx, err)
		}

		if transfer.PartFile != nil {
			chunkSize := transfer.Meta.ChunkSize
			if chunkSize <= 0 {
				chunkSize = DefaultChunkSize
			}
			offset := int64(chunkIdx) * int64(chunkSize)
			if _, err := transfer.PartFile.WriteAt(plaintext, offset); err != nil {
				crypto.Zeroize(plaintext)
				m.mu.Unlock()
				return nil, fmt.Errorf("failed to write chunk %d to part file: %w", chunkIdx, err)
			}
		}

		if transfer.Bitmask.Set(chunkIdx) {
			transfer.ReceivedBytes += int64(len(plaintext))
			if transfer.BitmaskPath != "" {
				_ = transfer.Bitmask.SaveToFile(transfer.BitmaskPath)
			}
		}
		crypto.Zeroize(plaintext)
	}

	isComplete := transfer.Bitmask.IsComplete()
	receivedBytes := transfer.ReceivedBytes
	totalBytes := transfer.Meta.FileSize
	partFile := transfer.PartFile
	partPath := transfer.PartPath
	bitmaskPath := transfer.BitmaskPath
	meta := transfer.Meta
	startTime := transfer.StartTime
	lastProgressTime := transfer.LastProgressTime
	lastProgressBytes := transfer.LastProgressBytes

	now := time.Now()
	isFirst := transfer.Bitmask.Count() == 1
	timeDelta := now.Sub(lastProgressTime)
	byteDelta := receivedBytes - lastProgressBytes
	minByteDelta := totalBytes / 100 // 1%
	if minByteDelta < int64(DefaultChunkSize) {
		minByteDelta = int64(DefaultChunkSize)
	}

	shouldReportProgress := m.onProgress != nil && (isFirst || isComplete || (timeDelta >= 100*time.Millisecond && (byteDelta >= minByteDelta || timeDelta >= 500*time.Millisecond)))
	if shouldReportProgress {
		transfer.LastProgressTime = now
		transfer.LastProgressBytes = receivedBytes
	}
	m.mu.Unlock()

	if shouldReportProgress {
		elapsed := now.Sub(startTime).Seconds()
		speed := 0.0
		if elapsed > 0 {
			speed = (float64(receivedBytes) * 8 / 1024) / elapsed
		}
		if meta != nil {
			targetMsgID := meta.MessageID
			if targetMsgID == "" {
				targetMsgID = meta.ID
			}
			if targetMsgID != "" && targetMsgID != messageID {
				m.onProgress(peerFP, targetMsgID, receivedBytes, totalBytes, speed)
			}
		}
		m.onProgress(peerFP, messageID, receivedBytes, totalBytes, speed)
	}

	if isComplete {
		m.mu.Lock()
		delete(m.inbound, messageID)
		m.mu.Unlock()

		if bitmaskPath != "" {
			_ = os.Remove(bitmaskPath)
		}

		if partFile != nil {
			_ = partFile.Sync()
			if _, err := partFile.Seek(0, io.SeekStart); err == nil && len(meta.FileHash) > 0 {
				hasher := sha256.New()
				if _, err := io.Copy(hasher, io.LimitReader(partFile, meta.FileSize)); err == nil {
					calculatedHash := hasher.Sum(nil)
					if !bytes.Equal(calculatedHash, meta.FileHash) {
						_ = partFile.Close()
						_ = os.Remove(partPath)
						return nil, errors.New("file SHA-256 checksum mismatch: data corrupted")
					}
				}
			}
			_ = partFile.Close()
		}

		if err := os.MkdirAll(downloadsDir, 0755); err != nil {
			return nil, fmt.Errorf("failed to create downloads directory: %w", err)
		}

		fileName := meta.FileName
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

		// Atomically move .part file to final destination
		if err := os.Rename(partPath, targetPath); err != nil {
			if cpErr := copyFile(partPath, targetPath); cpErr != nil {
				return nil, fmt.Errorf("failed to move completed file to %s: %w", targetPath, cpErr)
			}
			_ = os.Remove(partPath)
		}

		// Truncate to exact fileSize in case part file had padding
		_ = os.Truncate(targetPath, meta.FileSize)

		assembledMessageID := meta.MessageID
		if assembledMessageID == "" {
			assembledMessageID = meta.ID
		}
		if assembledMessageID == "" {
			assembledMessageID = messageID
		}
		if m.onProgress != nil {
			elapsed := time.Since(startTime).Seconds()
			speed := 0.0
			if elapsed > 0 {
				speed = (float64(meta.FileSize) * 8 / 1024) / elapsed
			}
			if assembledMessageID != "" && assembledMessageID != messageID {
				m.onProgress(peerFP, assembledMessageID, meta.FileSize, meta.FileSize, speed)
			}
			m.onProgress(peerFP, messageID, meta.FileSize, meta.FileSize, speed)
		}
		return &AssembledFile{
			MessageID: assembledMessageID,
			FilePath:  targetPath,
			FileName:  filepath.Base(targetPath),
			Caption:   meta.Caption,
			Emoji:     meta.Emoji,
			FileSize:  meta.FileSize,
		}, nil
	}

	return nil, nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()

	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer out.Close()

	_, err = io.Copy(out, in)
	return err
}
