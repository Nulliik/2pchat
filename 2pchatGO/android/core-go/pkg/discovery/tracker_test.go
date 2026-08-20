package discovery

import (
	"context"
	"encoding/binary"
	"net"
	"strings"
	"testing"
	"time"
)

func TestParseCompactIPv4Peers(t *testing.T) {
	// 2 peers: 192.168.1.50:50001 (0xC0A80132, 0xC351), 10.0.0.1:8080 (0x0A000001, 0x1F90)
	raw := []byte{
		192, 168, 1, 50, 0xC3, 0x51,
		10, 0, 0, 1, 0x1F, 0x90,
	}

	peers := ParseCompactIPv4Peers(raw)
	if len(peers) != 2 {
		t.Fatalf("Expected 2 peers, got %d", len(peers))
	}

	if peers[0].IP.String() != "192.168.1.50" || peers[0].Port != 50001 {
		t.Fatalf("Peer 0 mismatch: %v", peers[0])
	}
	if peers[1].IP.String() != "10.0.0.1" || peers[1].Port != 8080 {
		t.Fatalf("Peer 1 mismatch: %v", peers[1])
	}
}

func TestParseCompactIPv6Peers(t *testing.T) {
	// Yggdrasil IPv6: 200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7 on port 50001
	ip := net.ParseIP("200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7")
	raw := make([]byte, 18)
	copy(raw[0:16], ip.To16())
	binary.BigEndian.PutUint16(raw[16:18], 50001)

	peers := ParseCompactIPv6Peers(raw)
	if len(peers) != 1 {
		t.Fatalf("Expected 1 IPv6 peer, got %d", len(peers))
	}

	expectedRaw := "[200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7]:50001"
	if peers[0].String() != expectedRaw {
		t.Fatalf("IPv6 endpoint string mismatch: got %s, expected %s", peers[0].String(), expectedRaw)
	}
}

func TestParseUDPAnnounceResponse(t *testing.T) {
	// Construct simulated BEP 15 UDP Announce Response
	buf := make([]byte, 20+6)
	binary.BigEndian.PutUint32(buf[0:4], uint32(ActionAnnounce))
	binary.BigEndian.PutUint32(buf[4:8], 12345)      // txID
	binary.BigEndian.PutUint32(buf[8:12], 1800)      // interval
	binary.BigEndian.PutUint32(buf[12:16], 1)        // leechers
	binary.BigEndian.PutUint32(buf[16:20], 5)        // seeders
	copy(buf[20:], []byte{127, 0, 0, 1, 0x1F, 0x90}) // 127.0.0.1:8080

	res, err := ParseUDPAnnounceResponse(buf, 12345)
	if err != nil {
		t.Fatalf("ParseUDPAnnounceResponse failed: %v", err)
	}

	if res.Interval != 1800 || res.Seeders != 5 || len(res.Peers) != 1 {
		t.Fatalf("Result fields mismatch: %+v", res)
	}
	if res.Peers[0].Raw != "127.0.0.1:8080" {
		t.Fatalf("Peer address mismatch: %s", res.Peers[0].Raw)
	}
}

func TestParseUDPAnnounceResponseIPv6(t *testing.T) {
	// 18 bytes IPv6 payload in UDP response
	yggIP := net.ParseIP("200:182d:e207:ca9b:8205:5f82:3aa:c4f7")
	buf := make([]byte, 20+18)
	binary.BigEndian.PutUint32(buf[0:4], uint32(ActionAnnounce))
	binary.BigEndian.PutUint32(buf[4:8], 12345) // txID
	binary.BigEndian.PutUint32(buf[8:12], 1800) // interval
	binary.BigEndian.PutUint32(buf[12:16], 1)   // leechers
	binary.BigEndian.PutUint32(buf[16:20], 5)   // seeders
	copy(buf[20:36], yggIP.To16())
	binary.BigEndian.PutUint16(buf[36:38], 50001)

	res, err := ParseUDPAnnounceResponse(buf, 12345, true)
	if err != nil {
		t.Fatalf("ParseUDPAnnounceResponseIPv6 failed: %v", err)
	}

	if len(res.Peers) != 1 {
		t.Fatalf("Expected 1 peer, got %d", len(res.Peers))
	}
	expectedRaw := "[200:182d:e207:ca9b:8205:5f82:3aa:c4f7]:50001"
	if res.Peers[0].String() != expectedRaw {
		t.Fatalf("Peer mismatch: got %s, expected %s", res.Peers[0].String(), expectedRaw)
	}
}

func TestUDPTrackerTorDisabled(t *testing.T) {
	client := NewUDPTrackerClient(true, 2*time.Second) // Tor enabled
	var infoHash, peerID [20]byte
	_, err := client.Announce(context.Background(), "udp://tracker.openbittorrent.com:6969", infoHash, peerID, 50001)
	if err != ErrUDPDisabledUnderTor {
		t.Fatalf("Expected ErrUDPDisabledUnderTor, got: %v", err)
	}
}

