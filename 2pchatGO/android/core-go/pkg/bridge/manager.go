package bridge

import (
	"context"
	"crypto/ed25519"
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
	"twopchat/core/pkg/discovery"
	"twopchat/core/pkg/session"
	"twopchat/core/pkg/transport"
)

// SessionManager manages active Double Ratchet sessions, local identity, networking, and discovery.
type SessionManager struct {
	mu           sync.RWMutex
	storageDir   string
	identity     *crypto.IdentityKeyPair
	prekeyPriv   *crypto.X25519PrivateKey
	prekeyPub    *crypto.X25519PublicKey
	sessions     map[string]*crypto.SessionState
	netManager   *session.Manager
	discoverySvc *discovery.DiscoveryService
	callbacks    session.EventCallbacks
	onPeerDisc   discovery.DiscoveryCallback
	torEnabled   bool
	torProxy     string
	onionAddress string
	dialer       *transport.AdaptiveDialer
	upnpMapper   *transport.UPnPMapper
	natDiag      *transport.NATDiagnostics
	holePuncher  *transport.HolePuncher
}

var (
	globalManager *SessionManager
	once          sync.Once
)

// GetManager returns the singleton SessionManager instance.
func GetManager() *SessionManager {
	once.Do(func() {
		globalManager = &SessionManager{
			sessions: make(map[string]*crypto.SessionState),
			torProxy: "127.0.0.1:9050",
			dialer:   transport.NewAdaptiveDialer("127.0.0.1:9050", false, 10*time.Second),
		}
	})
	return globalManager
}

// SetCallbacks sets the event callback hooks for JNI dispatch.
func (m *SessionManager) SetCallbacks(cb session.EventCallbacks, onPeerDisc discovery.DiscoveryCallback) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.callbacks = cb
	m.onPeerDisc = onPeerDisc
}

// SetStorageDir sets the persistent directory for cryptographic keys and downloads.
func (m *SessionManager) SetStorageDir(dir string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.storageDir = dir
	if m.netManager != nil {
		m.netManager.SetStorageDir(dir)
	}
}

// Init initializes the local Go crypto engine, keys, network manager, and discovery service.
func (m *SessionManager) Init() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	effectiveDir := m.storageDir
	if effectiveDir == "" {
		for _, fallback := range []string{"/data/user/0/com.example.twopchat/files", "/data/data/com.example.twopchat/files"} {
			if info, err := os.Stat(fallback); err == nil && info.IsDir() {
				effectiveDir = fallback
				m.storageDir = fallback
				break
			}
		}
	}

	if m.identity == nil {
		var id *crypto.IdentityKeyPair
		var keyPath string
		if effectiveDir != "" {
			keyPath = filepath.Join(effectiveDir, "identity_v1.key")
			if data, readErr := os.ReadFile(keyPath); readErr == nil && len(data) == 96 {
				xPriv, xpErr := crypto.X25519PrivateKeyFromBytes(data[:32])
				if xpErr == nil {
					edPriv := ed25519.PrivateKey(data[32:96])
					id = &crypto.IdentityKeyPair{
						Private: xPriv,
						Public:  xPriv.Public(),
						Signing: edPriv,
						Verify:  edPriv.Public().(ed25519.PublicKey),
					}
				}
			}
		}
		if id == nil {
			var err error
			id, err = crypto.GenerateIdentityKeyPair()
			if err != nil {
				return fmt.Errorf("failed to generate identity: %w", err)
			}
			if keyPath != "" {
				keyData := make([]byte, 96)
				copy(keyData[:32], id.Private.Bytes())
				copy(keyData[32:96], id.Signing)
				_ = os.WriteFile(keyPath, keyData, 0600)
			}
		}
		m.identity = id
	}

	if m.prekeyPriv == nil {
		var priv *crypto.X25519PrivateKey
		var pub *crypto.X25519PublicKey
		var prekeyPath string
		if effectiveDir != "" {
			prekeyPath = filepath.Join(effectiveDir, "prekey_v1.key")
			if data, readErr := os.ReadFile(prekeyPath); readErr == nil && len(data) == 32 {
				priv, _ = crypto.X25519PrivateKeyFromBytes(data)
				if priv != nil {
					pub = priv.Public()
				}
			}
		}
		if priv == nil {
			var err error
			priv, pub, err = crypto.GenerateX25519Keypair()
			if err != nil {
				return fmt.Errorf("failed to generate signed prekey: %w", err)
			}
			if prekeyPath != "" {
				_ = os.WriteFile(prekeyPath, priv.Bytes(), 0600)
			}
		}
		m.prekeyPriv = priv
		m.prekeyPub = pub
	}

	if m.netManager == nil {
		m.netManager = session.NewManager(
			m.identity,
			m.prekeyPriv,
			m.prekeyPub,
			m.torProxy,
			m.torEnabled,
			m.callbacks,
		)
		if effectiveDir != "" {
			m.netManager.SetStorageDir(effectiveDir)
		}
		if m.onionAddress != "" {
			m.netManager.SetOnionAddress(m.onionAddress)
		}
	}

	if m.discoverySvc == nil {
		fp := crypto.Fingerprint(m.identity.Public.Bytes())
		m.discoverySvc = discovery.NewDiscoveryService(
			fp,
			50001,
			m.dialer,
			m.torEnabled,
			func(infoHashHex, endpoint, source string) {
				if m.onPeerDisc != nil {
					m.onPeerDisc(infoHashHex, endpoint, source)
				}
			},
		)
		if m.onionAddress != "" {
			m.discoverySvc.SetOnionAddress(m.onionAddress)
		}
	}

	return nil
}

