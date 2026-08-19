package transport

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"time"
)

const (
	// STUN Magic Cookie (RFC 5389)
	stunMagicCookie = 0x2112A442

	// STUN Message Types
	stunBindingRequest  = 0x0001
	stunBindingResponse = 0x0101

	// STUN Attribute Types
	attrMappedAddress       = 0x0001
	attrXorMappedAddress    = 0x0020
	attrXorMappedAddressAlt = 0x8020
	attrSoftware            = 0x8022
	attrFingerprint         = 0x8028

	// Default Public STUN Servers
	DefaultSTUNGoogle1    = "stun.l.google.com:19302"
	DefaultSTUNGoogle2    = "stun1.l.google.com:19302"
	DefaultSTUNCloudflare = "stun.cloudflare.com:3478"
)

var (
	ErrSTUNTimeout         = errors.New("STUN request timed out")
	ErrInvalidSTUNResponse = errors.New("invalid STUN response format")
	ErrSTUNTorBlocked      = errors.New("STUN over UDP is strictly disabled when Tor proxy is enabled")
)

// NATType describes the classification of the local NAT device.
type NATType string

const (
	NATTypeUnknown        NATType = "UNKNOWN"
	NATTypeOpen           NATType = "OPEN_INTERNET"
	NATTypeFullCone       NATType = "FULL_CONE"
	NATTypeRestrictedCone NATType = "RESTRICTED_CONE"
	NATTypePortRestricted NATType = "PORT_RESTRICTED"
	NATTypeSymmetric      NATType = "SYMMETRIC"
	NATTypeBlocked        NATType = "BLOCKED"
)

// STUNMappedAddress represents the external IP and port discovered via STUN.
type STUNMappedAddress struct {
	IP   net.IP
	Port int
}

func (a *STUNMappedAddress) String() string {
	if a == nil || a.IP == nil {
		return ""
	}
	if a.IP.To4() != nil {
		return fmt.Sprintf("%s:%d", a.IP.String(), a.Port)
	}
	return fmt.Sprintf("[%s]:%d", a.IP.String(), a.Port)
}

// NATDiagnostics contains full telemetry on the current NAT environment.
type NATDiagnostics struct {
	NATType        NATType `json:"nat_type"`
	PublicEndpoint string  `json:"public_endpoint"`
	LocalIP        string  `json:"local_ip"`
	UPnPMapped     bool    `json:"upnp_mapped"`
	UPnPExternalIP string  `json:"upnp_external_ip"`
	UPnPMappedPort int     `json:"upnp_mapped_port"`
	UPnPService    string  `json:"upnp_service"`
	UPnPError      string  `json:"upnp_error,omitempty"`
	CheckedAt      int64   `json:"checked_at"`
}

// BuildSTUNBindingRequest generates a 20-byte RFC 5389 STUN Binding Request packet.
func BuildSTUNBindingRequest() ([]byte, [12]byte, error) {
	packet := make([]byte, 20)

	// Message Type: Binding Request (0x0001)
	binary.BigEndian.PutUint16(packet[0:2], stunBindingRequest)

	// Message Length: 0 attributes initially
	binary.BigEndian.PutUint16(packet[2:4], 0)

	// Magic Cookie
	binary.BigEndian.PutUint32(packet[4:8], stunMagicCookie)

	// Transaction ID (96 bits / 12 bytes)
	var txID [12]byte
	if _, err := rand.Read(txID[:]); err != nil {
		return nil, txID, err
	}
	copy(packet[8:20], txID[:])

	return packet, txID, nil
}

