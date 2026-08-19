package discovery

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"time"
)

// ProbingTier classifies the latency/routing tier of an endpoint candidate.
type ProbingTier int

const (
	TierLAN       ProbingTier = 1 // Local Wi-Fi / Hotspot (timeout: 500ms)
	TierWANDirect ProbingTier = 2 // Public WAN IPv4/IPv6 (timeout: 2s)
	TierYggdrasil ProbingTier = 3 // Yggdrasil Mesh IPv6 (timeout: 3s)
	TierTor       ProbingTier = 4 // Tor Onion hidden service (timeout: 8s)
)

var (
	ErrNoViableEndpoints  = errors.New("no viable endpoints available or all connections timed out")
	ErrEndpointInCooldown = errors.New("endpoint is currently in failure cooldown")
)

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

// ClassifyTier determines the ProbingTier for a given endpoint.
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
		if ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
			return TierLAN
		}
		// Yggdrasil IP range: 200::/7 (0200::/7 or 0300::/7)
		if ip.To4() == nil && (strings.HasPrefix(ip.String(), "2") || strings.HasPrefix(ip.String(), "3")) {
			return TierYggdrasil
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
	case TierWANDirect:
		return 2 * time.Second
	case TierYggdrasil:
		return 3 * time.Second
	case TierTor:
		return 8 * time.Second
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
		return nil, "", ErrNoViableEndpoints
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

	// Staggered multi-tier launch sequence
	tiers := []ProbingTier{TierLAN, TierWANDirect, TierYggdrasil, TierTor}
	staggerDelays := map[ProbingTier]time.Duration{
		TierLAN:       0,
		TierWANDirect: 100 * time.Millisecond,
		TierYggdrasil: 250 * time.Millisecond,
		TierTor:       400 * time.Millisecond,
	}

	for _, tier := range tiers {
		eps := tierGroups[tier]
		if len(eps) > 0 {
			delay := staggerDelays[tier]
			if delay > 0 {
				select {
				case <-raceCtx.Done():
					break
				case <-time.After(delay):
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
