# 2PChat Agent System

## Agents

- `2pchat-orchestrator` — routes work through the right review pipeline.
- `2pchat-architect` — architecture, trust boundaries and impact.
- `2pchat-implementer` — implementation under project rules.
- `2pchat-crypto` — primitives, X3DH/Double Ratchet, key lifecycle.
- `2pchat-protocol` — wire formats, state machines and compatibility.
- `2pchat-jni` — CGO/JNI lifecycle, ownership and callbacks.
- `2pchat-android` — Kotlin/Compose/Gradle/runtime.
- `2pchat-go` — Go correctness/concurrency/performance.
- `2pchat-network` — transport, framing, policy, Tor/proxy, endpoint handling.
- `2pchat-adversary` — adversarial review.
- `2pchat-final` — independent final gate.

Agents should not all receive the same generic prompt. Their job is to challenge different failure modes.
