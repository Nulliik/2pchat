---
name: 2pchat-security-gate
description: Run a project-aware verification gate for 2PChat changes.
---

# Workflow

## 1. Classify impact

Inspect `git diff --name-only`.

Critical if changes touch:
- crypto
- session
- protocol
- transport security
- bridge/JNI/CGO
- identity/prekeys
- storage/restore of secrets

## 2. Baseline

Run from `2pchatGO/android/core-go`:
- `go build ./...`
- `go test ./...`
- `go test -race ./...`
- `go vet ./...`

Run from `2pchatGO/android`:
- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`

Verify native task execution and record whether `buildGoCoreBinaries` ran.

Run Python compatibility tests from repository root when applicable.

## 3. Security checks

Run available:
- staticcheck
- govulncheck
- semgrep
- gitleaks
- trivy

## 4. Specialist checks

Crypto -> `2pchat-crypto`
Protocol -> `2pchat-protocol`
JNI -> `2pchat-jni`
Network -> `2pchat-network`
Adversarial -> `2pchat-adversary`

## 5. Evidence

Create a machine-readable result with:
- command
- cwd
- exit code
- duration
- stdout/stderr summary
- PASS/FAIL/UNVERIFIED
- timestamp

Never hide unavailable tools.
