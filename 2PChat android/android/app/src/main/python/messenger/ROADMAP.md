# Project Roadmap

This document tracks the current state of the encrypted messenger scaffold and the
next set of milestones.

## Delivered
- **Transport abstraction** with direct IPv4/IPv6 and Yggdrasil-compatible transports
  (`direct`, `ygg`) plus an embedded Yggdrasil launcher (`ygg-embedded`)
  for self-contained setups.
- **End-to-end encryption** using X25519 key exchange and SecretBox symmetric
  encryption, applied to JSON/CBOR protocol messages.
- **Session framing** that handles handshakes, encrypted frames, and message delivery
  over asyncio streams.
- **Transport registry and CLI** allowing users to listen or connect with pluggable
  transports and optional embedded Yggdrasil runtime parameters.
- **Reliability and delivery guarantees**: message IDs with acknowledgements,
  retries, exponential backoff tuning, and an optional offline outbox that is
  replayed after reconnects.
- **Identity and trust**: persisted X25519 identity keys, printable fingerprints,
  trust-on-first-use store for peer verification, and user labels with conflict
  warnings when fingerprints change.
- **Tooling and tests** including pytest coverage for protocol/crypto/transport
  behaviors and flake8 linting configuration.
- **GUI foundation**: background chat controller for UIs and a Kivy-based chat
  window with presence chips, nickname support, and a settings dialog that wraps
  transport/session options (including rendezvous and embedded Yggdrasil).

## Planned
- **Reliability hardening**: tune resend/backoff policies per transport defaults,
  and add smarter offline queuing (e.g., prioritization, size limits).
- **Identity UX**: surface trust/label status in the GUI and export/import
  identities and trust stores.
- **Transport expansion**: Tor/I2P/SOCKS support plus serial/radio transports for
  Meshtastic/LoRa/Bluetooth.
- **Messaging features**: file transfer framing, typing indicators, and message
  history persistence.
- **User interfaces**: a Kivy GUI (Android/Windows) built on the existing transport
  and session layers.
- **Operational hardening**: packaged binaries, configuration templates, and continuous
  integration to validate transports and crypto on multiple platforms.

## Security gap analysis (vs. must-have list)
The current stack covers several core items (X25519 ECDH with forward secrecy,
HKDF-based separation, replay protection, and TOFU fingerprints), but the
following gaps remain and should be evaluated/implemented:

- **AES-GCM option**: add an AES-GCM AEAD path (128/256) alongside SecretBox for
  environments that require NIST-tracked primitives.
- **Authenticated identity signatures**: layer ECDSA/Ed25519-style signing of
  session handshakes/messages to bind long-term identity keys beyond TOFU
  fingerprints.
- **Explicit MITM verification UX**: strengthen out-of-band safety-number
  checks (QR/text compare) and require confirmation on first contact before
  trusting a new fingerprint.
- **Session IDs and key versioning**: attach explicit session identifiers,
  salt/context labels, and key version tags in encrypted headers for rotation
  and downgrade detection.
- **Rate limiting/DoS safeguards**: add per-connection rate caps, handshake
  timeouts, and resource budgets to blunt abuse.
- **Non-extractable key support**: design hooks for OS keystores/HSMs so
  long-term identity keys can be hardware-bound when available.
- **Sanitized logging**: review verbose/debug output to ensure no sensitive key
  material or plaintext is written even in verbose mode.
- **Optional ASN.1/point validation**: if future transports require DER/PEM
  parsing or alternative curves, include explicit validation and point checks.

### Tiered tasks queued for evaluation

- **Tier 1 — Identity & verification**
  - Ed25519 identity keypair generation plus local persistence aligned with the
    current config store.
  - Signed handshakes that bind identity keys to ephemeral X25519 keys with an
    explicit handshake version with signed-only validation.
  - Deterministic fingerprints/safety numbers and QR payloads for out-of-band
    verification, with trust states progressing from `unknown` → `tofu` →
    `verified` and warnings on key changes.
- **Tier 2 — Session & DoS hardening**
  - Session IDs and key-version counters embedded in encrypted metadata with
    downgrade rejection and HKDF context labels per version.
  - Handshake rate limiting (per peer and global) enforced before heavy crypto.
  - Reconnect storm suppression via exponential backoff per peer, reset on
    success.
  - Logging hygiene pass to redact keys/nonces/plaintext while retaining length
    and version diagnostics.
- **Tier 3 — Transport-aware refinements**
  - Transport-specific resend/backoff tuning and congestion awareness.
  - Optional offline outbox policy per transport latency/MTU profile.
