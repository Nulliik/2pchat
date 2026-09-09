# Android integration

Updated against the repository on 2026-09-05.

## Primary client: Go

[2pchatGO/android](../2pchatGO/android/README.md) is the release CI target. Kotlin calls `NativeBridge` / `bridge/NativeBridgeImpl`; native sources are in `core-go/`. Gradle's `buildGoCoreBinaries` task builds the JNI library when an NDK is available. Configure the NDK explicitly; see the Android README.

Group runtime, SQLCipher storage, ACL and Compose UI remain in Kotlin. Python tests check reference behavior and selected interoperability contracts; they do not replace Go or Android tests.

## Retired client: Chaquopy

The previous `2PChat android/` tree was deprecated and removed on 2026-09-09. Use the Go client above for Android development. Historical source remains available in Git history. The Python desktop client and shared `messenger/` package retain their existing tests.

## Validation

From repository root:

```sh
python -m pip install -r messenger/requirements.txt
python -m pytest
```

For Android, follow its [build and test guide](../2pchatGO/android/README.md).
