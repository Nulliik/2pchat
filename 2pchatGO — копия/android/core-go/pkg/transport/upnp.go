package transport

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	ssdpMulticastAddr = "239.255.255.250:1900"
	ssdpDiscoverMsg   = "M-SEARCH * HTTP/1.1\r\n" +
		"HOST: 239.255.255.250:1900\r\n" +
		"MAN: \"ssdp:discover\"\r\n" +
		"MX: 2\r\n" +
		"ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"

	upnpURNIPConnection  = "urn:schemas-upnp-org:service:WANIPConnection:1"
	upnpURNPPPConnection = "urn:schemas-upnp-org:service:WANPPPConnection:1"
)

var (
	ErrUPnPTorBlocked    = errors.New("UPnP discovery is disabled when Tor mode is active")
	ErrUPnPNoGateway     = errors.New("no UPnP InternetGatewayDevice found on local network")
	ErrUPnPMappingFailed = errors.New("UPnP port mapping request failed")
)

// UPnPMapper manages automatic UPnP IGD port mappings.
type UPnPMapper struct {
	mu          sync.Mutex
	controlURL  string
	serviceType string
	externalIP  string
	mappedPort  int
	localIP     string
	stopRenewCh chan struct{}
	torEnabled  bool
	active      bool
}

// NewUPnPMapper creates a new UPnP manager instance.
func NewUPnPMapper(torEnabled bool) *UPnPMapper {
	return &UPnPMapper{
		torEnabled:  torEnabled,
		stopRenewCh: make(chan struct{}),
	}
}

// BuildSSDPDiscoverPacket returns the raw SSDP discovery payload.
func BuildSSDPDiscoverPacket() []byte {
	return []byte(ssdpDiscoverMsg)
}

// BuildAddPortMappingSOAP creates the SOAP envelope for AddPortMapping.
func BuildAddPortMappingSOAP(serviceType, localIP string, port int, desc string, leaseSeconds int) string {
	return fmt.Sprintf(`<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:AddPortMapping xmlns:u="%s">
      <NewRemoteHost></NewRemoteHost>
      <NewExternalPort>%d</NewExternalPort>
      <NewProtocol>TCP</NewProtocol>
      <NewInternalPort>%d</NewInternalPort>
      <NewInternalClient>%s</NewInternalClient>
      <NewEnabled>1</NewEnabled>
      <NewPortMappingDescription>%s</NewPortMappingDescription>
      <NewLeaseDuration>%d</NewLeaseDuration>
    </u:AddPortMapping>
  </s:Body>
</s:Envelope>`, serviceType, port, port, localIP, desc, leaseSeconds)
}

// BuildDeletePortMappingSOAP creates the SOAP envelope for DeletePortMapping.
func BuildDeletePortMappingSOAP(serviceType string, port int) string {
	return fmt.Sprintf(`<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:DeletePortMapping xmlns:u="%s">
      <NewRemoteHost></NewRemoteHost>
      <NewExternalPort>%d</NewExternalPort>
      <NewProtocol>TCP</NewProtocol>
    </u:DeletePortMapping>
  </s:Body>
</s:Envelope>`, serviceType, port)
}

// DiscoverAndMapPort attempts SSDP discovery and forwards the given TCP port.
func (u *UPnPMapper) DiscoverAndMapPort(ctx context.Context, port int) error {
	u.mu.Lock()
	defer u.mu.Unlock()

	if u.torEnabled {
		return ErrUPnPTorBlocked
	}

	loc, err := discoverSSDPLocation(ctx)
	if err != nil {
		// Fallback to NAT-PMP on default gateway
		return u.tryNATPMP(port)
	}

	controlURL, sType, err := parseRootDeviceXML(ctx, loc)
	if err != nil {
		return err
	}

	localIP, err := getOutboundLocalIP()
	if err != nil {
		return err
	}

	// Request Port Mapping via SOAP
	soapReq := BuildAddPortMappingSOAP(sType, localIP, port, "2PChat P2P Relay", 3600)
	req, err := http.NewRequestWithContext(ctx, "POST", controlURL, strings.NewReader(soapReq))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "text/xml; charset=\"utf-8\"")
	req.Header.Set("SOAPAction", fmt.Sprintf("\"%s#AddPortMapping\"", sType))

	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("SOAP AddPortMapping error: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return fmt.Errorf("SOAP AddPortMapping failed with status %d", resp.StatusCode)
	}

	u.controlURL = controlURL
	u.serviceType = sType
	u.mappedPort = port
	u.localIP = localIP
	u.active = true

	// Query External IP
	u.externalIP = u.queryExternalIP(ctx)

	// Start periodic renewal with fresh channel
	u.stopRenewCh = make(chan struct{})
	go u.renewLoop()

	return nil
}

