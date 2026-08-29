package transport

import (
	"bytes"
	"testing"
)

func FuzzReadFrame(f *testing.F) {
	var buf bytes.Buffer
	_ = WriteFrame(&buf, []byte("Valid test frame payload"))
	f.Add(buf.Bytes())
	f.Add([]byte{})
	f.Add([]byte{0x00, 0x00, 0x00, 0x05, 'a', 'b', 'c', 'd', 'e'})
	f.Add([]byte{0xFF, 0xFF, 0xFF, 0xFF}) // Huge size header

	f.Fuzz(func(t *testing.T, data []byte) {
		r := bytes.NewReader(data)
		_, _ = ReadFrame(r, 64*1024)
	})
}

func FuzzBitmaskSerialization(f *testing.F) {
	bm := NewTransferBitmask(100)
	bm.Set(0)
	bm.Set(50)
	bm.Set(99)
	f.Add(bm.ToBytes())
	f.Add([]byte{})
	f.Add([]byte{0x01, 0x00})
	f.Add([]byte{0x00, 0x00, 0x00, 0x0A, 0xFF, 0xFF})

	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = FromBytes(data)
	})
}
