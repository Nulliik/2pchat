# ADR 001: 2pchatGO/android as the Primary Android Client

## Status
**Accepted** (2026-08-31)

## Context
The repository historically contained two Android client source trees:
1. `2PChat android/android` — Legacy client utilizing Chaquopy (embedded Python interpreter for networking).
2. `2pchatGO/android` — Modern client utilizing a compiled native Go core (`core-go/` compiled to `lib2pcore.so` via CGO/JNI).

Maintaining parallel Android trees introduces severe maintenance risks, divergence of security patches, ambiguity in CI release gates, and developer friction.

## Decision
We establish **`2pchatGO/android`** as the single authoritative, primary Android application for 2PChat.

The legacy `2PChat android` tree is hereby frozen and marked as archived.

## Comparison Matrix

| Dimension | Legacy Tree (`2PChat android/android`) | Primary Tree (`2pchatGO/android`) |
|---|---|---|
| **Core Architecture** | Python 3 (Chaquopy JNI embedding) | Native Go Core (`lib2pcore.so` via CGO/JNI) |
| **Performance & Threading** | Restricted by Python Global Interpreter Lock (GIL) | Native goroutines, 0% GIL, hardware-accelerated crypto |
| **Cryptography** | Python `cryptography` library / PyNaCl | Pure Go (`golang.org/x/crypto`), X3DH, Double Ratchet, memory zeroization |
| **Network Engine** | Python `asyncio` / socket loops | Adaptive Go dialer, BEP 15 UDP & HTTP trackers, LAN beacons, UPnP, STUN, hole punching |
| **Tor v3 Integration** | External binary spawn / SOCKS proxy | Tor control protocol integration + native fallback DNS |
| **Yggdrasil Integration** | Standalone process / Python bridge | User-space Proxy & VPN PacketTunnelProvider with Go interop |
| **Group Messaging** | Basic broadcast | Full Epoch-based Group Chat Coordinator with pairwise ratchet chains |
| **Storage & Security** | SQLite / SharedPreferences | SQLCipher (AES-256 encrypted database), encrypted SharedPreferences |
| **UI Framework** | Jetpack Compose + Material 3 | Jetpack Compose + Material 3, Motion system, Media viewer |
| **Application ID** | `com.example.twopchat` | `com.example.twopchat.go` / `com.example.twopchat` |
| **Active CI & Release** | Deprecated / Frozen | **Primary Release Target (`./gradlew assembleDebug` / `assembleRelease`)** |

## Consequences
- All new features, bug fixes, security hardening, and audits will be made strictly in `2pchatGO/android`.
- CI release pipelines build exclusively from `2pchatGO/android`.
- The legacy `2PChat android` directory remains in the repository solely for historical reference and is excluded from active build pipelines.