func (u *UPnPMapper) queryExternalIP(ctx context.Context) string {
	if u.controlURL == "" || u.serviceType == "" {
		return ""
	}
	soapReq := fmt.Sprintf(`<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetExternalIPAddress xmlns:u="%s"/>
  </s:Body>
</s:Envelope>`, u.serviceType)

	req, err := http.NewRequestWithContext(ctx, "POST", u.controlURL, strings.NewReader(soapReq))
	if err != nil {
		return ""
	}
	req.Header.Set("Content-Type", "text/xml; charset=\"utf-8\"")
	req.Header.Set("SOAPAction", fmt.Sprintf("\"%s#GetExternalIPAddress\"", u.serviceType))

	client := &http.Client{Timeout: 4 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	// Parse <NewExternalIPAddress>x.x.x.x</NewExternalIPAddress>
	startTag := "<NewExternalIPAddress>"
	endTag := "</NewExternalIPAddress>"
	sIdx := strings.Index(string(body), startTag)
	eIdx := strings.Index(string(body), endTag)
	if sIdx != -1 && eIdx != -1 && eIdx > sIdx+len(startTag) {
		return string(body[sIdx+len(startTag) : eIdx])
	}
	return ""
}

// Unmap cleans up active port mappings.
func (u *UPnPMapper) Unmap() {
	u.mu.Lock()
	defer u.mu.Unlock()

	if !u.active || u.controlURL == "" {
		return
	}

	select {
	case <-u.stopRenewCh:
	default:
		close(u.stopRenewCh)
	}

	soapReq := BuildDeletePortMappingSOAP(u.serviceType, u.mappedPort)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, "POST", u.controlURL, strings.NewReader(soapReq))
	if err == nil {
		req.Header.Set("Content-Type", "text/xml; charset=\"utf-8\"")
		req.Header.Set("SOAPAction", fmt.Sprintf("\"%s#DeletePortMapping\"", u.serviceType))
		client := &http.Client{Timeout: 3 * time.Second}
		_, _ = client.Do(req)
	}

	u.active = false
}

func (u *UPnPMapper) renewLoop() {
	ticker := time.NewTicker(30 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-u.stopRenewCh:
			return
		case <-ticker.C:
			u.mu.Lock()
			if !u.active {
				u.mu.Unlock()
				return
			}
			soapReq := BuildAddPortMappingSOAP(u.serviceType, u.localIP, u.mappedPort, "2PChat P2P Relay", 3600)
			ctrl := u.controlURL
			sType := u.serviceType
			u.mu.Unlock()

			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			req, err := http.NewRequestWithContext(ctx, "POST", ctrl, strings.NewReader(soapReq))
			if err == nil {
				req.Header.Set("Content-Type", "text/xml; charset=\"utf-8\"")
				req.Header.Set("SOAPAction", fmt.Sprintf("\"%s#AddPortMapping\"", sType))
				client := &http.Client{Timeout: 5 * time.Second}
				_, _ = client.Do(req)
			}
			cancel()
		}
	}
}

// GetStatus returns current UPnP diagnostics.
func (u *UPnPMapper) GetStatus() (mapped bool, extIP string, port int, service string) {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.active, u.externalIP, u.mappedPort, u.serviceType
}

func discoverSSDPLocation(ctx context.Context) (string, error) {
	rAddr, err := net.ResolveUDPAddr("udp4", ssdpMulticastAddr)
	if err != nil {
		return "", err
	}

	conn, err := net.ListenUDP("udp4", nil)
	if err != nil {
		return "", err
	}
	defer conn.Close()

	if _, err := conn.WriteToUDP(BuildSSDPDiscoverPacket(), rAddr); err != nil {
		return "", err
	}

	buf := make([]byte, 2048)
	_ = conn.SetReadDeadline(time.Now().Add(2500 * time.Millisecond))

	for {
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		default:
		}

		n, _, err := conn.ReadFromUDP(buf)
		if err != nil {
			return "", ErrUPnPNoGateway
		}

		respStr := string(buf[:n])
		lines := strings.Split(respStr, "\r\n")
		for _, l := range lines {
			lower := strings.ToLower(l)
			if strings.HasPrefix(lower, "location:") {
				loc := strings.TrimSpace(l[9:])
				return loc, nil
			}
		}
	}
}

