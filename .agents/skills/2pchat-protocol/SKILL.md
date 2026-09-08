---
name: 2pchat-protocol
description: Protocol compatibility and state-machine workflow for 2PChat.
---

Inventory:
- `core-go/pkg/protocol`
- `core-go/pkg/session`
- `core-go/pkg/crypto/ratchet.go`
- Python interoperability tests
- testdata/fuzz

Record packet/handshake versions explicitly.

Current ratchet source declares PacketVersion 4 and HandshakeVersion 3. Treat these as current source facts and verify against tests/docs before changing them.

Build a transition table for every changed state machine.

Test:
normal, duplicate, replay, reorder, loss, ratchet, simultaneous, restart, restore, downgrade, malformed input.
