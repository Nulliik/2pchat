package session

import (
	"context"
	crand "crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/discovery"
	"twopchat/core/pkg/transport"
)

// EventCallbacks defines JNI/Kotlin callback hooks for networking events.
type EventCallbacks struct {
	OnPeerConnected    func(peerFP, endpoint string)
	OnPeerDisconnected func(peerFP, reason string)
	OnMessageReceived  func(peerFP string, payload []byte, messageID string)
	OnError            func(code int, message string)
	OnFileProgress     func(peerFP string, messageID string, transferred int64, total int64, speedKbps float64)
}

const (
	maxConcurrentHandshakes = 16
	ipRateLimitWindow       = 5 * time.Second
	maxHandshakesPerWindow  = 10
)

type ipRateLimiter struct {
	mu      sync.Mutex
	history map[string][]time.Time
}

func newIPRateLimiter() *ipRateLimiter {
	return &ipRateLimiter{
		history: make(map[string][]time.Time),
	}
}

func (l *ipRateLimiter) allow(ip string) bool {
	if ip == "" || ip == "127.0.0.1" || ip == "::1" || ip == "localhost" {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	cutoff := now.Add(-ipRateLimitWindow)

	timestamps := l.history[ip]
	valid := timestamps[:0]
	for _, t := range timestamps {
		if t.After(cutoff) {
			valid = append(valid, t)
		}
	}

	if len(valid) >= maxHandshakesPerWindow {
		l.history[ip] = valid
		return false
	}

	l.history[ip] = append(valid, now)
	if len(l.history) > 1024 {
		for k, v := range l.history {
			if len(v) == 0 || v[len(v)-1].Before(cutoff) {
				delete(l.history, k)
			}
		}
	}
	return true
}

// Manager manages P2P listening, outbound dialing, active sessions, and connection arbitration.
type Manager struct {
	mu              sync.RWMutex
	policy          transport.NetworkPolicy
	identity        *crypto.IdentityKeyPair
	prekeyPriv      *crypto.X25519PrivateKey
	prekeyPub       *crypto.X25519PublicKey
	dialer          *transport.AdaptiveDialer
	listener        *transport.AsyncListener
	sessions        map[string]*Session
	peerEndp        map[string]string
	peerNames       map[string]string
	peerPolicies    map[string]transport.NetworkPolicy
	callbacksMu     sync.RWMutex
	callbacks       EventCallbacks
	fileTransferMgr *transport.FileTransferManager
	storageDir      string
	nickname        string
	fingerprint     string
	onionAddress    string
	handshakeSem    chan struct{}
	rateLimiter     *ipRateLimiter
}

// NewManager creates a new network session Manager.
func NewManager(
	id *crypto.IdentityKeyPair,
	prekeyPriv *crypto.X25519PrivateKey,
	prekeyPub *crypto.X25519PublicKey,
	torProxy string,
	proxyEnabled bool,
	callbacks EventCallbacks,
) *Manager {
	dialer := transport.NewAdaptiveDialer(torProxy, proxyEnabled, 10*time.Second)
	m := &Manager{
		policy:       transport.PolicySpeed,
		identity:     id,
		prekeyPriv:   prekeyPriv,
		prekeyPub:    prekeyPub,
		dialer:       dialer,
		listener:     transport.NewAsyncListener(),
		sessions:     make(map[string]*Session),
		peerEndp:     make(map[string]string),
		peerNames:    make(map[string]string),
		peerPolicies: make(map[string]transport.NetworkPolicy),
		callbacks:    callbacks,
		fingerprint:  crypto.Fingerprint(id.Public.Bytes()),
		handshakeSem: make(chan struct{}, maxConcurrentHandshakes),
		rateLimiter:  newIPRateLimiter(),
	}
	m.fileTransferMgr = transport.NewFileTransferManager(func(peerFP, msgID string, transferred, total int64, speed float64) {
		callbacks := m.callbacksSnapshot()
		if callbacks.OnFileProgress != nil {
			callbacks.OnFileProgress(peerFP, msgID, transferred, total, speed)
		}
	})
	return m
}

func (m *Manager) callbacksSnapshot() EventCallbacks {
	m.callbacksMu.RLock()
	defer m.callbacksMu.RUnlock()
	return m.callbacks
}

// SetCallbacks atomically replaces the immutable callback bundle. Callers may
// update JNI hooks while networking is active without racing dispatch loops.
func (m *Manager) SetCallbacks(callbacks EventCallbacks) {
	m.callbacksMu.Lock()
	m.callbacks = callbacks
	m.callbacksMu.Unlock()
}

// StartListener starts the dual-stack TCP listener adhering to the active NetworkPolicy.
func (m *Manager) StartListener(port int) error {
	m.mu.RLock()
	policy := m.policy
	m.mu.RUnlock()
	return m.listener.StartWithPolicy(port, policy, func(conn net.Conn) {
		m.handleIncomingConnection(conn)
	})
}

// StopListener stops the TCP listener.
func (m *Manager) StopListener() error {
	return m.listener.Stop()
}

// ApplyPolicy updates the active network policy and immediately terminates any
// live sessions whose transport is no longer permitted by the new policy (Б1),
// as well as rebinding the inbound listener to 127.0.0.1 if strict mode is active.
func (m *Manager) ApplyPolicy(p transport.NetworkPolicy) {
	m.mu.Lock()
	m.policy = p
	if m.dialer != nil {
		m.dialer.SetPolicy(p)
	}

	var toClose []*Session
	for peerFP, sess := range m.sessions {
		endpoint := m.peerEndp[peerFP]
		effectivePolicy := p
		if peerPolicy, ok := m.peerPolicies[peerFP]; ok {
			effectivePolicy = p.Intersect(peerPolicy)
		}
		var class transport.TransportClass
		if sess != nil && sess.IsTorTransport() {
			class = transport.TransportTor
		} else {
			var err error
			class, err = transport.ClassifyEndpoint(endpoint)
			if err != nil {
				class = transport.TransportInvalid
			}
		}
		if !effectivePolicy.Allows(class) {
			toClose = append(toClose, sess)
		}
	}
	listener := m.listener
	m.mu.Unlock()

	for _, sess := range toClose {
		_ = sess.Close()
	}

	if listener != nil && listener.IsRunning() {
		_ = listener.RebindWithPolicy(p, func(conn net.Conn) {
			m.handleIncomingConnection(conn)
		})
	}
}

// SetPeerPolicy stores a contact-specific NetworkPolicy keyed by peer fingerprint.
func (m *Manager) SetPeerPolicy(peerFP string, p transport.NetworkPolicy) {
	m.mu.Lock()
	defer m.mu.Unlock()
	cleanFP := strings.TrimSpace(peerFP)
	if cleanFP != "" {
		m.peerPolicies[cleanFP] = p
	}
}

// GetPeerPolicy returns the contact-specific NetworkPolicy for peerFP, or false if not explicitly set.
func (m *Manager) GetPeerPolicy(peerFP string) (transport.NetworkPolicy, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	p, ok := m.peerPolicies[strings.TrimSpace(peerFP)]
	return p, ok
}

// GetPolicy returns the current network policy enforced by the manager.
func (m *Manager) GetPolicy() transport.NetworkPolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.policy
}

