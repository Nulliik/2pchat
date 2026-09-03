---
name: security-testing
description: Actively tests the 2PChat Android and Go codebase for security vulnerabilities using fuzzing, adversarial testing, crypto verification, JNI testing, concurrency testing, network testing, filesystem testing, and reproducible security proofs.
---

# 2PChat — Android Cybersecurity Testing & Red Team Skill v2.0

## 1. Mission

Act as the **CSO / Lead Penetration Tester / Application Security Engineer** for the 2PChat project.

Your job is to actively verify the security posture of the Android application and Go native core. Do not merely perform a checklist review. Construct executable attack scenarios, run tests against the real code where possible, collect evidence, minimize failures, reproduce them, and map findings to concrete security invariants.

Primary goals:

1. Discover real vulnerabilities.
2. Prove vulnerabilities with reproducible evidence.
3. Detect security regressions.
4. Identify hardening gaps when exploitation cannot be demonstrated.
5. Never claim a system is secure merely because a test did not crash.

The project is assumed to contain Android/Java/Kotlin code plus a Go native core connected through JNI or an equivalent native boundary.

---

## 2. Golden Rules

### Rule 1 — Actively test

Prefer:

- executing tests;
- writing temporary test harnesses;
- fuzzing;
- race testing;
- instrumentation;
- adversarial inputs;
- controlled network stress;
- static inspection only where dynamic testing is insufficient.

Do not stop at "the code looks safe."

### Rule 2 — No crash does not mean secure

A test that produces no crash is evidence only for the behavior actually exercised.

Never convert:

- `no crash` → `secure`;
- `successful decryption` → `nonce safe`;
- `binary-looking database` → `encrypted`;
- `configured Tor` → `no IP leak`;
- `logical race` → `native UAF`;
- `10,000 successful random values` → `cryptographically proven entropy`.

### Rule 3 — Failed reproduction is not proof of absence

If an issue cannot be reproduced, classify it as:

- `NOT_TESTED`;
- `BLOCKED`;
- `LIKELY_VULNERABILITY`;

as appropriate.

Do not mark it `SECURE` unless the relevant invariant was actually tested.

### Rule 4 — Preserve the protocol

Do not weaken authentication, encryption, authorization, replay protection, certificate/fingerprint validation, or privacy routing merely to make tests pass.

Do not redesign the protocol during a security test unless explicitly requested.

---

# 3. Threat Model

Test at least these attacker classes separately.

## T1 — Remote unauthenticated attacker

Capabilities:

- controls network traffic;
- sends malformed packets;
- opens many connections;
- delays/reorders/duplicates packets;
- attempts replay;
- attempts protocol downgrade;
- attempts resource exhaustion.

## T2 — Malicious authenticated peer

Capabilities:

- has a valid account/session;
- controls application-level protocol messages;
- sends malformed but authenticated data;
- attempts replay and state confusion;
- attempts path traversal/file abuse;
- attempts SSRF/local-network access if endpoints are peer-controlled.

## T3 — Malicious local Android application

Capabilities:

- invokes exported Android components;
- sends crafted intents;
- interacts with providers/services where permissions allow;
- attempts to access exposed files, logs, notifications, deep links, and IPC surfaces.

## T4 — Compromised/rooted device

Treat this as a separate trust boundary.

Do not claim protection against a fully compromised OS unless the architecture actually provides such guarantees.

Still test whether secrets are unnecessarily exposed through:

- logs;
- backups;
- world-readable files;
- debug interfaces;
- temporary files;
- crash dumps;
- IPC;
- exported components.

---

# 4. Security Invariants

Every serious test should map to one or more explicit invariants.

## Cryptography

- Nonces/IVs are unique where required.
- Nonces are generated from an appropriate CSPRNG or deterministic construction with the required uniqueness guarantee.
- Authentication tags detect ciphertext modification.
- Keys are not exposed through logs or unsafe storage.
- Key lifecycle is correct across creation, rotation, session termination, and errors.
- Randomness comes from cryptographically secure sources.
- Security-critical comparisons use constant-time mechanisms where required.

