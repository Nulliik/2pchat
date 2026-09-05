package discovery

import (
	"context"
	"errors"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"twopchat/core/pkg/transport"
)

const MaxCandidateEndpoints = 16

// GetLocalSubnets queries the OS network interfaces for active non-loopback subnets.
func GetLocalSubnets() ([]*net.IPNet, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	var subnets []*net.IPNet
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			if ipnet, ok := addr.(*net.IPNet); ok {
				subnets = append(subnets, ipnet)
			}
		}
	}
	return subnets, nil
}

type candidateItem struct {
	endpoint string
	tier     ProbingTier
	class    transport.TransportClass
}

// FilterCandidates normalizes, deduplicates, enforces Anti-SSRF, verifies local subnets for LAN candidates,
// applies the NetworkPolicy, and caps the result to at most 16 candidates.
func FilterCandidates(
	policy transport.NetworkPolicy,
	rawCandidates []string,
	localIfaces []*net.IPNet,
) ([]string, error) {
	seen := make(map[string]bool)
	var filtered []candidateItem

	for _, raw := range rawCandidates {
		cand := strings.TrimSpace(raw)
		if cand == "" {
			continue
		}
		norm := transport.NormalizeEndpoint(cand, 50001)
		if norm == "" || seen[norm] {
			continue
		}

		class, err := transport.ClassifyEndpoint(norm)
		if err != nil || class == transport.TransportInvalid {
			continue
		}

		// Policy check: reject any candidate whose class is denied by the policy
		if !policy.Allows(class) {
			continue
		}

		// Anti-SSRF: reject unspecified, link-local addresses, and sensitive infrastructure ports
		host := norm
		port := 0
		if h, p, err := net.SplitHostPort(norm); err == nil {
			host = h
			if parsedPort, parseErr := strconv.Atoi(p); parseErr == nil {
				port = parsedPort
			}
		}
		host = strings.Trim(host, "[]")
		ip := net.ParseIP(host)
		if ip != nil {
			if ip.IsUnspecified() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
				continue
			}

			// Reject loopback when LAN is denied
			if ip.IsLoopback() && !policy.AllowLAN {
				continue
			}

			// Anti-SSRF port restrictions on private/local addresses:
			// Prevent scanning privileged services (< 1024) and well-known web/admin/proxy ports
			if class == transport.TransportLAN || ip.IsLoopback() {
				if port < 1024 || isSensitiveLocalPort(port) {
					continue
				}
			}

			// Anti-SSRF for LAN & loopback candidates:
			// If candidate is a LAN or loopback address, verify it belongs to one of the local interfaces' subnets.
			// If localIfaces is non-empty and no subnet matches, drop it.
			if (class == transport.TransportLAN || ip.IsLoopback()) && len(localIfaces) > 0 {
				matched := false
				for _, ifaceNet := range localIfaces {
					if ifaceNet.Contains(ip) {
						matched = true
						break
					}
				}
				if !matched {
					continue
				}
			}
		}

		seen[norm] = true
		tier := ClassifyTier(norm)
		filtered = append(filtered, candidateItem{
			endpoint: norm,
			tier:     tier,
			class:    class,
		})
	}

	if len(filtered) == 0 {
		return nil, ErrNoViableEndpoints
	}

	// Sort by ProbingTier priority
	sort.SliceStable(filtered, func(i, j int) bool {
		return filtered[i].tier < filtered[j].tier
	})

	// Hard cap at MaxCandidateEndpoints (16)
	if len(filtered) > MaxCandidateEndpoints {
		filtered = filtered[:MaxCandidateEndpoints]
	}

	res := make([]string, len(filtered))
	for i, item := range filtered {
		res[i] = item.endpoint
	}
	return res, nil
}

// ProbingTier classifies the latency/routing tier of an endpoint candidate.
type ProbingTier int

