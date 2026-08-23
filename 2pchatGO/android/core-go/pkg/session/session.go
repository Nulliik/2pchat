package session

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/binary"
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
	DefaultAckTimeout       = 5 * time.Second
	TorAckTimeout           = 12 * time.Second
	DefaultMaxRetries       = 2
	DefaultHandshakeTimeout = 30 * time.Second
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
		return fmt.Errorf("failed to generate ephemeral keypair: %w", err)
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
		return fmt.Errorf("failed to marshal init handshake: %w", err)
	}

	if err := transport.WriteFrame(s.conn, rawInit); err != nil {
		return fmt.Errorf("failed to write init handshake frame: %w", err)
	}

	rawReply, err := transport.ReadFrame(s.conn, transport.MaxHandshakeSize)
	if err != nil {
		return fmt.Errorf("failed to read reply handshake frame: %w", err)
	}

	var replyPayload HandshakeJSON
	if err := json.Unmarshal(rawReply, &replyPayload); err != nil {
		return fmt.Errorf("failed to unmarshal reply handshake JSON: %w", err)
	}

	if replyPayload.Role != "reply" || replyPayload.Version != crypto.HandshakeVersion {
		return fmt.Errorf("invalid reply handshake packet: role=%s, version=%d", replyPayload.Role, replyPayload.Version)
	}

	remoteIdBytes, err := base64.StdEncoding.DecodeString(replyPayload.IdentityPub)
	if err != nil {
		return fmt.Errorf("failed to decode remote IdentityPub: %w", err)
	}
	remoteVerifyBytes, err := base64.StdEncoding.DecodeString(replyPayload.VerifyPub)
	if err != nil {
		return fmt.Errorf("failed to decode remote VerifyPub: %w", err)
	}
	remotePrekeyBytes, err := base64.StdEncoding.DecodeString(replyPayload.SignedPrekeyPub)
	if err != nil {
		return fmt.Errorf("failed to decode remote SignedPrekeyPub: %w", err)
	}
	remotePrekeySig, err := base64.StdEncoding.DecodeString(replyPayload.PrekeySignature)
	if err != nil {
		return fmt.Errorf("failed to decode remote PrekeySignature: %w", err)
	}
	remoteSessionSig, err := base64.StdEncoding.DecodeString(replyPayload.Signature)
	if err != nil {
		return fmt.Errorf("failed to decode remote Signature: %w", err)
	}

	remoteIdPub, err := crypto.X25519PublicKeyFromBytes(remoteIdBytes)
	if err != nil {
		return fmt.Errorf("invalid remote identity key: %w", err)
	}
	remotePrekeyPub, err := crypto.X25519PublicKeyFromBytes(remotePrekeyBytes)
	if err != nil {
		return fmt.Errorf("invalid remote signed prekey: %w", err)
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
		return fmt.Errorf("InitializeSessionFromPreKey failed: %w", err)
	}
	s.drState = drState
	return nil
}

