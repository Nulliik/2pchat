package crypto

import (
	"bytes"
	"crypto/ed25519"
	"crypto/hmac"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
)

const (
	// Keep this encrypted-packet version in lockstep with Python's double_ratchet.py.
	PacketVersion        = 4
	// The X3DH JSON handshake is independently versioned by Python's session.py.
	HandshakeVersion     = 3
	HeaderFlagObfuscated = 0x01
	PlainHeaderLen       = 32 + 4 // 32-byte DH pub + 4-byte uint32 message index
	ObfuscatedHeaderLen  = SecretBoxNonceSize + PlainHeaderLen + SecretBoxOverhead
	SignedPrekeyContext  = "p2p-chat-signed-prekey-v1"
	PacketAuthContext    = "p2p-chat-packet-auth-v4"
	PacketTagLen         = 32
	X3DHHandshakeContext = "p2p-chat-x3dh-handshake-v1"
	DefaultMaxSkip       = 2000
)

// PreKeyBundle represents a published pre-key bundle for X3DH.
type PreKeyBundle struct {
	IdentityPub       *X25519PublicKey
	IdentityVerifyPub ed25519.PublicKey
	SignedPrekeyPub   *X25519PublicKey
	SignedPrekeySig   []byte
	OneTimePrekeyPub  *X25519PublicKey
}

// SignPreKey signs a signed prekey using the companion Ed25519 signing key.
func SignPreKey(signingKey ed25519.PrivateKey, prekeyPub *X25519PublicKey) []byte {
	toSign := append([]byte(SignedPrekeyContext), prekeyPub[:]...)
	return ed25519.Sign(signingKey, toSign)
}

// VerifySignature verifies that the signed pre-key signature is valid.
func (b *PreKeyBundle) VerifySignature() error {
	toVerify := append([]byte(SignedPrekeyContext), b.SignedPrekeyPub[:]...)
	if !ed25519.Verify(b.IdentityVerifyPub, toVerify, b.SignedPrekeySig) {
		return errors.New("invalid signed pre-key signature")
	}
	return nil
}

// SkippedKeyIdentifier identifies a skipped message key.
type SkippedKeyIdentifier struct {
	DHPub [KeySize]byte
	Index uint32
}

// SessionState holds the Double Ratchet state for an active connection.
type SessionState struct {
	RootKey            []byte
	SendChainKey       []byte
	RecvChainKey       []byte
	HeaderKey          []byte
	DHSendKey          *X25519PrivateKey
	DHRecvKeyPub       *X25519PublicKey
	IdentityLocal      *IdentityKeyPair
	IdentityRemote     *X25519PublicKey
	SendIdx            uint32
	RecvIdx            uint32
	PreviousRecvIdx    uint32
	SkippedMessageKeys map[SkippedKeyIdentifier][]byte
	MaxSkip            int
	ObfuscateHeader    bool
	PendingSendRatchet bool

	mu sync.Mutex
}

// clone creates a deep copy of SessionState for speculative decryption.
func (s *SessionState) clone() *SessionState {
	c := &SessionState{
		RootKey:            append([]byte(nil), s.RootKey...),
		SendChainKey:       append([]byte(nil), s.SendChainKey...),
		RecvChainKey:       append([]byte(nil), s.RecvChainKey...),
		HeaderKey:          append([]byte(nil), s.HeaderKey...),
		SendIdx:            s.SendIdx,
		RecvIdx:            s.RecvIdx,
		PreviousRecvIdx:    s.PreviousRecvIdx,
		MaxSkip:            s.MaxSkip,
		ObfuscateHeader:    s.ObfuscateHeader,
		PendingSendRatchet: s.PendingSendRatchet,
		SkippedMessageKeys: make(map[SkippedKeyIdentifier][]byte, len(s.SkippedMessageKeys)),
	}
	if s.DHSendKey != nil {
		var k X25519PrivateKey
		copy(k[:], s.DHSendKey[:])
		c.DHSendKey = &k
	}
	if s.DHRecvKeyPub != nil {
		var k X25519PublicKey
		copy(k[:], s.DHRecvKeyPub[:])
		c.DHRecvKeyPub = &k
	}
	if s.IdentityRemote != nil {
		var k X25519PublicKey
		copy(k[:], s.IdentityRemote[:])
		c.IdentityRemote = &k
	}
	c.IdentityLocal = s.IdentityLocal
	for k, v := range s.SkippedMessageKeys {
		c.SkippedMessageKeys[k] = append([]byte(nil), v...)
	}
	return c
}

