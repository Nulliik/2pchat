# 2PChat Encrypted Profile Backup Format Specification (v2)

## 1. Abstract

This specification defines the authenticated binary format `2PBK` (v2) for exporting and importing 2PChat user profiles, identity keypairs, signed prekeys, profile avatar, and local configuration.

The format ensures:
- **Confidentiality:** 256-bit symmetric encryption using XChaCha20-Poly1305.
- **ASIC/GPU Resistance:** Key derivation via Argon2id (memory-hard KDF).
- **Tamper Resistance:** All header fields (KDF parameters, salt, nonce, fingerprint) are authenticated as Additional Authenticated Data (AAD).
- **Origin Authentication & Integrity:** The backup manifest is digitally signed using a domain-separated Ed25519 signing key derived via HKDF from the identity seed.
- **Cross-Account Protection:** The public fingerprint of the owner is bound in the authenticated header and validated upon import.

---

## 2. File Format Structure

A `.2pbackup` file consists of an unencrypted, authenticated binary header followed by an authenticated ciphertext payload:

```
+-------------------------------------------------------------------------+
|                        HEADER (Authenticated AAD)                       |
+-------------------------------------------------------------------------+
| Magic (4B)   : "2PBK" (0x32, 0x50, 0x42, 0x4B)                          |
| Version (1B) : 0x02                                                     |
| KDF Type (1B): 0x01 (Argon2id)                                          |
| Time (4B)    : uint32 (Big-Endian) = 3 iterations                       |
| Memory (4B)  : uint32 (Big-Endian) = 65536 KB (64 MB)                   |
| Threads (1B) : uint8 = 4 threads                                        |
| Salt (32B)   : Cryptographically random 32 bytes                        |
| Nonce (24B)  : Cryptographically random 24 bytes (XChaCha20)            |
| FP Len (2B)  : uint16 (Big-Endian) = Length of Fingerprint string       |
| FP (N B)     : UTF-8 Fingerprint string (Base64)                        |
+-------------------------------------------------------------------------+
|                        ENCRYPTED PAYLOAD (AEAD)                         |
+-------------------------------------------------------------------------+
| XChaCha20-Poly1305 Ciphertext (containing ZIP archive):                 |
|   - manifest.json                                                       |
|   - identity_v1.key (96 bytes)                                          |
|   - prekey_v1.key (32 bytes)                                            |
|   - profile_avatar.jpg (optional)                                       |
|   - settings.json                                                       |
+-------------------------------------------------------------------------+
| Poly1305 Authentication Tag (16B)                                       |
+-------------------------------------------------------------------------+
```

---

## 3. Cryptographic Primitives

### 3.1 Key Derivation Function (Argon2id)
- **Algorithm:** Argon2id (RFC 9106)
- **Salt:** 32 bytes from `crypto/rand`
- **Time Cost ($t$):** 3 passes
- **Memory Cost ($m$):** 64 MB (65,536 KiB)
- **Parallelism ($p$):** 4 threads
- **Key Length:** 32 bytes (256 bits)
- **Zeroization:** The derived symmetric key buffer is immediately wiped from memory (`crypto.Zeroize`) after encryption/decryption.

### 3.2 Authenticated Encryption (XChaCha20-Poly1305)
- **Algorithm:** XChaCha20-Poly1305 (IETF draft)
- **Key:** 32 bytes derived from Argon2id.
- **Nonce:** 24 bytes (192 bits) cryptographically random. The 192-bit nonce space eliminates the risk of nonce reuse under random generation.
- **Additional Authenticated Data (AAD):** The entire binary header:
  $$\text{AAD} = \text{Header}[0 \dots 73+N]$$
  If any byte in the header is modified (such as an attacker attempting to downgrade memory cost or tamper with the salt or fingerprint), Poly1305 verification fails and decryption aborts without disclosing plaintext.

### 3.3 Manifest Signing Key (Ed25519 with HKDF Domain Separation)
To prevent cross-protocol key reuse, the backup manifest is signed not directly with the raw primary identity key, but with a dedicated domain-separated backup signing key:
$$\text{BackupSigningSeed} = \text{HKDF-Expand}(\text{Extract}(\text{IdentitySeed}, \text{"2pchat-salt-v1"}), \text{"2pchat-backup-sign-v1"}, 32)$$
$$\text{BackupSigningKey} = \text{Ed25519.NewKeyFromSeed}(\text{BackupSigningSeed})$$

---

## 4. Manifest Schema (`manifest.json`)

```json
{
  "version": 2,
  "exported_at_ms": 1788734321000,
  "app_version": "0.0.9.1",
  "app_package": "com.example.twopchat.go",
  "nickname": "Alice",
  "fingerprint": "yS+...==",
  "seed_mnemonic": "word1 word2 ... word24",
  "tor_deterministic_enabled": true,
  "tor_onion_index": 3,
  "files": [
    { "name": "identity_v1.key", "sha256": "..." },
    { "name": "prekey_v1.key", "sha256": "..." },
    { "name": "profile_avatar.jpg", "sha256": "..." }
  ],
  "backup_verify_pub": "...",
  "signature": "..."
}
```

The signature is computed over the canonical JSON string of the manifest without the `"signature"` field.

---

## 5. Security & Threat Model

| Threat | Defense |
| :--- | :--- |
| **Offline Dictionary / GPU Brute-Force** | Argon2id with 64 MB RAM and 3 iterations makes high-speed parallel GPU/ASIC attacks cost-prohibitive. |
| **Header Tampering & Downgrade Attack** | Header bytes are bound to the Poly1305 authentication tag as AAD. Changing memory to 1 MB causes MAC rejection. |
| **Cross-Account Identity Injection** | Fingerprint in header is matched against the local active account before import, prompting for explicit user override. |
| **Path Traversal in ZIP (`../evil`)** | Extraction validates that canonical paths are strictly contained within `targetDir.canonicalPath`. |
| **In-Memory Key Leakage** | All derived key arrays, salts, and secret buffers are zeroized (`defer crypto.Zeroize(...)`). |
| **Incomplete File Write Corruption** | Export writes to a temporary file in internal cache before atomic copy to destination. |
| **Denial of Service / Memory Pressure** | Payload size is capped at 15 MB. Payloads exceeding this limit are rejected before processing. |

---

## 6. Backward Compatibility with v1

- When importing a file starting with `PK\x03\x04` (ZIP magic), `ProfileBackupManager` detects it as `BackupFormat.V1_PLAINTEXT_ZIP`.
- The system logs a security warning (`SafeLog.w`) and displays an alert informing the user that the file is unencrypted, advising them to re-export as `2PBK` v2.

---

## 7. Known Limitations

- **Deterministic Tor Onion Addresses**:
  Deterministic Tor onion addresses derived from the account seed via HKDF domain separation (`2pchat-tor-v3-onion-seed-v1`) are tied to an integer rotation index. Deterministic Tor onion addresses are not included in mnemonic backup. Upon account restoration from a 24-word seed phrase, the onion rotation index resets to index 0. Contacts who saved a previously rotated onion address (e.g., index > 0) must obtain the user's updated onion address.

