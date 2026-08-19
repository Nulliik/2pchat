package transport

import (
	"bytes"
	"crypto/rand"
	"io"
	"testing"
)

// chunkedReader simulates fragmented TCP stream delivering 1..N bytes per read.
type chunkedReader struct {
	data      []byte
	pos       int
	chunkSize int
}

func (r *chunkedReader) Read(p []byte) (n int, err error) {
	if r.pos >= len(r.data) {
		return 0, io.EOF
	}
	remaining := len(r.data) - r.pos
	toRead := r.chunkSize
	if toRead > remaining {
		toRead = remaining
	}
	if toRead > len(p) {
		toRead = len(p)
	}
	copy(p, r.data[r.pos:r.pos+toRead])
	r.pos += toRead
	return toRead, nil
}

func TestFramingRoundtrip(t *testing.T) {
	payload := []byte("Hello, this is a test frame payload across the 2PChat Go core transport!")

	var buf bytes.Buffer
	if err := WriteFrame(&buf, payload); err != nil {
		t.Fatalf("WriteFrame failed: %v", err)
	}

	readPayload, err := ReadFrame(&buf, MaxFrameSize)
	if err != nil {
		t.Fatalf("ReadFrame failed: %v", err)
	}

	if !bytes.Equal(payload, readPayload) {
		t.Fatalf("Payload mismatch: got %q, want %q", readPayload, payload)
	}
}

func TestFramingEmptyPayload(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteFrame(&buf, []byte{}); err != nil {
		t.Fatalf("WriteFrame with empty payload failed: %v", err)
	}

	readPayload, err := ReadFrame(&buf, MaxFrameSize)
	if err != nil {
		t.Fatalf("ReadFrame failed: %v", err)
	}

	if len(readPayload) != 0 {
		t.Fatalf("Expected empty payload, got %d bytes", len(readPayload))
	}
}

func TestFramingFragmentedStream(t *testing.T) {
	payload := make([]byte, 1024*64) // 64 KB
	rand.Read(payload)

	var buf bytes.Buffer
	if err := WriteFrame(&buf, payload); err != nil {
		t.Fatalf("WriteFrame failed: %v", err)
	}

	// Read with 7 bytes per read chunk to stress-test stream fragmentation
	fragReader := &chunkedReader{
		data:      buf.Bytes(),
		chunkSize: 7,
	}

	readPayload, err := ReadFrame(fragReader, MaxFrameSize)
	if err != nil {
		t.Fatalf("ReadFrame over fragmented stream failed: %v", err)
	}

	if !bytes.Equal(payload, readPayload) {
		t.Fatalf("Payload corrupted across fragmented reads")
	}
}

func TestFramingOversizedFrame(t *testing.T) {
	payload := []byte("Small payload that exceeds tiny limit")
	var buf bytes.Buffer
	WriteFrame(&buf, payload)

	// Set limit smaller than payload length
	_, err := ReadFrame(&buf, 10)
	if err == nil {
		t.Fatal("Expected error on oversized frame, got nil")
	}
}
