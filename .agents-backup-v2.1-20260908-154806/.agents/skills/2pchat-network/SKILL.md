---
name: 2pchat-network
description: Analyze 2PChat P2P networking, framing, parsing, transport security, resource limits and adversarial network behavior.
---

# 2PChat Network Security

Treat peers and network input as attacker-controlled.

Inspect:
- framing
- parser behavior
- message size limits
- partial reads/writes
- replay
- duplicates
- ordering
- malformed messages
- timeouts
- reconnects
- resource exhaustion
- authentication
- TLS/DNS behavior where applicable

For packet captures, prefer read-only analysis and preserve evidence.