func (s *Session) performResponderHandshake(expectedFingerprint string) error {
	rawInit, err := transport.ReadFrame(s.conn, transport.MaxHandshakeSize)
	if err != nil {
		return fmt.Errorf("failed to read init handshake frame: %w", err)
	}

	var initPayload HandshakeJSON
	if err := json.Unmarshal(rawInit, &initPayload); err != nil {
		return fmt.Errorf("failed to unmarshal init handshake JSON: %w", err)
	}

	if initPayload.Role != "init" || initPayload.Version != crypto.HandshakeVersion {
		return fmt.Errorf("responder expected init handshake, got role=%s, version=%d", initPayload.Role, initPayload.Version)
	}

	remoteIdBytes, err := base64.StdEncoding.DecodeString(initPayload.IdentityPub)
	if err != nil {
		return fmt.Errorf("failed to decode initiator IdentityPub: %w", err)
	}
	remoteVerifyBytes, err := base64.StdEncoding.DecodeString(initPayload.VerifyPub)
	if err != nil {
		return fmt.Errorf("failed to decode initiator VerifyPub: %w", err)
	}
	remotePrekeyBytes, err := base64.StdEncoding.DecodeString(initPayload.SignedPrekeyPub)
	if err != nil {
		return fmt.Errorf("failed to decode initiator SignedPrekeyPub: %w", err)
	}
	remotePrekeySig, err := base64.StdEncoding.DecodeString(initPayload.PrekeySignature)
	if err != nil {
		return fmt.Errorf("failed to decode initiator PrekeySignature: %w", err)
	}
	remoteSessionSig, err := base64.StdEncoding.DecodeString(initPayload.Signature)
	if err != nil {
		return fmt.Errorf("failed to decode initiator Signature: %w", err)
	}
	remoteEphBytes, err := base64.StdEncoding.DecodeString(initPayload.EphemeralPub)
	if err != nil {
		return fmt.Errorf("failed to decode initiator EphemeralPub: %w", err)
	}

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
		return fmt.Errorf("failed to marshal reply handshake: %w", err)
	}

	if err := transport.WriteFrame(s.conn, rawReply); err != nil {
		return fmt.Errorf("failed to write reply handshake frame: %w", err)
	}

	remoteIdPub, err := crypto.X25519PublicKeyFromBytes(remoteIdBytes)
	if err != nil {
		return fmt.Errorf("invalid remote identity public key: %w", err)
	}
	remoteEphPub, err := crypto.X25519PublicKeyFromBytes(remoteEphBytes)
	if err != nil {
		return fmt.Errorf("invalid remote ephemeral public key: %w", err)
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
		return fmt.Errorf("RespondToPreKeyInit failed: %w", err)
	}
	s.drState = drState
	return nil
}

