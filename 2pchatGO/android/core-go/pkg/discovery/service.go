package discovery

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"net/url"
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

// DiscoveryService manages tracker queries, LAN beacons, and peer endpoint discovery.
type DiscoveryService struct {
	mu           sync.RWMutex
	fingerprint  string
	peerID       [20]byte
	listenPort   int
	torEnabled   bool
	trackers     []string
	infoHashes   map[string][20]byte
	udpClient    *UDPTrackerClient
	httpClient   *HTTPTrackerClient
	lanEngine    *LANEngine
	prober       *FastTieredProber
	callback     DiscoveryCallback
	running      int32
	onionAddress string
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
}

// NewDiscoveryService creates a new unified DiscoveryService.
func NewDiscoveryService(
	fingerprint string,
	listenPort int,
	dialer *transport.AdaptiveDialer,
	torEnabled bool,
	callback DiscoveryCallback,
) *DiscoveryService {
	var pID [20]byte
	_, _ = rand.Read(pID[:])

	s := &DiscoveryService{
		fingerprint: fingerprint,
		peerID:      pID,
		listenPort:  listenPort,
		torEnabled:  torEnabled,
		infoHashes:  make(map[string][20]byte),
		udpClient:   NewUDPTrackerClient(torEnabled, 5*time.Second),
		httpClient:  NewHTTPTrackerClient(dialer, torEnabled, 5*time.Second),
		prober:      NewFastTieredProber(),
		callback:    callback,
	}

	s.lanEngine = NewLANEngine(fingerprint, listenPort, DefaultLANPort, func(peerFP, endpoint string) {
		if s.callback != nil {
			s.callback(peerFP, endpoint, "lan")
		}
	})

	return s
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

// RegisterInfoHash adds an info hash (20 bytes hex or raw) to announce/discover.
func (s *DiscoveryService) RegisterInfoHash(hashHex string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	var h [20]byte
	b, err := hex.DecodeString(hashHex)
	if err == nil && len(b) == 20 {
		copy(h[:], b)
	} else if len(hashHex) == 20 {
		copy(h[:], []byte(hashHex))
	} else {
		return fmt.Errorf("invalid info hash format: %s", hashHex)
	}

	s.infoHashes[hashHex] = h
	return nil
}

// UnregisterInfoHash removes an info hash from active tracking.
func (s *DiscoveryService) UnregisterInfoHash(hashHex string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.infoHashes, hashHex)
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
func (s *DiscoveryService) AnnounceAll() {
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

	if len(trackers) == 0 || len(hashes) == 0 {
		return
	}

	if parentCtx == nil {
		parentCtx = context.Background()
	}

	// Limit concurrent tracker network queries to 8 workers to prevent socket storms
	sem := make(chan struct{}, 8)

	for hashKey, hashVal := range hashes {
		for _, trackerURL := range trackers {
			select {
			case <-parentCtx.Done():
				return
			default:
			}

			sem <- struct{}{}
			go func(tURL, hKey string, hVal [20]byte) {
				defer func() { <-sem }()

				ctx, cancel := context.WithTimeout(parentCtx, 8*time.Second)
				defer cancel()

				res, err := s.announceSingle(ctx, tURL, hVal, peerID, port)
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
	u, err := url.Parse(trackerURL)
	if err != nil {
		return nil, err
	}

	if u.Scheme == "udp" {
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
