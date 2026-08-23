package crypto

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
	"io"
)

// Noise Protocol Constants for Noise_IK_25519_ChaChaPoly_SHA256 and Noise_XX_25519_ChaChaPoly_SHA256
const (
	NoiseProtocolNameIK = "Noise_IK_25519_ChaChaPoly_SHA256"
	NoiseProtocolNameXX = "Noise_XX_25519_ChaChaPoly_SHA256"
	NoiseMaxMessageLen  = 65535
	NoiseKeyLen         = 32
	NoiseTagLen         = 16
)

var (
	ErrNoiseMessageTooLong = errors.New("noise: message exceeds max length")
	ErrNoiseAuthFailed     = errors.New("noise: authentication decryption failed")
	ErrNoiseInvalidState   = errors.New("noise: invalid handshake state")
)

// NoiseCipherState manages symmetric encryption key and nonce for Noise transport.
type NoiseCipherState struct {
	key    [32]byte
	nonce  uint64
	hasKey bool
}

// NewNoiseCipherState creates an uninitialized CipherState.
func NewNoiseCipherState() *NoiseCipherState {
	return &NoiseCipherState{}
}

// InitializeKey sets the 32-byte symmetric key and resets the nonce.
func (c *NoiseCipherState) InitializeKey(key []byte) {
	if len(key) == 32 {
		copy(c.key[:], key)
		c.nonce = 0
		c.hasKey = true
	}
}

// EncryptWithAd encrypts plaintext with ChaCha20-Poly1305 and associated data.
func (c *NoiseCipherState) EncryptWithAd(ad, plaintext []byte) ([]byte, error) {
	if !c.hasKey {
		return plaintext, nil
	}
	aead, err := chacha20poly1305.New(c.key[:])
	if err != nil {
		return nil, err
	}
	var nonce [12]byte
	// Little-endian 64-bit nonce in 12-byte array per Noise spec
	nonce[4] = byte(c.nonce)
	nonce[5] = byte(c.nonce >> 8)
	nonce[6] = byte(c.nonce >> 16)
	nonce[7] = byte(c.nonce >> 24)
	nonce[8] = byte(c.nonce >> 32)
	nonce[9] = byte(c.nonce >> 40)
	nonce[10] = byte(c.nonce >> 48)
	nonce[11] = byte(c.nonce >> 56)

	c.nonce++
	return aead.Seal(nil, nonce[:], plaintext, ad), nil
}

// DecryptWithAd decrypts ciphertext with ChaCha20-Poly1305 and associated data.
func (c *NoiseCipherState) DecryptWithAd(ad, ciphertext []byte) ([]byte, error) {
	if !c.hasKey {
		return ciphertext, nil
	}
	aead, err := chacha20poly1305.New(c.key[:])
	if err != nil {
		return nil, err
	}
	var nonce [12]byte
	nonce[4] = byte(c.nonce)
	nonce[5] = byte(c.nonce >> 8)
	nonce[6] = byte(c.nonce >> 16)
	nonce[7] = byte(c.nonce >> 24)
	nonce[8] = byte(c.nonce >> 32)
	nonce[9] = byte(c.nonce >> 40)
	nonce[10] = byte(c.nonce >> 48)
	nonce[11] = byte(c.nonce >> 56)

	c.nonce++
	return aead.Open(nil, nonce[:], ciphertext, ad)
}

// Zeroize wipes key material in CipherState.
func (c *NoiseCipherState) Zeroize() {
	Zeroize(c.key[:])
	c.nonce = 0
	c.hasKey = false
}

// NoiseSymmetricState manages the running hash (h) and chaining key (ck).
type NoiseSymmetricState struct {
	cipherState *NoiseCipherState
	ck          [32]byte
	h           [32]byte
}

// NewNoiseSymmetricState initializes running hash and chaining key from protocol name.
func NewNoiseSymmetricState(protocolName string) *NoiseSymmetricState {
	s := &NoiseSymmetricState{
		cipherState: NewNoiseCipherState(),
	}
	if len(protocolName) <= 32 {
		copy(s.h[:], []byte(protocolName))
	} else {
		s.h = sha256.Sum256([]byte(protocolName))
	}
	s.ck = s.h
	return s
}

