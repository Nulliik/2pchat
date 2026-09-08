# 2PChat Engineering & Repository Agent Rules

## Mission

2PChat is a security-critical peer-to-peer messaging application.

Potential components include:
- Android / Kotlin / Jetpack Compose
- Go Core
- JNI
- P2P networking
- local persistence
- X3DH
- Double Ratchet
- cryptographic primitives
- authentication and identity
- protocol serialization

Treat cryptography, protocol state, JNI, persistence, networking, authentication and trust decisions as high-risk code.

Security, correctness, compatibility and verifiability take priority over speed.

## Mandatory Security Change Protocol

Before changing security-sensitive code, determine:
1. threat model
2. security assumptions
3. invariants
4. trust boundaries
5. attack surface
6. compatibility impact
7. failure modes
8. relevant callers/callees
9. relevant tests

Inspect:
- callers
- callees
- serialization
- persistence
- concurrency
- JNI boundaries
- error handling
- configuration
- tests

Never infer security from compilation alone.

Never invent cryptographic algorithms or silently replace primitives.
Prefer established, audited primitives and mature libraries.

## Android

Before changing Android/Kotlin/Compose code inspect:
- Gradle configuration
- modules/source sets
- AndroidManifest.xml
- build variants
- dependencies
- R8/ProGuard rules
- permissions
- exported components
- intents/deep links
- lifecycle
- coroutines/dispatchers
- JNI bindings
- persistence

Relevant validation:
```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Run instrumentation/UI tests when applicable.

## Go Core

For relevant Go changes:
```bash
go test ./...
go test -race ./...
go vet ./...
```

Also use when available:
```bash
staticcheck ./...
govulncheck ./...
```

Inspect goroutines, channels, locks, atomics, cancellation, ownership, lifetime, blocking and shutdown behavior.

## JNI

For JNI changes inspect both sides:
Kotlin/Java -> JNI -> Go.

Check:
- ownership/lifetime
- native handles
- references
- thread attachment
- callbacks
- exceptions
- error propagation
- synchronization
- cancellation
- shutdown
- use-after-free
- double-free
- stale handles

Never hide a native crash by swallowing errors.

## Cryptography

Never invent custom crypto.

For crypto changes verify:
- algorithm
- key generation
- randomness
- nonce uniqueness
- KDF inputs
- domain/key separation
- authentication
- associated data
- identity binding
- replay protection
- state transitions
- persistence
- crash recovery
- key lifecycle/erasure where applicable

For X3DH inspect identity keys, signed prekeys, one-time prekeys, signatures, DH composition, transcript/associated data and prekey exhaustion.

For Double Ratchet inspect root key, sending/receiving chains, message keys, DH ratchet, skipped keys, counters, replay/out-of-order behavior, persistence and concurrent state access.

Do not change protocol semantics silently.

## Networking

Treat all remote input as attacker-controlled.

Inspect:
- framing
- parsing
- serialization
- authentication
- replay
- ordering
- duplicates
- partial I/O
- size limits
- timeouts
- reconnects
- resource exhaustion
- TLS/DNS behavior where applicable

## Serialization

Check:
- lengths
- integer overflow
- truncation
- duplicate fields
- unknown fields
- canonicalization
- versioning
- backward/forward compatibility
- parser discrepancies

## Testing

Use the relevant:
- unit tests
- integration tests
- E2E tests
- negative tests
- malformed-input tests
- concurrency/race tests
- protocol interoperability tests
- cryptographic test vectors
- regression tests

Do not disable tests, security checks or race detection merely to obtain a green build.

## Repository Test Protocol

If `messenger/requirements.txt` exists, install its dependencies before Python tests using the repository's documented environment/package manager.

Default Python validation:
```bash
pytest
```

Do not assume pytest is the only suite. Determine affected components and run their validators.

Never claim a command passed unless it was actually executed.

If validation cannot be performed, report:
`NOT VERIFIED`

and explain why.

## Git/Diff

Before finalizing:
```bash
git status
git diff
```

Check for:
- secrets
- private keys
- tokens
- credentials
- debug code
- generated artifacts
- unrelated changes
- weakened tests/security checks

## Security Finding Format

Every finding must contain:

Severity:
Confidence:
Title:
Affected Component:
Attack Surface:
Preconditions:
Attacker Model:
Exploitability:
Impact:
Root Cause:
Technical Explanation:
Proof / Evidence:
Recommended Remediation:
Regression Test:
Compatibility Impact:
Residual Risk:

Do not present speculation as confirmation.

## Completion Criteria

Work is complete only after:
1. implementation review
2. architecture review
3. diff review
4. relevant tests
5. relevant static analysis
6. relevant security checks
7. compatibility review
8. error-handling review
9. concurrency review
10. JNI review when applicable
11. crypto invariant review when applicable
12. secret/debug scan

Final report:
- Summary
- Files Changed
- Security Impact
- Compatibility
- Validation (commands actually run)
- Remaining Risks

Golden rule:
security + correctness + verifiability > speed.