const (
	TierLAN        ProbingTier = 1 // Local Wi-Fi / Hotspot / Private IP (timeout: 500ms)
	TierDirectIPv6 ProbingTier = 2 // Direct Global / Mobile IPv6 (timeout: 1.5s)
	TierWANDirect  ProbingTier = 3 // Public WAN IPv4 (STUN / UPnP mapped) (timeout: 2s)
	TierYggdrasil  ProbingTier = 4 // Yggdrasil Mesh IPv6 (200::/7) (timeout: 3s)
	TierTor        ProbingTier = 5 // Tor Onion hidden service (timeout: 8s)
)

var (
	ErrNoViableEndpoints  = errors.New("no viable endpoints available or all connections timed out")
	ErrEndpointInCooldown = errors.New("endpoint is currently in failure cooldown")
)

var yggdrasilSubnet = func() *net.IPNet {
	_, subnet, _ := net.ParseCIDR("200::/7")
	return subnet
}()

func isYggdrasilIP(ip net.IP) bool {
	if ip == nil || ip.To4() != nil {
		return false
	}
	return yggdrasilSubnet != nil && yggdrasilSubnet.Contains(ip)
}

// EndpointDialer defines the signature for establishing a connection to an endpoint.
type EndpointDialer func(ctx context.Context, endpoint string) (net.Conn, error)

// FastTieredProber coordinates parallel multi-tier connection races and cooldown backoffs.
type FastTieredProber struct {
	mu        sync.RWMutex
	cooldowns map[string]time.Time
	failures  map[string]int
}

// NewFastTieredProber creates a new fast tiered probing engine.
func NewFastTieredProber() *FastTieredProber {
	return &FastTieredProber{
		cooldowns: make(map[string]time.Time),
		failures:  make(map[string]int),
	}
}

// ClassifyTier determines the ProbingTier for a given endpoint per Transport Priority Cascade.
func ClassifyTier(endpoint string) ProbingTier {
	host := endpoint
	if strings.Contains(endpoint, ":") {
		h, _, err := net.SplitHostPort(endpoint)
		if err == nil {
			host = h
		}
	}
	host = strings.Trim(host, "[]")

	if strings.HasSuffix(strings.ToLower(host), ".onion") {
		return TierTor
	}

	ip := net.ParseIP(host)
	if ip != nil {
		if ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() {
			return TierLAN
		}
		if isYggdrasilIP(ip) {
			return TierYggdrasil
		}
		if ip.To4() == nil {
			return TierDirectIPv6
		}
		return TierWANDirect
	}

	return TierWANDirect
}

// GetTierTimeout returns the maximum dial deadline for each tier.
func GetTierTimeout(tier ProbingTier) time.Duration {
	switch tier {
	case TierLAN:
		return 500 * time.Millisecond
	case TierDirectIPv6:
		return 1500 * time.Millisecond
	case TierWANDirect:
		return 2 * time.Second
	case TierYggdrasil:
		return 3 * time.Second
	case TierTor:
		return 35 * time.Second
	default:
		return 3 * time.Second
	}
}

type probeResult struct {
	conn     net.Conn
	endpoint string
	tier     ProbingTier
	err      error
}