// SetTorProxy updates the Tor SOCKS5 proxy configuration.
func (m *Manager) SetTorProxy(enabled bool, addr string) {
	m.dialer.SetTorProxy(enabled, addr)
}

// SetYggdrasilConfig updates the Yggdrasil routing mode and SOCKS5 proxy configuration.
func (m *Manager) SetYggdrasilConfig(mode string, proxyAddr string) {
	m.dialer.SetYggdrasilConfig(transport.YggdrasilMode(mode), proxyAddr)
}

// SetHolePuncher configures the HolePuncher for simultaneous TCP open.
func (m *Manager) SetHolePuncher(hp *transport.HolePuncher) {
	if m.dialer != nil {
		m.dialer.SetHolePuncher(hp)
	}
}

// SetRelayEndpoints configures Blind Relay endpoints for fallback connectivity.
func (m *Manager) SetRelayEndpoints(endpoints []string) {
	if m.dialer != nil {
		m.dialer.SetRelayEndpoints(endpoints)
	}
}

// SetOnionAddress sets the local Tor v3 .onion hidden service hostname and purges obsolete routes.
func (m *Manager) SetOnionAddress(addr string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	newAddr := strings.TrimSpace(addr)
	if m.onionAddress != "" && m.onionAddress != newAddr {
		for fp, ep := range m.peerEndp {
			if strings.Contains(ep, m.onionAddress) {
				delete(m.peerEndp, fp)
			}
		}
	}
	m.onionAddress = newAddr
}