// restore copies the state back from a successful candidate snapshot.
func (s *SessionState) restore(c *SessionState) {
	s.RootKey = append([]byte(nil), c.RootKey...)
	s.SendChainKey = append([]byte(nil), c.SendChainKey...)
	s.RecvChainKey = append([]byte(nil), c.RecvChainKey...)
	s.HeaderKey = append([]byte(nil), c.HeaderKey...)
	s.DHSendKey = c.DHSendKey
	s.DHRecvKeyPub = c.DHRecvKeyPub
	s.SendIdx = c.SendIdx
	s.RecvIdx = c.RecvIdx
	s.PreviousRecvIdx = c.PreviousRecvIdx
	s.PendingSendRatchet = c.PendingSendRatchet
	s.SkippedMessageKeys = c.SkippedMessageKeys
}

// Zeroize wipes all secret keys and chain keys in the SessionState from memory.
func (s *SessionState) Zeroize() {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	Zeroize(s.RootKey)
	Zeroize(s.SendChainKey)
	Zeroize(s.RecvChainKey)
	Zeroize(s.HeaderKey)
	if s.DHSendKey != nil {
		Zeroize(s.DHSendKey[:])
	}
	for k, v := range s.SkippedMessageKeys {
		Zeroize(v)
		delete(s.SkippedMessageKeys, k)
	}
}

func deriveFourKeys(material []byte) ([]byte, []byte, []byte, []byte, error) {
	derived, err := HKDFSHA256(material, nil, []byte("X3DH-INIT"), 128)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	k1 := append([]byte(nil), derived[:32]...)
	k2 := append([]byte(nil), derived[32:64]...)
	k3 := append([]byte(nil), derived[64:96]...)
	k4 := append([]byte(nil), derived[96:128]...)
	return k1, k2, k3, k4, nil
}

// InitializeSessionFromPreKey initializes the Double Ratchet as the initiator using remote pre-key bundle.
func InitializeSessionFromPreKey(
	localIdentity *IdentityKeyPair,
	remotePrekey *PreKeyBundle,
	localEphemeral *IdentityKeyPair,
) (*SessionState, error) {
	if err := remotePrekey.VerifySignature(); err != nil {
		return nil, fmt.Errorf("prekey verification failed: %w", err)
	}

	dh1, err := DH(localIdentity.Private, remotePrekey.SignedPrekeyPub)
	if err != nil {
		return nil, err
	}
	dh2, err := DH(localEphemeral.Private, remotePrekey.IdentityPub)
	if err != nil {
		return nil, err
	}
	dh3, err := DH(localEphemeral.Private, remotePrekey.SignedPrekeyPub)
	if err != nil {
		return nil, err
	}

	material := append(append(dh1, dh2...), dh3...)
	if remotePrekey.OneTimePrekeyPub != nil {
		dh4, err := DH(localEphemeral.Private, remotePrekey.OneTimePrekeyPub)
		if err != nil {
			return nil, err
		}
		material = append(material, dh4...)
	}

	rootKey, sendChainKey, recvChainKey, headerKey, err := deriveFourKeys(material)
	if err != nil {
		return nil, err
	}

	return &SessionState{
		RootKey:            rootKey,
		SendChainKey:       sendChainKey,
		RecvChainKey:       recvChainKey,
		HeaderKey:          headerKey,
		ObfuscateHeader:    true,
		DHSendKey:          localEphemeral.Private,
		DHRecvKeyPub:       remotePrekey.SignedPrekeyPub,
		IdentityLocal:      localIdentity,
		IdentityRemote:     remotePrekey.IdentityPub,
		MaxSkip:            DefaultMaxSkip,
		SkippedMessageKeys: make(map[SkippedKeyIdentifier][]byte),
	}, nil
}

