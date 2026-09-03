package transport

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

const (
	// RelayTunnelMagic is the 1-byte protocol identifier (0x7E).
	RelayTunnelMagic = 0x7E

	// Relay Frame Types
	RelayFrameTypeRegister byte = 0x01 // Node announces readiness to receive relayed conns for targetFP
	RelayFrameTypeConnect  byte = 0x02 // Sender initiates relay tunnel to targetFP
	RelayFrameTypeData     byte = 0x03 // Payload frame (Noise/Double Ratchet E2EE data)
	RelayFrameTypeClose    byte = 0x04 // Session closed / disconnected
	RelayFrameTypePing     byte = 0x05 // Keep-alive heartbeat
	RelayFrameTypePong     byte = 0x06 // Heartbeat response

	RelayHeaderSize = 1 + 1 + 16 + 4 // Magic(1) + Type(1) + SessionID(16) + PayloadLen(4)
)

// RelayTunnelFrame represents a binary frame routed through a blind relay.
type RelayTunnelFrame struct {
	Type      byte
	SessionID [16]byte
	Payload   []byte
}

// EncodeRelayFrame encodes a frame to wire format.
func EncodeRelayFrame(frameType byte, sessionID [16]byte, payload []byte) ([]byte, error) {
	payloadLen := len(payload)
	if payloadLen > 10*1024*1024 { // 10 MiB frame limit
		return nil, errors.New("relay frame payload exceeds maximum allowed size")
	}

	buf := make([]byte, RelayHeaderSize+payloadLen)
	buf[0] = RelayTunnelMagic
	buf[1] = frameType
	copy(buf[2:18], sessionID[:])
	binary.BigEndian.PutUint32(buf[18:22], uint32(payloadLen))
	if payloadLen > 0 {
		copy(buf[22:], payload)
	}
	return buf, nil
}

// ReadRelayFrame reads and parses a RelayTunnelFrame from an io.Reader.
func ReadRelayFrame(r io.Reader) (*RelayTunnelFrame, error) {
	var header [RelayHeaderSize]byte
	if _, err := io.ReadFull(r, header[:]); err != nil {
		return nil, err
	}

	if header[0] != RelayTunnelMagic {
		return nil, fmt.Errorf("invalid relay frame magic: 0x%02X", header[0])
	}

	frameType := header[1]
	var sessionID [16]byte
	copy(sessionID[:], header[2:18])
	payloadLen := int(binary.BigEndian.Uint32(header[18:22]))

	if payloadLen < 0 || payloadLen > 10*1024*1024 {
		return nil, fmt.Errorf("invalid relay payload length: %d", payloadLen)
	}

	payload := make([]byte, payloadLen)
	if payloadLen > 0 {
		if _, err := io.ReadFull(r, payload); err != nil {
			return nil, fmt.Errorf("failed to read relay payload: %w", err)
		}
	}

	return &RelayTunnelFrame{
		Type:      frameType,
		SessionID: sessionID,
		Payload:   payload,
	}, nil
}

// RelayTunnelConn adapts a stream through a Blind Relay into a standard net.Conn.
type RelayTunnelConn struct {
	rawConn    net.Conn
	sessionID  [16]byte
	readBuf    []byte
	readMu     sync.Mutex
	writeMu    sync.Mutex
	closed     bool
	closeMu    sync.Mutex
	closeChan  chan struct{}
	remoteAddr net.Addr
	localAddr  net.Addr
}

// NewRelayTunnelConn creates a virtual net.Conn routed through a raw connection.
func NewRelayTunnelConn(rawConn net.Conn, sessionID [16]byte, localAddr, remoteAddr net.Addr) *RelayTunnelConn {
	return &RelayTunnelConn{
		rawConn:    rawConn,
		sessionID:  sessionID,
		closeChan:  make(chan struct{}),
		localAddr:  localAddr,
		remoteAddr: remoteAddr,
	}
}

