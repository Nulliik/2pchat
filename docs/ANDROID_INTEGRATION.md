# Android integration

Updated against the repository on 2026-09-05.

## Primary client: Go

[2pchatGO/android](../2pchatGO/android/README.md) is the release CI target. Kotlin calls `NativeBridge` / `bridge/NativeBridgeImpl`; native sources are in `core-go/`. Gradle's `buildGoCoreBinaries` task builds the JNI library when an NDK is available. Configure the NDK explicitly; see the Android README.

Group runtime, SQLCipher storage, ACL and Compose UI remain in Kotlin. Python tests check reference behavior and selected interoperability contracts; they do not replace Go or Android tests.

## Previous client: Chaquopy

The actual directory is `2PChat android/android` (the old `Android_App/android` path no longer exists).

- Edit shared Python code and bridge glue in root `messenger/`, including `discovery_bridge.py` and `bootstrap.py`.
- `syncCanonicalPythonCore` in `2PChat android/android/app/build.gradle.kts` copies the package and bridge entrypoints to `app/build/generated/python/main`.
- `forbidDuplicatedPythonCore` prevents a second maintained Python copy under `app/src/main/python`.
- Edit this client's Kotlin code under `2PChat android/android/app/src/main/java` only when working on compatibility with that client.

Chaquopy runtime defaults to Python `3.11`; this is independent of the desktop interpreter. Overrides: `-PchaquopyRuntimePython`, `-PchaquopyBuildPython`, `CHAQUOPY_PYTHON_VERSION`, `CHAQUOPY_BUILD_PYTHON`.

## Validation

From repository root:

```sh
python -m pip install -r messenger/requirements.txt
python -m pytest
```

For primary Android, follow its [build and test guide](../2pchatGO/android/README.md). To validate the previous client in PowerShell:

```powershell
Set-Location '2PChat android/android'
.\gradlew.bat testDebugUnitTest assembleDebug
```