// ParseSTUNResponse decodes an incoming STUN packet and extracts the XOR-MAPPED-ADDRESS or MAPPED-ADDRESS.
func ParseSTUNResponse(data []byte, expectedTxID [12]byte) (*STUNMappedAddress, error) {
	if len(data) < 20 {
		return nil, ErrInvalidSTUNResponse
	}

	msgType := binary.BigEndian.Uint16(data[0:2])
	if msgType != stunBindingResponse {
		return nil, fmt.Errorf("unexpected STUN message type: 0x%04x", msgType)
	}

	msgLen := int(binary.BigEndian.Uint16(data[2:4]))
	cookie := binary.BigEndian.Uint32(data[4:8])
	if cookie != stunMagicCookie {
		return nil, fmt.Errorf("invalid STUN magic cookie: 0x%08x", cookie)
	}

	for i := 0; i < 12; i++ {
		if data[8+i] != expectedTxID[i] {
			return nil, errors.New("STUN transaction ID mismatch")
		}
	}

	if len(data) < 20+msgLen {
		return nil, ErrInvalidSTUNResponse
	}

	// Parse Attributes
	offset := 20
	end := 20 + msgLen

	var mappedAddr *STUNMappedAddress

	for offset+4 <= end {
		attrType := binary.BigEndian.Uint16(data[offset : offset+2])
		attrLen := int(binary.BigEndian.Uint16(data[offset+2 : offset+4]))
		offset += 4

		if offset+attrLen > end {
			break
		}

		attrValue := data[offset : offset+attrLen]
		// Attributes are padded to 4-byte boundaries
		pad := (4 - (attrLen % 4)) % 4
		offset += attrLen + pad

		switch attrType {
		case attrXorMappedAddress, attrXorMappedAddressAlt:
			if len(attrValue) >= 8 {
				family := attrValue[1]
				xorPort := binary.BigEndian.Uint16(attrValue[2:4])
				port := int(xorPort ^ uint16(stunMagicCookie>>16))

				if family == 0x01 && len(attrValue) >= 8 { // IPv4
					xorIP := binary.BigEndian.Uint32(attrValue[4:8])
					ipBytes := make([]byte, 4)
					binary.BigEndian.PutUint32(ipBytes, xorIP^stunMagicCookie)
					mappedAddr = &STUNMappedAddress{
						IP:   net.IP(ipBytes),
						Port: port,
					}
				} else if family == 0x02 && len(attrValue) >= 20 { // IPv6
					ipBytes := make([]byte, 16)
					var cookieBytes [4]byte
					binary.BigEndian.PutUint32(cookieBytes[:], stunMagicCookie)
					for i := 0; i < 4; i++ {
						ipBytes[i] = attrValue[4+i] ^ cookieBytes[i]
					}
					for i := 0; i < 12; i++ {
						ipBytes[4+i] = attrValue[8+i] ^ expectedTxID[i]
					}
					mappedAddr = &STUNMappedAddress{
						IP:   net.IP(ipBytes),
						Port: port,
					}
				}
			}

		case attrMappedAddress:
			if mappedAddr == nil && len(attrValue) >= 8 {
				family := attrValue[1]
				port := int(binary.BigEndian.Uint16(attrValue[2:4]))
				if family == 0x01 && len(attrValue) >= 8 {
					mappedAddr = &STUNMappedAddress{
						IP:   net.IPv4(attrValue[4], attrValue[5], attrValue[6], attrValue[7]),
						Port: port,
					}
				} else if family == 0x02 && len(attrValue) >= 20 {
					ip := make([]byte, 16)
					copy(ip, attrValue[4:20])
					mappedAddr = &STUNMappedAddress{
						IP:   net.IP(ip),
						Port: port,
					}
				}
			}
		}
	}

	if mappedAddr == nil {
		return nil, errors.New("no mapped address attribute found in STUN response")
	}

	return mappedAddr, nil
}

// QuerySTUNServer sends a single STUN Binding Request to the specified server over UDP.
func QuerySTUNServer(ctx context.Context, stunServer string, torEnabled bool) (*STUNMappedAddress, error) {
	if torEnabled {
		return nil, ErrSTUNTorBlocked
	}

	rAddr, err := net.ResolveUDPAddr("udp4", stunServer)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve STUN server %s: %w", stunServer, err)
	}

	conn, err := net.ListenUDP("udp4", nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create UDP socket: %w", err)
	}
	defer conn.Close()

	req, txID, err := BuildSTUNBindingRequest()
	if err != nil {
		return nil, err
	}

	if _, err := conn.WriteToUDP(req, rAddr); err != nil {
		return nil, fmt.Errorf("failed to send STUN request: %w", err)
	}

	buf := make([]byte, 1024)
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))

	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}

		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			return nil, fmt.Errorf("failed reading STUN response from %s: %w", stunServer, err)
		}

		addr, err := ParseSTUNResponse(buf[:n], txID)
		if err == nil {
			return addr, nil
		}
	}
}

// DetectNATEnvironment performs comprehensive NAT classification using multiple STUN servers.
func DetectNATEnvironment(ctx context.Context, torEnabled bool) *NATDiagnostics {
	diag := &NATDiagnostics{
		NATType:   NATTypeUnknown,
		CheckedAt: time.Now().Unix(),
	}

	if torEnabled {
		diag.NATType = NATTypeBlocked
		return diag
	}

	servers := []string{
		DefaultSTUNGoogle1,
		DefaultSTUNGoogle2,
		DefaultSTUNCloudflare,
	}

	var results []*STUNMappedAddress
	for _, s := range servers {
		sCtx, cancel := context.WithTimeout(ctx, 2500*time.Millisecond)
		addr, err := QuerySTUNServer(sCtx, s, torEnabled)
		cancel()
		if err == nil && addr != nil {
			results = append(results, addr)
		}
	}

	if len(results) == 0 {
		diag.NATType = NATTypeBlocked
		return diag
	}

	diag.PublicEndpoint = results[0].String()

	// Compare mapped ports across different STUN servers to detect Symmetric vs Cone NAT
	if len(results) >= 2 {
		first := results[0]
		second := results[1]

		if first.IP.Equal(second.IP) && first.Port == second.Port {
			diag.NATType = NATTypeRestrictedCone // Port remains consistent across different remote destinations
		} else {
			diag.NATType = NATTypeSymmetric // Port changes based on destination IP
		}
	} else {
		diag.NATType = NATTypeRestrictedCone
	}

	return diag
}