func parseRootDeviceXML(ctx context.Context, locationURL string) (controlURL, serviceType string, err error) {
	client := &http.Client{Timeout: 4 * time.Second}
	req, err := http.NewRequestWithContext(ctx, "GET", locationURL, nil)
	if err != nil {
		return "", "", err
	}

	resp, err := client.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", "", err
	}

	// Parse XML to locate WANIPConnection or WANPPPConnection service
	type Service struct {
		ServiceType string `xml:"serviceType"`
		ControlURL  string `xml:"controlURL"`
	}
	type Device struct {
		XMLName     xml.Name  `xml:"root"`
		ServiceList []Service `xml:"device>serviceList>service"`
	}

	var root Device
	decoder := xml.NewDecoder(bytes.NewReader(body))
	_ = decoder.Decode(&root)

	// Direct text fallback search
	for _, target := range []string{upnpURNIPConnection, upnpURNPPPConnection} {
		if strings.Contains(string(body), target) {
			sIdx := strings.Index(string(body), target)
			sub := string(body[sIdx:])
			cIdx := strings.Index(sub, "<controlURL>")
			ceIdx := strings.Index(sub, "</controlURL>")
			if cIdx != -1 && ceIdx != -1 && ceIdx > cIdx+12 {
				relURL := sub[cIdx+12 : ceIdx]
				parsedLoc, _ := url.Parse(locationURL)
				parsedCtrl, _ := url.Parse(relURL)
				return parsedLoc.ResolveReference(parsedCtrl).String(), target, nil
			}
		}
	}

	return "", "", errors.New("no compatible WAN IP/PPP connection service found in device XML")
}

// NAT-PMP (RFC 6886) fallback
func (u *UPnPMapper) tryNATPMP(port int) error {
	// Query NAT-PMP on default gateway (usually 192.168.1.1 or 192.168.0.1 on port 5351)
	gateways := []string{"192.168.1.1:5351", "192.168.0.1:5351", "10.0.0.1:5351", "10.0.2.2:5351"}

	for _, gw := range gateways {
		rAddr, err := net.ResolveUDPAddr("udp4", gw)
		if err != nil {
			continue
		}

		conn, err := net.DialUDP("udp4", nil, rAddr)
		if err != nil {
			continue
		}

		// NAT-PMP Port Mapping Request (12 bytes)
		// Version (0), Opcode (2 for TCP), Reserved (0), Internal Port (2 bytes), External Port (2 bytes), Lifetime (4 bytes)
		req := make([]byte, 12)
		req[0] = 0 // Vers
		req[1] = 2 // Opcode: TCP Mapping
		binary.BigEndian.PutUint16(req[4:6], uint16(port))
		binary.BigEndian.PutUint16(req[6:8], uint16(port))
		binary.BigEndian.PutUint32(req[8:12], 3600) // 1 hr lifetime

		_ = conn.SetDeadline(time.Now().Add(800 * time.Millisecond))
		if _, err := conn.Write(req); err != nil {
			conn.Close()
			continue
		}

		resp := make([]byte, 16)
		n, err := conn.Read(resp)
		conn.Close()
		if err == nil && n >= 16 && resp[1] == 130 && binary.BigEndian.Uint16(resp[2:4]) == 0 {
			// Success! ResultCode == 0
			extPort := int(binary.BigEndian.Uint16(resp[10:12]))
			u.mappedPort = extPort
			u.serviceType = "NAT-PMP"
			u.active = true
			return nil
		}
	}

	return ErrUPnPNoGateway
}

func getOutboundLocalIP() (string, error) {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "127.0.0.1", nil
	}
	defer conn.Close()
	localAddr := conn.LocalAddr().(*net.UDPAddr)
	return localAddr.IP.String(), nil
}

// ParsePortMappingResponse parses the status code from an XML SOAP response.
func ParsePortMappingResponse(body []byte) bool {
	return !strings.Contains(string(body), "errorCode") && !strings.Contains(string(body), "Fault")
}

// BuildNATPMPPacket creates an RFC 6886 UDP request packet.
func BuildNATPMPPacket(internalPort, externalPort, lifetimeSec int) []byte {
	packet := make([]byte, 12)
	packet[0] = 0 // Version
	packet[1] = 2 // Opcode: TCP
	binary.BigEndian.PutUint16(packet[4:6], uint16(internalPort))
	binary.BigEndian.PutUint16(packet[6:8], uint16(externalPort))
	binary.BigEndian.PutUint32(packet[8:12], uint32(lifetimeSec))
	return packet
}
