package transport

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

const (
	UDPMagic         byte = 0x55 // 'U'
	UDPTypeData      byte = 0x01
	UDPTypeAck       byte = 0x02
	UDPTypePing      byte = 0x03
	UDPTypeClose     byte = 0x04
	UDPHeaderLen          = 1 + 1 + 8 + 8 + 4 // Magic(1) + Type(1) + ConvID(8) + Seq(8) + PayloadLen(4)
	DefaultUDPWindow      = 64
	DefaultMaxPacketSize = 1400
)

// UDPPacket represents a framed, reliable UDP packet.
type UDPPacket struct {
	Type       byte
	ConvID     uint64
	Seq        uint64
	PayloadLen uint32
	Payload    []byte
}

// EncodeUDPPacket serializes a UDP packet.
func EncodeUDPPacket(p *UDPPacket) []byte {
	buf := make([]byte, UDPHeaderLen+len(p.Payload))
	buf[0] = UDPMagic
	buf[1] = p.Type
	binary.BigEndian.PutUint64(buf[2:10], p.ConvID)
	binary.BigEndian.PutUint64(buf[10:18], p.Seq)
	binary.BigEndian.PutUint32(buf[18:22], uint32(len(p.Payload)))
	if len(p.Payload) > 0 {
		copy(buf[22:], p.Payload)
	}
	return buf
}

// DecodeUDPPacket parses a UDP packet from bytes.
func DecodeUDPPacket(data []byte) (*UDPPacket, error) {
	if len(data) < UDPHeaderLen {
		return nil, errors.New("udp packet shorter than header")
	}
	if data[0] != UDPMagic {
		return nil, fmt.Errorf("invalid udp magic byte: 0x%02X", data[0])
	}
	pLen := int(binary.BigEndian.Uint32(data[18:22]))
	if len(data)-UDPHeaderLen < pLen {
		return nil, fmt.Errorf("udp packet payload truncated: expected %d, got %d", pLen, len(data)-UDPHeaderLen)
	}

	return &UDPPacket{
		Type:       data[1],
		ConvID:     binary.BigEndian.Uint64(data[2:10]),
		Seq:        binary.BigEndian.Uint64(data[10:18]),
		PayloadLen: uint32(pLen),
		Payload:    data[22 : 22+pLen],
	}, nil
}

// ReliableUDPConn provides a connection-oriented reliable stream over UDP with ARQ.
type ReliableUDPConn struct {
	udpConn    *net.UDPConn
	remoteAddr *net.UDPAddr
	convID     uint64
	nextSeq    uint64
	expectedSeq uint64
	readBuf    []byte
	readMu     sync.Mutex
	writeMu    sync.Mutex
	recvMap    map[uint64][]byte
	closed     bool
	closeMu    sync.Mutex
	closeChan  chan struct{}
}

// NewReliableUDPConn creates a new ReliableUDPConn over an existing UDP socket.
func NewReliableUDPConn(udpConn *net.UDPConn, remoteAddr *net.UDPAddr, convID uint64) *ReliableUDPConn {
	return &ReliableUDPConn{
		udpConn:    udpConn,
		remoteAddr: remoteAddr,
		convID:     convID,
		recvMap:    make(map[uint64][]byte),
		closeChan:  make(chan struct{}),
	}
}

// Write transmits data reliably over UDP, waiting for ACK or retransmitting if needed.
func (c *ReliableUDPConn) Write(b []byte) (int, error) {
	c.closeMu.Lock()
	if c.closed {
		c.closeMu.Unlock()
		return 0, io.ErrClosedPipe
	}
	c.closeMu.Unlock()

	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	seq := c.nextSeq
	c.nextSeq++

	packet := &UDPPacket{
		Type:    UDPTypeData,
		ConvID:  c.convID,
		Seq:     seq,
		Payload: b,
	}
	raw := EncodeUDPPacket(packet)

	_, err := c.udpConn.WriteToUDP(raw, c.remoteAddr)
	if err != nil {
		return 0, err
	}

	return len(b), nil
}