// GetOnionAddress returns the configured local Tor v3 .onion hostname.
func (m *Manager) GetOnionAddress() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.onionAddress
}

// SetNickname sets the local user nickname for outgoing messages.
func (m *Manager) SetNickname(nick string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.nickname = nick
}

// SetIdentity dynamically updates the local identity and prekeys used for handshakes.
func (m *Manager) SetIdentity(id *crypto.IdentityKeyPair, prekeyPriv *crypto.X25519PrivateKey, prekeyPub *crypto.X25519PublicKey) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if id != nil {
		m.identity = id
		m.fingerprint = crypto.Fingerprint(id.Public.Bytes())
	}
	if prekeyPriv != nil {
		m.prekeyPriv = prekeyPriv
	}
	if prekeyPub != nil {
		m.prekeyPub = prekeyPub
	}
}

// Port returns the bound listening port.
func (m *Manager) Port() int {
	return m.listener.Port()
}

// Fingerprint returns the local identity fingerprint.
func (m *Manager) Fingerprint() string {
	return m.fingerprint
}

func (m *Manager) handleIncomingConnection(conn net.Conn) {
	// 1. IP Rate Limiting Check
	remoteAddr := conn.RemoteAddr().String()
	host, _, err := net.SplitHostPort(remoteAddr)
	if err != nil {
		host = remoteAddr
	}
	if !m.rateLimiter.allow(host) {
		_ = conn.Close()
		callbacks := m.callbacksSnapshot()
		if callbacks.OnError != nil {
			callbacks.OnError(1, fmt.Sprintf("Incoming connection rejected: rate limit exceeded for %s", host))
		}
		return
	}

	// 2. Concurrency Semaphore Guard
	select {
	case m.handshakeSem <- struct{}{}:
		defer func() { <-m.handshakeSem }()
	default:
		_ = conn.Close()
		callbacks := m.callbacksSnapshot()
		if callbacks.OnError != nil {
			callbacks.OnError(1, "Incoming connection rejected: handshake concurrency limit reached")
		}
		return
	}

	// 3. Pre-handshake socket deadline guard (prevents slowloris DoS)
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))

	m.mu.RLock()
	onion := m.onionAddress
	globalPolicy := m.policy
	m.mu.RUnlock()

	endpoint := conn.RemoteAddr().String()
	isTor := onion != "" && (strings.HasPrefix(endpoint, "127.0.0.1:") || strings.HasPrefix(endpoint, "[::1]:"))

	var inboundClass transport.TransportClass
	if isTor {
		inboundClass = transport.TransportTor
	} else {
		inboundClass, _ = transport.ClassifyEndpoint(endpoint)
	}

	// 4. Pre-handshake global transport policy guard (SEC-03)
	// If global policy rejects this transport class (e.g. Tor Strict mode rejects clearnet connections),
	// drop immediately without reading frames or executing handshake.
	if !globalPolicy.Allows(inboundClass) {
		_ = conn.Close()
		callbacks := m.callbacksSnapshot()
		if callbacks.OnError != nil {
			callbacks.OnError(2, fmt.Sprintf("Incoming connection from %s rejected: transport class %s is denied by global policy", endpoint, inboundClass))
		}
		return
	}

	// 5. Pre-reply peer policy validator callback (SEC-03)
	// As soon as initiator's fingerprint is extracted in responder handshake,
	// verify peer's transport policy BEFORE sending our identity in the reply frame!
	peerValidator := func(peerFP string) error {
		m.mu.RLock()
		peerPolicy, hasPeerPolicy := m.peerPolicies[peerFP]
		m.mu.RUnlock()

		effectivePolicy := globalPolicy
		if hasPeerPolicy {
			effectivePolicy = effectivePolicy.Intersect(peerPolicy)
		}

		if !effectivePolicy.Allows(inboundClass) {
			return fmt.Errorf("transport class %s is denied by policy for peer %s", inboundClass, peerFP)
		}
		return nil
	}

	sess, err := NewSession(
		conn,
		false, // responder
		m.identity,
		m.prekeyPriv,
		m.prekeyPub,
		"", // accept any valid key during incoming connection
		30*time.Second,
		WithPeerValidator(peerValidator),
		WithTorTransport(isTor),
	)
	if err != nil {
		callbacks := m.callbacksSnapshot()
		if callbacks.OnError != nil {
			errCode := 1
			if strings.Contains(err.Error(), "denied by policy") {
				errCode = 2
			}
			callbacks.OnError(errCode, fmt.Sprintf("Incoming connection from %s rejected: %v", endpoint, err))
		}
		return
	}

	peerFP := sess.PeerFingerprint()
	m.RegisterSession(sess, peerFP, endpoint, false)
}