// RespondToPreKeyInit creates the responder Double Ratchet session upon receiving an initiator handshake.
func RespondToPreKeyInit(
	localIdentity *IdentityKeyPair,
	signedPrekey *X25519PrivateKey,
	localOneTimePrekey *X25519PrivateKey,
	initiatorIdentityPub *X25519PublicKey,
	initiatorEphemeralPub *X25519PublicKey,
) (*SessionState, error) {
	dh1, err := DH(signedPrekey, initiatorIdentityPub)
	if err != nil {
		return nil, err
	}
	dh2, err := DH(localIdentity.Private, initiatorEphemeralPub)
	if err != nil {
		return nil, err
	}
	dh3, err := DH(signedPrekey, initiatorEphemeralPub)
	if err != nil {
		return nil, err
	}

	material := append(append(dh1, dh2...), dh3...)
	if localOneTimePrekey != nil {
		dh4, err := DH(localOneTimePrekey, initiatorEphemeralPub)
		if err != nil {
			return nil, err
		}
		material = append(material, dh4...)
	}

	rootKey, recvChainKey, sendChainKey, headerKey, err := deriveFourKeys(material)
	if err != nil {
		return nil, err
	}

	return &SessionState{
		RootKey:            rootKey,
		SendChainKey:       sendChainKey,
		RecvChainKey:       recvChainKey,
		HeaderKey:          headerKey,
		ObfuscateHeader:    true,
		DHSendKey:          signedPrekey,
		DHRecvKeyPub:       initiatorEphemeralPub,
		IdentityLocal:      localIdentity,
		IdentityRemote:     initiatorIdentityPub,
		PendingSendRatchet: true,
		MaxSkip:            DefaultMaxSkip,
		SkippedMessageKeys: make(map[SkippedKeyIdentifier][]byte),
	}, nil
}

// RatchetStep advances the DH ratchet when a new remote DH public key is observed.
func (s *SessionState) RatchetStep(newRemoteDHPub *X25519PublicKey) error {
	// Step 1: derive new root key + receive chain key
	dhOut, err := DH(s.DHSendKey, newRemoteDHPub)
	if err != nil {
		return err
	}
	input1 := append(append([]byte(nil), s.RootKey...), dhOut...)
	rkCk, err := HKDFSHA256(input1, nil, []byte("DH-RATCHET"), 64)
	if err != nil {
		return err
	}
	s.RootKey = append([]byte(nil), rkCk[:32]...)
	s.RecvChainKey = append([]byte(nil), rkCk[32:64]...)

	// Step 2: advance our sending key
	newPriv, _, err := GenerateX25519Keypair()
	if err != nil {
		return err
	}
	s.DHSendKey = newPriv
	dhOut2, err := DH(s.DHSendKey, newRemoteDHPub)
	if err != nil {
		return err
	}
	input2 := append(append([]byte(nil), s.RootKey...), dhOut2...)
	rkCk2, err := HKDFSHA256(input2, nil, []byte("DH-RATCHET"), 64)
	if err != nil {
		return err
	}
	s.RootKey = append([]byte(nil), rkCk2[:32]...)
	s.SendChainKey = append([]byte(nil), rkCk2[32:64]...)

	s.DHRecvKeyPub = newRemoteDHPub
	s.PreviousRecvIdx = s.RecvIdx
	s.RecvIdx = 0
	s.SendIdx = 0
	s.PendingSendRatchet = false
	return nil
}

