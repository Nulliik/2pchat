package discovery

import (
	"context"
	"crypto/rand"
	"fmt"
	"net"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"twopchat/core/pkg/transport"
)

// DiscoveryCallback notifies Kotlin / upper layers when a peer endpoint is discovered.
type DiscoveryCallback func(infoHashHex string, endpoint string, source string)
type TrackerStatusCallback func(trackerURL string, success bool, peerCount int, elapsed time.Duration, detail string)

// DiscoveryService manages tracker queries, LAN beacons, and peer endpoint discovery.
type DiscoveryService struct {
	mu              sync.RWMutex
	policy          transport.NetworkPolicy
	fingerprint     string
	peerID          [20]byte
	listenPort      int
	torEnabled      bool
	trackers        []string
	infoHashes      map[string][20]byte
	udpClient       *UDPTrackerClient
	httpClient      *HTTPTrackerClient
	lanEngine       *LANEngine
	prober          *FastTieredProber
	callback        DiscoveryCallback
	trackerStatus   TrackerStatusCallback
	running         int32
	onionAddress    string
	ctx             context.Context
	cancel          context.CancelFunc
	wg              sync.WaitGroup
	lifecycleMu     sync.Mutex
	announceWake    chan struct{}
	announces       map[announceKey]*announceState
	activeAnnounces int
	selfHashes      map[string][20]byte
	lookupExpiry    map[string]time.Time
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
		selfHashes:    make(map[string][20]byte),
		lookupExpiry:  make(map[string]time.Time),
		announces:     make(map[announceKey]*announceState),
		announceWake:  make(chan struct{}, 1),
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

// SetRecordProvider sets a factory to attach signed DiscoveryRecord to LAN beacons.
func (s *DiscoveryService) SetRecordProvider(provider LANRecordProvider) {
	s.mu.RLock()
	lan := s.lanEngine
	s.mu.RUnlock()
	if lan != nil {
		lan.SetRecordProvider(provider)
	}
}

// SetStrictSignatures configures strict signature validation on incoming LAN discovery.
func (s *DiscoveryService) SetStrictSignatures(strict bool) {
	s.mu.RLock()
	lan := s.lanEngine
	s.mu.RUnlock()
	if lan != nil {
		lan.SetStrictSignatures(strict)
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
	s.wakeAnnouncer()
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
	s.mu.Lock()
	s.refreshSchedulesLocked(time.Now())
	s.mu.Unlock()
	s.wakeAnnouncer()
	return nil
}
