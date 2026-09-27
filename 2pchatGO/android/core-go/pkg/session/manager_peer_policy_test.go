package session

import (
	"net"
	"testing"

	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

func TestPeerAuthority_SetPeerPolicy_TerminatesViolatingSession(t *testing.T) {
	aliceID, _ := crypto.GenerateIdentityKeyPair()
	alicePrekeyPriv, alicePrekeyPub, _ := crypto.GenerateX25519Keypair()
	aliceMgr := NewManager(aliceID, alicePrekeyPriv, alicePrekeyPub, "127.0.0.1:9050", false, EventCallbacks{})

	bobID, _ := crypto.GenerateIdentityKeyPair()
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()

	sess := &Session{
		conn:            c1,
		online:          1,
		peerFingerprint: bobFP,
		closeChan:       make(chan struct{}),
	}
	aliceMgr.RegisterSession(sess, bobFP, "192.168.1.5:50001", false)

	if !aliceMgr.IsPeerOnline(bobFP) {
		t.Fatalf("Expected Bob session to be online")
	}

	yggPolicy := transport.PolicyFromFlags(transport.PolicyFlagAllowYggdrasil)
	aliceMgr.SetPeerPolicy(bobFP, yggPolicy)

	if aliceMgr.IsPeerOnline(bobFP) {
		t.Fatalf("Expected Bob's LAN session to be terminated when policy changed to Yggdrasil-only")
	}
}
