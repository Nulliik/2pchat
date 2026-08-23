package transport

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
	"twopchat/core/pkg/crypto"
)

const (
	// DefaultChunkSize is 256 KiB.
	DefaultChunkSize = 256 * 1024
	// FileIDSize is 12 bytes.
	FileIDSize = 12
	// FileNoncePrefixSize is 16 bytes.
	FileNoncePrefixSize = 16
	// FileChunkFrameTypeV2 is intentionally distinct from the legacy Go
	// reliable-binary marker (0x02).
	FileChunkFrameTypeV2             = 0x03
	LegacyPythonFileChunkFrameTypeV1 = 0x02
	FileChunkHeaderSize              = 1 + FileIDSize + 4 + 4
	FileChunkFormatV2                = "binary-v2"
	LegacyFileChunkFormatV1          = "binary-v1"
	DefaultFileChunkAckWindow        = 4
)

// chunkBufferPool provides reusable 256 KiB buffers to minimize GC allocations during file streaming.
var chunkBufferPool = sync.Pool{
	New: func() any {
		b := make([]byte, DefaultChunkSize)
		return &b
	},
}

func getChunkBuffer(size int) *[]byte {
	if size == DefaultChunkSize {
		return chunkBufferPool.Get().(*[]byte)
	}
	b := make([]byte, size)
	return &b
}

func putChunkBuffer(bufPtr *[]byte, size int) {
	if bufPtr == nil {
		return
	}
	// Zeroize memory before returning buffer to pool per Rule §8
	crypto.Zeroize(*bufPtr)
	if size == DefaultChunkSize {
		chunkBufferPool.Put(bufPtr)
	}
}

// FileMetadata contains the encryption and integrity parameters for a transferred file.
type FileMetadata struct {
	Type            string `json:"type,omitempty"`
	ID              string `json:"id,omitempty"`
	MessageID       string `json:"message_id,omitempty"`
	FileID          []byte `json:"-"`
	FileKey         []byte `json:"-"`
	FileNoncePrefix []byte `json:"-"`
	FileSize        int64  `json:"file_size"`
	NumChunks       int    `json:"num_chunks"`
	FileHash        []byte `json:"-"`
	FileName        string `json:"file_name,omitempty"`
	Caption         string `json:"caption,omitempty"`
	Emoji           string `json:"emoji,omitempty"`
	ChunkSize       int    `json:"chunk_size,omitempty"`
	ChunkFormat     string `json:"chunk_format,omitempty"`
	AckWindow       int    `json:"ack_window,omitempty"`
	Timestamp       int64  `json:"timestamp,omitempty"`

	// Wire Base64 JSON fields
	FileIDB64          string `json:"file_id"`
	FileKeyB64         string `json:"file_key"`
	FileNoncePrefixB64 string `json:"file_nonce_prefix"`
	FileHashB64        string `json:"file_hash"`
}

type FileChunkFrame struct {
	VersionType byte
	FileID      []byte
	ChunkIndex  uint32
	Payload     []byte
}

// FileChunkAckID is byte-for-byte compatible with Python's
// file_chunk_ack_id implementation.
func FileChunkAckID(fileID []byte, chunkIndex uint32) (string, error) {
	if len(fileID) != FileIDSize {
		return "", fmt.Errorf("file ID must be %d bytes", FileIDSize)
	}
	return fmt.Sprintf("file:%s:%d", base64.RawURLEncoding.EncodeToString(fileID), chunkIndex), nil
}

func EncodeFileChunkFrame(fileID []byte, chunkIndex uint32, payload []byte) ([]byte, error) {
	if len(fileID) != FileIDSize {
		return nil, fmt.Errorf("file ID must be %d bytes", FileIDSize)
	}
	if uint64(len(payload)) > uint64(^uint32(0)) {
		return nil, errors.New("file chunk payload is too large")
	}
	frame := make([]byte, FileChunkHeaderSize+len(payload))
	frame[0] = FileChunkFrameTypeV2
	copy(frame[1:1+FileIDSize], fileID)
	binary.BigEndian.PutUint32(frame[1+FileIDSize:1+FileIDSize+4], chunkIndex)
	binary.BigEndian.PutUint32(frame[1+FileIDSize+4:FileChunkHeaderSize], uint32(len(payload)))
	copy(frame[FileChunkHeaderSize:], payload)
	return frame, nil
}

