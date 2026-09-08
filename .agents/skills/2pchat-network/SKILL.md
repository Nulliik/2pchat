---
name: 2pchat-network
description: Network and transport security workflow for 2PChat.
---

Inspect:
- framing
- dialer/listener
- endpoint classification
- policy
- relay tunnel
- hole punching
- STUN/UPnP
- KCP/multiplex
- file transfer
- Tor/proxy integration

Use existing fuzz tests and policy tests.

Threats:
- parser confusion
- length/resource exhaustion
- endpoint policy bypass
- DNS leaks
- accidental public bind
- proxy bypass
- malicious peer flooding
- file-transfer abuse
- race during listener rebind.
