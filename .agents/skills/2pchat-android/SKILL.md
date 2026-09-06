---
name: 2pchat-android
description: Build, debug and review the Android/Kotlin/Compose side of 2PChat with special attention to lifecycle, storage, permissions, JNI and security boundaries.
---

# 2PChat Android

Inspect module graph, Gradle configuration, manifests, build variants, dependencies, R8/ProGuard, permissions, exported components, deep links, lifecycle, coroutines and JNI.

For relevant changes run:
```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Use instrumentation/UI tests for UI or device behavior.

Never weaken Android security configuration just to make a test pass.
