package crypto

import (
	"crypto/hmac"
	"crypto/sha256"
	"errors"
	"fmt"
	"sync"
)

var (
	senderKeyMessageInfo = []byte("2pchat_sender_key_msg")
	senderKeyChainInfo   = []byte("2pchat_sender_key_chain")
)

// SenderMessageKey represents a single-use derived symmetric key for group message encryption.
type SenderMessageKey struct {
	Iteration uint32
	CipherKey []byte
	Nonce     []byte
}

// SenderChainKey represents the symmetric ratchet state for a sender in a group.
type SenderChainKey struct {
	Iteration uint32
	Seed      []byte
}

// NewSenderChainKey creates a new chain key from an initial 32-byte secret.
func NewSenderChainKey(seed []byte) (*SenderChainKey, error) {
	if len(seed) != KeySize {
		return nil, fmt.Errorf("sender chain seed must be %d bytes, got %d", KeySize, len(seed))
	}
	s := make([]byte, KeySize)
	copy(s, seed)
	return &SenderChainKey{
		Iteration: 0,
		Seed:      s,
	}, nil
}

// SenderMessageKey derives the message encryption key and nonce for the current ratchet step.
func (ck *SenderChainKey) SenderMessageKey() (*SenderMessageKey, error) {
	h := hmac.New(sha256.New, ck.Seed)
	h.Write(senderKeyMessageInfo)
	derived := h.Sum(nil)

	// Split 32-byte output into 32-byte cipher key and derive 12-byte nonce
	cipherKey := make([]byte, KeySize)
	copy(cipherKey, derived)

	nonceH := hmac.New(sha256.New, derived)
	nonceH.Write([]byte{0x01})
	nonceFull := nonceH.Sum(nil)
	nonce := make([]byte, GroupAEADNonceSize)
	copy(nonce, nonceFull[:GroupAEADNonceSize])

	return &SenderMessageKey{
		Iteration: ck.Iteration,
		CipherKey: cipherKey,
		Nonce:     nonce,
	}, nil
}

// Next advances the sender chain key by one ratchet step.
func (ck *SenderChainKey) Next() *SenderChainKey {
	h := hmac.New(sha256.New, ck.Seed)
	h.Write(senderKeyChainInfo)
	nextSeed := h.Sum(nil)

	return &SenderChainKey{
		Iteration: ck.Iteration + 1,
		Seed:      nextSeed,
	}
}

// SenderSessionState tracks a sender's ratchet state and cached skipped message keys.
type SenderSessionState struct {
	mu          sync.RWMutex
	ChainKey    *SenderChainKey
	SkippedKeys map[uint32]*SenderMessageKey
}

// NewSenderSessionState initializes a sender state with an initial chain key.
func NewSenderSessionState(initialSeed []byte) (*SenderSessionState, error) {
	ck, err := NewSenderChainKey(initialSeed)
	if err != nil {
		return nil, err
	}
	return &SenderSessionState{
		ChainKey:    ck,
		SkippedKeys: make(map[uint32]*SenderMessageKey),
	}, nil
}

// RatchetKeyForIteration advances the chain to the requested iteration or retrieves from skipped cache.
func (s *SenderSessionState) RatchetKeyForIteration(targetIteration uint32) (*SenderMessageKey, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if key, found := s.SkippedKeys[targetIteration]; found {
		delete(s.SkippedKeys, targetIteration)
		return key, nil
	}

	if s.ChainKey.Iteration > targetIteration {
		return nil, errors.New("cannot ratchet backwards: message key already consumed")
	}

	const maxSkip = 2000
	if targetIteration-s.ChainKey.Iteration > maxSkip {
		return nil, errors.New("too many skipped message keys")
	}

	for s.ChainKey.Iteration < targetIteration {
		msgKey, err := s.ChainKey.SenderMessageKey()
		if err != nil {
			return nil, err
		}
		s.SkippedKeys[s.ChainKey.Iteration] = msgKey
		s.ChainKey = s.ChainKey.Next()
	}

	msgKey, err := s.ChainKey.SenderMessageKey()
	if err != nil {
		return nil, err
	}
	s.ChainKey = s.ChainKey.Next()

	return msgKey, nil
}

// Zeroize wipes all symmetric key material in the sender session from memory.
func (s *SenderSessionState) Zeroize() {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.ChainKey != nil {
		Zeroize(s.ChainKey.Seed)
	}
	for k, v := range s.SkippedKeys {
		if v != nil {
			Zeroize(v.CipherKey)
			Zeroize(v.Nonce)
		}
		delete(s.SkippedKeys, k)
	}
}
