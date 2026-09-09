package crypto

import (
	"bytes"
	"testing"
)

func TestNoiseIKHandshakeRoundtrip(t *testing.T) {
	// Alice: Initiator
	alicePriv, alicePub, err := GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("Generate Alice keypair failed: %v", err)
	}
	defer Zeroize(alicePriv.Bytes())

	// Bob: Responder
	bobPriv, bobPub, err := GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("Generate Bob keypair failed: %v", err)
	}
	defer Zeroize(bobPriv.Bytes())

	// 1. Initialize Alice (knows Bob's public key bobPub)
	aliceHS, err := NewNoiseIKHandshake(true, alicePriv, bobPub)
	if err != nil {
		t.Fatalf("NewNoiseIKHandshake Alice failed: %v", err)
	}
	defer aliceHS.Zeroize()

	// 2. Initialize Bob (knows own private key bobPriv)
	bobHS, err := NewNoiseIKHandshake(false, bobPriv, nil)
	if err != nil {
		t.Fatalf("NewNoiseIKHandshake Bob failed: %v", err)
	}
	defer bobHS.Zeroize()

	// 3. Alice generates Msg1 with payload
	alicePayload := []byte("Hello Bob via Noise_IK!")
	msg1, err := aliceHS.StepInitiatorMsg1(alicePayload)
	if err != nil {
		t.Fatalf("Alice StepInitiatorMsg1 failed: %v", err)
	}

	// 4. Bob processes Msg1 and generates Msg2 with reply
	bobReply := []byte("Hello Alice, welcome to Noise!")
	msg2, bobInbound, bobOutbound, err := bobHS.StepResponderMsg1(msg1, bobReply)
	if err != nil {
		t.Fatalf("Bob StepResponderMsg1 failed: %v", err)
	}
	defer bobInbound.Zeroize()
	defer bobOutbound.Zeroize()

	// Verify Bob discovered Alice's static key
	if !bytes.Equal(bobHS.rs.Bytes(), alicePub.Bytes()) {
		t.Fatalf("Bob did not authenticate Alice's static public key correctly")
	}

	// 5. Alice processes Msg2 and completes handshake
	aliceOutbound, aliceInbound, err := aliceHS.StepInitiatorMsg2(msg2)
	if err != nil {
		t.Fatalf("Alice StepInitiatorMsg2 failed: %v", err)
	}
	defer aliceOutbound.Zeroize()
	defer aliceInbound.Zeroize()

	// 6. Test post-handshake transport encryption: Alice -> Bob
	aliceMsg := []byte("Post-handshake message from Alice to Bob")
	aliceCiphertext, err := aliceOutbound.EncryptWithAd(nil, aliceMsg)
	if err != nil {
		t.Fatalf("Alice EncryptWithAd failed: %v", err)
	}

	bobDecrypted, err := bobInbound.DecryptWithAd(nil, aliceCiphertext)
	if err != nil {
		t.Fatalf("Bob DecryptWithAd failed: %v", err)
	}

	if !bytes.Equal(bobDecrypted, aliceMsg) {
		t.Errorf("Alice->Bob payload mismatch: got %s, want %s", bobDecrypted, aliceMsg)
	}

	// 7. Test post-handshake transport encryption: Bob -> Alice
	bobMsg := []byte("Post-handshake response from Bob to Alice")
	bobCiphertext, err := bobOutbound.EncryptWithAd(nil, bobMsg)
	if err != nil {
		t.Fatalf("Bob EncryptWithAd failed: %v", err)
	}

	aliceDecrypted, err := aliceInbound.DecryptWithAd(nil, bobCiphertext)
	if err != nil {
		t.Fatalf("Alice DecryptWithAd failed: %v", err)
	}

	if !bytes.Equal(aliceDecrypted, bobMsg) {
		t.Errorf("Bob->Alice payload mismatch: got %s, want %s", aliceDecrypted, bobMsg)
	}
}

func TestNoiseTamperResistance(t *testing.T) {
	alicePriv, _, _ := GenerateX25519Keypair()
	bobPriv, bobPub, _ := GenerateX25519Keypair()
	defer Zeroize(alicePriv.Bytes())
	defer Zeroize(bobPriv.Bytes())

	aliceHS, _ := NewNoiseIKHandshake(true, alicePriv, bobPub)
	bobHS, _ := NewNoiseIKHandshake(false, bobPriv, nil)
	defer aliceHS.Zeroize()
	defer bobHS.Zeroize()

	msg1, err := aliceHS.StepInitiatorMsg1([]byte("Original payload"))
	if err != nil {
		t.Fatalf("StepInitiatorMsg1 failed: %v", err)
	}

	// Tamper with Msg1
	tamperedMsg1 := append([]byte(nil), msg1...)
	tamperedMsg1[len(tamperedMsg1)-1] ^= 0xFF

	_, _, _, err = bobHS.StepResponderMsg1(tamperedMsg1, []byte("Reply"))
	if err == nil {
		t.Fatalf("Expected Bob to reject tampered Msg1, but got success")
	}
}