// MixKey derives a new chaining key and cipher key using HKDF-SHA256.
func (s *NoiseSymmetricState) MixKey(inputKeyMaterial []byte) {
	r := hkdf.New(sha256.New, inputKeyMaterial, s.ck[:], nil)
	var ck, k [32]byte
	_, _ = io.ReadFull(r, ck[:])
	_, _ = io.ReadFull(r, k[:])
	s.ck = ck
	s.cipherState.InitializeKey(k[:])
	Zeroize(k[:])
}

// MixHash mixes data into the running handshake hash.
func (s *NoiseSymmetricState) MixHash(data []byte) {
	h := sha256.New()
	h.Write(s.h[:])
	h.Write(data)
	copy(s.h[:], h.Sum(nil))
}

// MixKeyAndHash performs HKDF to derive ck, tempHash, and cipher key.
func (s *NoiseSymmetricState) MixKeyAndHash(inputKeyMaterial []byte) {
	r := hkdf.New(sha256.New, inputKeyMaterial, s.ck[:], nil)
	var ck, tempHash, k [32]byte
	_, _ = io.ReadFull(r, ck[:])
	_, _ = io.ReadFull(r, tempHash[:])
	_, _ = io.ReadFull(r, k[:])
	s.ck = ck
	s.MixHash(tempHash[:])
	s.cipherState.InitializeKey(k[:])
	Zeroize(k[:])
}

// EncryptAndHash encrypts plaintext with running hash as associated data, and updates hash.
func (s *NoiseSymmetricState) EncryptAndHash(plaintext []byte) ([]byte, error) {
	ciphertext, err := s.cipherState.EncryptWithAd(s.h[:], plaintext)
	if err != nil {
		return nil, err
	}
	s.MixHash(ciphertext)
	return ciphertext, nil
}

// DecryptAndHash decrypts ciphertext with running hash as associated data, and updates hash.
func (s *NoiseSymmetricState) DecryptAndHash(ciphertext []byte) ([]byte, error) {
	plaintext, err := s.cipherState.DecryptWithAd(s.h[:], ciphertext)
	if err != nil {
		return nil, err
	}
	s.MixHash(ciphertext)
	return plaintext, nil
}

// Split derives two final transport CipherStates for bidirectional communication.
func (s *NoiseSymmetricState) Split() (*NoiseCipherState, *NoiseCipherState) {
	r := hkdf.New(sha256.New, nil, s.ck[:], nil)
	var k1, k2 [32]byte
	_, _ = io.ReadFull(r, k1[:])
	_, _ = io.ReadFull(r, k2[:])

	c1 := NewNoiseCipherState()
	c1.InitializeKey(k1[:])
	c2 := NewNoiseCipherState()
	c2.InitializeKey(k2[:])

	Zeroize(k1[:])
	Zeroize(k2[:])
	Zeroize(s.ck[:])
	return c1, c2
}

// NoiseHandshakeState executes the Noise_IK or Noise_XX handshake pattern.
type NoiseHandshakeState struct {
	symmetricState *NoiseSymmetricState
	s              *X25519PrivateKey // Local static private key
	sPub           *X25519PublicKey  // Local static public key
	e              *X25519PrivateKey // Local ephemeral private key
	ePub           *X25519PublicKey  // Local ephemeral public key
	rs             *X25519PublicKey  // Remote static public key
	re             *X25519PublicKey  // Remote ephemeral public key
	isInitiator    bool
	pattern        string
	step           int
}

