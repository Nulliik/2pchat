package main

import (
	"fmt"
	"os"
	"time"
	"twopchat/core/pkg/crypto"
)

func main() {
	fmt.Println("==================================================")
	fmt.Println("  2PChat Native Go Core CLI Utility (v0.1.0)")
	fmt.Println("==================================================")

	// Benchmark keypair generation
	start := time.Now()
	iterations := 1000
	for i := 0; i < iterations; i++ {
		_, _, _ = crypto.GenerateX25519Keypair()
	}
	elapsed := time.Since(start)
	fmt.Printf("[Bench] %d X25519 Keypair generations: %v (%.2f µs/op)\n",
		iterations, elapsed, float64(elapsed.Microseconds())/float64(iterations))

	// Benchmark Ed25519
	start = time.Now()
	for i := 0; i < iterations; i++ {
		_, _, _ = crypto.GenerateEd25519Keypair()
	}
	elapsed = time.Since(start)
	fmt.Printf("[Bench] %d Ed25519 Keypair generations: %v (%.2f µs/op)\n",
		iterations, elapsed, float64(elapsed.Microseconds())/float64(iterations))

	// Run full X3DH and Double Ratchet simulation
	fmt.Println("\n[Test] Initializing Alice and Bob X3DH & Double Ratchet session...")
	aliceId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error generating Alice identity: %v\n", err)
		os.Exit(1)
	}

	bobId, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error generating Bob identity: %v\n", err)
		os.Exit(1)
	}

	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error generating Bob prekey: %v\n", err)
		os.Exit(1)
	}

	bobPrekeySig := crypto.SignPreKey(bobId.Signing, bobPrekeyPub)
	bobBundle := &crypto.PreKeyBundle{
		IdentityPub:       bobId.Public,
		IdentityVerifyPub: bobId.Verify,
		SignedPrekeyPub:   bobPrekeyPub,
		SignedPrekeySig:   bobPrekeySig,
	}

	aliceEph, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error generating Alice ephemeral: %v\n", err)
		os.Exit(1)
	}

	aliceSession, err := crypto.InitializeSessionFromPreKey(aliceId, bobBundle, aliceEph)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error initializing Alice session: %v\n", err)
		os.Exit(1)
	}

	bobSession, err := crypto.RespondToPreKeyInit(bobId, bobPrekeyPriv, nil, aliceId.Public, aliceEph.Public)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error initializing Bob session: %v\n", err)
		os.Exit(1)
	}

	safetyNum, _ := crypto.SafetyNumber(aliceId.Public.Bytes(), bobId.Public.Bytes(), aliceId.Verify, bobId.Verify)
	fmt.Printf("[Test] Safety Number (Alice/Bob): %s\n", safetyNum)

	// Encrypt & Decrypt 10,000 messages benchmark
	msgCount := 10000
	fmt.Printf("[Bench] Encrypting & Decrypting %d Double Ratchet messages...\n", msgCount)
	start = time.Now()
	for i := 0; i < msgCount; i++ {
		text := fmt.Sprintf("Message payload #%d from Alice", i)
		packet, err := aliceSession.EncryptMessage([]byte(text))
		if err != nil {
			fmt.Fprintf(os.Stderr, "Encrypt error: %v\n", err)
			os.Exit(1)
		}

		plain, err := bobSession.DecryptMessage(packet)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Decrypt error: %v\n", err)
			os.Exit(1)
		}
		if string(plain) != text {
			fmt.Fprintf(os.Stderr, "Payload mismatch at %d\n", i)
			os.Exit(1)
		}
	}
	elapsed = time.Since(start)
	fmt.Printf("[Bench] 10,000 Double Ratchet roundtrips: %v (%.2f µs/roundtrip, %.0f msg/sec)\n",
		elapsed,
		float64(elapsed.Microseconds())/float64(msgCount),
		float64(msgCount)/(elapsed.Seconds()),
	)

	fmt.Println("\n✓ Phase 1 Cryptography Core tests completed successfully.")
}
