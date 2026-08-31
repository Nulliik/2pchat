package discovery

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"time"
	"twopchat/core/pkg/transport"
)

const (
	UDPTrackerProtocolID = uint64(0x41727101980) // BEP 15 magic constant
	ActionConnect        = int32(0)
	ActionAnnounce       = int32(1)
	ActionScrape         = int32(2)
	ActionError          = int32(3)

	DefaultTrackerTimeout = 5 * time.Second
)

var (
	ErrUDPDisabledUnderTor = errors.New("UDP tracker is disabled when Tor proxy is enabled")
	ErrInvalidTrackerURL   = errors.New("invalid UDP tracker URL")
	ErrTransactionMismatch = errors.New("tracker transaction ID mismatch")
	ErrTrackerResponse     = errors.New("error response from tracker")
)

func resolveUDPAddress(ctx context.Context, hostPort string) (*net.UDPAddr, error) {
	host, portStr, err := net.SplitHostPort(hostPort)
	if err != nil {
		host = hostPort
		portStr = "0"
	}
	port, _ := strconv.Atoi(portStr)

	cleanHost := strings.Trim(host, "[]")
	if ip := net.ParseIP(cleanHost); ip != nil {
		return &net.UDPAddr{IP: ip, Port: port}, nil
	}

	ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
	if err == nil && len(ips) > 0 {
		return &net.UDPAddr{IP: ips[0], Port: port}, nil
	}

	ips, err = transport.FallbackResolver.LookupIP(ctx, "ip", host)
	if err == nil && len(ips) > 0 {
		return &net.UDPAddr{IP: ips[0], Port: port}, nil
	}

	rAddr, err := net.ResolveUDPAddr("udp", hostPort)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve UDP tracker %s: %w", hostPort, err)
	}
	return rAddr, nil
}

// PeerEndpoint represents a discovered peer network address.
type PeerEndpoint struct {
	IP   net.IP
	Port int
	Raw  string
}

// String returns host:port representation.
func (p PeerEndpoint) String() string {
	return net.JoinHostPort(p.IP.String(), strconv.Itoa(p.Port))
}

// AnnounceResult contains the list of discovered peers and tracker interval.
type AnnounceResult struct {
	Interval int
	Leechers int
	Seeders  int
	Peers    []PeerEndpoint
}

// UDPTrackerClient implements the BitTorrent BEP 15 UDP Tracker Protocol.
type UDPTrackerClient struct {
	torEnabled bool
	timeout    time.Duration
	yggRelay   string
}

// SetYggdrasilUDPRelay configures the local user-space Yggdrasil UDP relay.
func (c *UDPTrackerClient) SetYggdrasilUDPRelay(addr string) { c.yggRelay = addr }

// NewUDPTrackerClient creates a new BEP 15 UDP tracker client.
func NewUDPTrackerClient(torEnabled bool, timeout time.Duration) *UDPTrackerClient {
	if timeout <= 0 {
		timeout = DefaultTrackerTimeout
	}
	return &UDPTrackerClient{
		torEnabled: torEnabled,
		timeout:    timeout,
	}
}

