---
name: 2pchat-adversarial
description: Break-the-assumption security workflow for 2PChat.
---

Attack only the local implementation and test harness.

Prioritize:
- malformed packet input;
- authentication failure;
- replay/out-of-order;
- identity substitution;
- prekey misuse;
- ratchet rollback;
- state persistence;
- concurrency;
- shutdown;
- endpoint policy;
- framing;
- resource exhaustion.

Every confirmed issue needs reproducible evidence and a regression test.
