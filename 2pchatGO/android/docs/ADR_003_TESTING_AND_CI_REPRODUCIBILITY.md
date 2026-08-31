# ADR 003: Unified Test Taxonomy, CI Gate, and Build Reproducibility

## Status
**Accepted** (2026-08-31)

## Context
As 2PChat expanded into a multi-language, multi-platform architecture (Python desktop client, Go native cryptographic/transport core, and Kotlin Android application), test suites and CI checks diverged:
1. Some tests hardcoded static ports (e.g. `50123`), causing intermittent test failures on Windows and multi-tenant CI runners.
2. Live tracker network tests occasionally failed during internet outages, introducing CI flakiness.
3. Toolchain versions (JDK, Go, NDK, Gradle) varied across developer workstations, leading to non-reproducible build artifacts.

## Decision

### 1. Test Taxonomy & Classification
We structure all test suites into distinct, standardized categories:

| Category | Scope | Execution Target | Gate Requirement |
|---|---|---|---|
| **`unit`** | Isolated algorithmic, crypto, framing, and utility logic | `go test ./pkg/...`, `./gradlew testDebugUnitTest`, `pytest -m "not live_network"` | **Mandatory PR Gate (100% PASS)** |
| **`integration-local`** | Cross-component IPC, in-memory `net.Pipe()`, simultaneous dialing tie-break | `go test -race ./pkg/session/... ./pkg/bridge/...` | **Mandatory PR Gate (100% PASS)** |
| **`android-unit`** | ViewModels, AppLog redaction, encryption migration, repository layers | `./gradlew testDebugUnitTest` | **Mandatory PR Gate (100% PASS)** |
| **`fuzz`** | Wire protocol framing and crypto deserialization under hostile random inputs | `go test -fuzz=Fuzz ./pkg/transport ./pkg/crypto` | **Nightly / Continuous** |
| **`integration-live`** | External BEP 15 UDP trackers, public rendezvous relays | Opt-in via `P2PCHAT_RUN_LIVE_TRACKER_TESTS=1` | **Nightly / Scheduled** |

### 2. Dynamic Port & Resource Cleanup Invariant (Rule QA-01)
- All network and transport tests MUST bind to port `0` (`net.Listen("tcp", ":0")` or `net.ListenUDP("udp4", &net.UDPAddr{Port: 0})`).
- Every test allocating network listeners, files, or background goroutines MUST register teardown via `t.Cleanup(func() { ... })`.

### 3. Toolchain Pinning for Reproducibility
All local builds and CI environments adhere to the pinned toolchain baseline:
- **JDK**: Eclipse Temurin 17 LTS
- **Go**: 1.22+
- **Gradle**: 8.11.1
- **Android Gradle Plugin (AGP)**: 8.8.0
- **Android NDK**: 26.3.11579264 (clang24 targeting minSdk 24)
- **Python**: 3.11+

### 4. Release Promotion Gate
A production/release APK is assembled and signed **only** after:
1. Python unit & interop tests pass (205+ tests).
2. Go Core unit & race tests pass with 0 data races.
3. Android unit tests pass with 0 errors.
4. APK compiles and passes dex/native-lib packaging validation.
