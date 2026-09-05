# 2PChat Android — Go core

Primary Android release target, reviewed 2026-09-05. Kotlin/Jetpack Compose owns UI, relay and SQLCipher storage; `core-go/` owns native sessions, crypto and transport through CGO/JNI. Group runtime remains in Kotlin.

## Build inputs

Source-controlled configuration currently declares:

| Input | Value / source |
| --- | --- |
| Go | `1.26.3` in [go.mod](core-go/go.mod) |
| Gradle wrapper | `9.5.0` in [gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties) |
| Android Gradle Plugin / Kotlin | `9.3.1` / `2.3.20` in [libs.versions.toml](gradle/libs.versions.toml) |
| JVM toolchain | 17 in [app/build.gradle.kts](app/build.gradle.kts) |
| Android SDK | compile 37, target 36, minimum 24 |
| NDK | `26.3.11579264` |
| Application ID | `com.example.twopchat.go`; QA override: `groupQaApplicationId` |
| App version | `0.0.8.9`, code 25 |

These are repository settings, not a new toolchain compatibility certification. Install the declared SDK/NDK and provide `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`. Gradle builds native libraries through `buildGoCoreBinaries`; that task is skipped if the NDK is absent, so an APK build alone must not be treated as proof of a fresh Go build.

## Build and test

From `2pchatGO/android` in PowerShell:

```powershell
Push-Location core-go
go build ./...
go test ./...
Pop-Location
.\gradlew.bat testDebugUnitTest assembleDebug
```

On POSIX use `./gradlew`. Host `go build` validates Go packages; it does not generate the Android JNI binaries. Gradle handles the Android cross-compilation (via Make on POSIX and PowerShell on Windows).

APK: `app/build/outputs/apk/debug/app-debug.apk`.

From the repository root, run Python compatibility tests separately:

```sh
python -m pip install -r messenger/requirements.txt
python -m pytest
```

Android instrumentation requires a device/emulator. The isolated group QA setup and E2E commands are documented in the [group port audit](../../docs/GROUP_PORT_AUDIT_2026-09-05.md).

## CI scope

[android-release.yml](../../.github/workflows/android-release.yml) builds this tree on `ci-v*` tags or manual dispatch. It assembles a debug APK; it does not currently run the full Python/Go/Android test gates from ADR 003. Its Go setup still specifies `1.22`, whereas `go.mod` declares `1.26.3`; toolchain download/selection must be accounted for before calling this a pinned reproducible build.

## Documents

- [Documentation index](../../docs/README.md)
- [ADR 001: Primary Android tree](docs/ADR_001_PRIMARY_ANDROID_TREE.md)
- [ADR 002: JNI contract](docs/ADR_002_JNI_BRIDGE_SAFETY_CONTRACT.md)
- [ADR 003: Testing and CI](docs/ADR_003_TESTING_AND_CI_REPRODUCIBILITY.md)
- [Historical audit](FULL_PROJECT_AUDIT_REPORT.md)
- [Engineering rules](RULES.md), [security policy](SECURITY.md)
