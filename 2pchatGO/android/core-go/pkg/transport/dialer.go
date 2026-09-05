package transport

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/proxy"
)

const (
	DefaultDialTimeout    = 30 * time.Second
	DefaultTorDialTimeout = 90 * time.Second
	DefaultTorProxy       = "127.0.0.1:9050"
	DefaultYggdrasilProxy = "127.0.0.1:9053"
)

var (
	ErrUDPOverTorNotSupported = errors.New("UDP transport is strictly prohibited when Tor proxy is active")
	ErrInvalidEndpoint        = errors.New("invalid network endpoint format")
)

// NormalizeEndpoint ensures the endpoint has a valid host and port, appending defaultPort if missing.
func NormalizeEndpoint(address string, defaultPort int) string {
	address = strings.TrimSpace(address)
	if address == "" {
		return ""
	}
	if defaultPort <= 0 {
		defaultPort = 50001
	}
	// If already valid host:port
	if host, port, err := net.SplitHostPort(address); err == nil && host != "" && port != "" {
		return net.JoinHostPort(host, port)
	}
	// If IPv6 literal with brackets [::1] or without brackets ::1
	clean := strings.Trim(address, "[]")
	if ip := net.ParseIP(clean); ip != nil && ip.To4() == nil {
		return net.JoinHostPort(clean, strconv.Itoa(defaultPort))
	}
	// Hostname (.onion or domain) or IPv4
	return net.JoinHostPort(clean, strconv.Itoa(defaultPort))
}

// FindAvailablePort checks if preferredPort on host is available. If not, it requests an ephemeral free port from the OS.
func FindAvailablePort(host string, preferredPort int) (int, error) {
	if host == "" {
		host = "127.0.0.1"
	}
	if preferredPort > 0 {
		addr := fmt.Sprintf("%s:%d", host, preferredPort)
		l, err := net.Listen("tcp", addr)
		if err == nil {
			port := l.Addr().(*net.TCPAddr).Port
			_ = l.Close()
			return port, nil
		}
	}
	// Ask OS for a free port
	addr := fmt.Sprintf("%s:0", host)
	l, err := net.Listen("tcp", addr)
	if err != nil {
		return 0, fmt.Errorf("failed to obtain free port from OS: %w", err)
	}
	port := l.Addr().(*net.TCPAddr).Port
	_ = l.Close()
	return port, nil
}

// TransportType represents the underlying network transport used for dialing.
type TransportType = TransportClass

// YggdrasilMode defines whether Yggdrasil connections are routed via SOCKS5 proxy (default) or OS VPN TUN.
type YggdrasilMode string

const (
	YggdrasilModeProxy YggdrasilMode = "proxy"
	YggdrasilModeVPN   YggdrasilMode = "vpn"
)

// AdaptiveDialer automatically routes outbound connections through Direct TCP, Yggdrasil, Tor SOCKS5, or Blind Relay.
type AdaptiveDialer struct {
	mu             sync.RWMutex
	policy         NetworkPolicy
	torProxyAddr   string
	proxyEnabled   bool
	yggProxyAddr   string
	yggdrasilMode  YggdrasilMode
	relayEndpoints []string
	directDialer   *net.Dialer
	torDialer      proxy.Dialer
	yggDialer      proxy.Dialer
	holePuncher    *HolePuncher
	timeout        time.Duration
}

var PublicDNSServers = []string{
	"1.1.1.1:53",
	"8.8.8.8:53",
	"9.9.9.9:53",
	"10.0.2.3:53", // Android emulator default gateway DNS
}

var FallbackResolver = &net.Resolver{
	PreferGo: true,
	Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		d := net.Dialer{Timeout: 2 * time.Second}
		for _, dns := range PublicDNSServers {
			conn, err := d.DialContext(ctx, "udp", dns)
			if err == nil {
				return conn, nil
			}
		}
		return d.DialContext(ctx, network, address)
	},
}

// NewAdaptiveDialer creates a new AdaptiveDialer.
func NewAdaptiveDialer(torProxyAddr string, proxyEnabled bool, timeout time.Duration) *AdaptiveDialer {
	if torProxyAddr == "" {
		torProxyAddr = DefaultTorProxy
	}
	if timeout <= 0 {
		timeout = DefaultDialTimeout
	}

	d := &AdaptiveDialer{
		policy:         PolicySpeed,
		torProxyAddr:   torProxyAddr,
		proxyEnabled:   proxyEnabled,
		yggProxyAddr:   DefaultYggdrasilProxy,
		yggdrasilMode:  YggdrasilModeProxy,
		relayEndpoints: make([]string, 0),
		timeout:        timeout,
		directDialer: &net.Dialer{
			Timeout:   timeout,
			KeepAlive: 30 * time.Second,
			DualStack: true,
			Resolver:  FallbackResolver,
		},
	}
	d.initTorDialer()
	d.initYggDialer()
	return d
}

