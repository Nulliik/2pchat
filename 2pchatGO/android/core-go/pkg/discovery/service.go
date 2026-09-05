package discovery

import (
	"context"
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"net"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"twopchat/core/pkg/transport"
)

const (
	DefaultAnnounceInterval = 45 * time.Second
)

// DiscoveryCallback notifies Kotlin / upper layers when a peer endpoint is discovered.
type DiscoveryCallback func(infoHashHex string, endpoint string, source string)
type TrackerStatusCallback func(trackerURL string, success bool, peerCount int, elapsed time.Duration, detail string)

// DiscoveryService manages tracker queries, LAN beacons, and peer endpoint discovery.
type DiscoveryService struct {
	mu               sync.RWMutex
	policy           transport.NetworkPolicy
	fingerprint      string
	peerID           [20]byte
	listenPort       int
	torEnabled       bool
	trackers         []string
	infoHashes       map[string][20]byte
	udpClient        *UDPTrackerClient
	httpClient       *HTTPTrackerClient
	lanEngine        *LANEngine
	prober           *FastTieredProber
	callback         DiscoveryCallback
	trackerStatus    TrackerStatusCallback
	running          int32
	onionAddress     string
	ctx              context.Context
	cancel           context.CancelFunc
	wg               sync.WaitGroup
	announceInFlight int32     // 1 when an AnnounceAll is already running
	lastAnnounceAt   int64     // unix nano of last AnnounceAll start
}

// NewDiscoveryService creates a new unified DiscoveryService.
func NewDiscoveryService(
	fingerprint string,
	listenPort int,
	dialer *transport.AdaptiveDialer,
	torEnabled bool,
	callback DiscoveryCallback,
	trackerStatus TrackerStatusCallback,
) *DiscoveryService {
	var pID [20]byte
	_, _ = rand.Read(pID[:])

	initialPolicy := transport.PolicySpeed
	if dialer != nil {
		initialPolicy = dialer.GetPolicy()
	}

	s := &DiscoveryService{
		policy:        initialPolicy,
		fingerprint:   fingerprint,
		peerID:        pID,
		listenPort:    listenPort,
		torEnabled:    torEnabled,
		infoHashes:    make(map[string][20]byte),
		udpClient:     NewUDPTrackerClient(torEnabled, DefaultTrackerTimeout),
		httpClient:    NewHTTPTrackerClient(dialer, torEnabled, DefaultTrackerTimeout),
		prober:        NewFastTieredProber(),
		callback:      callback,
		trackerStatus: trackerStatus,
	}

	s.lanEngine = NewLANEngine(fingerprint, listenPort, DefaultLANPort, func(peerFP, endpoint string) {
		if s.callback != nil {
			s.callback(peerFP, endpoint, "lan")
		}
	})
	s.lanEngine.SetPolicy(initialPolicy)

	return s
}

// SetTorProxy updates whether Tor proxy routing is active for discovery services.
func (s *DiscoveryService) SetTorProxy(enabled bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.torEnabled = enabled
	if s.udpClient != nil {
		s.udpClient.SetTorEnabled(enabled)
	}
	if s.httpClient != nil {
		s.httpClient.SetTorEnabled(enabled)
	}
}

func (s *DiscoveryService) reportTrackerStatus(url string, result *AnnounceResult, started time.Time, err error) {
	if s.trackerStatus == nil {
		return
	}
	if err != nil {
		s.trackerStatus(url, false, 0, time.Since(started), err.Error())
		return
	}
	peers := 0
	if result != nil {
		peers = len(result.Peers)
	}
	s.trackerStatus(url, true, peers, time.Since(started), "")
}

// SetOnionAddress sets the local Tor v3 .onion hidden service hostname.
func (s *DiscoveryService) SetOnionAddress(addr string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.onionAddress = addr
}

// GetOnionAddress returns the configured local Tor v3 .onion hostname.
func (s *DiscoveryService) GetOnionAddress() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.onionAddress
}

// SetTrackers updates the list of active BitTorrent tracker URLs.
func (s *DiscoveryService) SetTrackers(trackers []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.trackers = make([]string, len(trackers))
	copy(s.trackers, trackers)
}

