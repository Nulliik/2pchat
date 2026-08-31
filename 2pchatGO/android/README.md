# 2PChat Android (Go Core Edition)

2PChat is a secure, serverless peer-to-peer messaging application featuring end-to-end encryption, multi-transport routing (Direct IPv4/IPv6, Tor v3, Yggdrasil, LAN), and high-performance native cryptographic core.

## Architecture

```text
┌────────────────────────────────────────────────────────┐
│             Android UI (Jetpack Compose)               │
│          Chat, Groups, Media, Settings, Themes         │
└───────────────────────────▲────────────────────────────┘
                            │ Kotlin Coroutines / StateFlow
┌───────────────────────────▼────────────────────────────┐
│      Android Relay Layer (P2PMessageRelay, DB)         │
│     SQLCipher (AES-256), Notifications, Room/SQLite    │
└───────────────────────────▲────────────────────────────┘
                            │ JNI / NativeBridge
┌───────────────────────────▼────────────────────────────┐
│            Native Go Core (lib2pcore.so)               │
│  Session Manager, X3DH / Double Ratchet, Noise         │
│  Adaptive Dialer, UDP/HTTP Trackers, Streaming Engine  │
└────────────────────────────────────────────────────────┘
```

## Quick Start & Build

### 1. Build Go Core
```bash
cd core-go
go build ./...
go test ./...
```

### 2. Build Android APK
```bash
./gradlew assembleDebug
```
The resulting debug APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Run Tests
- **Go Unit Tests**: `cd core-go && go test -v ./...`
- **Android Unit Tests**: `./gradlew testDebugUnitTest`
- **Python Compatibility Tests**: `python -m pytest messenger/tests/ -m "not live_network"`

## Documentation & Architecture Decision Records
- [ADR 001: Primary Android Tree](docs/ADR_001_PRIMARY_ANDROID_TREE.md)
- [Full Project Audit Report](FULL_PROJECT_AUDIT_REPORT.md)
- [Engineering Rules & Quality Invariants](RULES.md)
- [Security Policy](SECURITY.md)
