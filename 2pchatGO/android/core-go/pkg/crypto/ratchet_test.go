package crypto

import (
	"bytes"
	"crypto/rand"
	"testing"
)

func TestKeysAndSignatures(t *testing.T) {
	alice, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("GenerateIdentityKeyPair failed: %v", err)
	}

	bob, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("GenerateIdentityKeyPair failed: %v", err)
	}

	// Test DH
	sharedAB, err := DH(alice.Private, bob.Public)
	if err != nil {
		t.Fatalf("DH(alice, bob) failed: %v", err)
	}
	sharedBA, err := DH(bob.Private, alice.Public)
	if err != nil {
		t.Fatalf("DH(bob, alice) failed: %v", err)
	}
	if !bytes.Equal(sharedAB, sharedBA) {
		t.Fatal("DH shared secret mismatch between Alice and Bob")
	}

	// Test Fingerprints
	fpAlice := Fingerprint(alice.Public.Bytes())
	if len(fpAlice) == 0 {
		t.Fatal("empty fingerprint")
	}

	// Test Safety Number
	safetyAB, err := SafetyNumber(alice.Public.Bytes(), bob.Public.Bytes(), alice.Verify, bob.Verify)
	if err != nil {
		t.Fatalf("SafetyNumber failed: %v", err)
	}
	safetyBA, err := SafetyNumber(bob.Public.Bytes(), alice.Public.Bytes(), bob.Verify, alice.Verify)
	if err != nil {
		t.Fatalf("SafetyNumber failed: %v", err)
	}
	if safetyAB != safetyBA {
		t.Fatalf("Safety numbers do not match: %s != %s", safetyAB, safetyBA)
	}
	if len(safetyAB) != 60 {
		t.Fatalf("Expected 60 digits safety number, got %d digits: %s", len(safetyAB), safetyAB)
	}

	// Test Zeroize
	buf := []byte{1, 2, 3, 4, 5}
	Zeroize(buf)
	for i, b := range buf {
		if b != 0 {
			t.Fatalf("Zeroize failed at index %d, value=%d", i, b)
		}
	}
}

func TestSecretBox(t *testing.T) {
	key := make([]byte, SecretBoxKeySize)
	rand.Read(key)

	plaintext := []byte("Hello, this is a secret 2PChat test payload!")
	sealed, err := SecretBoxEncrypt(key, plaintext)
	if err != nil {
		t.Fatalf("SecretBoxEncrypt failed: %v", err)
	}

	decrypted, err := SecretBoxDecrypt(key, sealed)
	if err != nil {
		t.Fatalf("SecretBoxDecrypt failed: %v", err)
	}

	if !bytes.Equal(plaintext, decrypted) {
		t.Fatalf("Decrypted plaintext mismatch: got %q, want %q", decrypted, plaintext)
	}

	// Test tampering
	corrupted := append([]byte(nil), sealed...)
	corrupted[len(corrupted)-1] ^= 0xFF
	if _, err := SecretBoxDecrypt(key, corrupted); err == nil {
		t.Fatal("Expected error on corrupted ciphertext, got nil")
	}
}

func TestDoubleRatchetX3DHSession(t *testing.T) {
	aliceId, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	bobId, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}

	// Bob creates prekey bundle
	bobSignedPrekeyPriv, bobSignedPrekeyPub, err := GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	bobPrekeySig := SignPreKey(bobId.Signing, bobSignedPrekeyPub)

	bobBundle := &PreKeyBundle{
		IdentityPub:       bobId.Public,
		IdentityVerifyPub: bobId.Verify,
		SignedPrekeyPub:   bobSignedPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	// Alice initiates session
	aliceEphemeral, err := GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	aliceSession, err := InitializeSessionFromPreKey(aliceId, bobBundle, aliceEphemeral)
	if err != nil {
		t.Fatalf("Alice InitializeSessionFromPreKey failed: %v", err)
	}

	// Bob responds
	bobSession, err := RespondToPreKeyInit(
		bobId,
		bobSignedPrekeyPriv,
		nil,
		aliceId.Public,
		aliceEphemeral.Public,
	)
	if err != nil {
		t.Fatalf("Bob RespondToPreKeyInit failed: %v", err)
	}

	// Step 1: Alice sends multiple messages to Bob
	messagesFromAlice := []string{
		"Hello Bob! Welcome to 2PChat native Go core.",
		"Second message from Alice in sequence.",
		"Third sequential message testing symmetric KDF ratchet.",
	}

	for _, text := range messagesFromAlice {
		pkt, err := aliceSession.EncryptMessage([]byte(text))
		if err != nil {
			t.Fatalf("Alice encrypt failed: %v", err)
		}

		plain, err := bobSession.DecryptMessage(pkt)
		if err != nil {
			t.Fatalf("Bob decrypt failed: %v", err)
		}
		if string(plain) != text {
			t.Fatalf("Message mismatch: got %q, want %q", plain, text)
		}
	}

	// Step 2: Bob replies to Alice (Triggers DH ratchet step!)
	messagesFromBob := []string{
		"Hi Alice! I received your messages cleanly.",
		"Replying from Bob to advance our DH ratchet.",
	}

	for _, text := range messagesFromBob {
		pkt, err := bobSession.EncryptMessage([]byte(text))
		if err != nil {
			t.Fatalf("Bob encrypt failed: %v", err)
		}

		plain, err := aliceSession.DecryptMessage(pkt)
		if err != nil {
			t.Fatalf("Alice decrypt failed: %v", err)
		}
		if string(plain) != text {
			t.Fatalf("Message mismatch: got %q, want %q", plain, text)
		}
	}

	// Step 3: Alice sends again after DH ratchet
	replyText := "DH ratchet rotation verified successfully!"
	replyPkt, err := aliceSession.EncryptMessage([]byte(replyText))
	if err != nil {
		t.Fatalf("Alice encrypt after ratchet failed: %v", err)
	}
	replyPlain, err := bobSession.DecryptMessage(replyPkt)
	if err != nil {
		t.Fatalf("Bob decrypt after ratchet failed: %v", err)
	}
	if string(replyPlain) != replyText {
		t.Fatalf("Reply mismatch: got %q, want %q", replyPlain, replyText)
	}
}