func TestParseHTTPAnnounceResponse(t *testing.T) {
	// Simulated Bencoded response: d8:intervali900e5:peers6:\x7f\x00\x00\x01\x1f\x90e
	bencoded := []byte("d8:intervali900e5:peers6:\x7f\x00\x00\x01\x1f\x90e")
	res, err := ParseHTTPAnnounceResponse(bencoded)
	if err != nil {
		t.Fatalf("ParseHTTPAnnounceResponse failed: %v", err)
	}

	if res.Interval != 900 {
		t.Fatalf("Expected interval 900, got %d", res.Interval)
	}
	if len(res.Peers) != 1 || res.Peers[0].Raw != "127.0.0.1:8080" {
		t.Fatalf("Expected peer 127.0.0.1:8080, got: %+v", res.Peers)
	}
}

func TestParseHTTPAnnounceResponsePeers6(t *testing.T) {
	// BEP 7: peers6 containing 18-byte Yggdrasil IPv6
	yggIP := net.ParseIP("200:182d:e207:ca9b:8205:5f82:3aa:c4f7")
	raw18 := make([]byte, 18)
	copy(raw18[0:16], yggIP.To16())
	binary.BigEndian.PutUint16(raw18[16:18], 50001)

	bencoded := []byte("d8:intervali900e6:peers618:" + string(raw18) + "e")
	res, err := ParseHTTPAnnounceResponse(bencoded)
	if err != nil {
		t.Fatalf("ParseHTTPAnnounceResponsePeers6 failed: %v", err)
	}

	if len(res.Peers) != 1 {
		t.Fatalf("Expected 1 peer from peers6, got %d", len(res.Peers))
	}
	expectedRaw := "[200:182d:e207:ca9b:8205:5f82:3aa:c4f7]:50001"
	if res.Peers[0].String() != expectedRaw {
		t.Fatalf("peers6 endpoint mismatch: got %s, expected %s", res.Peers[0].String(), expectedRaw)
	}
}

func TestPeerEndpointString(t *testing.T) {
	p := PeerEndpoint{
		IP:   net.ParseIP("192.168.0.10"),
		Port: 50001,
	}
	if p.String() != "192.168.0.10:50001" {
		t.Fatalf("String mismatch: %s", p.String())
	}
}

func TestParseUDPAnnounceResponseErrorAction(t *testing.T) {
	// Action = 3 (Error), txID = 9999, message = "Connection limit exceeded"
	errMsg := "Connection limit exceeded"
	buf := make([]byte, 8+len(errMsg))
	binary.BigEndian.PutUint32(buf[0:4], uint32(ActionError))
	binary.BigEndian.PutUint32(buf[4:8], 9999)
	copy(buf[8:], []byte(errMsg))

	_, err := ParseUDPAnnounceResponse(buf, 9999)
	if err == nil {
		t.Fatalf("Expected error when Action == ActionError")
	}
	if !strings.Contains(err.Error(), errMsg) {
		t.Fatalf("Unexpected error message: %v", err)
	}
}

func TestParseUDPAnnounceResponseTruncated(t *testing.T) {
	// Truncated buffer (< 20 bytes)
	buf := make([]byte, 12)
	binary.BigEndian.PutUint32(buf[0:4], uint32(ActionAnnounce))
	binary.BigEndian.PutUint32(buf[4:8], 12345)

	_, err := ParseUDPAnnounceResponse(buf, 12345)
	if err == nil {
		t.Fatalf("Expected error for truncated response")
	}
}

func TestParseUDPAnnounceTxIDMismatch(t *testing.T) {
	buf := make([]byte, 20)
	binary.BigEndian.PutUint32(buf[0:4], uint32(ActionAnnounce))
	binary.BigEndian.PutUint32(buf[4:8], 11111) // mismatch

	_, err := ParseUDPAnnounceResponse(buf, 22222)
	if err != ErrTransactionMismatch {
		t.Fatalf("Expected ErrTransactionMismatch, got: %v", err)
	}
}

func TestParseHTTPAnnounceResponseFailureReason(t *testing.T) {
	failurePayload := []byte("d14:failure reason25:Torrent disabled on servere")
	_, err := ParseHTTPAnnounceResponse(failurePayload)
	if err == nil {
		t.Fatalf("Expected error on failure reason HTTP response")
	}
}

func TestParseHTTPAnnounceResponseEmpty(t *testing.T) {
	_, err := ParseHTTPAnnounceResponse([]byte{})
	if err == nil {
		t.Fatalf("Expected error on empty HTTP response")
	}
}