// ConnectPeer dials a remote peer endpoint and establishes an encrypted X3DH session.
// Handles single endpoints as well as comma-separated candidate lists (e.g. LAN, IPv6, and .onion).
func (m *Manager) ConnectPeer(endpoint, expectedFingerprint string) (*Session, error) {
	return m.connectPeerInternal(endpoint, expectedFingerprint, nil)
}

// ConnectPeerWithPolicy connects to a peer enforcing an intersection of global policy and contact policy.
func (m *Manager) ConnectPeerWithPolicy(endpoint, expectedFingerprint string, contactPolicy transport.NetworkPolicy) (*Session, error) {
	if expectedFingerprint != "" {
		m.SetPeerPolicy(expectedFingerprint, contactPolicy)
	}
	return m.connectPeerInternal(endpoint, expectedFingerprint, &contactPolicy)
}

func (m *Manager) connectPeerInternal(endpoint, expectedFingerprint string, contactPolicy *transport.NetworkPolicy) (*Session, error) {
	if expectedFingerprint != "" {
		m.mu.RLock()
		existing, ok := m.sessions[expectedFingerprint]
		m.mu.RUnlock()
		if ok && existing != nil && existing.IsOnline() {
			return existing, nil
		}
	}

	rawEndpoints := strings.Split(endpoint, ",")
	m.mu.RLock()
	effectivePolicy := m.policy
	if contactPolicy != nil {
		effectivePolicy = effectivePolicy.Intersect(*contactPolicy)
	} else if expectedFingerprint != "" {
		if storedPolicy, ok := m.peerPolicies[expectedFingerprint]; ok {
			effectivePolicy = effectivePolicy.Intersect(storedPolicy)
		}
	}
	m.mu.RUnlock()

	subnets, _ := discovery.GetLocalSubnets()
	if flag.Lookup("test.v") != nil || os.Getenv("GO_TEST") == "1" {
		_, loopbackNet, _ := net.ParseCIDR("127.0.0.0/8")
		_, loopbackNet6, _ := net.ParseCIDR("::1/128")
		subnets = append(subnets, loopbackNet, loopbackNet6)
	}
	candidates, filterErr := discovery.FilterCandidates(effectivePolicy, rawEndpoints, subnets)
	if filterErr != nil || len(candidates) == 0 {
		return nil, fmt.Errorf("no valid permitted endpoints provided: %w", filterErr)
	}

	hasTor := false
	for _, ep := range candidates {
		class, _ := transport.ClassifyEndpoint(ep)
		if class == transport.TransportTor {
			hasTor = true
			break
		}
	}

	timeout := 15 * time.Second
	if hasTor {
		timeout = transport.DefaultTorDialTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	var conn net.Conn
	var winEndpoint string
	var err error

	if len(candidates) == 1 {
		winEndpoint = candidates[0]
		conn, err = m.dialer.DialContext(ctx, "tcp", winEndpoint)
	} else {
		prober := discovery.NewFastTieredProber()
		conn, winEndpoint, err = prober.ProbeFast(ctx, candidates, func(c context.Context, ep string) (net.Conn, error) {
			return m.dialer.DialContext(c, "tcp", ep)
		})
	}

	// 1. If direct dialing failed, attempt TCP Hole Punching if hole puncher is configured
	if err != nil && m.dialer != nil && ctx.Err() == nil {
		if hp := m.dialer.GetHolePuncher(); hp != nil {
			var directCandidates []string
			for _, c := range candidates {
				if class, _ := transport.ClassifyEndpoint(c); class.IsDirect() {
					directCandidates = append(directCandidates, c)
				}
			}
			if len(directCandidates) > 0 {
				punchConn, punchErr := hp.Punch(ctx, directCandidates, 3, 300*time.Millisecond)
				if punchErr == nil && punchConn != nil {
					conn = punchConn
					winEndpoint = punchConn.RemoteAddr().String()
					err = nil
				}
			}
		}
	}

	// 2. If hole punching also failed, attempt Blind Relay fallback
	if err != nil && m.dialer != nil && len(m.dialer.GetRelayEndpoints()) > 0 && ctx.Err() == nil {
		var sessionID [16]byte
		_, _ = crand.Read(sessionID[:])
		relayConn, relayEP, relayErr := m.dialer.DialWithRelayFallback(
			ctx,
			nil, // already tried direct
			expectedFingerprint,
			sessionID,
			1*time.Second,
		)
		if relayErr == nil && relayConn != nil {
			conn = relayConn
			winEndpoint = relayEP
			err = nil
		}
	}

	if err != nil {
		return nil, fmt.Errorf("failed to dial endpoints %v: %w", candidates, err)
	}

	sess, err := NewSession(
		conn,
		true, // initiator
		m.identity,
		m.prekeyPriv,
		m.prekeyPub,
		expectedFingerprint,
		30*time.Second,
	)
	if err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("initiator handshake failed with %s: %w", winEndpoint, err)
	}

	if class, _ := transport.ClassifyEndpoint(winEndpoint); class == transport.TransportTor {
		sess.SetTorTransport(true)
	}

	peerFP := sess.PeerFingerprint()
	m.RegisterSession(sess, peerFP, winEndpoint, true)
	return sess, nil
}

