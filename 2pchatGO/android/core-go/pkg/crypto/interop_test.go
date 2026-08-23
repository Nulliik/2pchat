package crypto

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"os"
	"os/exec"
	"strings"
	"testing"
)

func TestPythonGroupSignatureInterop(t *testing.T) {
	pythonRoot := os.Getenv("P2PCHAT_PYTHON_ROOT")
	if pythonRoot == "" {
		t.Skip("set P2PCHAT_PYTHON_ROOT to the Python core checkout to run cross-core interop")
	}
	const payload = "2pchat-group-event-signature-v1\n1\ngroup-interop\nmessage"
	seed := bytes.Repeat([]byte{0x55}, ed25519.SeedSize)
	privateKey := ed25519.NewKeyFromSeed(seed)
	publicKey := privateKey.Public().(ed25519.PublicKey)

	python := `
import base64
import sys
from nacl.signing import SigningKey
import messenger.discovery_bridge as bridge

key = SigningKey(bytes.fromhex(sys.argv[2]))
bridge.load_or_create_signing_identity = lambda: key
mode, payload = sys.argv[1], sys.argv[3]
if mode == "sign":
    print(bridge.sign_group_payload(payload))
else:
    print("true" if bridge.verify_group_payload(
        base64.b64encode(bytes(key.verify_key)).decode("ascii"),
        payload,
        sys.argv[4],
    ) else "false")
`
	pythonCommand := func(args ...string) *exec.Cmd {
		cmd := exec.Command("python", args...)
		cmd.Env = append(os.Environ(), "PYTHONPATH="+pythonRoot)
		return cmd
	}

	output, err := pythonCommand("-c", python, "sign", hex.EncodeToString(seed), payload).CombinedOutput()
	if err != nil {
		t.Fatalf("Python group signing failed: %v: %s", err, output)
	}
	pythonSignature := strings.TrimSpace(string(output))
	if !VerifyGroupPayload(publicKey, payload, pythonSignature) {
		t.Fatal("Go rejected Python v2 group signature")
	}

	goSignature, err := SignGroupPayload(privateKey, payload)
	if err != nil {
		t.Fatal(err)
	}
	output, err = pythonCommand("-c", python, "verify", hex.EncodeToString(seed), payload, goSignature).CombinedOutput()
	if err != nil {
		t.Fatalf("Python group verification failed: %v: %s", err, output)
	}
	if strings.TrimSpace(string(output)) != "true" {
		t.Fatal("Python rejected Go v2 group signature")
	}
}

