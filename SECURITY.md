# Security Policy

## Reporting a Vulnerability

The 2PChat team takes the security of our peer-to-peer messaging system and users' privacy extremely seriously. We appreciate any responsible disclosure of security vulnerabilities from the community and security researchers.

### How to Report

Please **do NOT report security vulnerabilities via public GitHub issues, discussions, or pull requests.**

Instead, please report security vulnerabilities using one of the following methods:

1. **GitHub Private Vulnerability Reporting:**
   Use the **"Report a vulnerability"** button under the **Security** tab of this repository.

2. **Confidential Security Advisory:**
   Open a private draft security advisory to coordinate remediation and patch verification with the maintainers.
   - Please include detailed steps to reproduce the issue, proof of concept (PoC), or trace logs.
   - Mention the affected platforms (Android, Go Core CLI, etc.) and versions.

### Scope

In-scope security topics:
- **Cryptographic implementations:** Double Ratchet, X3DH, Noise protocol, Key Exchange, Zeroization of keys in RAM.
- **Network & Protocol Parsers:** Framing, KCP/UDP deframing, BitTorrent BEP 15 Bencode decoding, STUN/UPnP handling.
- **Local Storage Security:** SQLCipher AES-256 database protection, Android Keystore integration, EncryptedSharedPreferences.
- **Privacy & Anonymity:** Tor v3 onion routing leaks, Yggdrasil mesh privacy, EXIF / metadata sanitization, Logcat data leaks.
- **Memory Safety & IPC:** JNI bridge memory boundaries, Android exported component isolation.

### Response SLA & Timeline

- **Initial Response / Triage:** Within 48 hours.
- **Status Update:** Regular updates as we reproduce and prepare a patch.
- **Patch Release:** Security fixes are prioritized and released in emergency patch builds before public disclosure.
- **Credit & Hall of Fame:** With your permission, we will acknowledge your contribution in our release notes and Security Hall of Fame.

---

## Supported Versions

| Version | Supported |
| :--- | :--- |
| `1.4.x` (Go Core v1.4 / Android) | :white_check_mark: |
| `< 1.4.0` | :x: |

---

## Security Invariants & Hardening Guidelines

2PChat enforces the following security invariants across its codebase:
1. **Zero-Trust Networking:** All peer communications are End-to-End Encrypted (E2EE) using Double Ratchet session keys. Trackers and relays only see obfuscated hashes and blind tokens.
2. **Memory Hygiene:** Sensitive private keys, pre-keys, and intermediate Diffie-Hellman secrets are zeroized in RAM immediately after use.
3. **No Central Servers:** No central authentication, message storage, or contact directories exist. All discovery is decentralized (BEP 15, mDNS, Tor v3, Yggdrasil).