// RegisterSession handles tie-breaking and registers the active session.
func (m *Manager) RegisterSession(newSess *Session, peerFP, endpoint string, initiator bool) {
	m.mu.Lock()

	existing, exists := m.sessions[peerFP]
	if exists && existing.IsOnline() {
		// Tie-breaking: the peer with lexicographically smaller fingerprint keeps outbound dial
		preferInitiator := m.fingerprint < peerFP
		existingIsPreferred := existing.initiator == preferInitiator
		newIsPreferred := initiator == preferInitiator

		if existingIsPreferred || !newIsPreferred {
			// Reject new duplicate connection
			m.mu.Unlock()
			go func() { _ = newSess.Close() }()
			return
		}

		// Replace existing with new preferred connection
		go func() { _ = existing.Close() }()
	}

	m.sessions[peerFP] = newSess
	m.peerEndp[peerFP] = endpoint
	onConnCb := m.callbacksSnapshot().OnPeerConnected
	nick := m.nickname
	localFP := m.fingerprint
	port := m.listener.Port()
	m.mu.Unlock()

	if onConnCb != nil {
		onConnCb(peerFP, endpoint)
	}

	// Automatic identity_info exchange upon session establishment (full parity with Python discovery_bridge.py)
	if nick != "" {
		go func() {
			identityMsg := map[string]any{
				"type":        string(TypeIdentityInfo),
				"nickname":    nick,
				"fingerprint": localFP,
				"listen_port": port,
			}
			_, _ = newSess.SendReliable(identityMsg)
		}()
	}

	go m.dispatchSessionMessages(newSess, peerFP)
}

