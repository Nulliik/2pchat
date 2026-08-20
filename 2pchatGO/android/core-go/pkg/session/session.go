package session

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

const (
	DefaultAckTimeout       = 3 * time.Second
	TorAckTimeout           = 8 * time.Second
	DefaultMaxRetries       = 3
	DefaultHandshakeTimeout = 10 * time.Second
	MessageQueueCapacity    = 256
	MaxReceivedIDsHistory   = 4096
)

var (
	ErrSessionClosed   = errors.New("session is closed")
	ErrHandshakeFailed = errors.New("X3DH handshake failed")
	ErrAckTimeout      = errors.New("ACK for message not received")
)

// Session represents an active, authenticated, and encrypted P2P connection over net.Conn.
type Session struct {
	conn            net.Conn
	initiator       bool
	localIdentity   *crypto.IdentityKeyPair
	localPrekeyPriv *crypto.X25519PrivateKey
	localPrekeyPub  *crypto.X25519PublicKey

	peerFingerprint string
	peerIdentityPub *crypto.X25519PublicKey
	peerVerifyPub   ed25519.PublicKey

	drState      *crypto.SessionState
	messageQueue chan map[string]any

	pendingAcksMu sync.Mutex
	pendingAcks   map[string]chan bool

	receivedIDsMu sync.Mutex
	receivedIDs   map[string]bool
	receivedOrder []string

	sendMu    sync.Mutex
	closeOnce sync.Once
	closeChan chan struct{}
	online    int32
	counter   uint64

	ackTimeout time.Duration
	maxRetries int
}

// HandshakeJSON matches the 2PChat protocol V3 handshake wire frame.
type HandshakeJSON struct {
	Type            string `json:"type"`
	Version         int    `json:"version"`
	Role            string `json:"role"`
	IdentityPub     string `json:"identityPub"`
	VerifyPub       string `json:"verifyPub"`
	SignedPrekeyPub string `json:"signedPrekeyPub"`
	PrekeySignature string `json:"prekeySignature"`
	Signature       string `json:"signature"`
	EphemeralPub    string `json:"ephPub,omitempty"`
}

// NewSession creates and initializes an encrypted Session over an existing net.Conn.
func NewSession(
	conn net.Conn,
	initiator bool,
	localId *crypto.IdentityKeyPair,
	prekeyPriv *crypto.X25519PrivateKey,
	prekeyPub *crypto.X25519PublicKey,
	expectedFingerprint string,
	timeout time.Duration,
) (*Session, error) {
	if timeout <= 0 {
		timeout = DefaultHandshakeTimeout
	}

	initAckTimeout := DefaultAckTimeout
	if conn != nil && conn.RemoteAddr() != nil {
		remote := strings.ToLower(conn.RemoteAddr().String())
		if strings.Contains(remote, ".onion") {
			initAckTimeout = TorAckTimeout
		}
	}

	s := &Session{
		conn:            conn,
		initiator:       initiator,
		localIdentity:   localId,
		localPrekeyPriv: prekeyPriv,
		localPrekeyPub:  prekeyPub,
		closeChan:       make(chan struct{}),
		messageQueue:    make(chan map[string]any, MessageQueueCapacity),
		pendingAcks:     make(map[string]chan bool),
		receivedIDs:     make(map[string]bool),
		receivedOrder:   make([]string, 0, MaxReceivedIDsHistory),
		ackTimeout:      initAckTimeout,
		maxRetries:      DefaultMaxRetries,
	}
	atomic.StoreInt32(&s.online, 1)

	// Set deadline for handshake
	_ = conn.SetDeadline(time.Now().Add(timeout))

	if err := s.performHandshake(expectedFingerprint); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("%w: %v", ErrHandshakeFailed, err)
	}

	// Reset deadline for normal bidirectional streaming
	_ = conn.SetDeadline(time.Time{})

	go s.readerLoop()
	return s, nil
}

func (s *Session) performHandshake(expectedFingerprint string) error {
	if s.initiator {
		return s.performInitiatorHandshake(expectedFingerprint)
	}
	return s.performResponderHandshake(expectedFingerprint)
}