// NewNoiseIKHandshake creates a Noise_IK handshake state (Initiator knows remote static key rs).
func NewNoiseIKHandshake(isInitiator bool, localPriv *X25519PrivateKey, remotePub *X25519PublicKey) (*NoiseHandshakeState, error) {
	hs := &NoiseHandshakeState{
		symmetricState: NewNoiseSymmetricState(NoiseProtocolNameIK),
		s:              localPriv,
		isInitiator:    isInitiator,
		pattern:        "IK",
	}

	if localPriv != nil {
		hs.sPub = localPriv.Public()
	}

	// Mix in prologue
	hs.symmetricState.MixHash([]byte("2PChat-P2P-v5"))

	if isInitiator {
		if remotePub == nil {
			return nil, errors.New("noise IK initiator requires remote public key")
		}
		hs.rs = remotePub
		// Initiator mixes in responder's known static key (rs)
		hs.symmetricState.MixHash(remotePub.Bytes())
	} else {
		// Responder mixes in own static key (s)
		if hs.sPub == nil {
			return nil, errors.New("noise IK responder requires local static key")
		}
		hs.symmetricState.MixHash(hs.sPub.Bytes())
	}

	return hs, nil
}

// StepInitiatorMsg1 generates Msg1: -> e, es, s, ss, payload
func (hs *NoiseHandshakeState) StepInitiatorMsg1(payload []byte) ([]byte, error) {
	if !hs.isInitiator || hs.step != 0 {
		return nil, ErrNoiseInvalidState
	}

	// 1. Generate ephemeral key e
	ePriv, ePub, err := GenerateX25519Keypair()
	if err != nil {
		return nil, err
	}
	hs.e = ePriv
	hs.ePub = ePub

	// 2. MixHash(e.pub)
	hs.symmetricState.MixHash(ePub.Bytes())

	// 3. DH(e, rs) -> MixKey
	dhES, err := curve25519.X25519(hs.e.Bytes(), hs.rs.Bytes())
	if err != nil {
		return nil, err
	}
	defer Zeroize(dhES)
	hs.symmetricState.MixKey(dhES)

	// 4. EncryptAndHash(s.pub)
	encS, err := hs.symmetricState.EncryptAndHash(hs.sPub.Bytes())
	if err != nil {
		return nil, err
	}

	// 5. DH(s, rs) -> MixKey
	dhSS, err := curve25519.X25519(hs.s.Bytes(), hs.rs.Bytes())
	if err != nil {
		return nil, err
	}
	defer Zeroize(dhSS)
	hs.symmetricState.MixKey(dhSS)

	// 6. EncryptAndHash(payload)
	encPayload, err := hs.symmetricState.EncryptAndHash(payload)
	if err != nil {
		return nil, err
	}

	hs.step = 1

	// Frame message: ePub (32) + encS (32+16=48) + encPayload
	msg := make([]byte, 0, len(hs.ePub.Bytes())+len(encS)+len(encPayload))
	msg = append(msg, hs.ePub.Bytes()...)
	msg = append(msg, encS...)
	msg = append(msg, encPayload...)
	return msg, nil
}