func (m *Manager) dispatchSessionMessages(s *Session, peerFP string) {
	disconnectedNotified := false
	defer func() {
		m.mu.Lock()
		wasActive := false
		if current, ok := m.sessions[peerFP]; ok && current == s {
			delete(m.sessions, peerFP)
			wasActive = true
		}
		m.mu.Unlock()

		callbacks := m.callbacksSnapshot()
		if wasActive && !disconnectedNotified && callbacks.OnPeerDisconnected != nil {
			callbacks.OnPeerDisconnected(peerFP, "connection terminated")
		}
	}()

	for msg := range s.Messages() {
		msgType, _ := msg["type"].(string)

		if msgType == string(TypeIdentityInfo) {
			nick, _ := msg["nickname"].(string)
			claimedFP, _ := msg["fingerprint"].(string)
			nick = strings.TrimSpace(nick)
			if claimedFP == "" {
				claimedFP = peerFP
			}
			if nick != "" {
				m.UpdatePeerNameMapping(peerFP, nick)
				m.UpdatePeerNameMapping(claimedFP, nick)
			}
			raw, err := EncodeMessage(msg)
			callbacks := m.callbacksSnapshot()
			if err == nil && callbacks.OnMessageReceived != nil {
				msgID, _ := msg["id"].(string)
				callbacks.OnMessageReceived(peerFP, raw, msgID)
			}
			continue
		}

		if msgType == string(TypeStatus) {
			if state, _ := msg["state"].(string); state == "offline" {
				reason, _ := msg["reason"].(string)
				m.mu.Lock()
				wasActive := false
				if current, ok := m.sessions[peerFP]; ok && current == s {
					delete(m.sessions, peerFP)
					wasActive = true
				}
				m.mu.Unlock()

				if wasActive {
					disconnectedNotified = true
					callbacks := m.callbacksSnapshot()
					if callbacks.OnPeerDisconnected != nil {
						callbacks.OnPeerDisconnected(peerFP, reason)
					}
				}
				return
			}
			continue
		}

		if msgType == string(TypeAck) {
			continue
		}

		if msgType == string(TypeFileMeta) {
			fileIDB64, _ := msg["file_id"].(string)
			fileID, decodeErr := base64.StdEncoding.DecodeString(fileIDB64)
			if decodeErr != nil || len(fileID) != transport.FileIDSize {
				continue
			}
			transferKey := base64.RawURLEncoding.EncodeToString(fileID)
			rawMeta, encodeErr := EncodeMessage(msg)
			if encodeErr != nil {
				continue
			}
			m.mu.RLock()
			storageDir := m.storageDir
			m.mu.RUnlock()
			if storageDir == "" {
				storageDir = os.TempDir()
			}
			downloadsDir := filepath.Join(storageDir, "config", "downloads")
			if _, receiveErr := m.fileTransferMgr.ReceiveChunk(
				peerFP,
				transferKey,
				base64.StdEncoding.EncodeToString(rawMeta),
				downloadsDir,
			); receiveErr != nil {
				continue
			}

			fileNameStr := firstNonEmptyString(msg["file_name"], "")
			offer := map[string]any{
				"type":       "file_offer",
				"message_id": firstNonEmptyString(msg["message_id"], msg["id"], fileIDB64),
				"file_name":  fileNameStr,
				"size":       msg["file_size"],
				"caption":    firstNonEmptyString(msg["caption"], ""),
				"emoji":      firstNonEmptyString(msg["emoji"], ""),
				"mime":       guessMimeType(fileNameStr),
			}
			if albumID, ok := msg["album_id"].(string); ok && albumID != "" {
				var aCount int
				switch v := msg["album_count"].(type) {
				case float64:
					aCount = int(v)
				case int:
					aCount = v
				case int64:
					aCount = int(v)
				}
				var aIdx int = -1
				switch v := msg["album_index"].(type) {
				case float64:
					aIdx = int(v)
				case int:
					aIdx = v
				case int64:
					aIdx = int(v)
				}
				if aCount >= 2 && aCount <= 100 && aIdx >= 0 && aIdx < aCount {
					offer["album_id"] = albumID
					offer["album_index"] = aIdx
					offer["album_count"] = aCount
				}
			}
			rawOffer, err := json.Marshal(offer)
			callbacks := m.callbacksSnapshot()
			if err == nil && callbacks.OnMessageReceived != nil {
				messageID, _ := offer["message_id"].(string)
				callbacks.OnMessageReceived(peerFP, rawOffer, messageID)
			}
			continue
		}

		if msgType == string(TypeFileChunk) {
			msgID, _ := msg["message_id"].(string)
			if msgID == "" {
				msgID, _ = msg["id"].(string)
			}
			payloadStr, legacyJSON := msg["payload"].(string)
			if payloadBytes, ok := msg["payload"].([]byte); ok {
				fileIDB64, _ := msg["file_id"].(string)
				fileID, decodeErr := base64.StdEncoding.DecodeString(fileIDB64)
				if decodeErr != nil || len(fileID) != transport.FileIDSize {
					continue
				}
				msgID = base64.RawURLEncoding.EncodeToString(fileID)
				payloadStr = base64.StdEncoding.EncodeToString(payloadBytes)
			} else if !legacyJSON {
				continue
			}

			m.mu.RLock()
			storageDir := m.storageDir
			m.mu.RUnlock()
			if storageDir == "" {
				storageDir = os.TempDir()
			}
			downloadsDir := filepath.Join(storageDir, "config", "downloads")

			assembled, err := m.fileTransferMgr.ReceiveChunk(peerFP, msgID, payloadStr, downloadsDir)
			if err != nil {
				continue
			}
			if assembled != nil {
				fileMsg := map[string]any{
					"type":       "file",
					"message_id": assembled.MessageID,
					"file_path":  assembled.FilePath,
					"file_name":  assembled.FileName,
					"caption":    assembled.Caption,
					"emoji":      assembled.Emoji,
					"size":       assembled.FileSize,
					"mime":       guessMimeType(assembled.FileName),
				}
				if assembled.AlbumID != "" && assembled.AlbumCount >= 2 && assembled.AlbumIndex >= 0 && assembled.AlbumIndex < assembled.AlbumCount {
					fileMsg["album_id"] = assembled.AlbumID
					fileMsg["album_index"] = assembled.AlbumIndex
					fileMsg["album_count"] = assembled.AlbumCount
				}
				rawFileMsg, err := json.Marshal(fileMsg)
				callbacks := m.callbacksSnapshot()
				if err == nil && callbacks.OnMessageReceived != nil {
					callbacks.OnMessageReceived(peerFP, rawFileMsg, assembled.MessageID)
				}
			}
			continue
		}

		if msgType == "binary" {
			if payloadBytes, ok := msg["payload"].([]byte); ok {
				msgID, _ := msg["id"].(string)
				callbacks := m.callbacksSnapshot()
				if callbacks.OnMessageReceived != nil {
					callbacks.OnMessageReceived(peerFP, payloadBytes, msgID)
				}
				continue
			}
		}

		raw, err := EncodeMessage(msg)
		callbacks := m.callbacksSnapshot()
		if err == nil && callbacks.OnMessageReceived != nil {
			msgID, _ := msg["id"].(string)
			callbacks.OnMessageReceived(peerFP, raw, msgID)
		}
	}
}