func (s *Session) performInitiatorHandshake(expectedFingerprint string) error {
	eph, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		return err
	}

	prekeySig := crypto.SignPreKey(s.localIdentity.Signing, s.localPrekeyPub)

	toSign := append([]byte(crypto.X3DHHandshakeContext), []byte("init")...)
	toSign = append(toSign, s.localIdentity.Public.Bytes()...)
	toSign = append(toSign, s.localIdentity.Verify...)
	toSign = append(toSign, s.localPrekeyPub.Bytes()...)
	toSign = append(toSign, eph.Public.Bytes()...)
	sessionSig := ed25519.Sign(s.localIdentity.Signing, toSign)

	initPayload := &HandshakeJSON{
		Type:            "handshake",
		Version:         crypto.HandshakeVersion,
		Role:            "init",
		IdentityPub:     base64.StdEncoding.EncodeToString(s.localIdentity.Public.Bytes()),
		VerifyPub:       base64.StdEncoding.EncodeToString(s.localIdentity.Verify),
		SignedPrekeyPub: base64.StdEncoding.EncodeToString(s.localPrekeyPub.Bytes()),
		PrekeySignature: base64.StdEncoding.EncodeToString(prekeySig),
		Signature:       base64.StdEncoding.EncodeToString(sessionSig),
		EphemeralPub:    base64.StdEncoding.EncodeToString(eph.Public.Bytes()),
	}

	rawInit, err := json.Marshal(initPayload)
	if err != nil {
		return err
	}

	if err := transport.WriteFrame(s.conn, rawInit); err != nil {
		return err
	}

	rawReply, err := transport.ReadFrame(s.conn, transport.MaxHandshakeSize)
	if err != nil {
		return err
	}

	var replyPayload HandshakeJSON
	if err := json.Unmarshal(rawReply, &replyPayload); err != nil {
		return err
	}

	if replyPayload.Role != "reply" || replyPayload.Version != crypto.HandshakeVersion {
		return errors.New("invalid reply handshake packet")
	}

	remoteIdBytes, _ := base64.StdEncoding.DecodeString(replyPayload.IdentityPub)
	remoteVerifyBytes, _ := base64.StdEncoding.DecodeString(replyPayload.VerifyPub)
	remotePrekeyBytes, _ := base64.StdEncoding.DecodeString(replyPayload.SignedPrekeyPub)
	remotePrekeySig, _ := base64.StdEncoding.DecodeString(replyPayload.PrekeySignature)
	remoteSessionSig, _ := base64.StdEncoding.DecodeString(replyPayload.Signature)

	remoteIdPub, err := crypto.X25519PublicKeyFromBytes(remoteIdBytes)
	if err != nil {
		return err
	}
	remotePrekeyPub, err := crypto.X25519PublicKeyFromBytes(remotePrekeyBytes)
	if err != nil {
		return err
	}

	// Verify signatures
	toVerifyPrekey := append([]byte(crypto.SignedPrekeyContext), remotePrekeyBytes...)
	if !ed25519.Verify(remoteVerifyBytes, toVerifyPrekey, remotePrekeySig) {
		return errors.New("invalid remote prekey signature")
	}

	toVerifySession := append([]byte(crypto.X3DHHandshakeContext), []byte("reply")...)
	toVerifySession = append(toVerifySession, remoteIdBytes...)
	toVerifySession = append(toVerifySession, remoteVerifyBytes...)
	toVerifySession = append(toVerifySession, remotePrekeyBytes...)
	if !ed25519.Verify(remoteVerifyBytes, toVerifySession, remoteSessionSig) {
		return errors.New("invalid remote session signature")
	}

	s.peerFingerprint = crypto.Fingerprint(remoteIdBytes)
	if expectedFingerprint != "" && s.peerFingerprint != expectedFingerprint {
		return fmt.Errorf("peer fingerprint mismatch: expected %s, got %s", expectedFingerprint, s.peerFingerprint)
	}

	s.peerIdentityPub = remoteIdPub
	s.peerVerifyPub = remoteVerifyBytes

	bundle := &crypto.PreKeyBundle{
		IdentityPub:       remoteIdPub,
		IdentityVerifyPub: remoteVerifyBytes,
		SignedPrekeyPub:   remotePrekeyPub,
		SignedPrekeySig:   remotePrekeySig,
	}

	drState, err := crypto.InitializeSessionFromPreKey(s.localIdentity, bundle, eph)
	if err != nil {
		return err
	}
	s.drState = drState
	return nil
}