// ApplyPolicy updates network policy for discovery (LAN beacons, trackers).
func (s *DiscoveryService) ApplyPolicy(p transport.NetworkPolicy) {
	s.mu.Lock()
	s.policy = p
	lan := s.lanEngine
	running := atomic.LoadInt32(&s.running) == 1
	s.mu.Unlock()

	if lan != nil {
		lan.SetPolicy(p)
		if running && p.AllowLAN {
			_ = lan.Start()
		}
	}
}

// GetPolicy returns the active network policy.
func (s *DiscoveryService) GetPolicy() transport.NetworkPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.policy
}

func (s *DiscoveryService) isTrackerAllowed(trackerURL string) bool {
	s.mu.RLock()
	policy := s.policy
	s.mu.RUnlock()

	u, err := url.Parse(trackerURL)
	if err != nil {
		return false
	}
	host := strings.ToLower(u.Hostname())
	if strings.HasSuffix(host, ".onion") {
		return policy.AllowOnion
	}
	if isYggdrasilTrackerHost(host) {
		return policy.AllowYggdrasil
	}
	return policy.AllowWAN
}

func (s *DiscoveryService) SetYggdrasilUDPRelay(addr string) { s.udpClient.SetYggdrasilUDPRelay(addr) }

// RegisterInfoHash adds an info hash (20 bytes hex, raw 20-byte string, or arbitrary key to SHA-1) to announce/discover.
func (s *DiscoveryService) RegisterInfoHash(hashStr string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	var h [20]byte
	b, err := hex.DecodeString(hashStr)
	if err == nil && len(b) == 20 {
		copy(h[:], b)
	} else if len(hashStr) == 20 {
		copy(h[:], []byte(hashStr))
	} else {
		sha := sha1.Sum([]byte(hashStr))
		copy(h[:], sha[:])
	}

	s.infoHashes[hashStr] = h
	if atomic.LoadInt32(&s.running) == 1 {
		go s.AnnounceHash(hashStr, h)
	}
	return nil
}

// UnregisterInfoHash removes an info hash from active tracking.
func (s *DiscoveryService) UnregisterInfoHash(hashHex string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.infoHashes, hashHex)
}

// AnnounceHash queries all trackers for a single info hash immediately.
func (s *DiscoveryService) AnnounceHash(hashKey string, hashVal [20]byte) {
	s.mu.RLock()
	trackers := make([]string, len(s.trackers))
	copy(trackers, s.trackers)
	port := s.listenPort
	peerID := s.peerID
	parentCtx := s.ctx
	s.mu.RUnlock()

	if len(trackers) == 0 {
		return
	}
	if parentCtx == nil {
		parentCtx = context.Background()
	}

	for _, trackerURL := range trackers {
		if !s.isTrackerAllowed(trackerURL) {
			continue
		}
		go func(tURL string) {
			started := time.Now()
			ctx, cancel := context.WithTimeout(parentCtx, 8*time.Second)
			defer cancel()

			res, err := s.announceSingle(ctx, tURL, hashVal, peerID, port)
			s.reportTrackerStatus(tURL, res, started, err)
			if err == nil && res != nil {
				for _, peer := range res.Peers {
					if s.callback != nil {
						s.callback(hashKey, peer.Raw, "tracker")
					}
				}
			}
		}(trackerURL)
	}
}

// Start initiates background LAN discovery and periodic tracker announces.
func (s *DiscoveryService) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if atomic.LoadInt32(&s.running) == 1 {
		return nil
	}

	_ = s.lanEngine.Start()

	s.ctx, s.cancel = context.WithCancel(context.Background())
	atomic.StoreInt32(&s.running, 1)

	s.wg.Add(1)
	go s.periodicAnnounceLoop()

	return nil
}

func (s *DiscoveryService) periodicAnnounceLoop() {
	defer s.wg.Done()

	// Initial announce
	s.AnnounceAll()

	ticker := time.NewTicker(DefaultAnnounceInterval)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.AnnounceAll()
		}
	}
}

