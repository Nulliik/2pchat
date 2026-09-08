---
name: 2pchat-jni
description: Review Kotlin/Java-to-Go JNI boundaries for ownership, lifetime, threading, exceptions, callbacks and shutdown correctness.
---

# 2PChat JNI

Always inspect both sides of the JNI boundary.

Check:
- native handle lifetime
- allocation/deallocation
- references
- thread attach/detach
- callbacks
- exception propagation
- concurrent calls
- cancellation
- shutdown
- stale handles
- use-after-free
- double-free

Never fix a crash by suppressing the symptom.