// StartDiscovery launches the discovery service with given trackers and info hashes.
func (m *SessionManager) StartDiscovery(trackersJSON, infoHashesJSON string, port int) error {
	if err := m.Init(); err != nil {
		return err
	}

	m.mu.Lock()
	svc := m.discoverySvc
	m.mu.Unlock()

	if trackersJSON != "" {
		var trackers []string
		if err := json.Unmarshal([]byte(trackersJSON), &trackers); err == nil {
			svc.SetTrackers(trackers)
		}
	}

	if infoHashesJSON != "" {
		var hashes []string
		if err := json.Unmarshal([]byte(infoHashesJSON), &hashes); err == nil {
			for _, h := range hashes {
				_ = svc.RegisterInfoHash(h)
			}
		}
	}

	return svc.Start()
}

// StopDiscovery halts the discovery service.
func (m *SessionManager) StopDiscovery() error {
	m.mu.RLock()
	svc := m.discoverySvc
	m.mu.RUnlock()

	if svc != nil {
		return svc.Stop()
	}
	return nil
}

// UpdateTrackers updates the list of active BitTorrent trackers on the fly.
func (m *SessionManager) UpdateTrackers(trackersJSON string) error {
	m.mu.RLock()
	svc := m.discoverySvc
	m.mu.RUnlock()

	if svc == nil {
		return errors.New("discovery service not initialized")
	}

	var trackers []string
	if err := json.Unmarshal([]byte(trackersJSON), &trackers); err != nil {
		return fmt.Errorf("invalid trackers JSON: %w", err)
	}

	svc.SetTrackers(trackers)
	return nil
}

// ReloadIdentity resets cached identity keys and reloads from disk.
func (m *SessionManager) ReloadIdentity() error {
	m.mu.Lock()
	m.identity = nil
	m.prekeyPriv = nil
	m.netManager = nil
	m.discoverySvc = nil
	m.mu.Unlock()

	if err := m.Init(); err != nil {
		return err
	}

	m.mu.RLock()
	id := m.identity
	disc := m.discoverySvc
	m.mu.RUnlock()

	if disc != nil && id != nil {
		fp := crypto.Fingerprint(id.Public.Bytes())
		_ = disc.RegisterInfoHash(fp)
	}

	return nil
}

// AnnounceSelf registers an info hash and announces to all trackers.
func (m *SessionManager) AnnounceSelf(infoHashHex string, port int) error {
	if err := m.Init(); err != nil {
		return err
	}

	m.mu.RLock()
	svc := m.discoverySvc
	m.mu.RUnlock()

	if svc != nil {
		if err := svc.RegisterInfoHash(infoHashHex); err != nil {
			return err
		}
		go svc.AnnounceAll()
	}
	return nil
}

