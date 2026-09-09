package crypto

import (
	"testing"
)

func FuzzDecryptMessage(f *testing.F) {
	aliceId, err := GenerateIdentityKeyPair()
	if err != nil {
		f.Fatal(err)
	}
	bobId, err := GenerateIdentityKeyPair()
	if err != nil {
		f.Fatal(err)
	}

	bobSignedPrekeyPriv, bobSignedPrekeyPub, err := GenerateX25519Keypair()
	if err != nil {
		f.Fatal(err)
	}
	bobPrekeySig := SignPreKey(bobId.Signing, bobSignedPrekeyPub)

	bobBundle := &PreKeyBundle{
		IdentityPub:       bobId.Public,
		IdentityVerifyPub: bobId.Verify,
		SignedPrekeyPub:   bobSignedPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	aliceEphemeral, err := GenerateIdentityKeyPair()
	if err != nil {
		f.Fatal(err)
	}
	aliceSession, err := InitializeSessionFromPreKey(aliceId, bobBundle, aliceEphemeral)
	if err != nil {
		f.Fatal(err)
	}

	bobSession, err := RespondToPreKeyInit(
		bobId,
		bobSignedPrekeyPriv,
		nil,
		aliceId.Public,
		aliceEphemeral.Public,
	)
	if err != nil {
		f.Fatal(err)
	}

	validPacket, _ := aliceSession.EncryptMessage([]byte("Hello, Fuzzing World!"))
	f.Add(validPacket)
	f.Add([]byte{})
	f.Add([]byte{0x04, 0x01, 0x00})
	f.Add(make([]byte, 100))

	f.Fuzz(func(t *testing.T, data []byte) {
		// Decrypting arbitrary data must never panic, crash or hang
		_, _ = bobSession.DecryptMessage(data)
	})
}