// ProbeFast races connection attempts across candidate endpoints in tiered priority order.
func (p *FastTieredProber) ProbeFast(
	ctx context.Context,
	endpoints []string,
	dialer EndpointDialer,
) (net.Conn, string, error) {
	now := time.Now()
	p.mu.Lock()
	var candidates []string
	for _, ep := range endpoints {
		if until, inCooldown := p.cooldowns[ep]; !inCooldown || now.After(until) {
			candidates = append(candidates, ep)
		}
	}
	p.mu.Unlock()

	if len(candidates) == 0 {
		// If all candidates are in cooldown, don't stall user messaging; probe all candidates anyway.
		candidates = endpoints
	}

	// Group candidates by tier
	tierGroups := make(map[ProbingTier][]string)
	for _, ep := range candidates {
		tier := ClassifyTier(ep)
		tierGroups[tier] = append(tierGroups[tier], ep)
	}

	raceCtx, cancelAll := context.WithCancel(ctx)
	defer cancelAll()

	resChan := make(chan probeResult, len(candidates))
	var wg sync.WaitGroup

	launchTier := func(tier ProbingTier, eps []string) {
		for _, endpoint := range eps {
			wg.Add(1)
			go func(ep string, t ProbingTier) {
				defer wg.Done()

				timeout := GetTierTimeout(t)
				dialCtx, cancel := context.WithTimeout(raceCtx, timeout)
				defer cancel()

				conn, err := dialer(dialCtx, ep)
				if err == nil && conn != nil {
					select {
					case resChan <- probeResult{conn: conn, endpoint: ep, tier: t}:
						cancelAll() // Cancel other concurrent dials immediately
					case <-raceCtx.Done():
						_ = conn.Close()
					}
					return
				}

				// Record failure
				p.recordFailure(ep)
			}(endpoint, tier)
		}
	}

	// Staggered multi-tier launch sequence (Happy Eyeballs RFC 8305 cascade)
	tiers := []ProbingTier{TierLAN, TierDirectIPv6, TierWANDirect, TierYggdrasil, TierTor}
	staggerDelays := map[ProbingTier]time.Duration{
		TierLAN:        0,
		TierDirectIPv6: 50 * time.Millisecond,
		TierWANDirect:  150 * time.Millisecond,
		TierYggdrasil:  300 * time.Millisecond,
		TierTor:        500 * time.Millisecond,
	}

	startTime := time.Now()
	for _, tier := range tiers {
		eps := tierGroups[tier]
		if len(eps) > 0 {
			targetOffset := staggerDelays[tier]
			elapsed := time.Since(startTime)
			if targetOffset > elapsed {
				sleepDuration := targetOffset - elapsed
				select {
				case <-raceCtx.Done():
					break
				case <-time.After(sleepDuration):
				}
			}
			if raceCtx.Err() == nil {
				launchTier(tier, eps)
			}
		}
	}

	// Close channel when all workers finish
	go func() {
		wg.Wait()
		close(resChan)
	}()

	select {
	case <-ctx.Done():
		return nil, "", ctx.Err()
	case win, ok := <-resChan:
		if ok && win.conn != nil {
			p.recordSuccess(win.endpoint)
			return win.conn, win.endpoint, nil
		}
	}

	return nil, "", ErrNoViableEndpoints
}

func (p *FastTieredProber) recordSuccess(endpoint string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.cooldowns, endpoint)
	delete(p.failures, endpoint)
}

func (p *FastTieredProber) recordFailure(endpoint string) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.failures[endpoint]++
	count := p.failures[endpoint]

	// Exponential backoff: 1s, 2s, 4s, 8s ... up to 60s
	backoffSec := 1 << (count - 1)
	if backoffSec > 60 {
		backoffSec = 60
	}
	p.cooldowns[endpoint] = time.Now().Add(time.Duration(backoffSec) * time.Second)
}

// ResetCooldowns clears all failure cooldowns (e.g., when switching network interfaces).
func (p *FastTieredProber) ResetCooldowns() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.cooldowns = make(map[string]time.Time)
	p.failures = make(map[string]int)
}

// isSensitiveLocalPort returns true for ports commonly associated with
// local infrastructure, router administration, web panels, and local proxies.
func isSensitiveLocalPort(port int) bool {
	switch port {
	case 1080, // SOCKS proxy
		1900, // SSDP / UPnP control
		3000, // Common dev web UI (Node/Grafana)
		3128, // Squid proxy
		5000, // UPnP / NAS admin (Synology)
		5353, // mDNS
		8000, 8008, 8080, 8081, 8443, 8888, // Common router/proxy alternative HTTP(S) ports
		9050, 9051, // Tor SOCKS / Control
		9053:        // Yggdrasil admin/proxy
		return true
	default:
		return false
	}
}