// AnnounceAll queries all registered trackers for all registered info hashes.
// Concurrent calls are coalesced: if one is already in flight, new calls within
// 3 seconds are silently dropped to prevent startup socket storms.
func (s *DiscoveryService) AnnounceAll() {
	// Coalesce: allow at most one concurrent AnnounceAll, with 3-second cooldown.
	now := time.Now().UnixNano()
	last := atomic.LoadInt64(&s.lastAnnounceAt)
	if now-last < int64(3*time.Second) {
		// Too soon after the last announce — skip this call.
		return
	}
	if !atomic.CompareAndSwapInt32(&s.announceInFlight, 0, 1) {
		// Another goroutine is already running AnnounceAll.
		return
	}
	atomic.StoreInt64(&s.lastAnnounceAt, now)
	defer atomic.StoreInt32(&s.announceInFlight, 0)

	s.mu.RLock()
	trackers := make([]string, len(s.trackers))
	copy(trackers, s.trackers)

	hashes := make(map[string][20]byte)
	for k, v := range s.infoHashes {
		hashes[k] = v
	}
	port := s.listenPort
	peerID := s.peerID
	parentCtx := s.ctx
	s.mu.RUnlock()

	var allowedTrackers []string
	for _, t := range trackers {
		if s.isTrackerAllowed(t) {
			allowedTrackers = append(allowedTrackers, t)
		}
	}

	if len(allowedTrackers) == 0 || len(hashes) == 0 {
		return
	}

	if parentCtx == nil {
		parentCtx = context.Background()
	}

	// Limit concurrent tracker queries to 6 workers to prevent socket storms.
	sem := make(chan struct{}, 6)

	for hashKey, hashVal := range hashes {
		for _, trackerURL := range allowedTrackers {
			select {
			case <-parentCtx.Done():
				return
			default:
			}

			sem <- struct{}{}
			go func(tURL, hKey string, hVal [20]byte) {
				defer func() { <-sem }()
				started := time.Now()

				// 4-second timeout (was 8s) — halved to reduce startup latency.
				ctx, cancel := context.WithTimeout(parentCtx, 4*time.Second)
				defer cancel()

				res, err := s.announceSingle(ctx, tURL, hVal, peerID, port)
				s.reportTrackerStatus(tURL, res, started, err)
				if err == nil && res != nil {
					for _, peer := range res.Peers {
						if s.callback != nil {
							s.callback(hKey, peer.Raw, "tracker")
						}
					}
				}
			}(trackerURL, hashKey, hashVal)
		}
	}
}

func (s *DiscoveryService) announceSingle(
	ctx context.Context,
	trackerURL string,
	infoHash [20]byte,
	peerID [20]byte,
	port int,
) (*AnnounceResult, error) {
	if !s.isTrackerAllowed(trackerURL) {
		return nil, transport.ErrPolicyDenied
	}

	u, err := url.Parse(trackerURL)
	if err != nil {
		return nil, err
	}

	if u.Scheme == "udp" {
		s.mu.RLock()
		tor := s.torEnabled
		s.mu.RUnlock()
		if tor && !isYggdrasilTrackerHost(u.Hostname()) {
			return nil, ErrUDPDisabledUnderTor
		}
		return s.udpClient.Announce(ctx, trackerURL, infoHash, peerID, port)
	}

	if u.Scheme == "http" || u.Scheme == "https" {
		return s.httpClient.Announce(ctx, trackerURL, infoHash, peerID, port)
	}

	return nil, fmt.Errorf("unsupported tracker scheme: %s", u.Scheme)
}

// ProbeFast races connection attempts across candidate endpoints in tiered priority order.
func (s *DiscoveryService) ProbeFast(
	ctx context.Context,
	endpoints []string,
	dialer EndpointDialer,
) (net.Conn, string, error) {
	return s.prober.ProbeFast(ctx, endpoints, dialer)
}

// ResetCooldowns clears failure backoff on all candidate endpoints.
func (s *DiscoveryService) ResetCooldowns() {
	if s != nil && s.prober != nil {
		s.prober.ResetCooldowns()
	}
}

// RefreshAnnouncement triggers an immediate LAN beacon re-announcement and tracker announce.
func (s *DiscoveryService) RefreshAnnouncement() error {
	s.mu.RLock()
	lan := s.lanEngine
	s.mu.RUnlock()

	if lan != nil {
		_ = lan.RefreshAnnouncement()
	}
	go s.AnnounceAll()
	return nil
}

// Stop halts tracker loops and LAN discovery.
func (s *DiscoveryService) Stop() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if atomic.LoadInt32(&s.running) == 0 {
		return nil
	}

	atomic.StoreInt32(&s.running, 0)
	if s.cancel != nil {
		s.cancel()
	}
	_ = s.lanEngine.Stop()

	s.wg.Wait()
	return nil
}