## Authentication / sessions

- Authentication cannot be bypassed or downgraded.
- Security-critical identity fields are bound to the authenticated transcript.
- Session state cannot be confused across peers.
- Old sessions/epochs cannot silently authenticate new traffic.
- Replay and reordering cannot violate protocol state.

## Protocol / network

- Malformed input cannot crash or corrupt the process.
- Length fields are validated before allocation.
- Integer overflow cannot produce unsafe allocation or indexing.
- Parser failures are bounded and fail closed.
- Authentication is enforced at the correct protocol boundary.
- Privacy routing cannot be bypassed by alternate network paths.

## JNI / native boundary

- Native handles cannot be used after destruction.
- Java references have correct lifetime.
- JNI calls occur on valid `JNIEnv*` contexts.
- Native threads are correctly attached/detached where required.
- Java exceptions are checked and handled.
- Callbacks cannot deadlock or re-enter unsafe state.
- Boundary inputs are validated.

## Concurrency

- Shared state has correct synchronization.
- Close/shutdown is idempotent.
- Concurrent start/stop/send/receive operations do not corrupt state.
- Goroutines and native resources do not grow without bound.
- Callback reentrancy cannot violate lifecycle invariants.

## Storage / filesystem

- Sensitive data is protected at rest according to the intended threat model.
- Logs do not expose secrets.
- Backups do not unintentionally expose secrets.
- File paths cannot escape the intended directory.
- Symlink/hardlink behavior cannot bypass confinement where relevant.
- File size/chunk metadata cannot cause unsafe resource consumption.

---

# 5. Test Record Lifecycle

For every meaningful security test, record:

1. **Scope**
2. **Threat model**
3. **Security invariant**
4. **Hypothesis**
5. **Preconditions**
6. **Attack/input**
7. **Execution command**
8. **Observed behavior**
9. **Evidence**
10. **Minimal reproduction**
11. **Classification**
12. **Root cause**
13. **Minimal remediation**
14. **Regression test**
15. **Residual risk**

Use exact commands and versions whenever possible.

---

# 6. Result Classification

Use these classifications consistently.

### `CONFIRMED_VULNERABILITY`

A security invariant is demonstrably violated and the issue is reproducible or strongly evidenced.

### `LIKELY_VULNERABILITY`

Strong evidence indicates a vulnerability, but complete exploitation or reproduction is blocked.

### `HARDENING_RECOMMENDED`

A weakness or missing defense was identified, but no concrete security impact was demonstrated.

### `FALSE_POSITIVE`

The suspected issue was investigated and the security invariant was shown to hold.

### `NOT_TESTED`

The relevant behavior was not actually exercised.

### `BLOCKED`

The test could not be completed because of environment/tooling/access limitations.

Never use `SECURE` as a substitute for these classifications. Use `SECURE` only in a final audit statement when the relevant invariant was actively tested and no material limitation remains.

---

# 7. Severity Model

Use:

- **Critical** — remote or low-privilege compromise, authentication bypass, major key compromise, catastrophic privacy/security failure.
- **High** — significant remote compromise, persistent unauthorized access, major confidentiality/integrity violation, serious privacy bypass.
- **Medium** — meaningful security impact requiring conditions or limited privileges.
- **Low** — limited impact, defense-in-depth weakness, or difficult-to-exploit issue.

Severity must be based on demonstrated impact and realistic attacker capabilities, not on how interesting the bug looks.

---

# 8. Vector 1 — Cryptography

## 8.1 Nonce uniqueness

Do not use "decryption succeeds" as the security criterion.

Test:

1. Generate many encryption operations under the same key.
2. Collect nonces/IVs.
3. Check for duplicates.
4. Verify the implementation prevents nonce reuse according to the primitive/protocol requirements.
5. Exercise retries, reconnects, process restarts, counter resets, concurrent encryption, and key rotation.

Particularly test:

- restart after persisted state loss;
- concurrent senders;
- reconnect;
- session recreation;
- counter wraparound;
- error/retry paths.

For AEAD/stream constructions, successful decryption after nonce reuse can be expected and does **not** prove safety.

## 8.2 Ciphertext tampering

For valid ciphertexts:

- flip individual bits;
- truncate;
- append bytes;
- modify authentication tags;
- modify headers/associated data;
- alter lengths.

Expected result:

- authentication failure;
- no plaintext release;
- no state corruption;
- no crash.

## 8.3 Handshake transcript binding

Mutate security-critical handshake fields independently:

- identity;
- peer identifier;
- ephemeral public key;
- fingerprint;
- protocol version;
- algorithm identifiers;
- role;
- transcript metadata.

Verify that fields which influence authentication are actually covered by the authenticated/signed transcript.

A valid signature on a partial transcript is not sufficient.

## 8.4 Randomness

Inspect the actual implementation.

Prefer evidence such as:

- `crypto/rand`;
- OS CSPRNG;
- secure platform APIs.

Flag use of:

- `math/rand`;
- deterministic seeds;
- timestamps as entropy;
- predictable counters where randomness is required.

A small collision experiment is supporting evidence only, not a proof of entropy quality.

## 8.5 Constant-time operations

Inspect security-critical comparisons involving:

- authentication tags;
- MACs;
- hashes;
- fingerprints;
- secret values.

Where timing side channels are relevant, use appropriate constant-time primitives.

## 8.6 Zeroization

Test explicit zeroization where the project requires it.

Important limitation:

> Observing one buffer become zero does not prove that all copies of a secret have disappeared from memory.

Also inspect:

- copies;
- slices;
- serialization buffers;
- logs;
- exceptions;
- temporary files;
- native/Java boundary copies.

---

# 9. Vector 2 — Protocol Parsing and Fuzzing

Use coverage-guided fuzzing for parsers and decoders.

For Go targets, use the native fuzzing framework where applicable, for example:

```bash
go test -fuzz=FuzzDecodeMessage ./pkg/session/...
```

Adapt the package and fuzz target to the actual repository.

## Corpus requirements

Include:

- empty input;
- one-byte input;
- truncated messages;
- invalid versions;
- invalid message types;
- maximum valid lengths;
- oversized lengths;
- zero lengths;
- integer boundaries;
- malformed serialization;
- duplicate fields;
- missing fields;
- invalid UTF-8;
- embedded NUL;
- invalid authentication tags;
- nested structures;
- repeated structures.

## Required checks

Verify:

- no panic;
- no unsafe allocation;
- no integer overflow;
- no out-of-bounds access;
- no unbounded recursion;
- no unbounded memory growth;
- no parser state corruption.

For every discovered crash:

1. minimize the input;
2. reproduce it;
3. save the minimized corpus entry;
4. create a regression test;
5. classify severity.

---

# 10. Vector 3 — Network and DoS

Separate **parser fuzzing** from **network stress**.

## Stress scenarios

Test:

- many simultaneous connections;
- rapid connect/disconnect;
- slow senders;
- slow readers;
- oversized frames;
- invalid packets;
- repeated authentication failures;
- repeated handshake attempts;
- idle connections;
- concurrent reconnect storms.

Measure:

- CPU;
- memory;
- goroutine count;
- open file descriptors;
- socket count;
- connection count;
- latency;
- disk usage;
- queue sizes.

## Resource exhaustion

Explicitly test:

- memory exhaustion;
- CPU exhaustion;
- connection exhaustion;
- goroutine exhaustion;
- file descriptor exhaustion;
- disk exhaustion;
- queue/buffer exhaustion.

Look for:

- missing limits;
- attacker-controlled allocations;
- unbounded queues;
- goroutine-per-packet leaks;
- retry storms;
- expensive operations before authentication.

---

# 11. Vector 4 — Replay and Session State

Do not assume that a single `MessageIndex` check proves replay protection.

Test:

```text
1 2 3 2 4
```

and:

```text
1 3 2 4
```

Also test:

- exact ciphertext replay;
- replay after reconnect;
- replay from an old session;
- replay from an old ratchet/epoch;
- delayed packets;
- duplicate packets;
- reordered packets;
- duplicated control messages;
- duplicated file chunks;
- missing chunks;
- stale session handles.

Define the invariant first:

> What exactly makes a message acceptable once, and what makes it invalid after that?

Then test the implementation against that invariant.

---

# 12. Vector 5 — Tor and Privacy Routing

Do not equate "Tor configured" with "privacy verified."

Test all relevant paths:

- TCP;
- SOCKS5 TCP;
- UDP;
- DNS;
- STUN;
- UPnP;
- IPv4;
- IPv6;
- localhost;
- link-local;
- direct sockets;
- fallback network paths.

Verify that privacy-sensitive operations cannot silently bypass the intended privacy route.

If a required privacy route is unavailable, prefer **fail closed** rather than silently falling back to direct networking.

Check for:

- direct DNS;
- direct STUN;
- direct UDP;
- UPnP discovery;
- direct IPv6;
- localhost connections;
- alternate HTTP/network stacks;
- hardcoded IP connections.

---

# 13. Vector 6 — JNI / Native Boundary

Test both correctness and security.

## Input boundary

Use:

- `null`;
- empty strings;
- empty arrays;
- maximum lengths;
- oversized inputs;
- invalid UTF-8;
- embedded NUL;
- negative values;
- zero;
- maximum integers;
- malformed handles;
- stale handles;
- repeated close;
- wrong lifecycle order.

## JNI correctness

Inspect/test:

- `JNIEnv*` thread affinity;
- thread attach/detach;
- `GlobalRef` lifetime;
- local-reference growth;
- Java exception propagation;
- callback lifetime;
- native handle ownership;
- native object destruction;
- reentrant callbacks;
- Java/native lock ordering.

A native call made from the wrong thread or with an invalid JNI environment can cause failures that ordinary unit tests miss.

---

# 14. Vector 7 — Memory Safety

Distinguish carefully between:

### Go logical lifetime races

Examples:

- use of a closed channel/object;
- stale session state;
- concurrent shutdown;
- goroutine lifecycle races.

### Actual native memory safety

Examples:

- use-after-free;
- double-free;
- invalid pointer;
- buffer overflow;
- invalid JNI reference.

Do not label a normal Go lifecycle race as a C/C++ UAF unless native memory ownership is actually involved.

Where available, use:

- Go race detector;
- sanitizers for native code;
- debug allocators;
- crash dumps;
- minimized reproductions.

---

# 15. Vector 8 — Concurrency and Lifecycle

Stress operations in randomized sequences.

Examples:

```text
Start
Send
Receive
Close
Send
Start
Close
```

Also test:

- concurrent start;
- concurrent stop;
- concurrent send;
- concurrent receive;
- close while callback executes;
- callback reentrancy;
- reconnect during shutdown;
- peer disappearance during handshake;
- simultaneous session creation.

## Goroutine/resource leak detection

Establish a baseline and a tolerance.

Do not require an exact goroutine count because runtime internals and test infrastructure can legitimately vary.

Instead compare:

- before/after counts;
- repeated iterations;
- stable stack identities;
- persistent growth;
- resource ownership.

---

# 16. Vector 9 — File Transfer and Filesystem

Test path confinement with:

```text
../
../../
/absolute/path
C:\absolute\path
mixed\separators
encoded separators
Unicode normalization variants
NUL
symlink
hardlink
```