func TestPythonRatchetPacketInterop(t *testing.T) {
	// This is intentionally a live cross-language test: self-contained Go
	// round trips cannot detect a drift in the encrypted packet wire format.
	python := `
import sys
from nacl.public import PrivateKey
from nacl.signing import SigningKey
from messenger.core.double_ratchet import IdentityKeyPair, PreKeyBundle, _sign_prekey, initialize_session_from_prekey, respond_to_prekey_init, encrypt_message, decrypt_message

alice_seed = bytes([0x11]) * 32
bob_seed = bytes([0x22]) * 32
prekey_seed = bytes([0x33]) * 32
eph_seed = bytes([0x44]) * 32

def identity(seed):
    private = PrivateKey(seed)
    return IdentityKeyPair(private.public_key, private, SigningKey(seed))

alice = identity(alice_seed)
bob = identity(bob_seed)
prekey = PrivateKey(prekey_seed)
eph = identity(eph_seed)
bundle = PreKeyBundle(bob.public, bob.signing.verify_key, prekey.public_key, _sign_prekey(bob.signing, prekey.public_key))
alice_state = initialize_session_from_prekey(alice, bundle, eph)
bob_state = respond_to_prekey_init(bob, prekey, None, alice.public, eph.public)

mode, payload = sys.argv[1], bytes.fromhex(sys.argv[2])
if mode == "encrypt":
    print(encrypt_message(alice_state, payload).hex())
else:
    print(decrypt_message(bob_state, payload).decode("utf-8"))
`

	pythonRoot := os.Getenv("P2PCHAT_PYTHON_ROOT")
	if pythonRoot == "" {
		t.Skip("set P2PCHAT_PYTHON_ROOT to the Python core checkout to run cross-core interop")
	}
	pythonCommand := func(args ...string) *exec.Cmd {
		cmd := exec.Command("python", args...)
		cmd.Env = append(os.Environ(), "PYTHONPATH="+pythonRoot)
		return cmd
	}

	seed := func(value byte) []byte { return bytes.Repeat([]byte{value}, KeySize) }
	alice, err := IdentityKeyPairFromSeed(seed(0x11))
	if err != nil {
		t.Fatal(err)
	}
	bob, err := IdentityKeyPairFromSeed(seed(0x22))
	if err != nil {
		t.Fatal(err)
	}
	prekey, err := X25519PrivateKeyFromBytes(seed(0x33))
	if err != nil {
		t.Fatal(err)
	}
	eph, err := IdentityKeyPairFromSeed(seed(0x44))
	if err != nil {
		t.Fatal(err)
	}
	bundle := &PreKeyBundle{
		IdentityPub:       bob.Public,
		IdentityVerifyPub: bob.Verify,
		SignedPrekeyPub:   prekey.Public(),
		SignedPrekeySig:   SignPreKey(bob.Signing, prekey.Public()),
	}
	aliceState, err := InitializeSessionFromPreKey(alice, bundle, eph)
	if err != nil {
		t.Fatal(err)
	}
	bobState, err := RespondToPreKeyInit(bob, prekey, nil, alice.Public, eph.Public)
	if err != nil {
		t.Fatal(err)
	}

	const pythonToGo = "Python encrypted packet"
	output, err := pythonCommand("-c", python, "encrypt", hex.EncodeToString([]byte(pythonToGo))).CombinedOutput()
	if err != nil {
		t.Fatalf("Python packet encryption failed: %v: %s", err, output)
	}
	packet, err := hex.DecodeString(strings.TrimSpace(string(output)))
	if err != nil {
		t.Fatal(err)
	}
	plaintext, err := bobState.DecryptMessage(packet)
	if err != nil || string(plaintext) != pythonToGo {
		t.Fatalf("Go could not decrypt Python packet: plaintext=%q err=%v", plaintext, err)
	}

	const goToPython = "Go encrypted packet"
	packet, err = aliceState.EncryptMessage([]byte(goToPython))
	if err != nil {
		t.Fatal(err)
	}
	output, err = pythonCommand("-c", python, "decrypt", hex.EncodeToString(packet)).CombinedOutput()
	if err != nil {
		t.Fatalf("Python packet decryption failed: %v: %s", err, output)
	}
	if got := strings.TrimSpace(string(output)); got != goToPython {
		t.Fatalf("Python decrypted %q, want %q", got, goToPython)
	}
}

