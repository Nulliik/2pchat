package session

import (
	"strings"
	"testing"
)

func TestConnectPeerRejectsKnownLocalFingerprintBeforeDial(t *testing.T) {
	manager := &Manager{fingerprint: "local-fingerprint"}

	_, err := manager.ConnectPeer("[200:db8::1]:50001", "local-fingerprint")
	if err == nil || !strings.Contains(err.Error(), "refusing self connection") {
		t.Fatalf("expected self-connection rejection, got %v", err)
	}
}
