# ADR 001: Primary Android tree

Status: accepted 2026-08-31; retirement decision updated 2026-09-09.

## Decision

`2pchatGO/android` is the primary Android client and the target of `.github/workflows/android-release.yml`.

`2PChat android/android`, the previous Chaquopy client, is deprecated and removed as of 2026-09-09 to reduce checkout size and retire the duplicate Android implementation. Its source remains in Git history; history is not rewritten. The Python desktop client remains in `messenger/`.

## Historical comparison before removal

| Area | Previous client | Primary client |
| --- | --- | --- |
| Native integration | Chaquopy, root Python package copied at build time | Go CGO/JNI, `lib2pcore.so` |
| UI and group runtime | Kotlin / Compose | Kotlin / Compose |
| Group storage | SQLCipher, schema v6 | SQLCipher, schema v7 |
| Group cryptography | Python bridge for signatures | Native Go bridge for signatures |
| Release CI | Not selected | Selected |

No speedup or stronger cryptographic guarantee follows solely from the choice of implementation language. Group encryption uses epoch AES-GCM/Ed25519, not per-member group ratchet chains or MLS.

## Consequences

Android feature work targets `2pchatGO/android`. References and build instructions now target that tree. The two tests parsing the removed Kotlin `PythonBridge` contract are retired; the independent Python discovery bridge smoke test is retained. This source removal introduces no wire-format or database migration and does not establish an upgrade path from installed Chaquopy clients. Build commands are maintained in [Android README](../README.md).