// ProbePeer races multi-tier candidate endpoints to establish a fast connection.
func (m *SessionManager) ProbePeer(endpointsJSON, expectedFingerprint string) error {
	if err := m.Init(); err != nil {
		return err
	}

	var endpoints []string
	if err := json.Unmarshal([]byte(endpointsJSON), &endpoints); err != nil {
		return fmt.Errorf("invalid endpoints JSON: %w", err)
	}

	m.mu.RLock()
	svc := m.discoverySvc
	nm := m.netManager
	dialer := m.dialer
	m.mu.RUnlock()

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()

		conn, winEndpoint, err := svc.ProbeFast(ctx, endpoints, func(c context.Context, ep string) (net.Conn, error) {
			return dialer.DialContext(c, "tcp", ep)
		})
		if err != nil {
			if m.callbacks.OnError != nil {
				m.callbacks.OnError(3, fmt.Sprintf("ProbePeer failed for all endpoints: %v", err))
			}
			return
		}

		sess, err := session.NewSession(
			conn,
			true,
			m.identity,
			m.prekeyPriv,
			m.prekeyPub,
			expectedFingerprint,
			10*time.Second,
		)
		if err != nil {
			_ = conn.Close()
			return
		}

		if strings.HasSuffix(strings.ToLower(winEndpoint), ".onion") || dialer.ClassifyEndpoint(winEndpoint) == transport.TransportTor {
			sess.SetTorTransport(true)
		}

		peerFP := sess.PeerFingerprint()
		if nm != nil {
			nm.RegisterSession(sess, peerFP, winEndpoint, true)
		}
	}()

	return nil
}

// StartListener binds the async TCP listener to the specified port.
func (m *SessionManager) StartListener(port int) error {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		if err := m.Init(); err != nil {
			return err
		}
		nm = m.netManager
	}

	return nm.StartListener(port)
}

// StopListener stops the TCP listener.
func (m *SessionManager) StopListener() error {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		return nil
	}
	return nm.StopListener()
}

// GetLocalFingerprint returns the local identity fingerprint string.
func (m *SessionManager) GetLocalFingerprint() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.identity == nil {
		return ""
	}
	return crypto.Fingerprint(m.identity.Public.Bytes())
}

// GetBoundPort returns the bound listener port.
func (m *SessionManager) GetBoundPort() int {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()
	if nm == nil {
		return 0
	}
	return nm.Port()
}

// ConfigureLocalIdentity imports identity and updates manager state.
func (m *SessionManager) ConfigureLocalIdentity(nickname, privB64, aboutMe string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()

	privBytes, err := base64.StdEncoding.DecodeString(privB64)
	if err != nil || len(privBytes) != 32 {
		return false
	}

	idKey, err := crypto.IdentityKeyPairFromSeed(privBytes)
	if err != nil {
		return false
	}

	m.identity = idKey
	if m.netManager != nil {
		m.netManager.SetNickname(nickname)
	}
	return true
}

// SetTorProxy updates Tor SOCKS5 proxy settings.
func (m *SessionManager) SetTorProxy(enabled bool, addr string) {
	m.mu.Lock()
	m.torEnabled = enabled
	if addr != "" {
		m.torProxy = addr
	}
	m.dialer.SetTorProxy(enabled, addr)
	nm := m.netManager
	m.mu.Unlock()

	if nm != nil {
		nm.SetTorProxy(enabled, addr)
	}
}

// SetOnionAddress updates the local Tor v3 hidden service hostname across services.
func (m *SessionManager) SetOnionAddress(addr string) {
	m.mu.Lock()
	m.onionAddress = strings.TrimSpace(addr)
	nm := m.netManager
	ds := m.discoverySvc
	m.mu.Unlock()

	if nm != nil {
		nm.SetOnionAddress(m.onionAddress)
	}
	if ds != nil {
		ds.SetOnionAddress(m.onionAddress)
	}
}

// GetOnionAddress returns the configured local Tor v3 .onion hostname.
func (m *SessionManager) GetOnionAddress() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.onionAddress
}

// ConnectPeer dials a remote peer endpoint and establishes an encrypted session.
func (m *SessionManager) ConnectPeer(endpoint, expectedFingerprint string) error {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		if err := m.Init(); err != nil {
			return err
		}
		nm = m.netManager
	}

	go func() {
		_, err := nm.ConnectPeer(endpoint, expectedFingerprint)
		if err != nil && m.callbacks.OnError != nil {
			m.callbacks.OnError(2, fmt.Sprintf("ConnectPeer to %s failed: %v", endpoint, err))
		}
	}()

	return nil
}

// SendMessage sends a text message to a connected peer.
func (m *SessionManager) SendMessage(peerFP, text string) (string, error) {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		return "", errors.New("network manager not initialized")
	}

	return nm.SendMessage(peerFP, text)
}

// SendFile streams a local file to a connected peer in 64KB chunks.
func (m *SessionManager) SendFile(peerFP, filePath, messageID, fileName, caption, emoji string) (string, error) {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		return "", errors.New("network manager not initialized")
	}

	return nm.SendFile(peerFP, filePath, messageID, fileName, caption, emoji)
}

