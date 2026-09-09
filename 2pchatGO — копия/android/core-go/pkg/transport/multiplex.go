package transport

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"github.com/hashicorp/yamux"
)

// StreamType defines the dedicated logical channel over Yamux.
type StreamType uint8

const (
	StreamTypeControl StreamType = 0 // Keep-Alive, ACK, Presence
	StreamTypeChat    StreamType = 1 // Encrypted text & reactions
	StreamTypeFile    StreamType = 2 // Chunked file transfers
	StreamTypeMedia   StreamType = 3 // Realtime media & VoIP signaling
)

var (
	ErrMultiplexClosed = errors.New("multiplexer: session closed")
	ErrStreamNotFound  = errors.New("multiplexer: stream not found")
)

// MultiplexConfig sets Yamux configuration parameters.
func DefaultMultiplexConfig() *yamux.Config {
	cfg := yamux.DefaultConfig()
	cfg.EnableKeepAlive = true
	cfg.KeepAliveInterval = 15 * time.Second
	cfg.ConnectionWriteTimeout = 10 * time.Second
	cfg.MaxStreamWindowSize = 1024 * 1024 // 1MB window for high-throughput streaming
	cfg.LogOutput = io.Discard            // Suppress noise in production
	return cfg
}

// MultiplexedSession manages Yamux multiplexed logical streams over a single physical net.Conn.
type MultiplexedSession struct {
	conn      net.Conn
	session   *yamux.Session
	isServer  bool
	mu        sync.RWMutex
	streams   map[StreamType]net.Conn
	streamCh  chan net.Conn
	closed    chan struct{}
	closeOnce sync.Once
}

// NewMultiplexedSession wraps an existing net.Conn in a Yamux multiplexer.
func NewMultiplexedSession(conn net.Conn, isServer bool) (*MultiplexedSession, error) {
	cfg := DefaultMultiplexConfig()
	var ySess *yamux.Session
	var err error

	if isServer {
		ySess, err = yamux.Server(conn, cfg)
	} else {
		ySess, err = yamux.Client(conn, cfg)
	}
	if err != nil {
		return nil, fmt.Errorf("yamux init failed: %w", err)
	}

	ms := &MultiplexedSession{
		conn:     conn,
		session:  ySess,
		isServer: isServer,
		streams:  make(map[StreamType]net.Conn),
		streamCh: make(chan net.Conn, 32),
		closed:   make(chan struct{}),
	}

	go ms.acceptLoop()
	return ms, nil
}

// acceptLoop accepts incoming logical streams from the remote peer.
func (ms *MultiplexedSession) acceptLoop() {
	defer func() {
		_ = ms.Close()
	}()

	for {
		stream, err := ms.session.Accept()
		if err != nil {
			return
		}

		// Read 1-byte stream type header
		var header [1]byte
		if _, err := io.ReadFull(stream, header[:]); err != nil {
			_ = stream.Close()
			continue
		}

		st := StreamType(header[0])
		ms.mu.Lock()
		ms.streams[st] = stream
		ms.mu.Unlock()

		select {
		case ms.streamCh <- stream:
		case <-ms.closed:
			_ = stream.Close()
			return
		}
	}
}

// OpenStream opens a typed logical stream (e.g. StreamTypeChat, StreamTypeFile) to the peer.
func (ms *MultiplexedSession) OpenStream(st StreamType) (net.Conn, error) {
	ms.mu.RLock()
	if existing, ok := ms.streams[st]; ok {
		ms.mu.RUnlock()
		return existing, nil
	}
	ms.mu.RUnlock()

	stream, err := ms.session.Open()
	if err != nil {
		return nil, err
	}

	// Write 1-byte stream type header
	header := [1]byte{byte(st)}
	if _, err := stream.Write(header[:]); err != nil {
		_ = stream.Close()
		return nil, err
	}

	ms.mu.Lock()
	ms.streams[st] = stream
	ms.mu.Unlock()

	return stream, nil
}

// GetStream retrieves an existing opened or accepted stream by type.
func (ms *MultiplexedSession) GetStream(st StreamType) (net.Conn, bool) {
	ms.mu.RLock()
	defer ms.mu.RUnlock()
	s, ok := ms.streams[st]
	return s, ok
}

// StreamChannel returns a read-only channel of incoming accepted logical streams.
func (ms *MultiplexedSession) StreamChannel() <-chan net.Conn {
	return ms.streamCh
}

// WriteToStream writes framed bytes to a designated logical stream.
func (ms *MultiplexedSession) WriteToStream(st StreamType, payload []byte) error {
	stream, err := ms.OpenStream(st)
	if err != nil {
		return err
	}

	// Write 4-byte length prefix + payload
	var lenBuf [4]byte
	binary.BigEndian.PutUint32(lenBuf[:], uint32(len(payload)))

	if _, err := stream.Write(lenBuf[:]); err != nil {
		return err
	}
	_, err = stream.Write(payload)
	return err
}

// ReadFromStream reads a length-prefixed frame from a logical stream.
func ReadFromStream(stream io.Reader, maxLen uint32) ([]byte, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(stream, lenBuf[:]); err != nil {
		return nil, err
	}
	length := binary.BigEndian.Uint32(lenBuf[:])
	if length > maxLen {
		return nil, fmt.Errorf("stream frame exceeds max size: %d > %d", length, maxLen)
	}

	buf := make([]byte, length)
	if _, err := io.ReadFull(stream, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// Close closes all multiplexed streams and underlying session.
func (ms *MultiplexedSession) Close() error {
	ms.closeOnce.Do(func() {
		close(ms.closed)
		if ms.session != nil {
			_ = ms.session.Close()
		}
		if ms.conn != nil {
			_ = ms.conn.Close()
		}
	})
	return nil
}
