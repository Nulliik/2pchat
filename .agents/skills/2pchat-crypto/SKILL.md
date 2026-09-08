---
name: 2pchat-crypto
description: Deep review of 2PChat cryptographic primitives and stateful ratchet implementation.
---

Inspect `core-go/pkg/crypto`.

Known surfaces:
- X25519
- Ed25519
- HKDF-SHA256
- HMAC-SHA256
- XSalsa20-Poly1305 SecretBox
- ChaCha20-Poly1305
- XChaCha20-Poly1305
- X3DH-style prekey initialization
- Double Ratchet
- group/sender keys
- encrypted backups
- Tor/onion crypto helpers

Never replace a primitive during review.

Required scenarios:
1. all-zero DH;
2. malformed keys;
3. signature mismatch;
4. transcript/context confusion;
5. role inversion;
6. skipped-key retrieval;
7. replay;
8. authentication failure must not commit candidate state;
9. ratchet transition;
10. restart/restore;
11. zeroization regression tests;
12. Python interoperability.

Use existing vectors/tests before creating new ones.