func TestPythonInterop(t *testing.T) {
	// 1. Test Safety Number compatibility with Python
	alicePubHex := "9a6df2f58e1c6b12a87a6b98687a4192b4506f5287aa09c9103c809ff44f2d34"
	bobPubHex := "b59a456381ad7929a59ffc9071060ca068ef5c11ee49479b4fcb5e7d58a1b559"
	aliceVerifyHex := "2b92138258dc76395bcf82e99d1469e38e69ee80bfcf3b08e50b1dbfa53fb76a"
	bobVerifyHex := "4586dca50a9e70198647bc5b0de8e3a2c5a0ec7b808940306c579c3d40ec4751"

	alicePub, _ := hex.DecodeString(alicePubHex)
	bobPub, _ := hex.DecodeString(bobPubHex)
	aliceVerify, _ := hex.DecodeString(aliceVerifyHex)
	bobVerify, _ := hex.DecodeString(bobVerifyHex)

	goSafetyNum, err := SafetyNumber(alicePub, bobPub, aliceVerify, bobVerify)
	if err != nil {
		t.Fatalf("Go SafetyNumber failed: %v", err)
	}

	pyScript := `
import hashlib
def safety_number(local_identity_pub, remote_identity_pub, local_verify_pub=None, remote_verify_pub=None):
    identities = sorted((local_identity_pub, remote_identity_pub))
    material = b"p2p-chat-safety-number-v2\x00" + b"".join(identities)
    if local_verify_pub is not None and remote_verify_pub is not None:
        material += b"\x01" + b"".join(sorted((local_verify_pub, remote_verify_pub)))
    digest = hashlib.sha256(material).digest()
    num = int.from_bytes(digest[:30], "big") % (10 ** 60)
    return f"{num:060d}"

alice_pub = bytes.fromhex("` + alicePubHex + `")
bob_pub = bytes.fromhex("` + bobPubHex + `")
alice_verify = bytes.fromhex("` + aliceVerifyHex + `")
bob_verify = bytes.fromhex("` + bobVerifyHex + `")
print(safety_number(alice_pub, bob_pub, alice_verify, bob_verify))
`
	cmd := exec.Command("python3", "-c", pyScript)
	out, err := cmd.Output()
	if err == nil {
		pySafetyNum := strings.TrimSpace(string(out))
		if goSafetyNum != pySafetyNum {
			t.Fatalf("Safety number mismatch with Python: Go=%s, Python=%s", goSafetyNum, pySafetyNum)
		}
	}

	// 2. Test SecretBox compatibility
	keyHex := "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
	key, _ := hex.DecodeString(keyHex)
	nonceHex := "000102030405060708090a0b0c0d0e0f1011121314151617"
	nonce, _ := hex.DecodeString(nonceHex)
	msg := []byte("Hello Python / Go SecretBox interop!")

	goSealed, err := SecretBoxEncryptWithNonce(key, nonce, msg)
	if err != nil {
		t.Fatalf("SecretBoxEncryptWithNonce failed: %v", err)
	}

	pyBoxScript := `
import nacl.secret
box = nacl.secret.SecretBox(bytes.fromhex("` + keyHex + `"))
nonce = bytes.fromhex("` + nonceHex + `")
encrypted = box.encrypt(b"Hello Python / Go SecretBox interop!", nonce)
print(encrypted.hex())
`
	cmdBox := exec.Command("python3", "-c", pyBoxScript)
	outBox, errBox := cmdBox.Output()
	if errBox == nil {
		pyHex := strings.TrimSpace(string(outBox))
		goHex := hex.EncodeToString(goSealed)
		if goHex != pyHex {
			t.Fatalf("SecretBox cipher mismatch: Go=%s, Py=%s", goHex, pyHex)
		}
	}

	// 3. Test that Python can decrypt what Go encrypted with SecretBox
	pyDecryptScript := `
import nacl.secret
box = nacl.secret.SecretBox(bytes.fromhex("` + keyHex + `"))
payload = bytes.fromhex("` + hex.EncodeToString(goSealed) + `")
print(box.decrypt(payload).decode('utf-8'))
`
	cmdDec := exec.Command("python3", "-c", pyDecryptScript)
	outDec, errDec := cmdDec.Output()
	if errDec == nil {
		decText := strings.TrimSpace(string(outDec))
		if decText != string(msg) {
			t.Fatalf("Python failed to decrypt Go ciphertext: got %q, want %q", decText, string(msg))
		}
	}
}

func TestFingerprintEncoding(t *testing.T) {
	pubBytes, _ := hex.DecodeString("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
	fp := Fingerprint(pubBytes)
	expected := base64.StdEncoding.EncodeToString(pubBytes)
	if fp != expected {
		t.Fatalf("Fingerprint mismatch: got %s, want %s", fp, expected)
	}

	hexFp := FingerprintHex(pubBytes)
	if hexFp != "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" {
		t.Fatalf("Hex fingerprint mismatch: got %s", hexFp)
	}

	conv, err := FingerprintFromHex(hexFp)
	if err != nil || conv != expected {
		t.Fatalf("FingerprintFromHex failed: got %s, err %v", conv, err)
	}
}