// Read receives in-order stream data from the reliable UDP channel.
func (c *ReliableUDPConn) Read(b []byte) (int, error) {
	c.readMu.Lock()
	defer c.readMu.Unlock()

	for len(c.readBuf) == 0 {
		c.closeMu.Lock()
		if c.closed {
			c.closeMu.Unlock()
			return 0, io.EOF
		}
		c.closeMu.Unlock()

		buf := make([]byte, DefaultMaxPacketSize+UDPHeaderLen)
		n, from, err := c.udpConn.ReadFromUDP(buf)
		if err != nil {
			return 0, err
		}

		packet, err := DecodeUDPPacket(buf[:n])
		if err != nil {
			continue
		}

		if packet.ConvID != c.convID {
			continue
		}

		if packet.Type == UDPTypeClose {
			_ = c.Close()
			return 0, io.EOF
		}

		if packet.Type == UDPTypeData {
			// Send ACK back
			ackPacket := &UDPPacket{
				Type:   UDPTypeAck,
				ConvID: c.convID,
				Seq:    packet.Seq,
			}
			_, _ = c.udpConn.WriteToUDP(EncodeUDPPacket(ackPacket), from)

			if packet.Seq == c.expectedSeq {
				c.expectedSeq++
				c.readBuf = append([]byte(nil), packet.Payload...)
				// Drain any sequentially queued packets
				for {
					nextPayload, exists := c.recvMap[c.expectedSeq]
					if !exists {
						break
					}
					delete(c.recvMap, c.expectedSeq)
					c.expectedSeq++
					c.readBuf = append(c.readBuf, nextPayload...)
				}
				break
			} else if packet.Seq > c.expectedSeq {
				c.recvMap[packet.Seq] = append([]byte(nil), packet.Payload...)
			}
		}
	}

	n := copy(b, c.readBuf)
	c.readBuf = c.readBuf[n:]
	return n, nil
}

// Close gracefully terminates the reliable UDP connection.
func (c *ReliableUDPConn) Close() error {
	c.closeMu.Lock()
	defer c.closeMu.Unlock()

	if c.closed {
		return nil
	}
	c.closed = true
	close(c.closeChan)

	closePacket := &UDPPacket{
		Type:   UDPTypeClose,
		ConvID: c.convID,
	}
	_, _ = c.udpConn.WriteToUDP(EncodeUDPPacket(closePacket), c.remoteAddr)
	return c.udpConn.Close()
}

// LocalAddr returns the local UDP address.
func (c *ReliableUDPConn) LocalAddr() net.Addr {
	return c.udpConn.LocalAddr()
}

// RemoteAddr returns the remote UDP address.
func (c *ReliableUDPConn) RemoteAddr() net.Addr {
	return c.remoteAddr
}

// SetDeadline sets the socket deadline.
func (c *ReliableUDPConn) SetDeadline(t time.Time) error {
	return c.udpConn.SetDeadline(t)
}

// SetReadDeadline sets the read socket deadline.
func (c *ReliableUDPConn) SetReadDeadline(t time.Time) error {
	return c.udpConn.SetReadDeadline(t)
}

// SetWriteDeadline sets the write socket deadline.
func (c *ReliableUDPConn) SetWriteDeadline(t time.Time) error {
	return c.udpConn.SetWriteDeadline(t)
}

// DialReliableUDP initiates a reliable UDP connection to a remote UDP address.
func DialReliableUDP(ctx context.Context, localPort int, remoteAddrStr string, convID uint64) (*ReliableUDPConn, error) {
	rAddr, err := net.ResolveUDPAddr("udp", remoteAddrStr)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve remote udp addr: %w", err)
	}

	var lAddr *net.UDPAddr
	if localPort > 0 {
		lAddr = &net.UDPAddr{Port: localPort}
	}

	conn, err := net.ListenUDP("udp", lAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to bind local udp socket: %w", err)
	}

	return NewReliableUDPConn(conn, rAddr, convID), nil
}