// Read reads decrypted payload bytes from the relay stream.
func (c *RelayTunnelConn) Read(b []byte) (int, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()

	for len(c.readBuf) == 0 {
		c.closeMu.Lock()
		if c.closed {
			c.closeMu.Unlock()
			return 0, io.EOF
		}
		c.closeMu.Unlock()

		frame, err := ReadRelayFrame(c.rawConn)
		if err != nil {
			return 0, err
		}

		if frame.Type == RelayFrameTypeClose {
			_ = c.Close()
			return 0, io.EOF
		}

		if frame.Type == RelayFrameTypeData && frame.SessionID == c.sessionID {
			c.readBuf = frame.Payload
			break
		}
		// Ignore keep-alives or frames for other sessions
	}

	n := copy(b, c.readBuf)
	c.readBuf = c.readBuf[n:]
	return n, nil
}

// Write wraps data in a RelayTunnelFrame and sends it across the relay.
func (c *RelayTunnelConn) Write(b []byte) (int, error) {
	c.closeMu.Lock()
	if c.closed {
		c.closeMu.Unlock()
		return 0, io.ErrClosedPipe
	}
	c.closeMu.Unlock()

	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	frameBytes, err := EncodeRelayFrame(RelayFrameTypeData, c.sessionID, b)
	if err != nil {
		return 0, err
	}

	if _, err := c.rawConn.Write(frameBytes); err != nil {
		return 0, err
	}

	return len(b), nil
}

// Close closes the relay connection.
func (c *RelayTunnelConn) Close() error {
	c.closeMu.Lock()
	defer c.closeMu.Unlock()

	if c.closed {
		return nil
	}
	c.closed = true
	close(c.closeChan)

	// Send close frame best-effort
	c.writeMu.Lock()
	closeFrame, _ := EncodeRelayFrame(RelayFrameTypeClose, c.sessionID, nil)
	_, _ = c.rawConn.Write(closeFrame)
	c.writeMu.Unlock()

	return c.rawConn.Close()
}

// LocalAddr returns the local address.
func (c *RelayTunnelConn) LocalAddr() net.Addr {
	if c.localAddr != nil {
		return c.localAddr
	}
	return c.rawConn.LocalAddr()
}

// RemoteAddr returns the remote address.
func (c *RelayTunnelConn) RemoteAddr() net.Addr {
	if c.remoteAddr != nil {
		return c.remoteAddr
	}
	return c.rawConn.RemoteAddr()
}

// SetDeadline sets the read and write deadlines.
func (c *RelayTunnelConn) SetDeadline(t time.Time) error {
	return c.rawConn.SetDeadline(t)
}

// SetReadDeadline sets the read deadline.
func (c *RelayTunnelConn) SetReadDeadline(t time.Time) error {
	return c.rawConn.SetReadDeadline(t)
}

// SetWriteDeadline sets the write deadline.
func (c *RelayTunnelConn) SetWriteDeadline(t time.Time) error {
	return c.rawConn.SetWriteDeadline(t)
}

type relaySessionPair struct {
	connA net.Conn
	connB net.Conn
}

// RelayTunnelServer provides a zero-knowledge, blind relay proxy forwarding frames between two peers.
type RelayTunnelServer struct {
	mu          sync.RWMutex
	listener    net.Listener
	subscribers map[string]net.Conn           // targetFP -> incoming waiting conn
	sessions    map[[16]byte]*relaySessionPair // sessionID -> paired conns
	closed      bool
	closeChan   chan struct{}
}

// NewRelayTunnelServer creates a new Blind Relay Server.
func NewRelayTunnelServer() *RelayTunnelServer {
	return &RelayTunnelServer{
		subscribers: make(map[string]net.Conn),
		sessions:    make(map[[16]byte]*relaySessionPair),
		closeChan:   make(chan struct{}),
	}
}

