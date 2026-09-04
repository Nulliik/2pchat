package bridge

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
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
	mu              sync.RWMutex
	storageDir      string
	storageKey      [32]byte
	hasKey          bool
	identity        *crypto.IdentityKeyPair
	prekeyPriv      *crypto.X25519PrivateKey
	prekeyPub       *crypto.X25519PublicKey
	sessions        map[string]*crypto.SessionState
	netManager      *session.Manager
	discoverySvc    *discovery.DiscoveryService
	callbacks       session.EventCallbacks
	onPeerDisc      discovery.DiscoveryCallback
	onTrackerStatus discovery.TrackerStatusCallback
	torEnabled      bool
	torProxy        string
	onionAddress    string
	dialer          *transport.AdaptiveDialer
	yggUDPRelay     string
	upnpMapper      *transport.UPnPMapper
	natDiag         *transport.NATDiagnostics
	holePuncher     *transport.HolePuncher
}

func (m *SessionManager) SetTrackerStatusCallback(callback discovery.TrackerStatusCallback) {
	m.mu.Lock()
	m.onTrackerStatus = callback
	m.mu.Unlock()
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
	m.callbacks = cb
	m.onPeerDisc = onPeerDisc
	netManager := m.netManager
	m.mu.Unlock()
	if netManager != nil {
		netManager.SetCallbacks(cb)
	}
}

func (m *SessionManager) callbackSnapshot() (session.EventCallbacks, discovery.DiscoveryCallback) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.callbacks, m.onPeerDisc
}

const keyFileMagic = "2PK1"

// SetStorageKey sets the 32-byte key used to encrypt and decrypt persistent key files on disk.
func (m *SessionManager) SetStorageKey(key []byte) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(key) == 32 {
		crypto.Zeroize(m.storageKey[:])
		copy(m.storageKey[:], key)
		m.hasKey = true
	}
}

// writeKeyFile writes key data to path. If m.hasKey is true, the data is encrypted with XChaCha20-Poly1305.
func (m *SessionManager) writeKeyFile(path string, data []byte, perm os.FileMode) error {
	if !m.hasKey {
		return atomicWriteFile(path, data, perm)
	}

	aad := []byte(filepath.Base(path))
	encrypted, err := crypto.XChaCha20Poly1305Encrypt(m.storageKey[:], data, aad)
	if err != nil {
		return fmt.Errorf("failed to encrypt key file %s: %w", path, err)
	}

	payload := make([]byte, len(keyFileMagic)+len(encrypted))
	copy(payload[:len(keyFileMagic)], keyFileMagic)
	copy(payload[len(keyFileMagic):], encrypted)

	return atomicWriteFile(path, payload, perm)
}

