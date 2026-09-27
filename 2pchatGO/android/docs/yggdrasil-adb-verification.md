# Yggdrasil ADB verification

Tested with `emulator-5554` and `emulator-5556` in Yggdrasil proxy mode. Local peer discovery was disabled by the Yggdrasil mode gate, and the native transport policy excluded LAN. No LAN candidate or Direct route appeared in the logs after the final APK launch.

## Confirmed

- Existing contacts, message history, and the Yggdrasil route returned after app force-stop and relaunch on both emulators.
- Twelve alternating text messages arrived at the opposite emulator.
- A photo (78,773 bytes after image sanitization) and a short video (13,276 bytes) reached 100% progress at both sender and receiver.
- A prolonged TCP ACK stall now closes the affected stream and clears the false online state on both sides. JVM tests cover transient route recovery, persistent route loss, and a blocked mesh send.

## Open reliability issue

An 8,330,251-byte video did **not** complete over the public Yggdrasil route. Repeated attempts stopped around 1.57, 3.93, or 6.03 MB; another attempt produced no file progress. The send API's initial acceptance is not proof of delivery. The watchdog restores truthful connection state after a prolonged stall, but it does not resume or complete the file transfer. Large-video transfer is **FAIL**; long-running chat with large video attachments remains **UNVERIFIED** until a full receiver-side transfer and post-reconnect retry pass on two emulators without LAN.

## Verification gate

`go build ./...`, `go test ./...`, `go test -race ./...`, `go vet ./...`, `python -m pytest` (228 passed, 8 skipped), and Android `testDebugUnitTest assembleDebug` (860 tests, 0 failures, 1 skipped) passed. The Android build executed `buildGoCoreBinaries`. Security gate status: **PARTIAL** because the large-video E2E check failed. Optional `staticcheck`, `govulncheck`, `semgrep`, `gitleaks`, and `trivy` tools were unavailable.
