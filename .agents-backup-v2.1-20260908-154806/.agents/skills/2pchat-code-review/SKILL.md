---
name: 2pchat-code-review
description: Perform an adversarial code review of 2PChat changes with emphasis on correctness, security regressions and maintainability.
---

# 2PChat Code Review

Review the complete diff, not only changed lines.

Ask:
- What assumptions changed?
- What callers/callees are affected?
- What state invariants changed?
- What happens with malformed input?
- What happens concurrently?
- What happens after restart/crash?
- What happens during migration?
- Did protocol semantics change?
- Did security posture weaken?
- Are tests sufficient?

Report confirmed issues separately from hypotheses.