func DecodeFileChunkFrame(frame []byte) (*FileChunkFrame, error) {
	if len(frame) < FileChunkHeaderSize {
		return nil, errors.New("file chunk frame is shorter than its header")
	}
	if frame[0] != FileChunkFrameTypeV2 && frame[0] != LegacyPythonFileChunkFrameTypeV1 {
		return nil, fmt.Errorf("unsupported file chunk frame type: %d", frame[0])
	}
	payloadSize := int(binary.BigEndian.Uint32(frame[1+FileIDSize+4 : FileChunkHeaderSize]))
	if payloadSize != len(frame)-FileChunkHeaderSize {
		return nil, fmt.Errorf("file chunk length mismatch: declared %d, received %d", payloadSize, len(frame)-FileChunkHeaderSize)
	}
	fileID := append([]byte(nil), frame[1:1+FileIDSize]...)
	payload := append([]byte(nil), frame[FileChunkHeaderSize:]...)
	return &FileChunkFrame{
		VersionType: frame[0],
		FileID:      fileID,
		ChunkIndex:  binary.BigEndian.Uint32(frame[1+FileIDSize : 1+FileIDSize+4]),
		Payload:     payload,
	}, nil
}

// EncodeMetadataB64 converts byte slice to base64 string.
func EncodeMetadataB64(data []byte) string {
	return base64.StdEncoding.EncodeToString(data)
}

// EncodeMetadataJSON serializes FileMetadata to JSON matching Python _encode_metadata format.
func (m *FileMetadata) EncodeMetadataJSON() ([]byte, error) {
	if m.Type == "" {
		m.Type = "file_meta"
	}
	if m.ChunkSize == 0 {
		m.ChunkSize = DefaultChunkSize
	}
	if m.ChunkFormat == "" {
		m.ChunkFormat = FileChunkFormatV2
	}
	if m.AckWindow == 0 {
		m.AckWindow = DefaultFileChunkAckWindow
	}
	m.FileIDB64 = base64.StdEncoding.EncodeToString(m.FileID)
	m.FileKeyB64 = base64.StdEncoding.EncodeToString(m.FileKey)
	m.FileNoncePrefixB64 = base64.StdEncoding.EncodeToString(m.FileNoncePrefix)
	m.FileHashB64 = base64.StdEncoding.EncodeToString(m.FileHash)
	return json.Marshal(m)
}

// DecodeMetadataJSON deserializes FileMetadata from JSON matching Python _decode_metadata.
func DecodeMetadataJSON(data []byte) (*FileMetadata, error) {
	var m FileMetadata
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, fmt.Errorf("invalid file metadata JSON: %w", err)
	}

	var err error
	if m.FileID, err = base64.StdEncoding.DecodeString(m.FileIDB64); err != nil {
		return nil, errors.New("invalid file_id base64")
	}
	if m.FileKey, err = base64.StdEncoding.DecodeString(m.FileKeyB64); err != nil {
		return nil, errors.New("invalid file_key base64")
	}
	if m.FileNoncePrefix, err = base64.StdEncoding.DecodeString(m.FileNoncePrefixB64); err != nil {
		return nil, errors.New("invalid file_nonce_prefix base64")
	}
	if m.FileHash, err = base64.StdEncoding.DecodeString(m.FileHashB64); err != nil {
		return nil, errors.New("invalid file_hash base64")
	}
	if len(m.FileID) != FileIDSize {
		return nil, errors.New("invalid file_id size")
	}
	if m.ChunkSize == 0 {
		m.ChunkSize = DefaultChunkSize
	}
	if m.ChunkFormat == "" {
		m.ChunkFormat = LegacyFileChunkFormatV1
	}
	if m.ChunkSize <= 0 || m.ChunkSize > DefaultChunkSize {
		return nil, errors.New("invalid file chunk size")
	}
	if m.ChunkFormat != FileChunkFormatV2 && m.ChunkFormat != LegacyFileChunkFormatV1 {
		return nil, errors.New("unsupported file chunk format")
	}
	return &m, nil
}

// EncryptedChunk represents an individually encrypted slice of a file.
type EncryptedChunk struct {
	Index   int
	Payload []byte
	Error   error
}