func (s *Session) performResponderHandshake(expectedFingerprint string) error {
	rawInit, err := transport.ReadFrame(s.conn, transport.MaxHandshakeSize)
	if err != nil {
		return err
	}

	var initPayload HandshakeJSON
	if err := json.Unmarshal(rawInit, &initPayload); err != nil {
		return err
	}

	if initPayload.Role != "init" || initPayload.Version != crypto.HandshakeVersion {
		return errors.New("responder expected init handshake")
	}

	remoteIdBytes, _ := base64.StdEncoding.DecodeString(initPayload.IdentityPub)
	remoteVerifyBytes, _ := base64.StdEncoding.DecodeString(initPayload.VerifyPub)
	remotePrekeyBytes, _ := base64.StdEncoding.DecodeString(initPayload.SignedPrekeyPub)
	remotePrekeySig, _ := base64.StdEncoding.DecodeString(initPayload.PrekeySignature)
	remoteSessionSig, _ := base64.StdEncoding.DecodeString(initPayload.Signature)
	remoteEphBytes, _ := base64.StdEncoding.DecodeString(initPayload.EphemeralPub)

	// Verify signatures
	toVerifyPrekey := append([]byte(crypto.SignedPrekeyContext), remotePrekeyBytes...)
	if !ed25519.Verify(remoteVerifyBytes, toVerifyPrekey, remotePrekeySig) {
		return errors.New("invalid remote prekey signature")
	}

	toVerifySession := append([]byte(crypto.X3DHHandshakeContext), []byte("init")...)
	toVerifySession = append(toVerifySession, remoteIdBytes...)
	toVerifySession = append(toVerifySession, remoteVerifyBytes...)
	toVerifySession = append(toVerifySession, remotePrekeyBytes...)
	toVerifySession = append(toVerifySession, remoteEphBytes...)
	if !ed25519.Verify(remoteVerifyBytes, toVerifySession, remoteSessionSig) {
		return errors.New("invalid remote session signature")
	}

	// Send reply
	prekeySig := crypto.SignPreKey(s.localIdentity.Signing, s.localPrekeyPub)

	toSign := append([]byte(crypto.X3DHHandshakeContext), []byte("reply")...)
	toSign = append(toSign, s.localIdentity.Public.Bytes()...)
	toSign = append(toSign, s.localIdentity.Verify...)
	toSign = append(toSign, s.localPrekeyPub.Bytes()...)
	sessionSig := ed25519.Sign(s.localIdentity.Signing, toSign)

	replyPayload := &HandshakeJSON{
		Type:            "handshake",
		Version:         crypto.HandshakeVersion,
		Role:            "reply",
		IdentityPub:     base64.StdEncoding.EncodeToString(s.localIdentity.Public.Bytes()),
		VerifyPub:       base64.StdEncoding.EncodeToString(s.localIdentity.Verify),
		SignedPrekeyPub: base64.StdEncoding.EncodeToString(s.localPrekeyPub.Bytes()),
		PrekeySignature: base64.StdEncoding.EncodeToString(prekeySig),
		Signature:       base64.StdEncoding.EncodeToString(sessionSig),
	}

	rawReply, err := json.Marshal(replyPayload)
	if err != nil {
		return err
	}

	if err := transport.WriteFrame(s.conn, rawReply); err != nil {
		return err
	}

	remoteIdPub, err := crypto.X25519PublicKeyFromBytes(remoteIdBytes)
	if err != nil {
		return err
	}
	remoteEphPub, err := crypto.X25519PublicKeyFromBytes(remoteEphBytes)
	if err != nil {
		return err
	}

	s.peerFingerprint = crypto.Fingerprint(remoteIdBytes)
	if expectedFingerprint != "" && s.peerFingerprint != expectedFingerprint {
		return fmt.Errorf("peer fingerprint mismatch: expected %s, got %s", expectedFingerprint, s.peerFingerprint)
	}

	s.peerIdentityPub = remoteIdPub
	s.peerVerifyPub = remoteVerifyBytes

	drState, err := crypto.RespondToPreKeyInit(
		s.localIdentity,
		s.localPrekeyPriv,
		nil,
		remoteIdPub,
		remoteEphPub,
	)
	if err != nil {
		return err
	}
	s.drState = drState
	return nil
}

func (s *Session) readerLoop() {
	var closeReason string
	defer func() {
		s.emitOffline(closeReason)
		_ = s.Close()
	}()

	for {
		rawFrame, err := transport.ReadFrame(s.conn, transport.MaxFrameSize)
		if err != nil {
			closeReason = err.Error()
			return
		}

		plaintext, err := s.drState.DecryptMessage(rawFrame)
		if err != nil {
			continue
		}

		msgMap, err := DecodeMessage(plaintext)
		if err != nil {
			continue
		}

		msgType, _ := msgMap["type"].(string)

		if msgType == string(TypeAck) {
			if ackID, ok := msgMap["ack_id"].(string); ok {
				s.pendingAcksMu.Lock()
				if ch, exists := s.pendingAcks[ackID]; exists {
					select {
					case ch <- true:
					default:
					}
				}
				s.pendingAcksMu.Unlock()
			}
			continue
		}

		// Send ACK for messages carrying an ID
		if msgID, ok := msgMap["id"].(string); ok && msgID != "" {
			_ = s.sendAck(msgID)

			s.receivedIDsMu.Lock()
			if s.receivedIDs[msgID] {
				s.receivedIDsMu.Unlock()
				continue // deduplicate
			}
			s.receivedIDs[msgID] = true
			s.receivedOrder = append(s.receivedOrder, msgID)
			if len(s.receivedOrder) > MaxReceivedIDsHistory {
				oldest := s.receivedOrder[0]
				s.receivedOrder = s.receivedOrder[1:]
				delete(s.receivedIDs, oldest)
			}
			s.receivedIDsMu.Unlock()
		}

		select {
		case s.messageQueue <- msgMap:
		default:
		}
	}
}

