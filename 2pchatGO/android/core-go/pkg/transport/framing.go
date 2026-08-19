package transport

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

const (
	// FrameHeaderSize is 4 bytes (big-endian uint32 length prefix).
	FrameHeaderSize = 4
	// MaxFrameSize is 16 MB.
	MaxFrameSize = 16 * 1024 * 1024
	// MaxHandshakeSize is 16 KB.
	MaxHandshakeSize = 16 * 1024
)

var (
	ErrFrameTooLarge = errors.New("frame size exceeds maximum limit")
	ErrEmptyFrame    = errors.New("frame payload is empty")
)

// ReadFrame reads a 4-byte big-endian length prefix followed by the frame payload.
// It handles partial TCP reads correctly by using io.ReadFull.
func ReadFrame(r io.Reader, maxLimit int) ([]byte, error) {
	if maxLimit <= 0 || maxLimit > MaxFrameSize {
		maxLimit = MaxFrameSize
	}

	var header [FrameHeaderSize]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		return nil, err
	}

	length := binary.BigEndian.Uint32(header[:])
	if int(length) > maxLimit {
		return nil, fmt.Errorf("%w: %d bytes (limit: %d)", ErrFrameTooLarge, length, maxLimit)
	}

	if length == 0 {
		return []byte{}, nil
	}

	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return nil, fmt.Errorf("failed to read full frame payload: %w", err)
	}

	return payload, nil
}

// WriteFrame writes a 4-byte big-endian length prefix followed by the payload in a single buffer.
func WriteFrame(w io.Writer, payload []byte) error {
	if len(payload) > MaxFrameSize {
		return fmt.Errorf("%w: payload %d bytes (limit: %d)", ErrFrameTooLarge, len(payload), MaxFrameSize)
	}

	totalLen := FrameHeaderSize + len(payload)
	buf := make([]byte, totalLen)
	binary.BigEndian.PutUint32(buf[:FrameHeaderSize], uint32(len(payload)))
	copy(buf[FrameHeaderSize:], payload)

	n, err := w.Write(buf)
	if err != nil {
		return err
	}
	if n != totalLen {
		return io.ErrShortWrite
	}
	return nil
}
