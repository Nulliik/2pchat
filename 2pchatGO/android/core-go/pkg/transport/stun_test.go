package transport

import (
	"context"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

func TestBuildSTUNBindingRequest(t *testing.T) {
	req, txID, err := BuildSTUNBindingRequest()
	if err != nil {
		t.Fatalf("BuildSTUNBindingRequest failed: %v", err)
	}

	if len(req) != 20 {
		t.Fatalf("Expected 20-byte STUN header, got %d", len(req))
	}

	msgType := binary.BigEndian.Uint16(req[0:2])
	if msgType != stunBindingRequest {
		t.Errorf("Expected message type 0x%04x, got 0x%04x", stunBindingRequest, msgType)
	}

	cookie := binary.BigEndian.Uint32(req[4:8])
	if cookie != stunMagicCookie {
		t.Errorf("Expected magic cookie 0x%08x, got 0x%08x", stunMagicCookie, cookie)
	}

	for i := 0; i < 12; i++ {
		if req[8+i] != txID[i] {
			t.Errorf("Transaction ID mismatch at byte %d", i)
		}
	}
}

func TestParseSTUNResponseXorMappedIPv4(t *testing.T) {
	var txID [12]byte
	for i := range txID {
		txID[i] = byte(i + 1)
	}

	// Craft a valid STUN Binding Response with XOR-MAPPED-ADDRESS
	resp := make([]byte, 32)
	binary.BigEndian.PutUint16(resp[0:2], stunBindingResponse)
	binary.BigEndian.PutUint16(resp[2:4], 12) // Attribute length: 4 (header) + 8 (body) = 12
	binary.BigEndian.PutUint32(resp[4:8], stunMagicCookie)
	copy(resp[8:20], txID[:])

	// Attribute Header
	binary.BigEndian.PutUint16(resp[20:22], attrXorMappedAddress)
	binary.BigEndian.PutUint16(resp[22:24], 8) // Value length: 8 bytes

	// Attribute Value: Family 0x01 (IPv4), XOR Port, XOR IP
	targetPort := 54321
	targetIP := net.IPv4(203, 0, 113, 42).To4()

	resp[24] = 0x00
	resp[25] = 0x01 // IPv4

	xorPort := uint16(targetPort) ^ uint16(stunMagicCookie>>16)
	binary.BigEndian.PutUint16(resp[26:28], xorPort)

	xorIP := binary.BigEndian.Uint32(targetIP) ^ stunMagicCookie
	binary.BigEndian.PutUint32(resp[28:32], xorIP)

	addr, err := ParseSTUNResponse(resp, txID)
	if err != nil {
		t.Fatalf("ParseSTUNResponse failed: %v", err)
	}

	if addr.Port != targetPort {
		t.Errorf("Expected port %d, got %d", targetPort, addr.Port)
	}
	if !addr.IP.Equal(targetIP) {
		t.Errorf("Expected IP %s, got %s", targetIP, addr.IP)
	}
	if addr.String() != "203.0.113.42:54321" {
		t.Errorf("Expected string 203.0.113.42:54321, got %s", addr.String())
	}
}

func TestSTUNTorBlockedGuard(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Second)
	defer cancel()

	_, err := QuerySTUNServer(ctx, "stun.l.google.com:19302", true)
	if err != ErrSTUNTorBlocked {
		t.Fatalf("Expected ErrSTUNTorBlocked when Tor is enabled, got: %v", err)
	}

	diag := DetectNATEnvironment(ctx, true)
	if diag.NATType != NATTypeBlocked {
		t.Errorf("Expected NATTypeBlocked for Tor mode, got %v", diag.NATType)
	}
}
