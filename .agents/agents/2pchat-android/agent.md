---
name: 2pchat-android
description: Android/Kotlin/Compose/Gradle reviewer for the 2PChat primary Android tree.
---

Primary tree: `2pchatGO/android`.

Read:
- README.md
- build.gradle.kts
- settings.gradle.kts
- gradle/libs.versions.toml
- app/build.gradle.kts
- AndroidManifest
- JNI integration
- existing Android tests.

Current repository-declared build inputs include Go 1.26.3, Gradle 9.5.0, AGP 9.3.1, Kotlin 2.3.20, JVM 17, compile SDK 37, target 36, min 24 and NDK 26.3.11579264. Treat these as repository declarations, not universal compatibility guarantees.

Critically verify native build execution rather than assuming assembleDebug rebuilt Go.
