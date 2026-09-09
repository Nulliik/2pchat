package session

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"twopchat/core/pkg/protocol"
)

// WithCapabilities freezes the local feature profile for this connection.
func WithCapabilities(d protocol.Declaration) SessionOption {
	d = d.Clone()
	return func(s *Session) { s.localCapabilities = d.Clone() }
}

// NegotiatedProtocol returns a detached snapshot. Nil means no authenticated
// identity_info yet; optional features must remain disabled in that state.
func (s *Session) NegotiatedProtocol() *protocol.NegotiatedSession {
	s.protocolMu.RLock()
	defer s.protocolMu.RUnlock()
	if s.negotiated == nil || !s.IsOnline() {
		return nil
	}
	result := *s.negotiated
	result.ActiveCapabilities = append([]string(nil), result.ActiveCapabilities...)
	return &result
}

// Called only after successful Double Ratchet decryption. This avoids changing
// the v3 signature transcript, which old clients must still be able to verify.
func (s *Session) acceptCapabilities(plaintext []byte) error {
	envelope := make(map[string]json.RawMessage)
	decoder := json.NewDecoder(bytes.NewReader(plaintext))
	if token, err := decoder.Token(); err != nil || token != json.Delim('{') {
		return errors.New("invalid identity_info object")
	}
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return err
		}
		key, ok := token.(string)
		if !ok {
			return errors.New("invalid identity_info field")
		}
		if _, duplicate := envelope[key]; duplicate {
			return errors.New("duplicate identity_info field")
		}
		var raw json.RawMessage
		if err := decoder.Decode(&raw); err != nil {
			return err
		}
		envelope[key] = raw
	}
	if _, err := decoder.Token(); err != nil {
		return err
	}
	if _, err := decoder.Token(); err != io.EOF {
		return errors.New("trailing identity_info data")
	}
	var claimed string
	if raw, ok := envelope["fingerprint"]; ok {
		if err := json.Unmarshal(raw, &claimed); err != nil {
			return err
		}
		if claimed != "" && claimed != s.peerFingerprint {
			return errors.New("identity_info fingerprint mismatch")
		}
	}
	result := protocol.Legacy()
	canonical := "legacy"
	if raw, present := envelope["protocol"]; present {
		remote, err := protocol.Parse(raw)
		if err != nil {
			return err
		}
		result, err = protocol.Negotiate(s.localCapabilities, remote)
		if err != nil {
			return err
		}
		encoded, err := remote.CanonicalBytes()
		if err != nil {
			return err
		}
		canonical = string(encoded)
	} else if s.localCapabilities.MinSupportedVersion > 1 {
		return protocol.ErrIncompatible
	}
	s.protocolMu.Lock()
	defer s.protocolMu.Unlock()
	if s.negotiated != nil && s.remoteDeclaration != canonical {
		return errors.New("capability declaration changed within session")
	}
	s.negotiated = &result
	s.remoteDeclaration = canonical
	return nil
}