// SetPolicy sets the network policy enforced by this dialer.
func (d *AdaptiveDialer) SetPolicy(p NetworkPolicy) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.policy = p
}

// GetPolicy returns the current network policy enforced by this dialer.
func (d *AdaptiveDialer) GetPolicy() NetworkPolicy {
	d.mu.RLock()
	defer d.mu.RUnlock()
	return d.policy
}

// SetResolver overrides the DNS resolver used by directDialer (used for testing and DNS leak verification).
func (d *AdaptiveDialer) SetResolver(r *net.Resolver) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.directDialer != nil {
		d.directDialer.Resolver = r
	}
}

func (d *AdaptiveDialer) initTorDialer() {
	dialer, err := proxy.SOCKS5("tcp", d.torProxyAddr, nil, proxy.Direct)
	if err == nil {
		d.torDialer = dialer
	}
}

func (d *AdaptiveDialer) initYggDialer() {
	dialer, err := proxy.SOCKS5("tcp", d.yggProxyAddr, nil, proxy.Direct)
	if err == nil {
		d.yggDialer = dialer
	}
}

// SetTorProxy updates the Tor proxy configuration dynamically.
func (d *AdaptiveDialer) SetTorProxy(enabled bool, addr string) {
	d.mu.Lock()
	defer d.mu.Unlock()

	d.proxyEnabled = enabled
	if addr != "" {
		d.torProxyAddr = addr
	}
	d.initTorDialer()
}

// SetYggdrasilConfig updates the Yggdrasil routing mode (Proxy vs VPN) and proxy address dynamically.
func (d *AdaptiveDialer) SetYggdrasilConfig(mode YggdrasilMode, addr string) {
	d.mu.Lock()
	defer d.mu.Unlock()

	if mode == YggdrasilModeVPN {
		d.yggdrasilMode = YggdrasilModeVPN
	} else {
		d.yggdrasilMode = YggdrasilModeProxy
	}

	if addr != "" {
		d.yggProxyAddr = addr
	}
	d.initYggDialer()
}

// GetYggdrasilMode returns the current Yggdrasil operating mode.
func (d *AdaptiveDialer) GetYggdrasilMode() YggdrasilMode {
	d.mu.RLock()
	defer d.mu.RUnlock()
	return d.yggdrasilMode
}

var yggdrasilSubnet = func() *net.IPNet {
	_, subnet, _ := net.ParseCIDR("200::/7")
	return subnet
}()

var (
	cgnatCIDR       = mustParseCIDR("100.64.0.0/10")
	rfc1918_10      = mustParseCIDR("10.0.0.0/8")
	rfc1918_172     = mustParseCIDR("172.16.0.0/12")
	rfc1918_192     = mustParseCIDR("192.168.0.0/16")
	bogon198_18     = mustParseCIDR("198.18.0.0/15")
	bogon192_0_2    = mustParseCIDR("192.0.2.0/24")
	bogon198_51_100 = mustParseCIDR("198.51.100.0/24")
	bogon203_0_113  = mustParseCIDR("203.0.113.0/24")
	bogon240_0_0_0  = mustParseCIDR("240.0.0.0/4")
	ipv6DocCIDR     = mustParseCIDR("2001:db8::/32")
)

func mustParseCIDR(s string) *net.IPNet {
	_, ipnet, err := net.ParseCIDR(s)
	if err != nil {
		panic(err)
	}
	return ipnet
}

// IsYggdrasilIP returns true if the given IP falls within the Yggdrasil 200::/7 address space.
func IsYggdrasilIP(ip net.IP) bool {
	if ip == nil || ip.To4() != nil {
		return false
	}
	if len(ip) == 16 && (ip[0]&0xfe == 0x02) {
		return true
	}
	return yggdrasilSubnet != nil && yggdrasilSubnet.Contains(ip)
}

