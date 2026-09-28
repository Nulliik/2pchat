package session

import (
	"net"
	"path/filepath"
	"testing"

	"twopchat/core/pkg/crypto"
)

// A queued file job used to report success even when the file could not be
// opened. Android interpreted that early result as a completed transfer.
func TestSendFileReportsStreamFailure(t *testing.T) {
	id, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	priv, pub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	m := NewManager(id, priv, pub, "", false, EventCallbacks{})
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	const peer = "test-peer"
	m.sessions[peer] = &Session{conn: left, online: 1, peerFingerprint: peer}

	result, err := m.SendFile(peer, filepath.Join(t.TempDir(), "missing.mp4"), "file-test", "missing.mp4", "", "", "", -1, 0)
	if err == nil || result != "" {
		t.Fatalf("SendFile() = (%q, %v), want a stream failure", result, err)
	}
}
