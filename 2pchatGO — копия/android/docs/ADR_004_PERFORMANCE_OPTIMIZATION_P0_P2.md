# ADR-004: Performance Optimization Architecture (P0 - P2)

- **Status**: Accepted
- **Date**: 2026-09-07
- **Authors**: 2PChat Core & Android Engineering
- **Context**: Production profiling on Pixel 7a (Tensor G2) & Pixel 9 Pro Emulator (Android 35/36)

---

## 1. Context & Problem Statement

Prior to this milestone, comprehensive profiling across four independent tools (**Perfetto**, **Simpleperf**, **HPROF**, and **Live View ASDB**) identified four critical performance bottlenecks:

1. **P0: Keystore Synchronization Freeze on Main Thread (495 ms)**
   - Touching UI elements invoked `P2PPreferences` which synchronously accessed `AndroidKeyStore` / `MasterKey` via IPC to the Android `keystore` daemon, causing noticeable UI freezes (495 ms) and missed frames.
2. **P1a: Tracker Diagnostic Churn (67.75 MB / 2,868 calls)**
   - High-frequency P2P tracker discovery updates triggered regex replacements, JSON serialization, and disk-persisted `SharedPreferences` writes on every single event.
3. **P1b: Group Refresh Avalanche (42.87 MB / 11,013 calls)**
   - Unthrottled group timeline refresh triggered cascading database queries and memory re-allocations on rapid incoming group events.
4. **P2: Decrypt-on-Read Allocation Churn (55 MB / 1,392 operations)**
   - Every bind or scroll in `ChatMessageList` re-decrypted text, attachment metadata, and empty reaction JSON objects `"{}"` via AES-GCM / SQLCipher, causing garbage collection thrashing.

---

## 2. Decision & Architecture Changes

### P0: Asynchronous Keystore Provider & In-Memory Prewarming
- **Decision**: Decouple Android KeyStore interactions from the UI thread completely.
- **Implementation**:
  - Introduced `KeystoreProvider` with background `CompletableDeferred<MasterKey>` initialization running on `Dispatchers.IO`.
  - Added early lifecycle prewarming in `GlobalApplication.onCreate()`.
  - Cached the decrypted database passphrase in memory (`cachedDbPassphrase`) with immediate zeroization on wipe.

### P1: In-Memory Throttling & Debouncing
- **Decision**: Decouple high-frequency diagnostic and network events from persistent storage.
- **Implementation**:
  - `P2PPreferences`: Moved live tracker status to a thread-safe `ConcurrentHashMap` with a 2-second debounced batch write to disk. Pre-compiled regex patterns to eliminate string allocation churn.
  - `GroupChatCoordinator`: Implemented a conflated atomic dirty-flag pattern with a 200 ms debounce window (`refreshJob`) to coalesce rapid group updates into single atomic database reads.

### P2: Multi-Vector Decrypt-on-Read Optimization & MessageCache
- **Decision**: Optimize read paths and introduce bounded ephemeral in-memory caching.
- **Implementation**:
  - **Vector 1 (Bypass)**: Added `safeDecOrNull` / `safeDecOrEmpty` in `ChatDatabaseHelper`. SQL `NULL`, empty strings, and non-prefixed fields skip decryption. Normalized empty reaction JSONs to SQL `NULL` on write.
  - **Vector 2 (Zero-Copy Base64)**: Optimized `StringCipher.decrypt()` to decode Base64 directly by ASCII byte offset (`PREFIX.length`), eliminating intermediate prefix-stripped string allocations, followed by immediate zeroization of raw byte arrays.
  - **Vector 3 (MessageCache)**: Created a bounded LRU cache (`maxSize = 256` items, ~1-2 MB footprint) in `MessageCache`. Identical messages return cached instances in 0 ms / 0 allocs; status updates use copy-on-write without re-decrypting body/media.
  - **Security Invariants**: Strictly transient (never serialized to disk), auto-cleared on chat close, database close, and zeroized on account wipe.

---

## 3. Measured Performance Validation

Verification was performed comparing baseline traces with post-optimization traces:

| Metric / Dimension | Baseline (Pre-P0) | Post-Optimization (P0-P2) | Impact | Source |
| :--- | :--- | :--- | :--- | :--- |
| **Keystore UI Freeze (Main Thread)** | **495.36 ms** | **0.00 ms** | **-100% (Eliminated)** | Perfetto Trace |
| **P2PPreferences on Main Thread** | ~240 ms / call | **0.02 – 0.05 ms** | **~5000x faster** | Perfetto Trace |
| **Tracker Status Heap Churn** | 2,868 calls (67.75 MB) | **20 active lambdas, 36 items** | **>99% reduction** | HPROF Heap Dump |
| **Group Refresh Avalanche** | 11,013 calls (42.87 MB) | Coalesced into 200 ms batching | **>99% reduction** | HPROF & Logs |
| **Message Decrypt On Read** | 55 MB / 1,392 ops | **~90% bypass** via `MessageCache` | **Zero allocations on hit** | Simpleperf & HPROF |
| **Active Chat Message Objects** | Hundreds | **27 instances in heap** | **Clean heap footprint** | HPROF Heap Dump |
| **Dropped Frames (Jank)** | 88 dropped frames | **64 dropped frames** | **-27.3% reduction** | Perfetto Actual Frames |
| **Java Heap Range** | High GC churn | **26 – 47 MB** steady state | **Zero STW GC pauses** | Live View ASDB |

---

## 4. Performance Baseline Specification

Future pull requests and continuous integration performance regression tests should maintain the following budget:

```yaml
performance_budget:
  keystore_main_thread_max_ms: 0.0
  prefs_main_thread_max_ms: 0.5
  max_tracker_heap_objects: 100
  message_cache_hit_rate_min: 0.85
  java_heap_steady_state_max_mb: 60.0
  dropped_frames_per_standard_session_max: 70
```

---

## 5. Security & Compatibility Review

- **Zeroization**: All intermediate crypto buffers in `StringCipher` and cached database passphrases are overwritten with zeros via `SecurityUtils.zeroize()`.
- **Zero-Knowledge**: No plaintext messages are ever cached to persistent storage. In-memory cache is bounded to 256 messages and immediately flushed on lock/wipe.
- **Protocol & DB Compatibility**: Fully backward compatible with existing SQLite/SQLCipher databases; handles both legacy and newly formatted rows transparently.
