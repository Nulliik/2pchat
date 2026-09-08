# 2PChat Trust Boundaries

## Boundary A — Android application

Treat UI input, lifecycle callbacks and persisted data as potentially malformed.

## Boundary B — JNI/CGO

This is a memory/lifecycle boundary:
Kotlin -> JNI/CGO -> Go and callbacks in the reverse direction.

Threats include stale handles, lifetime races, thread misuse, memory ownership errors and callback-after-close.

## Boundary C — Network

Peers and network packets are hostile.

Validate:
- versions
- lengths
- framing
- authentication
- endpoint classification
- policy
- rate limits.

## Boundary D — Cryptographic state

Identity, prekeys, ratchet state and skipped keys are security state.

No unverified state mutation on authentication failure.
