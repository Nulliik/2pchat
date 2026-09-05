# ADR 003: Unified Test Taxonomy, CI Gate, and Build Reproducibility

## Status
**Accepted as a target policy** (2026-08-31). Implementation status reviewed 2026-09-05.

The release workflow currently assembles a debug APK and does not execute the mandatory gates below. The table describes the intended policy, not existing CI enforcement.

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
The source configuration currently declares the following values; the CI Go setup still differs (1.22). See [Android README](../README.md) for exact source files and build limitations:
- **JDK**: Eclipse Temurin 17 LTS
- **Go**: 1.26.3 (`core-go/go.mod`)
- **Gradle**: 9.5.0
- **Android Gradle Plugin (AGP)**: 9.3.1
- **Android NDK**: 26.3.11579264 (targeting Android API 24)
- **Python**: Chaquopy runtime defaults to 3.11; desktop tests were run with 3.10.11 on 2026-09-05. No desktop Python pin is declared.

### 4. Release Promotion Gate
Target policy for production promotion (not yet enforced by the release workflow):
1. The full Python unit & interop suite passes; test counts are recorded per run.
2. Go Core unit & race tests pass with 0 data races.
3. Android unit tests pass with 0 errors.
4. APK compiles and passes dex/native-lib packaging validation.
