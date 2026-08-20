package discovery

import (
	"context"
	"crypto/rand"
	"encoding/base64"
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

// DiscoveryService manages tracker queries, LAN beacons, and peer endpoint discovery.
type DiscoveryService struct {
	mu               sync.RWMutex
	fingerprint      string
	peerID           [20]byte
	listenPort       int
	torEnabled       bool
	localYggdrasilIP string
	trackers         []string
	infoHashes       map[string][20]byte
	udpClient        *UDPTrackerClient
	httpClient       *HTTPTrackerClient
	lanEngine        *LANEngine
	prober           *FastTieredProber
	callback         DiscoveryCallback
	running          int32
	onionAddress     string
	ctx              context.Context
	cancel           context.CancelFunc
	wg               sync.WaitGroup
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

// SetLocalYggdrasilIP updates the local IPv6 address to announce to trackers.
func (s *DiscoveryService) SetLocalYggdrasilIP(ip string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.localYggdrasilIP = strings.Trim(strings.TrimSpace(ip), "[]")
	if s.httpClient != nil {
		s.httpClient.SetLocalYggdrasilIP(s.localYggdrasilIP)
	}
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

// RegisterInfoHash adds an info hash (20 bytes hex, raw, or base64) to announce/discover.
func (s *DiscoveryService) RegisterInfoHash(hashStr string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	hashStr = strings.TrimSpace(hashStr)
	var h [20]byte

	if len(hashStr) == 40 {
		b, err := hex.DecodeString(hashStr)
		if err == nil && len(b) == 20 {
			copy(h[:], b)
			s.infoHashes[strings.ToLower(hashStr)] = h
			return nil
		}
	}

	if len(hashStr) == 20 {
		copy(h[:], []byte(hashStr))
		hexStr := hex.EncodeToString(h[:])
		s.infoHashes[hexStr] = h
		return nil
	}

	if b64, err := base64.StdEncoding.DecodeString(hashStr); err == nil {
		if len(b64) == 20 {
			copy(h[:], b64)
			hexStr := hex.EncodeToString(h[:])
			s.infoHashes[hexStr] = h
			return nil
		}
	}

	return fmt.Errorf("invalid info hash format: %s", hashStr)
}

// RegisterRendezvous computes and registers the rendezvous info hash for nickname + sharedCode.
func (s *DiscoveryService) RegisterRendezvous(nickname, sharedCode string) string {
	h := DeriveRendezvousKey(nickname, sharedCode)
	hexKey := hex.EncodeToString(h[:])
	s.mu.Lock()
	s.infoHashes[hexKey] = h
	s.mu.Unlock()
	return hexKey
}

// UnregisterInfoHash removes an info hash from active tracking.
func (s *DiscoveryService) UnregisterInfoHash(hashHex string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.infoHashes, strings.ToLower(hashHex))
}

// ResolvePeers queries all active trackers for the rendezvous key and returns discovered endpoints.
func (s *DiscoveryService) ResolvePeers(ctx context.Context, nickname, sharedCode string) ([]PeerEndpoint, error) {
	infoHash := DeriveRendezvousKey(nickname, sharedCode)

	s.mu.RLock()
	trackers := make([]string, len(s.trackers))
	copy(trackers, s.trackers)
	port := s.listenPort
	peerID := s.peerID
	s.mu.RUnlock()

	if len(trackers) == 0 {
		return nil, fmt.Errorf("no discovery trackers configured")
	}

	var resultsMu sync.Mutex
	discovered := make(map[string]PeerEndpoint)

	var wg sync.WaitGroup
	sem := make(chan struct{}, 8)

	for _, trackerURL := range trackers {
		wg.Add(1)
		go func(tURL string) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				return
			}

			reqCtx, cancel := context.WithTimeout(ctx, 4*time.Second)
			defer cancel()

			res, err := s.announceSingle(reqCtx, tURL, infoHash, peerID, port)
			if err == nil && res != nil {
				resultsMu.Lock()
				for _, peer := range res.Peers {
					epStr := peer.String()
					if epStr != "" {
						discovered[epStr] = peer
					}
				}
				resultsMu.Unlock()
			}
		}(trackerURL)
	}

	wg.Wait()

	out := make([]PeerEndpoint, 0, len(discovered))
	for _, ep := range discovered {
		out = append(out, ep)
	}
	return out, nil
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
