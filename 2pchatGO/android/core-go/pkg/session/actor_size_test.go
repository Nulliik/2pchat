package session

import (
	"bytes"
	"testing"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

func TestActorRejectsOversizedWirePayloadBeforeRatchetAdvance(t *testing.T) {
	key, _, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	chain := bytes.Repeat([]byte{0x42}, 32)
	state := &crypto.SessionState{SendChainKey: append([]byte(nil), chain...), SendIdx: 19, DHSendKey: key}
	defer state.Zeroize()
	actor := &PeerActor{drState: state, counter: 7}
	if _, err := actor.handleSendBinary(make([]byte, transport.MaxFrameSize)); err == nil {
		t.Fatal("payload accepted although the encrypted frame exceeds the receive limit")
	}
	if state.SendIdx != 19 || actor.counter != 7 || !bytes.Equal(state.SendChainKey, chain) {
		t.Fatal("rejected frame changed sending state")
	}
}
