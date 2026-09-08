---
name: 2pchat-jni
description: JNI/CGO lifecycle and memory-safety workflow.
---

Inspect `core-go/pkg/bridge` and all Android call sites.

Use existing:
- `jni_safety_and_stress_test.go`
- `advanced_stress_test.go`
- `contract_test.go`
- `core_verification_test.go`
- E2E/reliability suites

Verify callback replacement, concurrent callbacks, shutdown, stale handles and error propagation.

If sanitizer support exists, use ASan/UBSan/race tooling appropriate to the native build. If not available, report UNVERIFIED.