// CancelFile cancels an active file transfer by messageID.
func (m *SessionManager) CancelFile(messageID string) bool {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		return false
	}
	return nm.CancelFile(messageID)
}

// GetLocalIdentityJSON returns JSON containing local public keys.
func (m *SessionManager) GetLocalIdentityJSON() (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if m.identity == nil || m.prekeyPub == nil {
		return "", errors.New("native core not initialized")
	}

	sig := crypto.SignPreKey(m.identity.Signing, m.prekeyPub)

	data := map[string]string{
		"identityPub":     base64.StdEncoding.EncodeToString(m.identity.Public.Bytes()),
		"verifyPub":       base64.StdEncoding.EncodeToString(m.identity.Verify),
		"signedPrekeyPub": base64.StdEncoding.EncodeToString(m.prekeyPub.Bytes()),
		"prekeySignature": base64.StdEncoding.EncodeToString(sig),
		"fingerprint":     crypto.Fingerprint(m.identity.Public.Bytes()),
	}

	b, err := json.Marshal(data)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// InitSessionAsInitiator initializes an X3DH Double Ratchet session towards a peer.
func (m *SessionManager) InitSessionAsInitiator(sessionId string, bundleJSON string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.identity == nil {
		return errors.New("native core not initialized")
	}

	var raw map[string]string
	if err := json.Unmarshal([]byte(bundleJSON), &raw); err != nil {
		return fmt.Errorf("invalid bundle JSON: %w", err)
	}

	identityPubBytes, err := base64.StdEncoding.DecodeString(raw["identityPub"])
	if err != nil {
		return err
	}
	identityPub, err := crypto.X25519PublicKeyFromBytes(identityPubBytes)
	if err != nil {
		return err
	}

	verifyPubBytes, err := base64.StdEncoding.DecodeString(raw["verifyPub"])
	if err != nil {
		return err
	}

	signedPrekeyPubBytes, err := base64.StdEncoding.DecodeString(raw["signedPrekeyPub"])
	if err != nil {
		return err
	}
	signedPrekeyPub, err := crypto.X25519PublicKeyFromBytes(signedPrekeyPubBytes)
	if err != nil {
		return err
	}

	prekeySig, err := base64.StdEncoding.DecodeString(raw["prekeySignature"])
	if err != nil {
		return err
	}

	bundle := &crypto.PreKeyBundle{
		IdentityPub:       identityPub,
		IdentityVerifyPub: verifyPubBytes,
		SignedPrekeyPub:   signedPrekeyPub,
		SignedPrekeySig:   prekeySig,
	}

	eph, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		return err
	}

	session, err := crypto.InitializeSessionFromPreKey(m.identity, bundle, eph)
	if err != nil {
		return err
	}

	m.sessions[sessionId] = session
	return nil
}

// InitSessionAsResponder creates a responder session for incoming messages.
func (m *SessionManager) InitSessionAsResponder(
	sessionId string,
	initiatorIdentityPubBytes []byte,
	initiatorEphemeralPubBytes []byte,
) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.identity == nil || m.prekeyPriv == nil {
		return errors.New("native core not initialized")
	}

	initiatorIdPub, err := crypto.X25519PublicKeyFromBytes(initiatorIdentityPubBytes)
	if err != nil {
		return err
	}

	initiatorEphPub, err := crypto.X25519PublicKeyFromBytes(initiatorEphemeralPubBytes)
	if err != nil {
		return err
	}

	session, err := crypto.RespondToPreKeyInit(
		m.identity,
		m.prekeyPriv,
		nil,
		initiatorIdPub,
		initiatorEphPub,
	)
	if err != nil {
		return err
	}

	m.sessions[sessionId] = session
	return nil
}

// Encrypt encrypts a payload for a given session.
func (m *SessionManager) Encrypt(sessionId string, plaintext []byte) ([]byte, error) {
	m.mu.RLock()
	session, exists := m.sessions[sessionId]
	m.mu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("session not found: %s", sessionId)
	}

	return session.EncryptMessage(plaintext)
}

// Decrypt decrypts a packet for a given session.
func (m *SessionManager) Decrypt(sessionId string, packet []byte) ([]byte, error) {
	m.mu.RLock()
	session, exists := m.sessions[sessionId]
	m.mu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("session not found: %s", sessionId)
	}

	return session.DecryptMessage(packet)
}

// CloseSession removes a session from memory and zeroizes sensitive state.
func (m *SessionManager) CloseSession(sessionId string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if session, exists := m.sessions[sessionId]; exists {
		session.Zeroize()
		delete(m.sessions, sessionId)
	}
}

