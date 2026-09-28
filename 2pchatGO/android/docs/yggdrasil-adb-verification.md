# Yggdrasil ADB verification

Tested with `emulator-5554` and `emulator-5556` in Yggdrasil proxy mode. Local peer discovery was disabled by the Yggdrasil mode gate, and the native transport policy excluded LAN. No LAN candidate or Direct route appeared in the logs after the final APK launch.

## Confirmed

- Existing contacts, message history, and the Yggdrasil route returned after app force-stop and relaunch on both emulators.
- Twelve alternating text messages arrived at the opposite emulator.
- A photo (78,773 bytes after image sanitization) and a short video (13,276 bytes) reached 100% progress at both sender and receiver.
- A prolonged TCP ACK stall now closes the affected stream and clears the false online state on both sides. JVM tests cover transient route recovery, persistent route loss, and a blocked mesh send.

## Open reliability issue

An 8,330,251-byte video did **not** complete over the public Yggdrasil route in the initial runs. Repeated attempts stopped around 1.57, 3.93, or 6.03 MB; another attempt produced no file progress. The watchdog restores truthful connection state after a prolonged stall, but it does not resume or complete the file transfer.

On a later run with the same two emulators, the full 8,330,251-byte file reached `emulator-5556` over Yggdrasil. `sha256sum` in each app sandbox returned `4c2940b06e7a28f9689f1c4890f3884069ec227ee8df07477949fd3550303a8f` for source and destination. Both peer states remained online with transport `Yggdrasil`. This is one successful large-file run, not evidence of sustained reliability.

The next large-file run after rebuilding the Go core stalled near 1.27 MB. The debug ADB receiver then timed out as an Android broadcast because it waited for the newly synchronous transfer result; this test harness issue has been corrected to log `APP_FILE_RESULT` asynchronously. The original receiver eventually logged `completed=false`, consistent with the stalled transfer. Long-running media reliability remains **PARTIAL** pending repeated receiver-side completions and recovery after route loss, without LAN.

On the subsequent debug build, a fresh 8.33 MB attempt stopped near 0.8 MB. The corrected receiver logged `APP_FILE_RESULT completed=false`; it did not report a false completion. The sender's control plane still reported six Up peers while two independent mesh probes returned `PARTIAL ... timeout`. After the second partial probe, the proxy engine restarted automatically and the probe returned `LIVE`; queued text messages then reached the other emulator over Yggdrasil. A manual `retryPeersNow` before this had not restored text delivery. A further 8.33 MB retry after recovery stalled around 2.07 MB and also returned `completed=false`. The receiver ACKed beyond the sender's oldest retransmitted segment, but those ACKs stopped reaching the sender. Both apps later reported online `Yggdrasil` sessions. Sustained large-file delivery is therefore still **FAIL** in this environment, even though one full transfer passed.

## Verification gate

`go build ./...`, `go test ./...`, `go test -race ./...`, `go vet ./...`, `python -m pytest` (228 passed, 8 skipped), and Android `testDebugUnitTest assembleDebug` (861 tests, 0 failures, 1 skipped) passed. The Android build executed `buildGoCoreBinaries`. Security gate status: **PARTIAL** because repeated large-video E2E checks failed. Optional `staticcheck`, `govulncheck`, `semgrep`, `gitleaks`, and `trivy` tools were unavailable.