func (s *Session) readerLoop() {
	var closeReason string
	defer func() {
		s.emitOffline(closeReason)
		_ = s.Close()
		close(s.messageQueue)
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

		// File chunks have their own stable ID derived from file_id/index. Parse
		// them before the legacy Go 0x02 reliable-binary wrapper.
		if len(plaintext) > 0 && (plaintext[0] == transport.FileChunkFrameTypeV2 || plaintext[0] == transport.LegacyPythonFileChunkFrameTypeV1) {
			fileChunk, decodeErr := transport.DecodeFileChunkFrame(plaintext)
			if decodeErr == nil {
				msgID, idErr := transport.FileChunkAckID(fileChunk.FileID, fileChunk.ChunkIndex)
				if idErr != nil {
					continue
				}
				_ = s.sendAck(msgID)

				s.receivedIDsMu.Lock()
				duplicate := s.receivedIDs[msgID]
				if !duplicate {
					s.receivedIDs[msgID] = true
					s.receivedOrder = append(s.receivedOrder, msgID)
					if len(s.receivedOrder) > MaxReceivedIDsHistory {
						oldest := s.receivedOrder[0]
						s.receivedOrder = s.receivedOrder[1:]
						delete(s.receivedIDs, oldest)
					}
				}
				s.receivedIDsMu.Unlock()
				if duplicate {
					continue
				}

				format := transport.FileChunkFormatV2
				if fileChunk.VersionType == transport.LegacyPythonFileChunkFrameTypeV1 {
					format = transport.LegacyFileChunkFormatV1
				}
				msgMap := map[string]any{
					"type":         string(TypeFileChunk),
					"id":           msgID,
					"file_id":      base64.StdEncoding.EncodeToString(fileChunk.FileID),
					"chunk_index":  int(fileChunk.ChunkIndex),
					"chunk_format": format,
					"payload":      fileChunk.Payload,
				}
				select {
				case s.messageQueue <- msgMap:
				default:
				}
				continue
			}
		}

		if len(plaintext) > 0 && plaintext[0] == 0x02 {
			if len(plaintext) >= 3 {
				idLen := int(binary.BigEndian.Uint16(plaintext[1:3]))
				if len(plaintext) >= 3+idLen {
					msgID := string(plaintext[3 : 3+idLen])
					payload := plaintext[3+idLen:]

					_ = s.sendAck(msgID)

					s.receivedIDsMu.Lock()
					if s.receivedIDs[msgID] {
						s.receivedIDsMu.Unlock()
						continue
					}
					s.receivedIDs[msgID] = true
					s.receivedOrder = append(s.receivedOrder, msgID)
					if len(s.receivedOrder) > MaxReceivedIDsHistory {
						oldest := s.receivedOrder[0]
						s.receivedOrder = s.receivedOrder[1:]
						delete(s.receivedIDs, oldest)
					}
					s.receivedIDsMu.Unlock()

					msgMap := map[string]any{
						"type":    "binary",
						"id":      msgID,
						"payload": payload,
					}
					select {
					case s.messageQueue <- msgMap:
					default:
					}
					continue
				}
			}
		}

		msgMap, err := DecodeMessage(plaintext)
		if err != nil {
			continue
		}

		msgType, _ := msgMap["type"].(string)

		if msgType == string(TypeAck) {
			ackID, _ := msgMap["ack_id"].(string)
			if ackID == "" {
				ackID, _ = msgMap["id"].(string)
			}
			if ackID != "" {
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
		msgID, ok := msgMap["id"].(string)
		if !ok || msgID == "" {
			msgID, _ = msgMap["message_id"].(string)
		}
		if msgID != "" {
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

	raw, err := EncodeMessage(msg)
	if err != nil {
		return "", err
	}
	return s.sendReliablePlaintext(msgID, raw)
}

func (s *Session) sendReliablePlaintext(msgID string, raw []byte) (string, error) {
	ackChan := make(chan bool, 1)
	s.pendingAcksMu.Lock()
	s.pendingAcks[msgID] = ackChan
	s.pendingAcksMu.Unlock()

	defer func() {
		s.pendingAcksMu.Lock()
		delete(s.pendingAcks, msgID)
		s.pendingAcksMu.Unlock()
	}()

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

// SendReliableFileChunk sends the shared binary-v2 file envelope and waits for
// the ACK ID derived from its file ID and chunk index.
func (s *Session) SendReliableFileChunk(fileID []byte, chunkIndex uint32, payload []byte) (string, error) {
	msgID, err := transport.FileChunkAckID(fileID, chunkIndex)
	if err != nil {
		return "", err
	}
	frame, err := transport.EncodeFileChunkFrame(fileID, chunkIndex, payload)
	if err != nil {
		return "", err
	}
	return s.sendReliablePlaintext(msgID, frame)
}

// SendReliableBinary sends an arbitrary binary wire message payload and waits for the peer's ACK.
func (s *Session) SendReliableBinary(payload []byte) (string, error) {
	msgID := fmt.Sprintf("%d-%d", time.Now().UnixNano(), atomic.AddUint64(&s.counter, 1))

	// Construct binary packet: [0x02 Magic] [2 bytes ID Len] [ID Bytes] [Payload Bytes]
	idBytes := []byte(msgID)
	frame := make([]byte, 1+2+len(idBytes)+len(payload))
	frame[0] = 0x02
	binary.BigEndian.PutUint16(frame[1:3], uint16(len(idBytes)))
	copy(frame[3:3+len(idBytes)], idBytes)
	copy(frame[3+len(idBytes):], payload)

	ackChan := make(chan bool, 1)
	s.pendingAcksMu.Lock()
	s.pendingAcks[msgID] = ackChan
	s.pendingAcksMu.Unlock()

	defer func() {
		s.pendingAcksMu.Lock()
		delete(s.pendingAcks, msgID)
		s.pendingAcksMu.Unlock()
	}()

	delay := s.AckTimeout()
	if delay <= 0 {
		delay = DefaultAckTimeout
	}
	for attempt := 0; attempt <= s.maxRetries; attempt++ {
		if err := s.sendEncryptedFrame(frame); err != nil {
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
	trimmed := strings.TrimSpace(body)
	if strings.HasPrefix(trimmed, "{") && strings.HasSuffix(trimmed, "}") {
		var rawMap map[string]any
		if err := json.Unmarshal([]byte(trimmed), &rawMap); err == nil && rawMap["type"] != nil {
			return s.SendReliable(rawMap)
		}
	}

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
