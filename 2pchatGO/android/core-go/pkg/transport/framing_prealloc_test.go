package transport

import (
	"bytes"
	"encoding/binary"
	"io"
	"runtime"
	"sync"
	"testing"
	"time"
)

func heapAlloc() uint64 {
	runtime.GC()
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return m.HeapAlloc
}

// TestReadFrame_DoesNotPreallocateOnHeaderOnly tests SEC-04:
// When an adversarial peer sends a 4-byte header declaring 16MB and then stalls,
// ReadFrame must NOT allocate 16MB upfront on the heap before payload data actually arrives.
func TestReadFrame_DoesNotPreallocateOnHeaderOnly(t *testing.T) {
	pr, pw := io.Pipe()
	defer pr.Close()

	var hdr [FrameHeaderSize]byte
	binary.BigEndian.PutUint32(hdr[:], uint32(MaxFrameSize))

	before := heapAlloc()

	done := make(chan error, 1)
	go func() {
		_, err := ReadFrame(pr, MaxFrameSize)
		done <- err
	}()

	if _, err := pw.Write(hdr[:]); err != nil {
		t.Fatal(err)
	}
	time.Sleep(150 * time.Millisecond) // ReadFrame is now blocked waiting for body

	grew := int64(heapAlloc()) - int64(before)
	const limit = 1 << 20 // 1 MB limit (our allocation is ~128KB, well below 1MB)
	if grew > limit {
		t.Fatalf("heap grew by %d bytes on header-only frame (limit %d): buffer was preallocated before body arrived", grew, limit)
	}

	pw.CloseWithError(io.ErrUnexpectedEOF)
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("ReadFrame did not return after pipe close")
	}
}

// TestReadFrame_100HeaderOnlyConnectionsBounded tests that 100 concurrent slowloris connections
// declaring max frame sizes do not cause excessive memory growth (< 32MB total).
func TestReadFrame_100HeaderOnlyConnectionsBounded(t *testing.T) {
	before := heapAlloc()
	var writers []*io.PipeWriter
	var wg sync.WaitGroup

	for i := 0; i < 100; i++ {
		pr, pw := io.Pipe()
		writers = append(writers, pw)
		wg.Add(1)
		go func(reader io.Reader) {
			defer wg.Done()
			_, _ = ReadFrame(reader, MaxFrameSize)
		}(pr)

		var hdr [FrameHeaderSize]byte
		binary.BigEndian.PutUint32(hdr[:], uint32(MaxFrameSize))
		if _, err := pw.Write(hdr[:]); err != nil {
			t.Fatal(err)
		}
	}

	time.Sleep(200 * time.Millisecond)
	grew := int64(heapAlloc()) - int64(before)
	const maxAllowedGrowth = 32 << 20 // 32 MB
	if grew > maxAllowedGrowth {
		t.Fatalf("100 half-open frames grew heap by %d MB (limit 32 MB)", grew>>20)
	}

	for _, w := range writers {
		_ = w.CloseWithError(io.ErrUnexpectedEOF)
	}
	wg.Wait()
}

// TestReadFrame_IncrementalPayloadIntegrity ensures that streaming chunks reconstruct
// large frames with 100% byte-for-byte integrity.
func TestReadFrame_IncrementalPayloadIntegrity(t *testing.T) {
	const payloadSize = 256 * 1024 // 256 KB (> 64 KB InitialChunkSize)
	expected := make([]byte, payloadSize)
	for i := range expected {
		expected[i] = byte(i % 251)
	}

	var buf bytes.Buffer
	if err := WriteFrame(&buf, expected); err != nil {
		t.Fatalf("WriteFrame failed: %v", err)
	}

	got, err := ReadFrame(&buf, MaxFrameSize)
	if err != nil {
		t.Fatalf("ReadFrame failed: %v", err)
	}

	if !bytes.Equal(got, expected) {
		t.Fatalf("ReadFrame payload mismatch: got %d bytes, expected %d", len(got), len(expected))
	}
}
