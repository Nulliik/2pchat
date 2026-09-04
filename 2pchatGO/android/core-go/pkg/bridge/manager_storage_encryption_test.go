package bridge

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"os"
	"path/filepath"
	"testing"
	"twopchat/core/pkg/crypto"
)

func TestStorageEncryption_EncryptedOnDisk(t *testing.T) {
	tempDir := t.TempDir()
	storageKey := make([]byte, 32)
	_, _ = rand.Read(storageKey)

	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
	}
	mgr.SetStorageKey(storageKey)
	mgr.SetStorageDir(tempDir)

	if err := mgr.Init(); err != nil {
		t.Fatalf("mgr.Init failed: %v", err)
	}

	fpOriginal := mgr.GetLocalFingerprint()
	if fpOriginal == "" {
		t.Fatalf("expected non-empty fingerprint")
	}

	// Verify identity_v1.key on disk
	idPath := filepath.Join(tempDir, "identity_v1.key")
	rawId, err := os.ReadFile(idPath)
	if err != nil {
		t.Fatalf("failed to read identity_v1.key from disk: %v", err)
	}

	// Must start with magic "2PK1"
	if len(rawId) < 4 || string(rawId[:4]) != "2PK1" {
		t.Fatalf("identity_v1.key is not encrypted with 2PK1 header: header=%q", rawId[:4])
	}

	// Ciphertext length: 4 (magic) + 24 (nonce) + 96 (data) + 16 (overhead) = 140 bytes
	if len(rawId) != 140 {
		t.Fatalf("unexpected encrypted identity file size: expected 140, got %d", len(rawId))
	}

	// Verify prekey_v1.key on disk
	prekeyPath := filepath.Join(tempDir, "prekey_v1.key")
	rawPrekey, err := os.ReadFile(prekeyPath)
	if err != nil {
		t.Fatalf("failed to read prekey_v1.key from disk: %v", err)
	}

	if len(rawPrekey) < 4 || string(rawPrekey[:4]) != "2PK1" {
		t.Fatalf("prekey_v1.key is not encrypted with 2PK1 header")
	}

	// Ciphertext length: 4 + 24 + 32 + 16 = 76 bytes
	if len(rawPrekey) != 76 {
		t.Fatalf("unexpected encrypted prekey file size: expected 76, got %d", len(rawPrekey))
	}

	// Verify persistence: create new manager with the same storage key and directory
	mgr2 := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
	}
	mgr2.SetStorageKey(storageKey)
	mgr2.SetStorageDir(tempDir)
	if err := mgr2.Init(); err != nil {
		t.Fatalf("mgr2.Init failed: %v", err)
	}

	fpRestored := mgr2.GetLocalFingerprint()
	if fpRestored != fpOriginal {
		t.Fatalf("fingerprint mismatch after restore: got %s, expected %s", fpRestored, fpOriginal)
	}

	// A stable fingerprint alone is insufficient: the restored signing key is
	// used to authenticate every X3DH prekey. This catches retaining a slice of
	// the decrypted buffer and then zeroizing that buffer during Init.
	signature := crypto.SignPreKey(mgr2.identity.Signing, mgr2.prekeyPub)
	transcript := append([]byte(crypto.SignedPrekeyContext), mgr2.prekeyPub.Bytes()...)
	if !ed25519.Verify(mgr2.identity.Verify, transcript, signature) {
		t.Fatal("restored identity cannot sign a valid prekey handshake")
	}
}

func TestStorageEncryption_LegacyPlaintextMigration(t *testing.T) {
	tempDir := t.TempDir()

	// 1. Manually write legacy unencrypted 96-byte identity and 32-byte prekey
	idKey, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatalf("failed to generate identity: %v", err)
	}
	expectedFp := crypto.Fingerprint(idKey.Public.Bytes())

	legacyIdData := make([]byte, 96)
	copy(legacyIdData[:32], idKey.Private.Bytes())
	copy(legacyIdData[32:96], idKey.Signing)

	idPath := filepath.Join(tempDir, "identity_v1.key")
	if err := os.WriteFile(idPath, legacyIdData, 0600); err != nil {
		t.Fatalf("failed to write legacy identity file: %v", err)
	}

	prekeyPriv, _, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatalf("failed to generate prekey: %v", err)
	}
	prekeyPath := filepath.Join(tempDir, "prekey_v1.key")
	if err := os.WriteFile(prekeyPath, prekeyPriv.Bytes(), 0600); err != nil {
		t.Fatalf("failed to write legacy prekey file: %v", err)
	}

	// 2. Initialize SessionManager with a new storage key
	storageKey := make([]byte, 32)
	_, _ = rand.Read(storageKey)

	mgr := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
	}
	mgr.SetStorageKey(storageKey)
	mgr.SetStorageDir(tempDir)

	if err := mgr.Init(); err != nil {
		t.Fatalf("mgr.Init failed during migration: %v", err)
	}

	// Fingerprint must match the legacy identity
	if mgr.GetLocalFingerprint() != expectedFp {
		t.Fatalf("migrated identity fingerprint mismatch: got %s, expected %s", mgr.GetLocalFingerprint(), expectedFp)
	}

	// 3. Verify that the files on disk were automatically rewritten as encrypted (2PK1)
	migratedIdRaw, err := os.ReadFile(idPath)
	if err != nil {
		t.Fatalf("failed to read migrated identity file: %v", err)
	}
	if len(migratedIdRaw) != 140 || string(migratedIdRaw[:4]) != "2PK1" {
		t.Fatalf("migrated identity file was not re-encrypted on disk (len: %d, header: %q)", len(migratedIdRaw), migratedIdRaw[:4])
	}

	migratedPrekeyRaw, err := os.ReadFile(prekeyPath)
	if err != nil {
		t.Fatalf("failed to read migrated prekey file: %v", err)
	}
	if len(migratedPrekeyRaw) != 76 || string(migratedPrekeyRaw[:4]) != "2PK1" {
		t.Fatalf("migrated prekey file was not re-encrypted on disk (len: %d)", len(migratedPrekeyRaw))
	}

	// 4. Verify that plaintext bytes are gone from the file
	if bytes.Contains(migratedIdRaw, legacyIdData) {
		t.Fatalf("migrated identity file still contains raw plaintext bytes")
	}
}

func TestStorageEncryption_RejectsMismatchedStorageKey(t *testing.T) {
	tempDir := t.TempDir()
	storageKey1 := make([]byte, 32)
	_, _ = rand.Read(storageKey1)

	// Initialize manager 1 with key 1
	mgr1 := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
	}
	mgr1.SetStorageKey(storageKey1)
	mgr1.SetStorageDir(tempDir)
	if err := mgr1.Init(); err != nil {
		t.Fatalf("mgr1.Init failed: %v", err)
	}

	// Try reading with different storage key
	storageKey2 := make([]byte, 32)
	_, _ = rand.Read(storageKey2)

	mgr2 := &SessionManager{
		sessions: make(map[string]*crypto.SessionState),
	}
	mgr2.SetStorageKey(storageKey2)
	mgr2.SetStorageDir(tempDir)

	// mgr2.readKeyFile should fail on decryption
	idPath := filepath.Join(tempDir, "identity_v1.key")
	_, err := mgr2.readKeyFile(idPath)
	if err == nil {
		t.Fatalf("expected readKeyFile to fail with incorrect storage key, but succeeded")
	}
}