// EncryptFileStream streams chunks from an io.Reader and yields encrypted chunks over a channel.
func EncryptFileStream(
	r io.Reader,
	fileSize int64,
	fileName string,
	caption string,
	emoji string,
	chunkSize int,
) (*FileMetadata, <-chan *EncryptedChunk, error) {
	if rs, ok := r.(io.ReadSeeker); ok {
		return EncryptFileStreamWithResume(rs, fileSize, fileName, caption, emoji, chunkSize, 0)
	}

	if chunkSize <= 0 {
		chunkSize = DefaultChunkSize
	}

	var fileID [FileIDSize]byte
	if _, err := rand.Read(fileID[:]); err != nil {
		return nil, nil, err
	}

	fileKey := make([]byte, crypto.SecretBoxKeySize)
	if _, err := rand.Read(fileKey); err != nil {
		return nil, nil, err
	}

	var noncePrefix [FileNoncePrefixSize]byte
	if _, err := rand.Read(noncePrefix[:]); err != nil {
		return nil, nil, err
	}

	numChunks := int((fileSize + int64(chunkSize) - 1) / int64(chunkSize))
	if numChunks == 0 {
		numChunks = 1
	}

	meta := &FileMetadata{
		FileID:          fileID[:],
		FileKey:         fileKey,
		FileNoncePrefix: noncePrefix[:],
		FileSize:        fileSize,
		NumChunks:       numChunks,
		FileName:        fileName,
		Caption:         caption,
		Emoji:           emoji,
	}

	chunkChan := make(chan *EncryptedChunk, 4)

	go func() {
		defer close(chunkChan)

		hasher := sha256.New()
		bufPtr := getChunkBuffer(chunkSize)
		defer putChunkBuffer(bufPtr, chunkSize)
		buf := *bufPtr
		chunkIndex := 0

		for {
			n, readErr := io.ReadFull(r, buf)
			if n > 0 {
				hasher.Write(buf[:n])

				// Nonce = 16-byte prefix + 8-byte big-endian uint64 chunk index = 24 bytes
				var nonce [crypto.SecretBoxNonceSize]byte
				copy(nonce[:FileNoncePrefixSize], meta.FileNoncePrefix)
				binary.BigEndian.PutUint64(nonce[FileNoncePrefixSize:], uint64(chunkIndex))

				encrypted, err := crypto.SecretBoxEncryptWithNonce(meta.FileKey, nonce[:], buf[:n])
				if err != nil {
					chunkChan <- &EncryptedChunk{Error: err}
					return
				}

				chunkChan <- &EncryptedChunk{
					Index:   chunkIndex,
					Payload: encrypted,
				}
				chunkIndex++
			}

			if readErr != nil {
				if errors.Is(readErr, io.EOF) || errors.Is(readErr, io.ErrUnexpectedEOF) {
					break
				}
				chunkChan <- &EncryptedChunk{Error: readErr}
				return
			}
		}

		meta.FileHash = hasher.Sum(nil)
	}()

	return meta, chunkChan, nil
}

// EncryptFileStreamWithResume streams chunks starting at startChunkIdx from an io.ReadSeeker.
func EncryptFileStreamWithResume(
	r io.ReadSeeker,
	fileSize int64,
	fileName string,
	caption string,
	emoji string,
	chunkSize int,
	startChunkIdx int,
) (*FileMetadata, <-chan *EncryptedChunk, error) {
	if chunkSize <= 0 {
		chunkSize = DefaultChunkSize
	}

	var fileID [FileIDSize]byte
	if _, err := rand.Read(fileID[:]); err != nil {
		return nil, nil, err
	}

	fileKey := make([]byte, crypto.SecretBoxKeySize)
	if _, err := rand.Read(fileKey); err != nil {
		return nil, nil, err
	}

	var noncePrefix [FileNoncePrefixSize]byte
	if _, err := rand.Read(noncePrefix[:]); err != nil {
		return nil, nil, err
	}

	numChunks := int((fileSize + int64(chunkSize) - 1) / int64(chunkSize))
	if numChunks == 0 {
		numChunks = 1
	}

	// Compute overall file SHA-256 hash
	hasher := sha256.New()
	if _, err := r.Seek(0, io.SeekStart); err == nil {
		_, _ = io.Copy(hasher, io.LimitReader(r, fileSize))
	}

	meta := &FileMetadata{
		FileID:          fileID[:],
		FileKey:         fileKey,
		FileNoncePrefix: noncePrefix[:],
		FileSize:        fileSize,
		NumChunks:       numChunks,
		FileHash:        hasher.Sum(nil),
		FileName:        fileName,
		Caption:         caption,
		Emoji:           emoji,
	}

	chunkChan := make(chan *EncryptedChunk, 4)

	go func() {
		defer close(chunkChan)

		if startChunkIdx > 0 {
			offset := int64(startChunkIdx) * int64(chunkSize)
			if _, err := r.Seek(offset, io.SeekStart); err != nil {
				chunkChan <- &EncryptedChunk{Error: err}
				return
			}
		} else {
			if _, err := r.Seek(0, io.SeekStart); err != nil {
				chunkChan <- &EncryptedChunk{Error: err}
				return
			}
		}

		bufPtr := getChunkBuffer(chunkSize)
		defer putChunkBuffer(bufPtr, chunkSize)
		buf := *bufPtr
		chunkIndex := startChunkIdx

		for chunkIndex < numChunks {
			n, readErr := io.ReadFull(r, buf)
			if n > 0 {
				// Nonce = 16-byte prefix + 8-byte big-endian uint64 chunk index = 24 bytes
				var nonce [crypto.SecretBoxNonceSize]byte
				copy(nonce[:FileNoncePrefixSize], meta.FileNoncePrefix)
				binary.BigEndian.PutUint64(nonce[FileNoncePrefixSize:], uint64(chunkIndex))

				encrypted, err := crypto.SecretBoxEncryptWithNonce(meta.FileKey, nonce[:], buf[:n])
				if err != nil {
					chunkChan <- &EncryptedChunk{Error: err}
					return
				}

				chunkChan <- &EncryptedChunk{
					Index:   chunkIndex,
					Payload: encrypted,
				}
				chunkIndex++
			}

			if readErr != nil {
				if errors.Is(readErr, io.EOF) || errors.Is(readErr, io.ErrUnexpectedEOF) {
					break
				}
				chunkChan <- &EncryptedChunk{Error: readErr}
				return
			}
		}
	}()

	return meta, chunkChan, nil
}