// PrimeSendRatchet initializes the first sending chain once the peer's first DH key is known.
func (s *SessionState) PrimeSendRatchet() error {
	if !s.PendingSendRatchet {
		return nil
	}
	if s.DHRecvKeyPub == nil {
		return errors.New("remote ratchet key missing")
	}
	newPriv, _, err := GenerateX25519Keypair()
	if err != nil {
		return err
	}
	s.DHSendKey = newPriv
	dhOut, err := DH(s.DHSendKey, s.DHRecvKeyPub)
	if err != nil {
		return err
	}
	input := append(append([]byte(nil), s.RootKey...), dhOut...)
	rkCk, err := HKDFSHA256(input, nil, []byte("DH-RATCHET"), 64)
	if err != nil {
		return err
	}
	s.RootKey = append([]byte(nil), rkCk[:32]...)
	s.SendChainKey = append([]byte(nil), rkCk[32:64]...)
	s.SendIdx = 0
	s.PendingSendRatchet = false
	return nil
}

// DeriveMessageKey derives and rotates a symmetric message key for send or recv direction.
func (s *SessionState) DeriveMessageKey(direction string) ([]byte, error) {
	switch direction {
	case "send":
		msgKey := HMACSHA256(s.SendChainKey, []byte("MsgKey"))
		s.SendChainKey = HMACSHA256(s.SendChainKey, []byte("ChainKey"))
		s.SendIdx++
		return msgKey, nil
	case "recv":
		msgKey := HMACSHA256(s.RecvChainKey, []byte("MsgKey"))
		s.RecvChainKey = HMACSHA256(s.RecvChainKey, []byte("ChainKey"))
		s.RecvIdx++
		return msgKey, nil
	default:
		return nil, fmt.Errorf("invalid ratchet direction: %s", direction)
	}
}

func (s *SessionState) storeSkippedKey(dhPub *X25519PublicKey, index uint32, key []byte) error {
	if len(s.SkippedMessageKeys) >= s.MaxSkip {
		return errors.New("too many skipped message keys")
	}
	id := SkippedKeyIdentifier{DHPub: *dhPub, Index: index}
	s.SkippedMessageKeys[id] = append([]byte(nil), key...)
	return nil
}

func (s *SessionState) tryRetrieveSkippedKey(dhPub *X25519PublicKey, index uint32) []byte {
	id := SkippedKeyIdentifier{DHPub: *dhPub, Index: index}
	return s.SkippedMessageKeys[id]
}

func (s *SessionState) maybeSkipMessageKeys(until uint32, dhPub *X25519PublicKey) error {
	if until < s.RecvIdx {
		return nil
	}
	if int(until-s.RecvIdx) > s.MaxSkip {
		return errors.New("too many skipped messages")
	}
	for s.RecvIdx < until {
		key, err := s.DeriveMessageKey("recv")
		if err != nil {
			return err
		}
		if err := s.storeSkippedKey(dhPub, s.RecvIdx-1, key); err != nil {
			return err
		}
	}
	return nil
}

// EncryptMessage encrypts a plaintext payload using the current Double Ratchet sending chain.
// Wire format is 100% compatible with Python double_ratchet.py encrypt_message.
func (s *SessionState) EncryptMessage(plaintext []byte) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := s.PrimeSendRatchet(); err != nil {
		return nil, err
	}

	msgIndex := s.SendIdx
	msgKey, err := s.DeriveMessageKey("send")
	if err != nil {
		return nil, err
	}

	ciphertext, err := SecretBoxEncrypt(msgKey, plaintext)
	if err != nil {
		return nil, fmt.Errorf("SecretBox encryption failed: %w", err)
	}

	dhSendPub := s.DHSendKey.Public()
	headerPlain := make([]byte, PlainHeaderLen)
	copy(headerPlain[:32], dhSendPub[:])
	binary.BigEndian.PutUint32(headerPlain[32:36], msgIndex)

	var flags byte = 0
	var header []byte
	if s.ObfuscateHeader {
		flags |= HeaderFlagObfuscated
		var err error
		header, err = SecretBoxEncrypt(s.HeaderKey, headerPlain)
		if err != nil {
			return nil, err
		}
	} else {
		header = headerPlain
	}

	prefix := make([]byte, 0, 1+1+len(header)+len(ciphertext))
	prefix = append(prefix, PacketVersion, flags)
	prefix = append(prefix, header...)
	prefix = append(prefix, ciphertext...)

	authKey := HMACSHA256(msgKey, []byte(PacketAuthContext))
	tag := HMACSHA256(authKey, prefix)

	packet := append(prefix, tag...)
	return packet, nil
}