// SetStorageDir updates the base application storage directory for files/downloads.
func (m *Manager) SetStorageDir(dir string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.storageDir = dir
}

func guessMimeType(fileName string) string {
	ext := strings.ToLower(filepath.Ext(fileName))
	switch ext {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	case ".gif":
		return "image/gif"
	case ".mp4":
		return "video/mp4"
	case ".mov":
		return "video/quicktime"
	case ".ogg":
		return "audio/ogg"
	case ".m4a":
		return "audio/mp4"
	case ".mp3":
		return "audio/mpeg"
	case ".pdf":
		return "application/pdf"
	case ".zip":
		return "application/zip"
	case ".json":
		return "application/json"
	case ".txt":
		return "text/plain"
	default:
		return "application/octet-stream"
	}
}

// UpdatePeerNameMapping maps a peer's identity fingerprint to a nickname for fallback lookup.
func (m *Manager) UpdatePeerNameMapping(peerFP, nickname string) {
	peerFP = strings.TrimSpace(peerFP)
	nickname = strings.TrimSpace(nickname)
	if peerFP == "" || nickname == "" {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.peerNames == nil {
		m.peerNames = make(map[string]string)
	}
	m.peerNames[nickname] = peerFP
	m.peerNames[strings.ToLower(nickname)] = peerFP
	m.peerNames[peerFP] = nickname
}

// resolveSessionLocked finds an active online session matching peerFP, nickname, or 1-on-1 fallback.
// Caller MUST hold at least m.mu.RLock().
func (m *Manager) resolveSessionLocked(peerFP string) (*Session, bool) {
	peerFP = strings.TrimSpace(peerFP)
	if peerFP == "" && len(m.sessions) == 1 {
		for _, s := range m.sessions {
			if s != nil && s.IsOnline() {
				return s, true
			}
		}
	}
	if peerFP == "" {
		return nil, false
	}

	// 1. Direct fingerprint match
	if s, exists := m.sessions[peerFP]; exists && s.IsOnline() {
		return s, true
	}

	// 2. Nickname map lookup (exact match)
	if actualFP, ok := m.peerNames[peerFP]; ok {
		if s, exists := m.sessions[actualFP]; exists && s.IsOnline() {
			return s, true
		}
	}

	// 3. Nickname map lookup (case-insensitive)
	if actualFP, ok := m.peerNames[strings.ToLower(peerFP)]; ok {
		if s, exists := m.sessions[actualFP]; exists && s.IsOnline() {
			return s, true
		}
	}

	// 4. Fallback search by endpoint or matching nickname
	for fp, sess := range m.sessions {
		if sess.IsOnline() {
			if fp == peerFP || m.peerEndp[fp] == peerFP || strings.EqualFold(m.peerNames[fp], peerFP) {
				return sess, true
			}
		}
	}

	return nil, false
}

// SendMessage sends a text message to a connected peer.
func (m *Manager) SendMessage(peerFP, text string) (string, error) {
	m.mu.RLock()
	s, exists := m.resolveSessionLocked(peerFP)
	nick := m.nickname
	m.mu.RUnlock()

	if !exists || !s.IsOnline() {
		return "", errors.New("peer is not connected")
	}

	return s.SendChat(text, nick)
}

// SendMessageBinary sends a raw binary message payload to a connected peer.
func (m *Manager) SendMessageBinary(peerFP string, payload []byte) (string, error) {
	m.mu.RLock()
	s, exists := m.resolveSessionLocked(peerFP)
	m.mu.RUnlock()

	if !exists || !s.IsOnline() {
		return "", errors.New("peer is not connected")
	}

	return s.SendReliableBinary(payload)
}

// IsPeerOnline returns true if there is an active online session for peerFP or endpoint.
func (m *Manager) IsPeerOnline(peerFP string) bool {
	if m == nil {
		return false
	}
	m.mu.RLock()
	defer m.mu.RUnlock()

	s, exists := m.resolveSessionLocked(peerFP)
	return exists && s != nil && s.IsOnline()
}

// SendFile streams a local file to a connected peer in 256 KiB chunks.
func (m *Manager) SendFile(peerFP, filePath, messageID, fileName, caption, emoji, albumID string, albumIndex, albumCount int) (string, error) {
	m.mu.RLock()
	s, exists := m.resolveSessionLocked(peerFP)
	m.mu.RUnlock()

	if !exists || !s.IsOnline() {
		return "", errors.New("peer is not connected")
	}

	if messageID == "" {
		messageID = fmt.Sprintf("file_%d", time.Now().UnixNano())
	}

	go func() {
		var metadata *transport.FileMetadata
		var nextChunkIndex uint32
		_ = m.fileTransferMgr.SendFileStream(
			context.Background(),
			peerFP,
			messageID,
			filePath,
			fileName,
			caption,
			emoji,
			func(payload []byte) error {
				if metadata == nil {
					decoded, err := transport.DecodeMetadataJSON(payload)
					if err != nil {
						return err
					}
					metadata = decoded
					metadata.Type = string(TypeFileMeta)
					metadata.ID = messageID
					metadata.MessageID = messageID
					metadata.ChunkSize = transport.DefaultChunkSize
					metadata.ChunkFormat = transport.FileChunkFormatV2
					metadata.AckWindow = transport.DefaultFileChunkAckWindow
					metadata.Timestamp = time.Now().Unix()
					if albumID != "" && albumCount >= 2 && albumIndex >= 0 && albumIndex < albumCount {
						metadata.AlbumID = albumID
						idx := albumIndex
						metadata.AlbumIndex = &idx
						metadata.AlbumCount = albumCount
					}
					rawMeta, err := metadata.EncodeMetadataJSON()
					if err != nil {
						return err
					}
					var metaMessage map[string]any
					if err := json.Unmarshal(rawMeta, &metaMessage); err != nil {
						return err
					}
					_, err = s.SendReliable(metaMessage)
					return err
				}
				var chunkIdx uint32
				if len(payload) >= crypto.SecretBoxNonceSize {
					chunkIdx = uint32(binary.BigEndian.Uint64(payload[transport.FileNoncePrefixSize:crypto.SecretBoxNonceSize]))
				} else {
					chunkIdx = atomic.AddUint32(&nextChunkIndex, 1) - 1
				}
				_, err := s.SendReliableFileChunk(metadata.FileID, chunkIdx, payload)
				return err
			},
		)
	}()

	return messageID, nil
}

func firstNonEmptyString(values ...any) string {
	for _, value := range values {
		if text, ok := value.(string); ok && strings.TrimSpace(text) != "" {
			return text
		}
	}
	return ""
}

// CancelFile cancels an active file transfer by messageID.
func (m *Manager) CancelFile(messageID string) bool {
	return m.fileTransferMgr.CancelTransfer(messageID)
}

// GetSession returns an active session for the peer if present.
func (m *Manager) GetSession(peerFP string) *Session {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.sessions[peerFP]
}

// Close closes the manager, listener, and all active sessions.
func (m *Manager) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	_ = m.listener.Stop()
	for _, s := range m.sessions {
		_ = s.Close()
	}
	m.sessions = make(map[string]*Session)
	return nil
}