// IsPrivateOrLocalIP returns true if the host is a loopback, private RFC1918, link-local, or unspecified IP.
func IsPrivateOrLocalIP(host string) bool {
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	if ip4 := ip.To4(); ip4 != nil {
		return ip4.IsLoopback() || ip4.IsPrivate() || ip4.IsLinkLocalUnicast() || ip4.IsLinkLocalMulticast() || ip4.IsUnspecified()
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsUnspecified()
}

// ClassifyEndpoint determines the appropriate architectural TransportClass for a given destination address.
func ClassifyEndpoint(address string) (TransportClass, error) {
	address = strings.TrimSpace(address)
	if address == "" {
		return TransportInvalid, ErrInvalidEndpoint
	}

	host := address
	if strings.Contains(address, ":") {
		h, _, err := net.SplitHostPort(address)
		if err == nil {
			host = h
		}
	}
	host = strings.Trim(host, "[]")
	if host == "" {
		return TransportInvalid, ErrInvalidEndpoint
	}

	lowerHost := strings.ToLower(host)
	if strings.HasSuffix(lowerHost, ".onion") {
		onionPrefix := strings.TrimSuffix(lowerHost, ".onion")
		if len(onionPrefix) != 56 {
			return TransportInvalid, ErrMalformedOnionAddress
		}
		for i := 0; i < len(onionPrefix); i++ {
			c := onionPrefix[i]
			if !((c >= 'a' && c <= 'z') || (c >= '2' && c <= '7')) {
				return TransportInvalid, ErrMalformedOnionAddress
			}
		}
		return TransportTor, nil
	}

	ip := net.ParseIP(host)
	if ip != nil {
		// IPv4 or IPv4-mapped IPv6 (e.g. ::ffff:192.168.1.1)
		if ip4 := ip.To4(); ip4 != nil {
			if ip4.IsUnspecified() {
				return TransportInvalid, ErrUnspecifiedAddress
			}
			if ip4.IsMulticast() {
				return TransportInvalid, ErrMulticastAddress
			}
			if ip4.IsLinkLocalUnicast() || ip4.IsLinkLocalMulticast() {
				return TransportInvalid, ErrLinkLocalAddress
			}
			if bogon198_18.Contains(ip4) || bogon192_0_2.Contains(ip4) || bogon198_51_100.Contains(ip4) || bogon203_0_113.Contains(ip4) || bogon240_0_0_0.Contains(ip4) {
				return TransportInvalid, ErrBogonAddress
			}
			if ip4.IsLoopback() {
				return TransportLAN, nil
			}
			if cgnatCIDR.Contains(ip4) {
				return TransportWAN, nil
			}
			if rfc1918_10.Contains(ip4) || rfc1918_172.Contains(ip4) || rfc1918_192.Contains(ip4) {
				return TransportLAN, nil
			}
			return TransportWAN, nil
		}

		// True IPv6
		if ip.IsUnspecified() {
			return TransportInvalid, ErrUnspecifiedAddress
		}
		if ip.IsMulticast() {
			return TransportInvalid, ErrMulticastAddress
		}
		if ip.IsLinkLocalUnicast() {
			return TransportInvalid, ErrLinkLocalAddress
		}
		if ip.IsLoopback() {
			return TransportLAN, nil
		}

		// 1. Check Yggdrasil 200::/7 first per П2 (first byte 0000001x -> ip[0]&0xfe == 0x02)
		if len(ip) == 16 && (ip[0]&0xfe == 0x02) {
			return TransportYggdrasil, nil
		}

		// 2. Check ULA fc00::/7 (first byte 1111110x -> ip[0]&0xfe == 0xfc)
		if len(ip) == 16 && (ip[0]&0xfe == 0xfc) {
			return TransportLAN, nil
		}

		// 3. IPv6 Documentation / Bogon
		if ipv6DocCIDR.Contains(ip) {
			return TransportInvalid, ErrBogonAddress
		}

		// 4. Other global unicast IPv6
		return TransportWAN, nil
	}

	// Hostname / Domain (non-onion)
	if strings.ContainsAny(host, " \t\r\n/\\@%") {
		return TransportInvalid, ErrInvalidEndpoint
	}
	return TransportWAN, nil
}

func isIPAddress(address string) bool {
	host := address
	if strings.Contains(address, ":") {
		if h, _, err := net.SplitHostPort(address); err == nil {
			host = h
		}
	}
	host = strings.Trim(host, "[]")
	return net.ParseIP(host) != nil
}

// ClassifyEndpoint determines the appropriate transport class for a given destination address.
func (d *AdaptiveDialer) ClassifyEndpoint(address string) (TransportClass, error) {
	d.mu.RLock()
	proxyEnabled := d.proxyEnabled
	d.mu.RUnlock()

	class, err := ClassifyEndpoint(address)
	if err != nil {
		return TransportInvalid, err
	}
	// If Tor proxy is enabled, domain names route to Tor SOCKS5 to prevent DNS leaks
	if proxyEnabled && class == TransportWAN && !isIPAddress(address) {
		return TransportTor, nil
	}
	return class, nil
}

// DialContext establishes a connection to the target address choosing the optimal transport.
func (d *AdaptiveDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	address = NormalizeEndpoint(address, 50001)
	class, err := d.ClassifyEndpoint(address)
	if err != nil {
		return nil, fmt.Errorf("invalid endpoint %q: %w", address, err)
	}

	d.mu.RLock()
	p := d.policy
	torDialer := d.torDialer
	yggDialer := d.yggDialer
	yggMode := d.yggdrasilMode
	yggAddr := d.yggProxyAddr
	torProxyAddr := d.torProxyAddr
	d.mu.RUnlock()

	if !p.Allows(class) {
		return nil, fmt.Errorf("%w: %q (class: %s)", ErrPolicyDenied, address, class)
	}

	// Fail-closed DNS leak protection:
	// If the destination address is a hostname (not an IP literal and not .onion)
	// and local DNS resolution is forbidden by policy (AllowLocalDNS == false):
	// - If Tor SOCKS5 proxy is enabled, Tor resolves remote hostnames safely via SOCKS5 CONNECT.
	// - If Tor SOCKS5 proxy is disabled (or transport is not Tor), reject immediately
	//   BEFORE any DNS resolver call is made to prevent leaking DNS requests to ISP/clearnet.
	if !isIPAddress(address) && !strings.Contains(strings.ToLower(address), ".onion") {
		if !p.AllowLocalDNS && class != TransportTor {
			return nil, fmt.Errorf("%w: local DNS resolution is forbidden by policy for %q", ErrPolicyDenied, address)
		}
	}

	if class == TransportTor {
		if strings.HasPrefix(strings.ToLower(network), "udp") {
			return nil, ErrUDPOverTorNotSupported
		}

		if torDialer == nil {
			return nil, fmt.Errorf("Tor SOCKS5 dialer is uninitialized (proxy: %s)", torProxyAddr)
		}

		type dialResult struct {
			conn net.Conn
			err  error
		}
		resChan := make(chan dialResult, 1)

		go func() {
			defer func() {
				if r := recover(); r != nil {
					select {
					case resChan <- dialResult{conn: nil, err: fmt.Errorf("tor dialer recovered from panic: %v", r)}:
					default:
					}
				}
			}()

			conn, err := torDialer.Dial("tcp", address)
			if ctx.Err() != nil {
				if conn != nil {
					_ = conn.Close()
				}
				return
			}
			resChan <- dialResult{conn: conn, err: err}
		}()

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case res := <-resChan:
			return res.conn, res.err
		}
	}

	if class == TransportYggdrasil {
		if yggMode == YggdrasilModeProxy {
			if yggDialer == nil {
				return nil, fmt.Errorf("Yggdrasil SOCKS5 dialer is uninitialized (proxy: %s)", yggAddr)
			}

			type dialResult struct {
				conn net.Conn
				err  error
			}
			resChan := make(chan dialResult, 1)

			go func() {
				defer func() {
					if r := recover(); r != nil {
						select {
						case resChan <- dialResult{conn: nil, err: fmt.Errorf("yggdrasil dialer recovered from panic: %v", r)}:
						default:
						}
					}
				}()

				conn, err := yggDialer.Dial("tcp", address)
				if ctx.Err() != nil {
					if conn != nil {
						_ = conn.Close()
					}
					return
				}
				resChan <- dialResult{conn: conn, err: err}
			}()

			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case res := <-resChan:
				return res.conn, res.err
			}
		}

		// VPN Mode: rely on OS kernel TUN routing
		return d.directDialer.DialContext(ctx, network, address)
	}

	// TransportLAN or TransportWAN: Direct TCP connection
	return d.directDialer.DialContext(ctx, network, address)
}