// Announce sends a BEP 15 connect + announce request to a UDP tracker.
func (c *UDPTrackerClient) Announce(
	ctx context.Context,
	trackerURL string,
	infoHash [20]byte,
	peerID [20]byte,
	listenPort int,
) (*AnnounceResult, error) {
	if c.torEnabled {
		return nil, ErrUDPDisabledUnderTor
	}

	u, err := url.Parse(trackerURL)
	if err != nil || u.Scheme != "udp" {
		return nil, fmt.Errorf("%w: %s", ErrInvalidTrackerURL, trackerURL)
	}

	hostPort := u.Host
	rAddr, err := resolveUDPAddress(ctx, hostPort)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve UDP tracker %s: %w", hostPort, err)
	}

	relay := c.yggRelay
	if !isYggdrasilTrackerHost(u.Hostname()) {
		relay = ""
	}
	dialAddr := rAddr
	if relay != "" {
		dialAddr, err = net.ResolveUDPAddr("udp4", relay)
		if err != nil {
			return nil, fmt.Errorf("failed to resolve Yggdrasil UDP relay %s: %w", relay, err)
		}
	}
	conn, err := net.DialUDP("udp", nil, dialAddr)
	if err != nil {
		return nil, fmt.Errorf("failed to dial UDP tracker %s: %w", hostPort, err)
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(c.timeout))

	// Step 1: Connect Request
	var txID uint32
	_ = binary.Read(rand.Reader, binary.BigEndian, &txID)

	connectReq := make([]byte, 16)
	binary.BigEndian.PutUint64(connectReq[0:8], UDPTrackerProtocolID)
	binary.BigEndian.PutUint32(connectReq[8:12], uint32(ActionConnect))
	binary.BigEndian.PutUint32(connectReq[12:16], txID)

	writePacket := func(payload []byte) (int, error) {
		if relay == "" {
			return conn.Write(payload)
		}
		frame := make([]byte, 22+len(payload))
		copy(frame[:4], []byte("YUDP"))
		copy(frame[4:20], rAddr.IP.To16())
		binary.BigEndian.PutUint16(frame[20:22], uint16(rAddr.Port))
		copy(frame[22:], payload)
		return conn.Write(frame)
	}
	if _, err := writePacket(connectReq); err != nil {
		return nil, fmt.Errorf("failed to write connect request: %w", err)
	}

	connectResp := make([]byte, 16)
	n, err := conn.Read(connectResp)
	if err != nil {
		return nil, fmt.Errorf("failed to read connect response: %w", err)
	}
	if n < 16 {
		return nil, errors.New("connect response too short")
	}

	respAction := binary.BigEndian.Uint32(connectResp[0:4])
	respTxID := binary.BigEndian.Uint32(connectResp[4:8])
	if respAction != uint32(ActionConnect) || respTxID != txID {
		return nil, ErrTransactionMismatch
	}

	connectionID := binary.BigEndian.Uint64(connectResp[8:16])

	// Step 2: Announce Request
	_ = binary.Read(rand.Reader, binary.BigEndian, &txID)
	var key uint32
	_ = binary.Read(rand.Reader, binary.BigEndian, &key)

	announceReq := make([]byte, 98)
	binary.BigEndian.PutUint64(announceReq[0:8], connectionID)
	binary.BigEndian.PutUint32(announceReq[8:12], uint32(ActionAnnounce))
	binary.BigEndian.PutUint32(announceReq[12:16], txID)
	copy(announceReq[16:36], infoHash[:])
	copy(announceReq[36:56], peerID[:])
	binary.BigEndian.PutUint64(announceReq[56:64], 0) // downloaded
	binary.BigEndian.PutUint64(announceReq[64:72], 0) // left
	binary.BigEndian.PutUint64(announceReq[72:80], 0) // uploaded
	binary.BigEndian.PutUint32(announceReq[80:84], 0) // event: none
	binary.BigEndian.PutUint32(announceReq[84:88], 0) // ip: default (0)
	binary.BigEndian.PutUint32(announceReq[88:92], key)
	binary.BigEndian.PutUint32(announceReq[92:96], uint32(50)) // num_want: 50
	binary.BigEndian.PutUint16(announceReq[96:98], uint16(listenPort))

	if _, err := writePacket(announceReq); err != nil {
		return nil, fmt.Errorf("failed to write announce request: %w", err)
	}

	buf := make([]byte, 2048)
	n, err = conn.Read(buf)
	if err != nil {
		return nil, fmt.Errorf("failed to read announce response: %w", err)
	}

	return parseUDPAnnounceResponse(buf[:n], txID, isYggdrasilTrackerHost(u.Hostname()))
}

// ParseUDPAnnounceResponse decodes a raw BEP 15 announce response buffer.
func ParseUDPAnnounceResponse(data []byte, expectedTxID uint32) (*AnnounceResult, error) {
	return parseUDPAnnounceResponse(data, expectedTxID, false)
}

func parseUDPAnnounceResponse(data []byte, expectedTxID uint32, ipv6Peers bool) (*AnnounceResult, error) {
	if len(data) < 20 {
		return nil, errors.New("announce response too short")
	}

	action := binary.BigEndian.Uint32(data[0:4])
	txID := binary.BigEndian.Uint32(data[4:8])

	if action == uint32(ActionError) {
		msg := string(data[8:])
		return nil, fmt.Errorf("%w: %s", ErrTrackerResponse, msg)
	}

	if action != uint32(ActionAnnounce) {
		return nil, fmt.Errorf("unexpected action in response: %d", action)
	}

	if expectedTxID != 0 && txID != expectedTxID {
		return nil, ErrTransactionMismatch
	}

	interval := int(binary.BigEndian.Uint32(data[8:12]))
	leechers := int(binary.BigEndian.Uint32(data[12:16]))
	seeders := int(binary.BigEndian.Uint32(data[16:20]))

	peersData := data[20:]
	var peers []PeerEndpoint
	if ipv6Peers {
		peers = ParseCompactIPv6Peers(peersData)
	} else {
		peers = ParseCompactIPv4Peers(peersData)
	}

	return &AnnounceResult{
		Interval: interval,
		Leechers: leechers,
		Seeders:  seeders,
		Peers:    peers,
	}, nil
}

// ParseCompactIPv4Peers parses binary compact IPv4 peer list (6 bytes each).
func ParseCompactIPv4Peers(data []byte) []PeerEndpoint {
	var peers []PeerEndpoint
	for len(data) >= 6 {
		ip := net.IPv4(data[0], data[1], data[2], data[3])
		port := int(binary.BigEndian.Uint16(data[4:6]))
		if port > 0 && !ip.IsUnspecified() {
			peers = append(peers, PeerEndpoint{
				IP:   ip,
				Port: port,
				Raw:  net.JoinHostPort(ip.String(), strconv.Itoa(port)),
			})
		}
		data = data[6:]
	}
	return peers
}

// ParseCompactIPv6Peers parses BEP 7 compact IPv6 peers (16-byte IP + port).
func ParseCompactIPv6Peers(data []byte) []PeerEndpoint {
	var peers []PeerEndpoint
	for len(data) >= 18 {
		ip := append(net.IP(nil), data[:16]...)
		port := int(binary.BigEndian.Uint16(data[16:18]))
		if port > 0 && !ip.IsUnspecified() {
			peers = append(peers, PeerEndpoint{
				IP:   ip,
				Port: port,
				Raw:  net.JoinHostPort(ip.String(), strconv.Itoa(port)),
			})
		}
		data = data[18:]
	}
	return peers
}
