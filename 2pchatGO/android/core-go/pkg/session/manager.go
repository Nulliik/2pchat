package session

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"twopchat/core/pkg/crypto"
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
	identity        *crypto.IdentityKeyPair
	prekeyPriv      *crypto.X25519PrivateKey
	prekeyPub       *crypto.X25519PublicKey
	dialer          *transport.AdaptiveDialer
	listener        *transport.AsyncListener
	sessions        map[string]*Session
	peerEndp        map[string]string
	peerNames       map[string]string
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
	m := &Manager{
		identity:     id,
		prekeyPriv:   prekeyPriv,
		prekeyPub:    prekeyPub,
		dialer:       transport.NewAdaptiveDialer(torProxy, proxyEnabled, 10*time.Second),
		listener:     transport.NewAsyncListener(),
		sessions:     make(map[string]*Session),
		peerEndp:     make(map[string]string),
		peerNames:    make(map[string]string),
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

// StartListener starts the dual-stack TCP listener on the specified port.
func (m *Manager) StartListener(port int) error {
	return m.listener.Start(port, func(conn net.Conn) {
		m.handleIncomingConnection(conn)
	})
}

// StopListener stops the TCP listener.
func (m *Manager) StopListener() error {
	return m.listener.Stop()
}

// SetTorProxy updates the Tor SOCKS5 proxy configuration.
func (m *Manager) SetTorProxy(enabled bool, addr string) {
	m.dialer.SetTorProxy(enabled, addr)
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

	sess, err := NewSession(
		conn,
		false, // responder
		m.identity,
		m.prekeyPriv,
		m.prekeyPub,
		"", // accept any valid key during incoming connection
		30*time.Second,
	)
	if err != nil {
		callbacks := m.callbacksSnapshot()
		if callbacks.OnError != nil {
			callbacks.OnError(1, fmt.Sprintf("Incoming handshake failed: %v", err))
		}
		return
	}

	m.mu.RLock()
	onion := m.onionAddress
	m.mu.RUnlock()

	endpoint := conn.RemoteAddr().String()
	if onion != "" && (strings.HasPrefix(endpoint, "127.0.0.1:") || strings.HasPrefix(endpoint, "[::1]:")) {
		sess.SetTorTransport(true)
	}

	peerFP := sess.PeerFingerprint()
	m.RegisterSession(sess, peerFP, endpoint, false)
}

// ConnectPeer dials a remote peer endpoint and establishes an encrypted X3DH session.
func (m *Manager) ConnectPeer(endpoint, expectedFingerprint string) (*Session, error) {
	timeout := 15 * time.Second
	if strings.HasSuffix(strings.ToLower(endpoint), ".onion") || (m.dialer != nil && m.dialer.ClassifyEndpoint(endpoint) == transport.TransportTor) {
		timeout = transport.DefaultTorDialTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	conn, err := m.dialer.DialContext(ctx, "tcp", endpoint)
	if err != nil {
		return nil, fmt.Errorf("failed to dial endpoint %s: %w", endpoint, err)
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
		return nil, fmt.Errorf("initiator handshake failed with %s: %w", endpoint, err)
	}

	if strings.HasSuffix(strings.ToLower(endpoint), ".onion") || m.dialer.ClassifyEndpoint(endpoint) == transport.TransportTor {
		sess.SetTorTransport(true)
	}

	peerFP := sess.PeerFingerprint()
	m.RegisterSession(sess, peerFP, endpoint, true)
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

			offer := map[string]any{
				"type":       "file_offer",
				"message_id": firstNonEmptyString(msg["message_id"], msg["id"], fileIDB64),
				"file_name":  msg["file_name"],
				"size":       msg["file_size"],
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

	// 5. 1-on-1 fallback: If there is exactly one active session, route all messages to it (parity with Python discovery_bridge.py)
	if len(m.sessions) == 1 {
		for _, sess := range m.sessions {
			if sess.IsOnline() {
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

	_, exists := m.resolveSessionLocked(peerFP)
	return exists
}

// SendFile streams a local file to a connected peer in 256 KiB chunks.
func (m *Manager) SendFile(peerFP, filePath, messageID, fileName, caption, emoji string) (string, error) {
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
				_, err := s.SendReliableFileChunk(metadata.FileID, nextChunkIndex, payload)
				nextChunkIndex++
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