// Dial is a convenience wrapper around DialContext.
func (d *AdaptiveDialer) Dial(network, address string) (net.Conn, error) {
	timeout := d.timeout
	if class, _ := d.ClassifyEndpoint(address); class == TransportTor {
		timeout = DefaultTorDialTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return d.DialContext(ctx, network, address)
}

// AddRelayEndpoint registers a known Blind Relay server address.
func (d *AdaptiveDialer) AddRelayEndpoint(addr string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	addr = strings.TrimSpace(addr)
	if addr == "" {
		return
	}
	for _, existing := range d.relayEndpoints {
		if existing == addr {
			return
		}
	}
	d.relayEndpoints = append(d.relayEndpoints, addr)
}

// SetRelayEndpoints updates the full list of known Blind Relay server addresses.
func (d *AdaptiveDialer) SetRelayEndpoints(endpoints []string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.relayEndpoints = append([]string(nil), endpoints...)
}

// SetHolePuncher assigns a HolePuncher to the AdaptiveDialer for simultaneous TCP open.
func (d *AdaptiveDialer) SetHolePuncher(hp *HolePuncher) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.holePuncher = hp
}

// GetHolePuncher returns the currently configured HolePuncher.
func (d *AdaptiveDialer) GetHolePuncher() *HolePuncher {
	d.mu.RLock()
	defer d.mu.RUnlock()
	return d.holePuncher
}

