# Android Integration

`Android_App/android` now uses the root Python messenger library as its source of truth.

## How it works

- The Android app keeps only the Android-specific bridge in `android/app/src/main/python/discovery_bridge.py`.
- During Android builds, Gradle runs `syncMessengerPython`.
- That task copies the current root Python package pieces from `messenger/` into `android/app/build/generated/python/main/messenger`.
- Chaquopy includes that generated directory in the Python sources for the APK.

## What to edit

- Edit shared protocol, session, discovery, crypto, and transport logic in the root package under `messenger/core` and `messenger/utils`.
- Edit Android-specific Kotlin or bridge glue in `Android_App/android/app/src/main/java` and `Android_App/android/app/src/main/python/discovery_bridge.py`.

## What not to do

- Do not reintroduce a second hand-maintained copy of `messenger` under `android/app/src/main/python/messenger`.
- Do not patch Android protocol behavior only in the bridge if the same behavior belongs in the shared Python library.

## Validation

Recommended checks after Python protocol changes:

1. `python -m pytest`
2. `cd Android_App/android`
3. `./gradlew.bat testDebugUnitTest`
4. `./gradlew.bat assembleDebug`

## Chaquopy Python selection

The Android build defaults to Python `3.11`, matching the main repository environment.

If a developer needs a different local interpreter path or version, override it without editing shared build files:

- Gradle property: `-PchaquopyRuntimePython=3.11`
- Gradle property: `-PchaquopyBuildPython=C:/Path/To/python.exe`
- Environment variable: `CHAQUOPY_PYTHON_VERSION=3.11`
- Environment variable: `CHAQUOPY_BUILD_PYTHON=C:/Path/To/python.exe`
