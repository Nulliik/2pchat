package crypto

import (
	"bytes"
	"testing"
)

func TestOversizedPlaintextDoesNotAdvanceSendingChain(t *testing.T) {
	chain := bytes.Repeat([]byte{0x42}, 32)
	state := &SessionState{SendChainKey: append([]byte(nil), chain...), SendIdx: 19}
	defer state.Zeroize()
	if _, err := state.EncryptMessage(make([]byte, maxSecretBoxPlaintext+1)); err == nil {
		t.Fatal("oversized plaintext accepted")
	}
	if state.SendIdx != 19 || !bytes.Equal(state.SendChainKey, chain) {
		t.Fatal("rejected plaintext advanced the sending ratchet")
	}
}