Also test:

- oversized files;
- size mismatches;
- truncated files;
- duplicate chunks;
- missing chunks;
- reordered chunks;
- duplicate filenames;
- attacker-controlled extensions;
- temporary file cleanup.

Verify that a peer cannot escape the intended storage directory.

---

# 17. Vector 10 — Storage, Logs, and Backups

## Logcat

Search for:

- keys;
- tokens;
- session identifiers;
- plaintext messages;
- fingerprints;
- passwords;
- authentication material;
- file contents.

Use:

```bash
adb logcat
```

and targeted filters appropriate to the project.

## Database confidentiality

Do not infer encryption from the database "looking binary."

Test:

1. Open without the expected key.
2. Open with the wrong key.
3. Open with the correct key.
4. Inspect schema/data exposure where the threat model requires it.
5. Check temporary files and journals.

## Android backups

Test the actual backup mechanisms relevant to the supported Android versions and configuration.

Do not rely solely on:

```xml
android:allowBackup="false"
```

Verify whether sensitive application data can be included in:

- cloud backup;
- device transfer;
- backup/restore workflows;
- exported files;
- shared storage.

---

# 18. Android Attack Surface

Inspect and dynamically test, where applicable:

- exported activities;
- exported services;
- exported broadcast receivers;
- content providers;
- intent filters;
- deep links;
- URI handling;
- permissions;
- notification actions;
- file providers;
- WebViews;
- debug components;
- backup/restore;
- IPC boundaries.

For every exported component, ask:

1. Who can invoke it?
2. What input can they control?
3. What privileges does it execute with?
4. Does it expose sensitive state?
5. Can it be invoked out of expected lifecycle order?

---

# 19. SSRF and Local Network Abuse

If a remote peer can influence a network endpoint, test whether the application can be induced to access internal services.

Candidate targets include:

```text
127.0.0.1
::1
localhost
0.0.0.0
private IPv4 ranges
link-local IPv4
private IPv6
link-local IPv6
```

Also test:

- DNS rebinding where relevant;
- redirects;
- alternate IP representations;
- IPv4/IPv6 parsing inconsistencies;
- proxy bypasses;
- localhost aliases.

Only classify as SSRF when the application actually performs attacker-influenced network access.

---

# 20. Test Harness Strategy

Use the strongest appropriate layer:

## Unit tests

For:

- crypto;
- parsers;
- state machines;
- path validation;
- serialization.

## Go fuzz tests

For:

- decoders;
- parsers;
- serialization;
- state-machine inputs.

## Go race tests

Use:

```bash
go test -race ./...
```

or focused packages when the full repository is too expensive.

## Android instrumentation

For:

- lifecycle;
- IPC;
- exported components;
- storage;
- backup;
- JNI integration.

## Network integration

For:

- handshake;
- replay;
- malformed packets;
- privacy routing;
- reconnect;
- DoS behavior.

## Profiling

Where available, use profiling to investigate:

- CPU spikes;
- memory growth;
- goroutine leaks;
- blocking;
- connection exhaustion.

---

# 21. Fuzzing Requirements

Every important fuzz campaign should record:

- target;
- corpus;
- command;
- duration;
- iterations where available;
- coverage;
- crashes;
- hangs;
- memory growth;
- minimized reproducer;
- build version;
- environment;
- device/architecture where relevant.

For every failure:

1. reproduce;
2. minimize;
3. preserve the input;
4. create a regression test;
5. classify;
6. identify root cause;
7. propose the smallest safe remediation.

---

# 22. Evidence Requirements

A finding should contain enough information for another engineer to reproduce it.

Record:

- exact command;
- source revision/build identifier;
- device and Android API level;
- CPU architecture;
- Go version;
- compiler/toolchain where relevant;
- test input/corpus;
- reproduction steps;
- stack trace;
- race detector output;
- sanitizer output;
- profile evidence;
- relevant network metadata.

