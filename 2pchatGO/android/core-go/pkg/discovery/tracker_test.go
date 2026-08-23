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
