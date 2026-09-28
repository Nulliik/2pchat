# Yggdrasil ADB verification

Tested with `emulator-5554` and `emulator-5556` in Yggdrasil proxy mode. Local peer discovery was disabled by the Yggdrasil mode gate, and the native transport policy excluded LAN. No LAN candidate or Direct route appeared in the logs after the final APK launch.

## Confirmed

- Existing contacts, message history, and the Yggdrasil route returned after app force-stop and relaunch on both emulators.
- Twelve alternating text messages arrived at the opposite emulator.
- A photo (78,773 bytes after image sanitization) and a short video (13,276 bytes) reached 100% progress at both sender and receiver.
- A prolonged TCP ACK stall now closes the affected stream and clears the false online state on both sides. JVM tests cover transient route recovery, persistent route loss, and a blocked mesh send.

## Historical failure log (resolved by the ACK-deadlock fix)

An 8,330,251-byte video did **not** complete over the public Yggdrasil route in the initial runs. Repeated attempts stopped around 1.57, 3.93, or 6.03 MB; another attempt produced no file progress. The watchdog restores truthful connection state after a prolonged stall, but it does not resume or complete the file transfer.

On a later run with the same two emulators, the full 8,330,251-byte file reached `emulator-5556` over Yggdrasil. `sha256sum` in each app sandbox returned `4c2940b06e7a28f9689f1c4890f3884069ec227ee8df07477949fd3550303a8f` for source and destination. Both peer states remained online with transport `Yggdrasil`. This is one successful large-file run, not evidence of sustained reliability.

The next large-file run after rebuilding the Go core stalled near 1.27 MB. The debug ADB receiver then timed out as an Android broadcast because it waited for the newly synchronous transfer result; this test harness issue has been corrected to log `APP_FILE_RESULT` asynchronously. The original receiver eventually logged `completed=false`, consistent with the stalled transfer. Long-running media reliability remains **PARTIAL** pending repeated receiver-side completions and recovery after route loss, without LAN.

On the subsequent debug build, a fresh 8.33 MB attempt stopped near 0.8 MB. The corrected receiver logged `APP_FILE_RESULT completed=false`; it did not report a false completion. The sender's control plane still reported six Up peers while two independent mesh probes returned `PARTIAL ... timeout`. After the second partial probe, the proxy engine restarted automatically and the probe returned `LIVE`; queued text messages then reached the other emulator over Yggdrasil. A manual `retryPeersNow` before this had not restored text delivery. A further 8.33 MB retry after recovery stalled around 2.07 MB and also returned `completed=false`. The receiver ACKed beyond the sender's oldest retransmitted segment, but those ACKs stopped reaching the sender. Both apps later... The root cause was confirmed by a thread dump: the `Ygg-Mesh-Receiver` thread sent its own ACK and blocked inside `mobile.Yggdrasil.sendBuffer` while holding the shared send lock, so it stopped draining inbound packets that the sender needed. ACK transmission moved to a dedicated thread with a bounded queue; a JVM regression test (`YggdrasilShimStallRecoveryTest`) overflows that queue and proves the receive loop keeps draining.

## Sustained large-file verification (post ACK-deadlock fix)

With the fix installed and the transport policy still excluding LAN:

- A 12-round soak of the 8,330,251-byte video alternating both directions completed 12/12 with matching SHA-256 on every receiver (`.tmp-ygg-evidence/soak-20260928-fixed/`).
- 30/30 text messages sent while 40-125 MB files were in flight were delivered; no message loss. Text behind a large file is head-of-line blocked on the single Yggdrasil TCP stream for up to ~180 s at the old 124 KB/s rate - a UX backlog, not a stall.
- Recovery after force-stop mid-soak restored the stack, peers, and queued traffic; the next transfer completed with a matching hash.
- Mesh audits before and after every transfer showed external TLS uplinks only; no LAN or Direct route ever appeared.

## Throughput

Measured throughput was 124 KB/s, traced to the shim, not the mesh (public Yggdrasil links carry hundreds of Mbps): the user-space TCP shim allowed only 4 in-flight 900-byte segments and slept 5 ms between every segment, either of which alone caps a ~30 ms-RTT path far below the link. `MAX_IN_FLIGHT_SEGMENTS` is now 32 (~29 KiB flight, within the 64 KiB receive reorder buffer) and per-segment pacing sleeps were removed; backpressure comes from the cumulative-ACK window plus the existing RTO backoff. Re-measured after the change: 41 MB in 82 s (~502 KB/s) and 125 MB in 221 s (~575 KB/s), both with matching SHA-256 and Yggdrasil-only uplinks.

## Verification gate

`go build ./...`, `go test ./...`, `go test -race ./...`, `go vet ./...`, `python -m pytest` (228 passed, 8 skipped), and Android `testDebugUnitTest assembleDebug lintDebug` passed after the final change set; the Android build executed `buildGoCoreBinaries`. The send-window change is Kotlin-only; the Go suite ran against the final Go tree. E2E gate: 12/12 large-file soak, 30/30 concurrent texts, restart recovery, and two >40 MB throughput runs all PASS with SHA-256 verification and Yggdrasil-only uplinks. Security gate status: **PASS** for the covered areas. Optional `staticcheck`, `govulncheck`, `semgrep`, `gitleaks`, and `trivy` tools were unavailable (**UNVERIFIED**).
