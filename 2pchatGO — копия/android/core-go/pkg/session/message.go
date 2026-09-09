package session

import (
	"encoding/json"
	"time"
)

// WireMessageType defines standard message types in 2PChat wire protocol.
type WireMessageType string

const (
	TypeHandshake     WireMessageType = "handshake"
	TypeChat          WireMessageType = "chat"
	TypeAck           WireMessageType = "ack"
	TypeStatus        WireMessageType = "status"
	TypeFileMeta      WireMessageType = "file_meta"
	TypeFileChunk     WireMessageType = "file_chunk"
	TypeIdentityProbe WireMessageType = "identity_probe"
	TypeIdentityInfo  WireMessageType = "identity_info"
)

// BaseMessage represents common fields present across all message types.
type BaseMessage struct {
	Type      WireMessageType `json:"type"`
	ID        string          `json:"id,omitempty"`
	Timestamp int64           `json:"timestamp,omitempty"`
}

// ChatMessage represents a direct text/chat payload.
type ChatMessage struct {
	Type      WireMessageType `json:"type"`
	ID        string          `json:"id"`
	Timestamp int64           `json:"timestamp"`
	Body      string          `json:"body"`
	Nickname  string          `json:"nickname,omitempty"`
	PeerFP    string          `json:"peer_fp,omitempty"`
}

// AckMessage represents a delivery confirmation for reliable messages.
type AckMessage struct {
	Type      WireMessageType `json:"type"`
	AckID     string          `json:"ack_id"`
	Timestamp int64           `json:"timestamp"`
}

// StatusMessage represents online/offline connection state transitions.
type StatusMessage struct {
	Type      WireMessageType `json:"type"`
	State     string          `json:"state"`
	Timestamp int64           `json:"timestamp"`
	Reason    string          `json:"reason,omitempty"`
}

// IdentityProbeMessage requests the peer to announce its verified identity.
type IdentityProbeMessage struct {
	Type WireMessageType `json:"type"`
}

// IdentityInfoMessage contains identity metadata returned in response to a probe.
type IdentityInfoMessage struct {
	Type        WireMessageType `json:"type"`
	Nickname    string          `json:"nickname"`
	Fingerprint string          `json:"fingerprint"`
}

// NewChatMessage creates a new outgoing ChatMessage.
func NewChatMessage(id, body, nickname string) *ChatMessage {
	return &ChatMessage{
		Type:      TypeChat,
		ID:        id,
		Timestamp: time.Now().Unix(),
		Body:      body,
		Nickname:  nickname,
	}
}

// NewAckMessage creates a new AckMessage confirming receipt of a message ID.
func NewAckMessage(ackID string) *AckMessage {
	return &AckMessage{
		Type:      TypeAck,
		AckID:     ackID,
		Timestamp: time.Now().Unix(),
	}
}

// EncodeMessage converts any wire message struct to JSON bytes.
func EncodeMessage(v any) ([]byte, error) {
	return json.Marshal(v)
}

// DecodeMessage parses raw JSON bytes into a map or generic struct.
func DecodeMessage(data []byte) (map[string]any, error) {
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, err
	}
	return m, nil
}
