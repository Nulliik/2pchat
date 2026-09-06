---
name: 2pchat-security-audit
description: Perform a threat-model-driven security audit of 2PChat across Android, Go, JNI, networking, persistence and cryptographic protocol boundaries.
---

# 2PChat Security Audit

## Workflow

1. Map repository architecture and trust boundaries.
2. Identify assets and attacker capabilities.
3. Identify security-critical data flows.
4. Inspect authentication, authorization and identity handling.
5. Inspect serialization and parser boundaries.
6. Inspect persistence and secret lifecycle.
7. Inspect concurrency and JNI boundaries.
8. Inspect network input/output and resource limits.
9. Inspect X3DH/Double Ratchet invariants when present.
10. Run available static/security tooling.
11. Validate findings with code evidence.
12. Produce actionable findings and regression tests.

## Required output

For each finding:
- Severity
- Confidence
- Affected component
- Attack surface
- Preconditions
- Attacker model
- Exploitability
- Impact
- Root cause
- Evidence
- Remediation
- Regression test
- Compatibility impact
- Residual risk

Never call a speculative issue confirmed.
Never equate "no findings" with "secure".
