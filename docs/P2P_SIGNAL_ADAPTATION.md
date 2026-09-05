# P2P Signal adaptation: implementation status

Updated 2026-09-05. The former migration plan described Double Ratchet as experimental; that description is obsolete.

The Python session now performs a signed handshake v3 with X25519 identity/prekeys and an Ed25519 signature, derives a live X3DH-style bootstrap root, and sends Double Ratchet packets with version byte 4. Exact byte layouts, signature contexts and legacy compatibility are specified in [PROTOCOL.md](PROTOCOL.md).

| Responsibility | Source |
| --- | --- |
| Bootstrap and session | [session.py](../messenger/core/session.py) |
| Ratchet state and encryption | [double_ratchet.py](../messenger/core/double_ratchet.py) |
| Legacy crypto helpers | [crypto.py](../messenger/core/crypto.py) |
| Golden vectors | [protocol_vectors.json](../messenger/tests/fixtures/protocol_vectors.json) |
| Go implementation | [core-go](../2pchatGO/android/core-go) |

This is a project-specific live P2P adaptation, not a claim of interoperability with the Signal application. Discovery resolves routes; it does not authenticate contacts or host a Signal prekey service.

The original design notes cited the [positive-intentions P2P Signal article](https://positive-intentions.com/blog/p2p-signal-protocol/) as background. Its current contents were not revalidated in this repository documentation update; implementation claims above come from local code and the protocol tests.