// GetRelayEndpoints returns a copy of registered Blind Relay server addresses.
func (d *AdaptiveDialer) GetRelayEndpoints() []string {
	d.mu.RLock()
	defer d.mu.RUnlock()
	return append([]string(nil), d.relayEndpoints...)
}

// DialWithRelayFallback attempts direct connections to candidate endpoints first.
// If direct P2P fails within directTimeout, it transparently establishes an E2EE tunnel via Blind Relays.
func (d *AdaptiveDialer) DialWithRelayFallback(
	ctx context.Context,
	directEndpoints []string,
	targetFP string,
	sessionID [16]byte,
	directTimeout time.Duration,
) (net.Conn, string, error) {
	if directTimeout <= 0 {
		directTimeout = 4 * time.Second
	}

	// 1. Try direct endpoints first with a bounded timeout
	if len(directEndpoints) > 0 {
		directCtx, directCancel := context.WithTimeout(ctx, directTimeout)
		defer directCancel()

		type dialRes struct {
			conn net.Conn
			ep   string
			err  error
		}
		resChan := make(chan dialRes, len(directEndpoints))

		for _, ep := range directEndpoints {
			go func(endpoint string) {
				conn, err := d.DialContext(directCtx, "tcp", endpoint)
				resChan <- dialRes{conn: conn, ep: endpoint, err: err}
			}(ep)
		}

	DirectLoop:
		for i := 0; i < len(directEndpoints); i++ {
			select {
			case <-directCtx.Done():
				break DirectLoop
			case res := <-resChan:
				if res.err == nil && res.conn != nil {
					return res.conn, res.ep, nil
				}
			}
		}
	}

	// 1.5. If direct connection timed out/failed, attempt TCP Hole Punching if holePuncher is configured
	d.mu.RLock()
	hp := d.holePuncher
	d.mu.RUnlock()
	if hp != nil && len(directEndpoints) > 0 && ctx.Err() == nil {
		var directCandidates []string
		for _, ep := range directEndpoints {
			if class, _ := d.ClassifyEndpoint(ep); class.IsDirect() {
				directCandidates = append(directCandidates, ep)
			}
		}
		if len(directCandidates) > 0 {
			punchConn, err := hp.Punch(ctx, directCandidates, 3, 300*time.Millisecond)
			if err == nil && punchConn != nil {
				return punchConn, punchConn.RemoteAddr().String(), nil
			}
		}
	}

	// 2. Direct connection failed or timed out — fallback to Blind Relay
	relays := d.GetRelayEndpoints()
	if len(relays) == 0 {
		return nil, "", errors.New("direct P2P connection failed and no blind relay endpoints available")
	}

	for _, relayAddr := range relays {
		if ctx.Err() != nil {
			return nil, "", ctx.Err()
		}

		rawConn, err := d.DialContext(ctx, "tcp", relayAddr)
		if err != nil {
			continue
		}

		// Send connect frame to relay
		connectFrame, err := EncodeRelayFrame(RelayFrameTypeConnect, sessionID, []byte(targetFP))
		if err != nil {
			_ = rawConn.Close()
			continue
		}

		if _, err := rawConn.Write(connectFrame); err != nil {
			_ = rawConn.Close()
			continue
		}

		tunnelConn := NewRelayTunnelConn(rawConn, sessionID, rawConn.LocalAddr(), rawConn.RemoteAddr())
		return tunnelConn, fmt.Sprintf("relay://%s#%s", relayAddr, targetFP), nil
	}

	return nil, "", errors.New("all direct and relay connection attempts failed")
}