// readKeyFile reads key data from path, decrypting it if encrypted with 2PK1, or migrating legacy plaintext.
func (m *SessionManager) readKeyFile(path string) ([]byte, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	// Case 1: Encrypted with 2PK1
	if len(raw) >= len(keyFileMagic) && string(raw[:len(keyFileMagic)]) == keyFileMagic {
		if !m.hasKey {
			return nil, errors.New("key file is encrypted but storage key is not set")
		}
		aad := []byte(filepath.Base(path))
		ciphertext := raw[len(keyFileMagic):]
		decrypted, decErr := crypto.XChaCha20Poly1305Decrypt(m.storageKey[:], ciphertext, aad)
		if decErr != nil {
			return nil, fmt.Errorf("failed to decrypt key file %s: %w", path, decErr)
		}
		return decrypted, nil
	}

	// Case 2: Legacy plaintext key file (96 bytes for identity, 32 bytes for prekey)
	base := filepath.Base(path)
	if (base == "identity_v1.key" && len(raw) == 96) || (base == "prekey_v1.key" && len(raw) == 32) {
		// If storage key is available, transparently migrate by re-encrypting on disk immediately
		if m.hasKey {
			_ = m.writeKeyFile(path, raw, 0600)
		}
		return raw, nil
	}

	return nil, fmt.Errorf("unrecognized format or length in key file %s (len: %d)", path, len(raw))
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
		for _, fallback := range []string{
			"/data/user/0/com.example.twopchat.go/files",
			"/data/data/com.example.twopchat.go/files",
			"/data/user/0/com.example.twopchat/files",
			"/data/data/com.example.twopchat/files",
		} {
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
			if data, readErr := m.readKeyFile(keyPath); readErr == nil && len(data) == 96 {
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
				crypto.Zeroize(data)
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
				_ = m.writeKeyFile(keyPath, keyData, 0600)
				crypto.Zeroize(keyData)
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
			if data, readErr := m.readKeyFile(prekeyPath); readErr == nil && len(data) == 32 {
				priv, _ = crypto.X25519PrivateKeyFromBytes(data)
				if priv != nil {
					pub = priv.Public()
				}
				crypto.Zeroize(data)
			}
		}
		if priv == nil {
			var err error
			priv, pub, err = crypto.GenerateX25519Keypair()
			if err != nil {
				return fmt.Errorf("failed to generate signed prekey: %w", err)
			}
			if prekeyPath != "" {
				prekeyBytes := priv.Bytes()
				_ = m.writeKeyFile(prekeyPath, prekeyBytes, 0600)
				crypto.Zeroize(prekeyBytes)
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
				_, onPeerDiscovered := m.callbackSnapshot()
				if onPeerDiscovered != nil {
					onPeerDiscovered(infoHashHex, endpoint, source)
				}
			},
			func(trackerURL string, success bool, peerCount int, elapsed time.Duration, detail string) {
				m.mu.RLock()
				callback := m.onTrackerStatus
				m.mu.RUnlock()
				if callback != nil {
					callback(trackerURL, success, peerCount, elapsed, detail)
				}
			},
		)
		if m.onionAddress != "" {
			m.discoverySvc.SetOnionAddress(m.onionAddress)
		}
		m.discoverySvc.SetYggdrasilUDPRelay(m.yggUDPRelay)
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

// ResetCooldowns clears failure backoff on all candidate endpoints.
func (m *SessionManager) ResetCooldowns() {
	m.mu.RLock()
	svc := m.discoverySvc
	m.mu.RUnlock()

	if svc != nil {
		svc.ResetCooldowns()
	}
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

// ReloadIdentity resets cached identity keys, wipes them from memory, and reloads from disk.
func (m *SessionManager) ReloadIdentity() error {
	m.mu.Lock()
	if m.identity != nil {
		m.identity.Zeroize()
		m.identity = nil
	}
	if m.prekeyPriv != nil {
		crypto.Zeroize(m.prekeyPriv[:])
		m.prekeyPriv = nil
	}
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
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		return errors.New("network manager not initialized")
	}

	go func() {
		endpointStr := strings.Join(endpoints, ",")
		_, err := nm.ConnectPeer(endpointStr, expectedFingerprint)
		if err != nil {
			callbacks, _ := m.callbackSnapshot()
			if callbacks.OnError != nil {
				callbacks.OnError(3, fmt.Sprintf("ProbePeer failed for all endpoints: %v", err))
			}
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

	err := nm.StartListener(port)
	if err == nil {
		actualPort := nm.Port()
		m.mu.Lock()
		torActive := m.torEnabled
		if m.holePuncher == nil {
			m.holePuncher = transport.NewHolePuncher(actualPort, torActive)
		} else {
			m.holePuncher.SetLocalPort(actualPort)
		}
		if m.dialer != nil {
			m.dialer.SetHolePuncher(m.holePuncher)
		}
		nm.SetHolePuncher(m.holePuncher)
		m.mu.Unlock()
	}
	return err
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

// Close stops the listener and cleans up resources.
func (m *SessionManager) Close() error {
	m.mu.Lock()
	crypto.Zeroize(m.storageKey[:])
	m.hasKey = false
	m.mu.Unlock()
	return m.StopListener()
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

// ConfigureLocalIdentity imports identity and updates manager state and persists to disk.
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

	if m.identity != nil {
		m.identity.Zeroize()
	}
	m.identity = idKey

	// Generate fresh signed prekey for new identity
	newPrekeyPriv, newPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err == nil {
		if m.prekeyPriv != nil {
			crypto.Zeroize(m.prekeyPriv[:])
		}
		m.prekeyPriv = newPrekeyPriv
		m.prekeyPub = newPrekeyPub
	}

	// Persist new keys atomically to disk
	effectiveDir := m.storageDir
	if effectiveDir != "" {
		keyPath := filepath.Join(effectiveDir, "identity_v1.key")
		keyData := make([]byte, 96)
		copy(keyData[:32], idKey.Private.Bytes())
		copy(keyData[32:96], idKey.Signing)
		_ = m.writeKeyFile(keyPath, keyData, 0600)
		crypto.Zeroize(keyData)

		if newPrekeyPriv != nil {
			prekeyPath := filepath.Join(effectiveDir, "prekey_v1.key")
			prekeyBytes := newPrekeyPriv.Bytes()
			_ = m.writeKeyFile(prekeyPath, prekeyBytes, 0600)
			crypto.Zeroize(prekeyBytes)
		}
	}

	if m.netManager != nil {
		m.netManager.SetNickname(nickname)
		m.netManager.SetIdentity(idKey, newPrekeyPriv, newPrekeyPub)
	}
	if m.discoverySvc != nil {
		fp := crypto.Fingerprint(idKey.Public.Bytes())
		_ = m.discoverySvc.RegisterInfoHash(fp)
	}
	return true
}

// GetLocalSeedMnemonic exports the 24-word BIP-39 mnemonic phrase representing the local account seed.
func (m *SessionManager) GetLocalSeedMnemonic() (string, error) {
	m.mu.RLock()
	id := m.identity
	m.mu.RUnlock()

	if id == nil {
		if err := m.Init(); err != nil {
			return "", err
		}
		m.mu.RLock()
		id = m.identity
		m.mu.RUnlock()
	}

	if id == nil || id.Private == nil {
		return "", errors.New("local identity not initialized")
	}

	seed := id.Private.Bytes()
	defer crypto.Zeroize(seed)

	return crypto.MnemonicFromSeed(seed)
}

// RestoreFromMnemonic restores the local identity from a 24-word BIP-39 mnemonic phrase or raw hex/base64 seed.
func (m *SessionManager) RestoreFromMnemonic(nickname, mnemonicOrHex, aboutMe string) error {
	trimmed := strings.TrimSpace(mnemonicOrHex)
	var seed []byte
	var err error

	if len(strings.Fields(trimmed)) == 24 {
		seed, err = crypto.SeedFromMnemonic(trimmed)
		if err != nil {
			return fmt.Errorf("invalid mnemonic: %w", err)
		}
	} else {
		// Fallback: try decoding raw hex (64 chars) or base64 (44 chars)
		seed, err = hex.DecodeString(trimmed)
		if err != nil || len(seed) != 32 {
			seed, err = base64.StdEncoding.DecodeString(trimmed)
			if err != nil || len(seed) != 32 {
				return errors.New("invalid seed format: expected 24 BIP-39 words, 64 hex characters, or 32-byte Base64")
			}
		}
	}
	defer crypto.Zeroize(seed)

	b64Seed := base64.StdEncoding.EncodeToString(seed)
	if !m.ConfigureLocalIdentity(nickname, b64Seed, aboutMe) {
		return errors.New("failed to configure identity from seed")
	}
	return nil
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
	svc := m.discoverySvc
	m.mu.Unlock()

	if nm != nil {
		nm.SetTorProxy(enabled, addr)
	}
	if svc != nil {
		svc.SetTorProxy(enabled)
	}
}

// SetYggdrasilConfig updates Yggdrasil proxy vs VPN mode settings across bridge services.
func (m *SessionManager) SetYggdrasilConfig(mode string, proxyAddr string) {
	m.mu.Lock()
	m.dialer.SetYggdrasilConfig(transport.YggdrasilMode(mode), proxyAddr)
	nm := m.netManager
	m.yggUDPRelay = ""
	if strings.EqualFold(mode, "proxy") {
		if host, portText, err := net.SplitHostPort(proxyAddr); err == nil {
			if port, parseErr := strconv.Atoi(portText); parseErr == nil {
				m.yggUDPRelay = net.JoinHostPort(host, strconv.Itoa(port+1))
			}
		}
	}
	svc := m.discoverySvc
	relay := m.yggUDPRelay
	m.mu.Unlock()

	if nm != nil {
		nm.SetYggdrasilConfig(mode, proxyAddr)
	}
	if svc != nil {
		svc.SetYggdrasilUDPRelay(relay)
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
		callbacks, _ := m.callbackSnapshot()
		if err != nil && callbacks.OnError != nil {
			callbacks.OnError(2, fmt.Sprintf("ConnectPeer to %s failed: %v", endpoint, err))
		}
	}()

	return nil
}

// SetNickname sets the local user nickname for outgoing messages.
func (m *SessionManager) SetNickname(nickname string) {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm != nil {
		nm.SetNickname(nickname)
	}
}

// UpdatePeerNameMapping registers a mapping between peer fingerprint and nickname for lookup.
func (m *SessionManager) UpdatePeerNameMapping(peerFP, nickname string) {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm != nil {
		nm.UpdatePeerNameMapping(peerFP, nickname)
	}
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

// SendMessageBinary sends a raw binary message payload to a connected peer.
func (m *SessionManager) SendMessageBinary(peerFP string, payload []byte) (string, error) {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		return "", errors.New("network manager not initialized")
	}

	return nm.SendMessageBinary(peerFP, payload)
}

// IsPeerOnline checks if there is an active connection to peerFP.
func (m *SessionManager) IsPeerOnline(peerFP string) bool {
	if m == nil || peerFP == "" {
		return false
	}
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		return false
	}
	return nm.IsPeerOnline(peerFP)
}

// SendFile streams a local file to a connected peer in 256 KiB chunks.
func (m *SessionManager) SendFile(peerFP, filePath, messageID, fileName, caption, emoji, albumID string, albumIndex, albumCount int) (string, error) {
	m.mu.RLock()
	nm := m.netManager
	m.mu.RUnlock()

	if nm == nil {
		return "", errors.New("network manager not initialized")
	}

	return nm.SendFile(peerFP, filePath, messageID, fileName, caption, emoji, albumID, albumIndex, albumCount)
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

// RefreshNATDiagnostics performs STUN discovery and UPnP mapping before a
// route announcement. Callers that need an externally reachable IPv4 must use
// this synchronous path; a background probe can otherwise finish after the
// endpoint_update has already been sent.
func (m *SessionManager) RefreshNATDiagnostics(ctx context.Context) bool {
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
	if m.dialer != nil {
		m.dialer.SetHolePuncher(m.holePuncher)
	}
	if m.netManager != nil {
		m.netManager.SetHolePuncher(m.holePuncher)
	}
	m.mu.Unlock()

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
	return true
}

// TriggerNatTraversal retains the non-blocking diagnostics API used by the
// settings screen. Announce flows use RefreshNATDiagnostics instead.
func (m *SessionManager) TriggerNatTraversal() bool {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = m.RefreshNATDiagnostics(ctx)
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
	m.ResetCooldowns()
	m.mu.RLock()
	svc := m.discoverySvc
	m.mu.RUnlock()

	if svc != nil {
		return svc.RefreshAnnouncement()
	}
	return nil
}

// atomicWriteFile safely writes data to a temporary file before atomic rename to prevent corruption.
func atomicWriteFile(path string, data []byte, perm os.FileMode) error {
	tmpPath := fmt.Sprintf("%s.tmp.%d", path, time.Now().UnixNano())
	if err := os.WriteFile(tmpPath, data, perm); err != nil {
		return err
	}
	return os.Rename(tmpPath, path)
}
