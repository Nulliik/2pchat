# 2PChat AI Engineering Rules v2

## Authority

The repository's existing `RULES.md`, `SECURITY.md`, ADRs, protocol specifications and current tests remain authoritative. This file adds AI-agent safety and verification requirements; it must not silently contradict project rules.

## Repository map

Primary Android tree:
`2pchatGO/android`

Go core:
`2pchatGO/android/core-go`

Go packages currently include:
- `pkg/crypto`
- `pkg/session`
- `pkg/protocol`
- `pkg/transport`
- `pkg/discovery`
- `pkg/bridge`

Executables:
- `core-go/cmd/2pcore-cli`
- `core-go/cmd/lib2pcore`
- `core-go/cmd/soak`

## Mandatory behavior

Before changing security-sensitive code:
1. identify the threat model;
2. identify security invariants;
3. inspect callers and callees;
4. inspect serialization/wire format;
5. inspect persistence and restore paths;
6. inspect concurrency and lifecycle;
7. inspect JNI/CGO boundaries;
8. inspect existing regression/fuzz/interoperability tests;
9. identify compatibility impact;
10. define a regression test before finalizing.

Never:
- invent cryptographic primitives;
- silently replace primitives;
- disable validation;
- remove security tests to make CI pass;
- treat historical audit findings as current findings without re-verification;
- claim an APK build proves fresh native Go binaries were built;
- claim runtime verification without a device/emulator;
- expose unrestricted shell/network capabilities through MCP.

## Security-critical areas

Changes to these areas require Security Gate:
- `core-go/pkg/crypto/**`
- `core-go/pkg/session/**`
- `core-go/pkg/protocol/**`
- `core-go/pkg/transport/**`
- `core-go/pkg/bridge/**`
- JNI/CGO glue
- identity/prekey/session persistence
- packet framing/authentication
- network policy, Tor/proxy, relay and endpoint classification
- Android storage/security configuration

## Required verification

Go:
- `go build ./...`
- `go test ./...`
- `go test -race ./...`
- `go vet ./...`

Android:
- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- verify whether `buildGoCoreBinaries` actually executed
- if available, instrumentation/E2E on emulator/device

Python compatibility:
- install `messenger/requirements.txt`
- `python -m pytest`

Additional security checks when available:
- `staticcheck`
- `govulncheck`
- `semgrep`
- `gitleaks`
- `trivy`

## Evidence model

Every verification result is one of:
- PASS — command completed successfully and evidence was captured.
- FAIL — command ran and found a failure.
- UNVERIFIED — command/tool/environment was unavailable or evidence was insufficient.

Never convert UNVERIFIED into PASS.

## Completion

A security-sensitive task is complete only when:
- code is reviewed;
- affected tests pass;
- relevant race/fuzz/interoperability checks pass where applicable;
- security gate is PASS or explicitly reported as PARTIAL/UNVERIFIED;
- final diff is inspected;
- no unrelated files were changed.
