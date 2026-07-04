# Android App

The Android client lives in `Android_App/android`.

## Layout

- `android/app/src/main/java/com/example/twopchat`: active Kotlin application code
- `android/app/src/main/python/discovery_bridge.py`: Android-specific Python bridge
- root `messenger/`: shared Python protocol, discovery, crypto, and transport logic

## Important rule

Do not add another hand-maintained copy of the Python `messenger` package under the Android app.

The Gradle build syncs the shared root `messenger/` package into the APK automatically.

## First-time setup

1. Install Android Studio with Android SDK.
2. Create `Android_App/android/local.properties` on your machine if Android Studio did not create it automatically:
   `sdk.dir=/path/to/your/Android/Sdk`
3. Ensure Python 3.11 is available on your machine, or override Chaquopy locally.

## Build

```powershell
cd Android_App/android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

## Local-only overrides

Never commit machine-specific paths.

Use local Gradle properties or environment variables instead:

- `chaquopyRuntimePython=3.11`
- `chaquopyBuildPython=C:/Path/To/python.exe`
- `CHAQUOPY_PYTHON_VERSION=3.11`
- `CHAQUOPY_BUILD_PYTHON=C:/Path/To/python.exe`
