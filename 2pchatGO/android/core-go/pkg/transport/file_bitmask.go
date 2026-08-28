package transport

import (
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"sync"
)

// TransferBitmask represents a thread-safe chunk completion bitmap for large file transfers.
type TransferBitmask struct {
	mu        sync.RWMutex
	numChunks int
	bits      []byte
	count     int
}

// NewTransferBitmask initializes a TransferBitmask for a given total number of chunks.
func NewTransferBitmask(numChunks int) *TransferBitmask {
	if numChunks <= 0 {
		numChunks = 1
	}
	byteLen := (numChunks + 7) / 8
	return &TransferBitmask{
		numChunks: numChunks,
		bits:      make([]byte, byteLen),
		count:     0,
	}
}

// NumChunks returns the total chunk count.
func (b *TransferBitmask) NumChunks() int {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.numChunks
}

// Count returns the number of received/completed chunks.
func (b *TransferBitmask) Count() int {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.count
}

// IsComplete returns true if all chunks have been received.
func (b *TransferBitmask) IsComplete() bool {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.count >= b.numChunks
}

// Set marks a chunk index as received. Returns true if the chunk was newly marked.
func (b *TransferBitmask) Set(index int) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	if index < 0 || index >= b.numChunks {
		return false
	}

	byteIdx := index / 8
	bitOffset := uint(index % 8)

	if byteIdx >= len(b.bits) {
		return false
	}

	if (b.bits[byteIdx] & (1 << bitOffset)) != 0 {
		return false // Already set
	}

	b.bits[byteIdx] |= (1 << bitOffset)
	b.count++
	return true
}

// IsSet returns whether a specific chunk index has been marked.
func (b *TransferBitmask) IsSet(index int) bool {
	b.mu.RLock()
	defer b.mu.RUnlock()

	if index < 0 || index >= b.numChunks {
		return false
	}

	byteIdx := index / 8
	bitOffset := uint(index % 8)
	if byteIdx >= len(b.bits) {
		return false
	}

	return (b.bits[byteIdx] & (1 << bitOffset)) != 0
}

// MissingIndices returns a slice of all chunk indices that are not yet received.
func (b *TransferBitmask) MissingIndices() []int {
	b.mu.RLock()
	defer b.mu.RUnlock()

	missing := make([]int, 0, b.numChunks-b.count)
	for i := 0; i < b.numChunks; i++ {
		byteIdx := i / 8
		bitOffset := uint(i % 8)
		if (b.bits[byteIdx] & (1 << bitOffset)) == 0 {
			missing = append(missing, i)
		}
	}
	return missing
}

// ToBytes serializes the bitmask with a 4-byte big-endian numChunks header.
func (b *TransferBitmask) ToBytes() []byte {
	b.mu.RLock()
	defer b.mu.RUnlock()

	buf := make([]byte, 4+len(b.bits))
	binary.BigEndian.PutUint32(buf[:4], uint32(b.numChunks))
	copy(buf[4:], b.bits)
	return buf
}

// FromBytes deserializes a bitmask from a byte slice.
func FromBytes(data []byte) (*TransferBitmask, error) {
	if len(data) < 4 {
		return nil, errors.New("insufficient data for bitmask header")
	}

	numChunks := int(binary.BigEndian.Uint32(data[:4]))
	if numChunks <= 0 {
		return nil, errors.New("invalid numChunks in bitmask")
	}

	expectedBytes := (numChunks + 7) / 8
	if len(data)-4 < expectedBytes {
		return nil, fmt.Errorf("bitmask payload truncated: expected %d bytes, got %d", expectedBytes, len(data)-4)
	}

	bm := &TransferBitmask{
		numChunks: numChunks,
		bits:      make([]byte, expectedBytes),
		count:     0,
	}
	copy(bm.bits, data[4:4+expectedBytes])

	// Calculate set count
	count := 0
	for i := 0; i < numChunks; i++ {
		byteIdx := i / 8
		bitOffset := uint(i % 8)
		if (bm.bits[byteIdx] & (1 << bitOffset)) != 0 {
			count++
		}
	}
	bm.count = count

	return bm, nil
}

// SaveToFile writes the serialized bitmask atomically to disk.
func (b *TransferBitmask) SaveToFile(path string) error {
	data := b.ToBytes()
	tmpPath := fmt.Sprintf("%s.tmp", path)
	if err := os.WriteFile(tmpPath, data, 0600); err != nil {
		return fmt.Errorf("failed to write temporary bitmask file: %w", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		_ = os.Remove(tmpPath)
		return fmt.Errorf("failed to atomically rename bitmask file: %w", err)
	}
	return nil
}

// LoadBitmaskFromFile reads a TransferBitmask from a given file path.
func LoadBitmaskFromFile(path string) (*TransferBitmask, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return FromBytes(data)
}
