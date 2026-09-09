package session

import (
	"testing"
	"time"
)

func TestChatMessageSerialization(t *testing.T) {
	msg := NewChatMessage("msg-12345", "Hello, 2PChat over Native Go!", "Alice")
	data, err := EncodeMessage(msg)
	if err != nil {
		t.Fatalf("EncodeMessage failed: %v", err)
	}

	decoded, err := DecodeMessage(data)
	if err != nil {
		t.Fatalf("DecodeMessage failed: %v", err)
	}

	if decoded["type"] != string(TypeChat) {
		t.Errorf("Expected type %s, got %v", TypeChat, decoded["type"])
	}
	if decoded["id"] != "msg-12345" {
		t.Errorf("Expected id msg-12345, got %v", decoded["id"])
	}
	if decoded["body"] != "Hello, 2PChat over Native Go!" {
		t.Errorf("Expected body mismatch, got %v", decoded["body"])
	}
	if decoded["nickname"] != "Alice" {
		t.Errorf("Expected nickname Alice, got %v", decoded["nickname"])
	}
}

func TestAckMessageSerialization(t *testing.T) {
	ack := NewAckMessage("target-msg-999")
	data, err := EncodeMessage(ack)
	if err != nil {
		t.Fatalf("EncodeMessage failed: %v", err)
	}

	decoded, err := DecodeMessage(data)
	if err != nil {
		t.Fatalf("DecodeMessage failed: %v", err)
	}

	if decoded["type"] != string(TypeAck) {
		t.Errorf("Expected type %s, got %v", TypeAck, decoded["type"])
	}
	if decoded["ack_id"] != "target-msg-999" {
		t.Errorf("Expected ack_id target-msg-999, got %v", decoded["ack_id"])
	}
}

func TestStatusAndIdentityMessages(t *testing.T) {
	status := &StatusMessage{
		Type:      TypeStatus,
		State:     "connected",
		Timestamp: time.Now().Unix(),
		Reason:    "Handshake completed",
	}

	statusData, err := EncodeMessage(status)
	if err != nil {
		t.Fatalf("Encode status failed: %v", err)
	}

	statusDecoded, err := DecodeMessage(statusData)
	if err != nil {
		t.Fatalf("Decode status failed: %v", err)
	}
	if statusDecoded["state"] != "connected" {
		t.Errorf("Expected state connected, got %v", statusDecoded["state"])
	}

	idInfo := &IdentityInfoMessage{
		Type:        TypeIdentityInfo,
		Nickname:    "Bob",
		Fingerprint: "bob-fingerprint-base64",
	}
	idData, err := EncodeMessage(idInfo)
	if err != nil {
		t.Fatalf("Encode idInfo failed: %v", err)
	}
	idDecoded, err := DecodeMessage(idData)
	if err != nil {
		t.Fatalf("Decode idInfo failed: %v", err)
	}
	if idDecoded["nickname"] != "Bob" || idDecoded["fingerprint"] != "bob-fingerprint-base64" {
		t.Errorf("IdentityInfo mismatch: %v", idDecoded)
	}
}