// StepResponderMsg1 processes Msg1 and generates Msg2: <- e, ee, se, payload
func (hs *NoiseHandshakeState) StepResponderMsg1(msg1 []byte, replyPayload []byte) ([]byte, *NoiseCipherState, *NoiseCipherState, error) {
	if hs.isInitiator || hs.step != 0 {
		return nil, nil, nil, ErrNoiseInvalidState
	}

	if len(msg1) < 32+48 {
		return nil, nil, nil, errors.New("noise: msg1 too short")
	}

	// 1. Read remote ephemeral re
	var rePub X25519PublicKey
	copy(rePub[:], msg1[:32])
	hs.re = &rePub
	hs.symmetricState.MixHash(hs.re.Bytes())

	// 2. DH(s, re) -> MixKey
	dhES, err := curve25519.X25519(hs.s.Bytes(), hs.re.Bytes())
	if err != nil {
		return nil, nil, nil, err
	}
	defer Zeroize(dhES)
	hs.symmetricState.MixKey(dhES)

	// 3. Decrypt remote static rs
	decS, err := hs.symmetricState.DecryptAndHash(msg1[32:80])
	if err != nil {
		return nil, nil, nil, fmt.Errorf("decrypt remote static failed: %w", err)
	}
	var rsPub X25519PublicKey
	copy(rsPub[:], decS)
	hs.rs = &rsPub

	// 4. DH(s, rs) -> MixKey
	dhSS, err := curve25519.X25519(hs.s.Bytes(), hs.rs.Bytes())
	if err != nil {
		return nil, nil, nil, err
	}
	defer Zeroize(dhSS)
	hs.symmetricState.MixKey(dhSS)

	// 5. Decrypt payload
	if len(msg1) > 80 {
		_, err := hs.symmetricState.DecryptAndHash(msg1[80:])
		if err != nil {
			return nil, nil, nil, fmt.Errorf("decrypt msg1 payload failed: %w", err)
		}
	}

	// 6. Generate local ephemeral e for Msg2
	ePriv, ePub, err := GenerateX25519Keypair()
	if err != nil {
		return nil, nil, nil, err
	}
	hs.e = ePriv
	hs.ePub = ePub
	hs.symmetricState.MixHash(ePub.Bytes())

	// 7. DH(e, re) -> MixKey
	dhEE, err := curve25519.X25519(hs.e.Bytes(), hs.re.Bytes())
	if err != nil {
		return nil, nil, nil, err
	}
	defer Zeroize(dhEE)
	hs.symmetricState.MixKey(dhEE)

	// 8. DH(e, rs) -> MixKey (se)
	dhSE, err := curve25519.X25519(hs.e.Bytes(), hs.rs.Bytes())
	if err != nil {
		return nil, nil, nil, err
	}
	defer Zeroize(dhSE)
	hs.symmetricState.MixKey(dhSE)

	// 9. EncryptAndHash(replyPayload)
	encPayload, err := hs.symmetricState.EncryptAndHash(replyPayload)
	if err != nil {
		return nil, nil, nil, err
	}

	// Final split into transport ciphers
	cInbound, cOutbound := hs.symmetricState.Split()

	msg2 := make([]byte, 0, len(hs.ePub.Bytes())+len(encPayload))
	msg2 = append(msg2, hs.ePub.Bytes()...)
	msg2 = append(msg2, encPayload...)

	hs.step = 2
	return msg2, cInbound, cOutbound, nil
}

// StepInitiatorMsg2 processes Msg2 and completes handshake
func (hs *NoiseHandshakeState) StepInitiatorMsg2(msg2 []byte) (*NoiseCipherState, *NoiseCipherState, error) {
	if !hs.isInitiator || hs.step != 1 {
		return nil, nil, ErrNoiseInvalidState
	}

	if len(msg2) < 32 {
		return nil, nil, errors.New("noise: msg2 too short")
	}

	// 1. Read remote ephemeral re
	var rePub X25519PublicKey
	copy(rePub[:], msg2[:32])
	hs.re = &rePub
	hs.symmetricState.MixHash(hs.re.Bytes())

	// 2. DH(e, re) -> MixKey (ee)
	dhEE, err := curve25519.X25519(hs.e.Bytes(), hs.re.Bytes())
	if err != nil {
		return nil, nil, err
	}
	defer Zeroize(dhEE)
	hs.symmetricState.MixKey(dhEE)

	// 3. DH(s, re) -> MixKey (se)
	dhSE, err := curve25519.X25519(hs.s.Bytes(), hs.re.Bytes())
	if err != nil {
		return nil, nil, err
	}
	defer Zeroize(dhSE)
	hs.symmetricState.MixKey(dhSE)

	// 4. Decrypt payload
	if len(msg2) > 32 {
		_, err := hs.symmetricState.DecryptAndHash(msg2[32:])
		if err != nil {
			return nil, nil, fmt.Errorf("decrypt msg2 payload failed: %w", err)
		}
	}

	cOutbound, cInbound := hs.symmetricState.Split()
	hs.step = 2
	return cOutbound, cInbound, nil
}

// Zeroize wipes all sensitive ephemeral keys and handshake state.
func (hs *NoiseHandshakeState) Zeroize() {
	if hs.e != nil {
		Zeroize(hs.e.Bytes())
		hs.e = nil
	}
	if hs.symmetricState != nil {
		Zeroize(hs.symmetricState.ck[:])
		Zeroize(hs.symmetricState.h[:])
		if hs.symmetricState.cipherState != nil {
			hs.symmetricState.cipherState.Zeroize()
		}
	}
}
