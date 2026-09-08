# 2PChat v0.1.0 — Production-Grade P2P Messenger

## Release Highlights

2PChat is a secure, decentralized peer-to-peer messaging application operating without centralized relays or push gateways (no Google FCM, no Apple APNs). Version 0.1.0 marks the completion of production-grade security, resilient group protocol management, and robust mobile background persistence.

---

### Security & Privacy
- 🔐 **Encrypted Backup Format (2PBK v2)**: Argon2id key derivation + XChaCha20-Poly1305 AEAD authenticated encryption, deterministic headers, and authenticated export/import UX.
- 🧠 **Sensitive Memory Lifecycle Management**: Auto-purging of sensitive in-memory cryptographic materials and session keys on screen lock, background transitions, and account wipe.
- 🛡 **Authenticated Peer Discovery**: Ed25519-signed discovery records, monotonic counter-based anti-replay protection, and strict anti-SSRF IP filtering.
- 🧅 **Recoverable Tor Identity**: Deterministic onion key derivation with HKDF domain separation, index-based isolation, and manual rotation.

### Resilience & Protocol
- 👑 **Group Ownership Succession Protocol**: Automatic leadership failover with configurable heartbeat timeouts, verifiable succession certificates, and split-brain mitigation.
- 🔌 **Protocol Capability Negotiation**: Versioned capability bitmaps with graceful degradation and forward compatibility across protocol revisions.
- 🏭 **Nightly Soak Testing**: Continuous integration harness validating long-running multi-peer mesh network topologies (6h/night, 7d/week, 12-node mesh).

### Android Background Persistence (NEW in Task 4C)
- ✅ **WorkManager-based Outbox Drain**: Event-driven queue flushing with P2P-aware result semantics (`Result.success()` on offline peers, preventing battery-draining retry loops).
- ✅ **Succession Heartbeat Worker**: 12h periodic worker ensuring group owners emit signed heartbeats even during extended device sleep.
- ✅ **Headless Profile**: Minimal initialization profile in `GlobalApplication` for background workers (no Tor daemon, no Foreground Service, no UI prewarming).
- ✅ **Battery Optimization Whitelist UI**: Non-intrusive, dismissible banner with localized copy across 7 languages (`ru`, `en`, `de`, `es`, `fr`, `pt`, `tr`).
- ✅ **NetworkCallback & Doze Exit Integration**: Immediate outbox draining upon network reconnect or device wake from Doze maintenance windows.
- ✅ **Android 12–15 FGS Protection**: Hardened lifecycle boundaries preventing `ForegroundServiceStartNotAllowedException`.
