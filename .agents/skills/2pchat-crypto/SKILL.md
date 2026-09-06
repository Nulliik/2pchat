---
name: 2pchat-crypto
description: Review and implement cryptographic and secure-session code in 2PChat without inventing primitives or silently changing protocol semantics.
---

# 2PChat Cryptography

Treat protocol state as security-critical state.

## Review

Check:
- primitive choice
- key generation
- randomness
- nonce uniqueness
- KDF/domain separation
- authentication
- associated data
- identity binding
- replay protection
- state transitions
- persistence
- crash recovery
- concurrency

## X3DH

Verify identity keys, signed prekeys, one-time prekeys, signatures, DH composition, transcript/associated data and prekey lifecycle.

## Double Ratchet

Verify root/chain/message keys, DH ratchet, counters, skipped keys, out-of-order messages, replay handling, persistence, crash recovery and concurrency.

## Rules

Never invent a primitive.
Never replace a primitive silently.
Prefer established libraries and published test vectors.
When possible, cross-check outputs against independent implementations.