func (s *Session) sendAck(msgID string) error {
	ack := NewAckMessage(msgID)
	data, err := EncodeMessage(ack)
	if err != nil {
		return err
	}
	return s.sendEncryptedFrame(data)
}

func (s *Session) sendEncryptedFrame(plaintext []byte) error {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()

	if atomic.LoadInt32(&s.online) == 0 {
		return ErrSessionClosed
	}

	ciphertext, err := s.drState.EncryptMessage(plaintext)
	if err != nil {
		return err
	}

	return transport.WriteFrame(s.conn, ciphertext)
}

// SendReliable sends a wire message payload and waits for the peer's ACK with retry backoff.
func (s *Session) SendReliable(msg map[string]any) (string, error) {
	msgID, _ := msg["id"].(string)
	if msgID == "" {
		c := atomic.AddUint64(&s.counter, 1)
		msgID = fmt.Sprintf("%d-%d", time.Now().UnixNano(), c)
		msg["id"] = msgID
	}

	ackChan := make(chan bool, 1)
	s.pendingAcksMu.Lock()
	s.pendingAcks[msgID] = ackChan
	s.pendingAcksMu.Unlock()

	defer func() {
		s.pendingAcksMu.Lock()
		delete(s.pendingAcks, msgID)
		s.pendingAcksMu.Unlock()
	}()

	raw, err := EncodeMessage(msg)
	if err != nil {
		return "", err
	}

	delay := s.AckTimeout()
	if delay <= 0 {
		delay = DefaultAckTimeout
	}
	for attempt := 0; attempt <= s.maxRetries; attempt++ {
		if err := s.sendEncryptedFrame(raw); err != nil {
			return "", err
		}

		select {
		case <-s.closeChan:
			return "", ErrSessionClosed
		case <-ackChan:
			return msgID, nil
		case <-time.After(delay):
			if attempt < s.maxRetries {
				delay = time.Duration(float64(delay) * 1.5)
			}
		}
	}

	return "", ErrAckTimeout
}

// SendChat sends a chat message reliably over the Double Ratchet channel.
func (s *Session) SendChat(body, nickname string) (string, error) {
	c := atomic.AddUint64(&s.counter, 1)
	msgID := fmt.Sprintf("%d-%d", time.Now().UnixNano(), c)
	msg := NewChatMessage(msgID, body, nickname)

	var msgMap map[string]any
	raw, _ := json.Marshal(msg)
	_ = json.Unmarshal(raw, &msgMap)

	return s.SendReliable(msgMap)
}

func (s *Session) emitOffline(reason string) {
	if atomic.CompareAndSwapInt32(&s.online, 1, 0) {
		status := StatusMessage{
			Type:      TypeStatus,
			State:     "offline",
			Timestamp: time.Now().Unix(),
			Reason:    reason,
		}
		var msgMap map[string]any
		raw, _ := json.Marshal(status)
		_ = json.Unmarshal(raw, &msgMap)

		select {
		case s.messageQueue <- msgMap:
		default:
		}
	}
}

// Messages returns the read-only channel of incoming decrypted messages.
func (s *Session) Messages() <-chan map[string]any {
	return s.messageQueue
}

// PeerFingerprint returns the remote peer's verified fingerprint.
func (s *Session) PeerFingerprint() string {
	return s.peerFingerprint
}

// IsOnline returns true if the session is currently connected.
func (s *Session) IsOnline() bool {
	return atomic.LoadInt32(&s.online) == 1
}

// Close gracefully closes the session and underlying TCP connection.
func (s *Session) Close() error {
	var err error
	s.closeOnce.Do(func() {
		atomic.StoreInt32(&s.online, 0)
		if s.closeChan != nil {
			close(s.closeChan)
		}
		if s.drState != nil {
			s.drState.Zeroize()
		}
		if s.conn != nil {
			err = s.conn.Close()
		}
	})
	return err
}

// SetAckTimeout updates the ACK timeout duration for reliable message transmission.
func (s *Session) SetAckTimeout(d time.Duration) {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	if d > 0 {
		s.ackTimeout = d
	}
}

// AckTimeout returns the configured ACK timeout duration.
func (s *Session) AckTimeout() time.Duration {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	return s.ackTimeout
}

// SetTorTransport configures the session to use Tor-optimized timeouts.
func (s *Session) SetTorTransport(isTor bool) {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	if isTor {
		s.ackTimeout = TorAckTimeout
	} else {
		s.ackTimeout = DefaultAckTimeout
	}
}
