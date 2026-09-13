# 16 KiB Android native libraries

Every arm64 native library must have 16 KiB or greater ELF `PT_LOAD` alignment. `app/libs/yggdrasil.aar` is rebuilt from the official Yggdrasil Android binding at `1b921b313d983c4366207fb6c808083a30aa138b`, with submodule `yggdrasil-go` at `b88fec63ff4eb94add47191fca292fc3306ee71c`. Its `gomobile bind` command includes `-extldflags=-Wl,-z,max-page-size=16384`.

`jniLibs.useLegacyPackaging = true` causes Android to extract the bundled libraries. This is required because Tor executes `libtor.so` from `nativeLibraryDir`; extracted libraries remain compatible with 16 KiB devices because their ELF loads are aligned. If packaging is changed to direct loading, the verifier additionally requires each stored ZIP entry to begin on a 16 KiB boundary.

Verify a built APK with:

```powershell
python scripts/verify_16k_apk.py app/build/outputs/apk/debug/app-debug.apk
```