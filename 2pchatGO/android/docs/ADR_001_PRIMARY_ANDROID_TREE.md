# ADR 001: Primary Android tree

Status: accepted 2026-08-31; factual description updated 2026-09-05.

## Decision

`2pchatGO/android` is the primary Android client and the target of `.github/workflows/android-release.yml`.

`2PChat android/android` is the previous Chaquopy client. It remains in the repository for compatibility and comparison; the release workflow does not build it. The earlier statement that this tree was frozen is not an accurate description of the repository: it contains later group and compatibility changes.

## Comparison

| Area | Previous client | Primary client |
| --- | --- | --- |
| Native integration | Chaquopy, root Python package copied at build time | Go CGO/JNI, `lib2pcore.so` |
| UI and group runtime | Kotlin / Compose | Kotlin / Compose |
| Group storage | SQLCipher, schema v6 | SQLCipher, schema v7 |
| Group cryptography | Python bridge for signatures | Native Go bridge for signatures |
| Release CI | Not selected | Selected |

No speedup or stronger cryptographic guarantee follows solely from the choice of implementation language. Group encryption uses epoch AES-GCM/Ed25519, not per-member group ratchet chains or MLS.

## Consequences

Default Android feature work targets the primary tree. Compatibility work must explicitly identify whether both trees are involved. Do not remove the previous tree without migrating its references and tests. Build commands are maintained in [Android README](../README.md).