func TestDoubleRatchetOutOfOrderDelivery(t *testing.T) {
	aliceId, _ := GenerateIdentityKeyPair()
	bobId, _ := GenerateIdentityKeyPair()

	bobPrekeyPriv, bobPrekeyPub, _ := GenerateX25519Keypair()
	bobPrekeySig := SignPreKey(bobId.Signing, bobPrekeyPub)
	bobBundle := &PreKeyBundle{
		IdentityPub:       bobId.Public,
		IdentityVerifyPub: bobId.Verify,
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	aliceEph, _ := GenerateIdentityKeyPair()
	aliceSession, _ := InitializeSessionFromPreKey(aliceId, bobBundle, aliceEph)
	bobSession, _ := RespondToPreKeyInit(bobId, bobPrekeyPriv, nil, aliceId.Public, aliceEph.Public)

	// Alice encrypts 3 packets
	pkt0, _ := aliceSession.EncryptMessage([]byte("Msg 0"))
	pkt1, _ := aliceSession.EncryptMessage([]byte("Msg 1"))
	pkt2, _ := aliceSession.EncryptMessage([]byte("Msg 2"))

	// Bob receives out-of-order: pkt2 first, then pkt0, then pkt1
	plain2, err := bobSession.DecryptMessage(pkt2)
	if err != nil {
		t.Fatalf("Failed to decrypt pkt2 out of order: %v", err)
	}
	if string(plain2) != "Msg 2" {
		t.Fatalf("Expected Msg 2, got %s", plain2)
	}

	plain0, err := bobSession.DecryptMessage(pkt0)
	if err != nil {
		t.Fatalf("Failed to decrypt pkt0 out of order: %v", err)
	}
	if string(plain0) != "Msg 0" {
		t.Fatalf("Expected Msg 0, got %s", plain0)
	}

	plain1, err := bobSession.DecryptMessage(pkt1)
	if err != nil {
		t.Fatalf("Failed to decrypt pkt1 out of order: %v", err)
	}
	if string(plain1) != "Msg 1" {
		t.Fatalf("Expected Msg 1, got %s", plain1)
	}

	// Trying to decrypt pkt1 again should fail as duplicate/consumed
	if _, err := bobSession.DecryptMessage(pkt1); err == nil {
		t.Fatal("Expected error on duplicate packet delivery, got nil")
	}
}

func TestDoubleRatchetTamperResistance(t *testing.T) {
	aliceId, _ := GenerateIdentityKeyPair()
	bobId, _ := GenerateIdentityKeyPair()

	bobPrekeyPriv, bobPrekeyPub, _ := GenerateX25519Keypair()
	bobPrekeySig := SignPreKey(bobId.Signing, bobPrekeyPub)
	bobBundle := &PreKeyBundle{
		IdentityPub:       bobId.Public,
		IdentityVerifyPub: bobId.Verify,
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	aliceEph, _ := GenerateIdentityKeyPair()
	aliceSession, _ := InitializeSessionFromPreKey(aliceId, bobBundle, aliceEph)
	bobSession, _ := RespondToPreKeyInit(bobId, bobPrekeyPriv, nil, aliceId.Public, aliceEph.Public)

	pkt, _ := aliceSession.EncryptMessage([]byte("Protected Message"))

	// Corrupt authentication tag
	tamperedTag := append([]byte(nil), pkt...)
	tamperedTag[len(tamperedTag)-1] ^= 0xAA

	if _, err := bobSession.DecryptMessage(tamperedTag); err == nil {
		t.Fatal("Expected error on corrupted auth tag")
	}

	// Verify that the failed attempt did NOT break session state for valid packets
	plain, err := bobSession.DecryptMessage(pkt)
	if err != nil {
		t.Fatalf("Valid packet decryption failed after rejected forgery: %v", err)
	}
	if string(plain) != "Protected Message" {
		t.Fatalf("Expected 'Protected Message', got %s", plain)
	}
}

func TestSessionStateZeroize(t *testing.T) {
	aliceId, _ := GenerateIdentityKeyPair()
	bobId, _ := GenerateIdentityKeyPair()

	_, bobPrekeyPub, _ := GenerateX25519Keypair()
	bobPrekeySig := SignPreKey(bobId.Signing, bobPrekeyPub)
	bobBundle := &PreKeyBundle{
		IdentityPub:       bobId.Public,
		IdentityVerifyPub: bobId.Verify,
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	aliceEph, _ := GenerateIdentityKeyPair()
	aliceSession, _ := InitializeSessionFromPreKey(aliceId, bobBundle, aliceEph)

	aliceSession.Zeroize()

	for i, b := range aliceSession.RootKey {
		if b != 0 {
			t.Fatalf("Root key byte %d was not zeroed", i)
		}
	}
	for i, b := range aliceSession.SendChainKey {
		if b != 0 {
			t.Fatalf("Send chain key byte %d was not zeroed", i)
		}
	}
	for i, b := range aliceSession.RecvChainKey {
		if b != 0 {
			t.Fatalf("Recv chain key byte %d was not zeroed", i)
		}
	}
	if aliceSession.DHSendKey != nil {
		for i, b := range aliceSession.DHSendKey {
			if b != 0 {
				t.Fatalf("DH send key byte %d was not zeroed", i)
			}
		}
	}
}
