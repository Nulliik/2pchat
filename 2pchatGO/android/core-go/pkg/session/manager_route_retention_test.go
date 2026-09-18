package session

import (
	"net"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
)

// A completed background dial must never replace an established authenticated
// session. Route candidates are untrusted until their own handshake succeeds,
// and replacing the live ratchet would discard in-flight reliable messages.
func TestRegisterSessionPreservesEstablishedSession(t *testing.T) {
	id, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	prekeyPriv, prekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	m := NewManager(id, prekeyPriv, prekeyPub, "", false, EventCallbacks{})
	defer m.Close()

	existingConn, existingPeer := net.Pipe()
	defer existingPeer.Close()
	defer existingConn.Close()
	duplicateConn, duplicatePeer := net.Pipe()
	defer duplicatePeer.Close()

	existing := &Session{
		conn:      existingConn,
		closeChan: make(chan struct{}),
		createdAt: time.Now().Add(-3 * time.Second),
	}
	duplicate := &Session{
		conn:      duplicateConn,
		closeChan: make(chan struct{}),
		createdAt: time.Now(),
	}
	existing.online = 1
	duplicate.online = 1
	const peerFP = "pinned-contact-fingerprint"
	m.sessions[peerFP] = existing
	m.peerEndp[peerFP] = "[200::1]:50001"

	m.RegisterSession(duplicate, peerFP, "[200::2]:50001", true)

	if got := m.GetSession(peerFP); got != existing {
		t.Fatal("late duplicate replaced established session")
	}
	deadline := time.Now().Add(time.Second)
	for duplicate.IsOnline() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if duplicate.IsOnline() {
		t.Fatal("late duplicate was not closed")
	}
	if !existing.IsOnline() {
		t.Fatal("established session was closed")
	}
}
