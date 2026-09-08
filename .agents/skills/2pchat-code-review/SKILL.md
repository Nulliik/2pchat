---
name: 2pchat-code-review
description: Adversarial, evidence-based review of a 2PChat diff.
---

Review the diff in this order:
1. security boundary;
2. state transition;
3. ownership/lifecycle;
4. error handling;
5. serialization;
6. concurrency;
7. tests;
8. compatibility;
9. documentation.

Reject unrelated refactors in security-sensitive changes unless justified.