// DecryptMessage decrypts a packet and advances the Double Ratchet state.
func (s *SessionState) DecryptMessage(packet []byte) ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	candidate := s.clone()
	plaintext, err := candidate.decryptMessageInternal(packet)
	if err != nil {
		return nil, err
	}
	s.restore(candidate)
	return plaintext, nil
}

func (s *SessionState) decryptMessageInternal(packet []byte) ([]byte, error) {
	minLen := 1 + 1 + PlainHeaderLen + PacketTagLen
	if len(packet) < minLen {
		return nil, errors.New("packet too short")
	}

	version := packet[0]
	if version != PacketVersion {
		return nil, fmt.Errorf("unsupported packet version: %d", version)
	}

	flags := packet[1]
	offset := 2

	var headerPlain []byte
	var headerEnd int

	if flags&HeaderFlagObfuscated != 0 {
		headerEnd = offset + ObfuscatedHeaderLen
		if len(packet) < headerEnd {
			return nil, errors.New("packet too short for obfuscated header")
		}
		var err error
		headerPlain, err = SecretBoxDecrypt(s.HeaderKey, packet[offset:headerEnd])
		if err != nil {
			return nil, fmt.Errorf("header decryption failed: %w", err)
		}
	} else {
		headerEnd = offset + PlainHeaderLen
		headerPlain = packet[offset:headerEnd]
	}

	remoteDHPub, err := X25519PublicKeyFromBytes(headerPlain[:32])
	if err != nil {
		return nil, err
	}
	msgIndex := binary.BigEndian.Uint32(headerPlain[32:36])
	ciphertext := packet[headerEnd : len(packet)-PacketTagLen]
	suppliedTag := packet[len(packet)-PacketTagLen:]

	// Check if this message key was previously skipped
	if skippedKey := s.tryRetrieveSkippedKey(remoteDHPub, msgIndex); skippedKey != nil {
		authKey := HMACSHA256(skippedKey, []byte(PacketAuthContext))
		expectedTag := HMACSHA256(authKey, packet[:len(packet)-PacketTagLen])
		if !hmac.Equal(suppliedTag, expectedTag) {
			return nil, errors.New("packet authentication failed for skipped key")
		}
		plaintext, err := SecretBoxDecrypt(skippedKey, ciphertext)
		if err != nil {
			return nil, err
		}
		id := SkippedKeyIdentifier{DHPub: *remoteDHPub, Index: msgIndex}
		delete(s.SkippedMessageKeys, id)
		return plaintext, nil
	}

	// Advance DH ratchet if peer rotated their ratchet key
	if s.DHRecvKeyPub == nil || !bytes.Equal(remoteDHPub[:], s.DHRecvKeyPub[:]) {
		if err := s.RatchetStep(remoteDHPub); err != nil {
			return nil, err
		}
	}

	if msgIndex < s.RecvIdx {
		return nil, errors.New("duplicate or old message")
	}

	if err := s.maybeSkipMessageKeys(msgIndex, remoteDHPub); err != nil {
		return nil, err
	}

	msgKey, err := s.DeriveMessageKey("recv")
	if err != nil {
		return nil, err
	}

	authKey := HMACSHA256(msgKey, []byte(PacketAuthContext))
	expectedTag := HMACSHA256(authKey, packet[:len(packet)-PacketTagLen])
	if !hmac.Equal(suppliedTag, expectedTag) {
		return nil, errors.New("packet authentication failed")
	}

	return SecretBoxDecrypt(msgKey, ciphertext)
}

// HandshakeV3Payload models the JSON payload exchanged in X3DH Handshake Protocol V3.
type HandshakeV3Payload struct {
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

// EncodeHandshakeJSON serializes HandshakeV3 to compact JSON.
func (h *HandshakeV3Payload) EncodeHandshakeJSON() ([]byte, error) {
	return json.Marshal(h)
}
