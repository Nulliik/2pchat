# ADR 002: JNI Bridge Threading, Memory Safety, and Deadlock Contract

## Status
**Accepted** (2026-08-31)

## Context
2PChat uses a hybrid architecture combining a high-performance native Go core (`lib2pcore.so`) with a modern Kotlin Jetpack Compose Android UI. Communication between Go and Kotlin crosses the Java Native Interface (JNI) barrier via CGO. 

Improper JNI handling can lead to critical defects:
1. **Thread leaks**: If a background Go runtime OS thread attaches to the JVM via `AttachCurrentThread` but fails to call `DetachCurrentThread` upon termination, the JVM leaks thread local storage and thread objects.
2. **Deadlocks (Cross-runtime lock inversion)**: If Go holds a Go mutex while executing a JNI callback, and the Kotlin callback synchronously calls back into a Go JNI method that acquires that same mutex, a deadlock occurs.
3. **Local reference table overflow**: JNI limits the local reference table (typically 512 entries). Failure to explicitly release `jstring` or `jbyteArray` in high-frequency loops crashes the process.
4. **JNI event storming**: High-bandwidth transfers emitting unbounded callbacks for every 64KB chunk saturate the JNI boundary and thrash the JVM garbage collector.

## Contract Specification

### 1. Thread Attachment & Detachment Lifecycle
- Any Go goroutine requiring JNI invocation calls `getJNIEnv(&attached)`.
- If the thread is not currently attached (`JNI_EDETACHED`), it attaches cleanly via `AttachCurrentThread`.
- When the callback completes, `releaseJNIEnv(attached)` detaches the thread if it was attached for that call (`attached == 1`).
- All JNI calls check and clear pending JVM exceptions using `checkAndClearException(env)`.

### 2. Lock Invariant & Callback Dispatching (Rule §6.2)
- **Invariant**: Go locks (such as `Manager.mu`, `SessionManager.mu`, or `callbacksMu`) MUST NEVER be held across JNI calls.
- **Pattern**:
  ```text
  Acquire Go Lock -> Snapshot Callbacks / Data -> Release Go Lock -> Invoke JNI Callback
  ```
- **Kotlin Dispatcher**: In `NativeBridge.kt`, JNI callbacks immediately dispatch execution to `bridgeScope.launch(Dispatchers.Default)` and return back to Go immediately, ensuring Kotlin UI or database locks never block Go network goroutines.

### 3. Local Reference Management
- Every JNI callback wrapper function in `jni_callbacks.c` explicitly deletes all allocated local references (`DeleteLocalRef(env, jStr)`, `DeleteLocalRef(env, jArr)`) before returning.

### 4. File Progress Throttling
- `OnFileProgress` is rate-limited at the Go transport layer to at most **100 ms** intervals or **1%** progress increments.
- The initial chunk (0%) and final completion chunk (100%) are guaranteed to be delivered immediately.
- Discarded intermediate progress updates do not allocate C strings or cross JNI.

## Consequences

These are implementation invariants and review requirements. They reduce attachment/reference leaks, lock reentrancy risk and callback pressure; they do not prove deadlock freedom, bounded memory under every workload or 60 FPS. Validate changes with Go concurrency tests, Android JNI tests and measured device workloads.

Build and validation commands: [Android README](../README.md).
