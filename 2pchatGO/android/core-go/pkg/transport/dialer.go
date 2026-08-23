package transport

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/proxy"
)

const (
	DefaultDialTimeout = 30 * time.Second
	DefaultTorDialTimeout = 90 * time.Second
	DefaultTorProxy    = "127.0.0.1:9050"
)

var (
	ErrUDPOverTorNotSupported = errors.New("UDP transport is strictly prohibited when Tor proxy is active")
	ErrInvalidEndpoint        = errors.New("invalid network endpoint format")
)

// TransportType represents the underlying network transport used for dialing.
type TransportType string

const (
	TransportDirect    TransportType = "direct"
	TransportTor       TransportType = "tor"
	TransportYggdrasil TransportType = "yggdrasil"
)

// AdaptiveDialer automatically routes outbound connections through Direct TCP, Yggdrasil, or Tor SOCKS5.
type AdaptiveDialer struct {
	mu           sync.RWMutex
	torProxyAddr string
	proxyEnabled bool
	directDialer *net.Dialer
	torDialer    proxy.Dialer
	timeout      time.Duration
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
		torProxyAddr: torProxyAddr,
		proxyEnabled: proxyEnabled,
		timeout:      timeout,
		directDialer: &net.Dialer{
			Timeout:   timeout,
			KeepAlive: 30 * time.Second,
			DualStack: true,
		},
	}
	d.initTorDialer()
	return d
}

func (d *AdaptiveDialer) initTorDialer() {
	dialer, err := proxy.SOCKS5("tcp", d.torProxyAddr, nil, proxy.Direct)
	if err == nil {
		d.torDialer = dialer
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

var yggdrasilSubnet = func() *net.IPNet {
	_, subnet, _ := net.ParseCIDR("200::/7")
	return subnet
}()

// IsYggdrasilIP returns true if the given IP falls within the Yggdrasil 200::/7 address space.
func IsYggdrasilIP(ip net.IP) bool {
	if ip == nil || ip.To4() != nil {
		return false
	}
	return yggdrasilSubnet != nil && yggdrasilSubnet.Contains(ip)
}

// IsPrivateOrLocalIP returns true if the host is a loopback, private RFC1918, link-local, or unspecified IP.
func IsPrivateOrLocalIP(host string) bool {
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsUnspecified()
}

// ClassifyEndpoint determines the appropriate transport type for a given destination address.
func (d *AdaptiveDialer) ClassifyEndpoint(address string) TransportType {
	d.mu.RLock()
	defer d.mu.RUnlock()

	host := address
	if strings.Contains(address, ":") {
		h, _, err := net.SplitHostPort(address)
		if err == nil {
			host = h
		}
	}
	host = strings.Trim(host, "[]")

	if strings.HasSuffix(strings.ToLower(host), ".onion") {
		return TransportTor
	}

	ip := net.ParseIP(host)
	if ip != nil {
		if IsPrivateOrLocalIP(host) {
			return TransportDirect
		}
		if IsYggdrasilIP(ip) {
			return TransportYggdrasil
		}
		// Direct public IPv4 or direct global mobile IPv6
		if d.proxyEnabled {
			return TransportTor
		}
		return TransportDirect
	}

	if d.proxyEnabled {
		return TransportTor
	}

	return TransportDirect
}

// DialContext establishes a connection to the target address choosing the optimal transport.
func (d *AdaptiveDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	transportType := d.ClassifyEndpoint(address)

	if transportType == TransportTor {
		if strings.HasPrefix(strings.ToLower(network), "udp") {
			return nil, ErrUDPOverTorNotSupported
		}

		d.mu.RLock()
		torDialer := d.torDialer
		d.mu.RUnlock()

		if torDialer == nil {
			return nil, fmt.Errorf("Tor SOCKS5 dialer is uninitialized (proxy: %s)", d.torProxyAddr)
		}

		// Connect through Tor SOCKS5 proxy with remote DNS resolution
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

	// Direct or Yggdrasil TCP connection
	return d.directDialer.DialContext(ctx, network, address)
}

// Dial is a convenience wrapper around DialContext.
func (d *AdaptiveDialer) Dial(network, address string) (net.Conn, error) {
	timeout := d.timeout
	if d.ClassifyEndpoint(address) == TransportTor {
		timeout = DefaultTorDialTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return d.DialContext(ctx, network, address)
}