// GetLocalSigningPublicKey returns the Base64 representation of the local Ed25519 verify key.
func (m *SessionManager) GetLocalSigningPublicKey() (string, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if m.identity == nil {
		return "", errors.New("identity not initialized")
	}
	return base64.StdEncoding.EncodeToString(m.identity.Verify), nil
}

// SignGroupPayload signs a canonical group payload string with local Ed25519 signing key.
func (m *SessionManager) SignGroupPayload(canonicalPayload string) (string, error) {
	m.mu.RLock()
	id := m.identity
	m.mu.RUnlock()

	if id == nil {
		return "", errors.New("identity not initialized")
	}
	return crypto.SignGroupPayload(id.Signing, canonicalPayload)
}

// VerifyGroupPayload verifies an Ed25519 signature over a canonical group payload string.
func (m *SessionManager) VerifyGroupPayload(verificationKeyBase64, canonicalPayload, signatureBase64 string) bool {
	pubBytes, err := base64.StdEncoding.DecodeString(verificationKeyBase64)
	if err != nil || len(pubBytes) != crypto.KeySize {
		return false
	}
	return crypto.VerifyGroupPayload(pubBytes, canonicalPayload, signatureBase64)
}

// GroupEncrypt encrypts plaintext with AES-256-GCM using epochSecret and authenticatedData.
func (m *SessionManager) GroupEncrypt(epochSecret, authenticatedData, plaintext []byte) (string, string, error) {
	return crypto.GroupEncrypt(epochSecret, authenticatedData, plaintext)
}

// GroupDecrypt decrypts AES-256-GCM ciphertext using epochSecret and authenticatedData.
func (m *SessionManager) GroupDecrypt(epochSecret, authenticatedData []byte, nonceBase64, ciphertextBase64 string) ([]byte, error) {
	return crypto.GroupDecrypt(epochSecret, authenticatedData, nonceBase64, ciphertextBase64)
}

// TriggerNatTraversal launches background STUN NAT discovery and UPnP port mapping.
func (m *SessionManager) TriggerNatTraversal() bool {
	m.mu.Lock()
	torActive := m.torEnabled
	port := 0
	if m.netManager != nil {
		port = m.netManager.Port()
	}
	if m.upnpMapper == nil {
		m.upnpMapper = transport.NewUPnPMapper(torActive)
	}
	if m.holePuncher == nil {
		m.holePuncher = transport.NewHolePuncher(port, torActive)
	} else {
		m.holePuncher.SetLocalPort(port)
	}
	m.mu.Unlock()

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()

		diag := transport.DetectNATEnvironment(ctx, torActive)

		if !torActive && port > 0 {
			_ = m.upnpMapper.DiscoverAndMapPort(ctx, port)
			mapped, extIP, mPort, sType := m.upnpMapper.GetStatus()
			diag.UPnPMapped = mapped
			diag.UPnPExternalIP = extIP
			diag.UPnPMappedPort = mPort
			diag.UPnPService = sType
		}

		m.mu.Lock()
		m.natDiag = diag
		m.mu.Unlock()
	}()

	return true
}

// GetNatDiagnosticsJSON returns the latest JSON snapshot of NAT diagnostics.
func (m *SessionManager) GetNatDiagnosticsJSON() string {
	m.mu.RLock()
	diag := m.natDiag
	m.mu.RUnlock()

	if diag == nil {
		diag = &transport.NATDiagnostics{
			NATType:   transport.NATTypeUnknown,
			CheckedAt: time.Now().Unix(),
		}
	}

	data, err := json.Marshal(diag)
	if err != nil {
		return "{}"
	}
	return string(data)
}

// GetPublicEndpoint returns the discovered STUN or UPnP public IP:Port.
func (m *SessionManager) GetPublicEndpoint() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if m.natDiag != nil && m.natDiag.PublicEndpoint != "" {
		return m.natDiag.PublicEndpoint
	}
	if m.upnpMapper != nil {
		mapped, extIP, port, _ := m.upnpMapper.GetStatus()
		if mapped && extIP != "" && port > 0 {
			return fmt.Sprintf("%s:%d", extIP, port)
		}
	}
	return ""
}

// OnNetworkChanged handles network connectivity transitions (e.g. Wi-Fi reconnect) by re-announcing discovery.
func (m *SessionManager) OnNetworkChanged() error {
	m.mu.RLock()
	svc := m.discoverySvc
	m.mu.RUnlock()

	if svc != nil {
		return svc.RefreshAnnouncement()
	}
	return nil
}