Never include real production secrets in reports.

Use synthetic credentials, test keys, and redacted data.

---

# 23. Remediation Rules

Remediation must:

1. preserve the security model;
2. preserve protocol guarantees;
3. avoid weakening authentication;
4. avoid disabling encryption;
5. avoid bypassing privacy routing;
6. minimize architectural changes;
7. include a regression test.

Follow the project's `RULES.md` and existing architecture.

Do not "fix" a security test by disabling the feature under test.

---

# 24. Regression Tests

Every confirmed vulnerability should produce a permanent regression test when practical.

Examples:

- malformed packet no longer crashes;
- replayed ciphertext rejected;
- nonce reuse prevented;
- transcript mutation rejected;
- stale session rejected;
- path traversal rejected;
- oversized allocation rejected;
- JNI stale handle rejected;
- concurrent close/send remains safe;
- backup exposure prevented;
- direct network fallback blocked.

The regression test should fail against the vulnerable behavior and pass against the fixed behavior.

---

# 25. Stop Conditions

Stop the test immediately if it risks:

- production credentials;
- irreversible data loss;
- bootloops;
- destructive device changes;
- uncontrolled third-party traffic;
- protocol modification outside the authorized test environment;
- escape from the authorized environment.

Prefer isolated test devices, emulators, synthetic identities, and local test infrastructure.

If a third-party dependency is the root cause, report it clearly as an upstream issue instead of silently modifying unrelated code.

---

# 26. Security Report Format

For each finding use:

```text
Title:
Severity:
Classification:

Threat Model:
Security Invariant:

Affected Component:
Affected Version/Commit:

Hypothesis:

Attack/Input:

Exact Reproduction:

Observed Result:

Expected Secure Result:

Evidence:

Root Cause:

Security Impact:

Minimal Remediation:

Regression Test:

Residual Risk:
```

---

# 27. Final Audit Rules

Before declaring the project secure, explicitly verify that you are not making any of these invalid conclusions:

| Invalid conclusion | Correct interpretation |
|---|---|
| No crash = secure | Only tested behavior survived |
| Decryption succeeds = nonce safe | Nonce uniqueness must be verified |
| Buffer was zeroed = secret erased | Other copies may remain |
| 10k random values unique = entropy proven | Inspect CSPRNG and construction |
| One signature verifies = MITM impossible | Verify the entire security-critical transcript |
| One million packets pass = DoS safe | Test resource bounds and sustained load |
| Message index exists = replay safe | Test exact replay, reorder, delay, old epochs |
| Tor is configured = no leak | Test every network path |
| DB looks binary = encrypted | Test with no/wrong/correct key |
| `allowBackup=false` = backups safe | Test actual backup mechanisms |
| Race detector clean = native memory safe | Distinguish Go races from native memory safety |
| One goroutine count = no leak | Compare repeated runs and persistent stacks |

---

# 28. Definition of Done

A security audit is complete only when:

- threat model is defined;
- security invariants are explicit;
- crypto paths are tested;
- parser fuzzing is performed;
- network stress is performed;
- replay/state-machine behavior is tested;
- privacy routing is tested;
- JNI/native boundaries are tested;
- concurrency/lifecycle is stressed;
- filesystem/path traversal is tested;
- storage/logs/backups are inspected;
- Android attack surface is reviewed;
- relevant SSRF/local-network paths are tested;
- findings are reproducible;
- crashes are minimized;
- regressions are added;
- limitations are explicitly documented;
- `SECURE`, `NOT_TESTED`, and `BLOCKED` are not conflated.

The final report must distinguish clearly between:

- **CONFIRMED vulnerability**
- **LIKELY vulnerability**
- **HARDENING recommendation**
- **FALSE positive**
- **NOT TESTED**
- **BLOCKED**
- **verified secure behavior**

Never manufacture certainty where the evidence does not support it.