// Start launches the relay server on the specified port.
func (s *RelayTunnelServer) Start(port int) error {
	l, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		return fmt.Errorf("failed to bind relay server on port %d: %w", port, err)
	}
	s.listener = l

	go s.acceptLoop()
	return nil
}

func (s *RelayTunnelServer) acceptLoop() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			select {
			case <-s.closeChan:
				return
			default:
				time.Sleep(50 * time.Millisecond)
				continue
			}
		}

		go s.handleConnection(conn)
	}
}

func (s *RelayTunnelServer) handleConnection(conn net.Conn) {
	var registeredFPs []string

	defer func() {
		_ = conn.Close()
		s.mu.Lock()
		for _, fp := range registeredFPs {
			if s.subscribers[fp] == conn {
				delete(s.subscribers, fp)
			}
		}
		for sid, pair := range s.sessions {
			if pair.connA == conn || pair.connB == conn {
				var other net.Conn
				if pair.connA == conn {
					other = pair.connB
				} else {
					other = pair.connA
				}
				delete(s.sessions, sid)
				if other != nil {
					closeFrame, _ := EncodeRelayFrame(RelayFrameTypeClose, sid, nil)
					_, _ = other.Write(closeFrame)
				}
			}
		}
		s.mu.Unlock()
	}()

	for {
		frame, err := ReadRelayFrame(conn)
		if err != nil {
			return
		}

		switch frame.Type {
		case RelayFrameTypeRegister:
			targetFP := string(frame.Payload)
			s.mu.Lock()
			s.subscribers[targetFP] = conn
			registeredFPs = append(registeredFPs, targetFP)
			s.mu.Unlock()

		case RelayFrameTypeConnect:
			targetFP := string(frame.Payload)
			s.mu.Lock()
			peerConn, exists := s.subscribers[targetFP]
			if !exists {
				s.mu.Unlock()
				// Peer not registered / offline
				resp, _ := EncodeRelayFrame(RelayFrameTypeClose, frame.SessionID, []byte("peer_offline"))
				_, _ = conn.Write(resp)
				return
			}

			// Forward connect frame to peer to initiate session handshake
			connectFrame, _ := EncodeRelayFrame(RelayFrameTypeConnect, frame.SessionID, nil)
			if _, err := peerConn.Write(connectFrame); err != nil {
				s.mu.Unlock()
				resp, _ := EncodeRelayFrame(RelayFrameTypeClose, frame.SessionID, []byte("peer_offline"))
				_, _ = conn.Write(resp)
				return
			}

			pair := &relaySessionPair{
				connA: conn,
				connB: peerConn,
			}
			s.sessions[frame.SessionID] = pair
			s.mu.Unlock()

		case RelayFrameTypeData:
			s.mu.RLock()
			pair, exists := s.sessions[frame.SessionID]
			s.mu.RUnlock()

			if !exists {
				continue
			}
			var target net.Conn
			switch conn {
			case pair.connA:
				target = pair.connB
			case pair.connB:
				target = pair.connA
			}
			if target != nil {
				raw, _ := EncodeRelayFrame(RelayFrameTypeData, frame.SessionID, frame.Payload)
				_, _ = target.Write(raw)
			}

		case RelayFrameTypeClose:
			s.mu.Lock()
			pair, exists := s.sessions[frame.SessionID]
			if exists {
				delete(s.sessions, frame.SessionID)
			}
			s.mu.Unlock()

			if exists {
				var target net.Conn
				switch conn {
				case pair.connA:
					target = pair.connB
				case pair.connB:
					target = pair.connA
				}
				if target != nil {
					raw, _ := EncodeRelayFrame(RelayFrameTypeClose, frame.SessionID, frame.Payload)
					_, _ = target.Write(raw)
				}
			}
		}
	}
}

// Stop halts the relay server.
func (s *RelayTunnelServer) Stop() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.closed {
		return nil
	}
	s.closed = true
	close(s.closeChan)

	if s.listener != nil {
		return s.listener.Close()
	}
	return nil
}
