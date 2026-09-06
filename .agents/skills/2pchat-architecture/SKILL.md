---
name: 2pchat-architecture
description: Analyze 2PChat architecture, trust boundaries, module dependencies and cross-language data flow before large changes.
---

# 2PChat Architecture

Map:
- Android
- JNI
- Go Core
- storage
- networking
- crypto
- protocol
- background/lifecycle components

For each boundary identify:
- data crossing it
- ownership
- trust level
- validation
- error propagation
- concurrency
- lifecycle
- security assumptions

Prefer small, reversible architectural changes.