// EncryptFileStreamFromMeta streams chunks from r starting at startChunkIdx using an existing FileMetadata.
func EncryptFileStreamFromMeta(
	r io.ReadSeeker,
	meta *FileMetadata,
	chunkSize int,
	startChunkIdx int,
) (<-chan *EncryptedChunk, error) {
	if chunkSize <= 0 {
		chunkSize = DefaultChunkSize
	}

	chunkChan := make(chan *EncryptedChunk, 4)

	go func() {
		defer close(chunkChan)

		if startChunkIdx > 0 {
			offset := int64(startChunkIdx) * int64(chunkSize)
			if _, err := r.Seek(offset, io.SeekStart); err != nil {
				chunkChan <- &EncryptedChunk{Error: err}
				return
			}
		} else {
			if _, err := r.Seek(0, io.SeekStart); err != nil {
				chunkChan <- &EncryptedChunk{Error: err}
				return
			}
		}

		bufPtr := getChunkBuffer(chunkSize)
		defer putChunkBuffer(bufPtr, chunkSize)
		buf := *bufPtr
		chunkIndex := startChunkIdx

		for chunkIndex < meta.NumChunks {
			n, readErr := io.ReadFull(r, buf)
			if n > 0 {
				var nonce [crypto.SecretBoxNonceSize]byte
				copy(nonce[:FileNoncePrefixSize], meta.FileNoncePrefix)
				binary.BigEndian.PutUint64(nonce[FileNoncePrefixSize:], uint64(chunkIndex))

				encrypted, err := crypto.SecretBoxEncryptWithNonce(meta.FileKey, nonce[:], buf[:n])
				if err != nil {
					chunkChan <- &EncryptedChunk{Error: err}
					return
				}

				chunkChan <- &EncryptedChunk{
					Index:   chunkIndex,
					Payload: encrypted,
				}
				chunkIndex++
			}

			if readErr != nil {
				if errors.Is(readErr, io.EOF) || errors.Is(readErr, io.ErrUnexpectedEOF) {
					break
				}
				chunkChan <- &EncryptedChunk{Error: readErr}
				return
			}
		}
	}()

	return chunkChan, nil
}

// DecryptFileChunks reassembles a file from ordered encrypted chunks and validates integrity.
func DecryptFileChunks(meta *FileMetadata, chunks map[int][]byte) ([]byte, error) {
	if len(chunks) < meta.NumChunks {
		return nil, fmt.Errorf("missing chunks: got %d of %d", len(chunks), meta.NumChunks)
	}

	var assembled bytes.Buffer
	for i := 0; i < meta.NumChunks; i++ {
		encryptedChunk, exists := chunks[i]
		if !exists {
			return nil, fmt.Errorf("missing chunk index %d", i)
		}

		plaintext, err := crypto.SecretBoxDecrypt(meta.FileKey, encryptedChunk)
		if err != nil {
			return nil, fmt.Errorf("failed to decrypt chunk %d: %w", i, err)
		}
		assembled.Write(plaintext)
	}

	if len(meta.FileHash) > 0 {
		h := sha256.Sum256(assembled.Bytes())
		if !bytes.Equal(h[:], meta.FileHash) {
			return nil, errors.New("file SHA-256 checksum mismatch: data corrupted")
		}
	}

	return assembled.Bytes(), nil
}
