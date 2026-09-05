# Project roadmap

Reviewed against repository sources on 2026-09-05. This is a capability inventory, not a release certification or a dated delivery commitment.

## Implemented

- Python CLI and Kivy GUI, FastAPI backend, direct/Yggdrasil transports and proxy routing.
- Signed handshake v3, live X3DH-style bootstrap and Double Ratchet; legacy compatibility is documented in [PROTOCOL.md](../docs/PROTOCOL.md).
- Persisted identity, TOFU, labels, QR/text verification, ACK/retry, reconnect and offline outbox.
- File transfer, status messages and GUI control layer.
- Primary Kotlin/Compose Android client with Go core; previous Chaquopy client retained for compatibility.
- Android group runtime: epoch encryption, event log, ACL, invitations, media, polls, typing, mute, outbox and anti-entropy. See [group protocol](../docs/GROUP_CHAT_PROTOCOL.md).
- PyInstaller scripts and desktop/Android release workflows.

## Remaining work / areas to evaluate

- Restore or replace the missing `webui/` frontend before advertising a complete browser client. Backend and launcher code remain.
- Reconcile release CI with the Go version in `core-go/go.mod` and implement the test gates proposed by [ADR 003](../2pchatGO/android/docs/ADR_003_TESTING_AND_CI_REPRODUCIBILITY.md).
- Validate external Tor/Yggdrasil/NAT paths and group invitation discovery on real devices; local tests do not establish reachability on all networks.
- Group media retention, batch ingestion and large-network behavior require separate design/measurements; see the [group port audit](../docs/GROUP_PORT_AUDIT_2026-09-05.md).
- MLS, true multi-device accounts, topics/threads and broadcast channels are not delivered by the current group protocol.
- Serial/radio transports (Meshtastic/LoRa/Bluetooth), I2P and hardware-backed desktop key storage remain separate potential extensions.

Historical UI plans and bug findings are indexed in [plans](../plans/README.md); verify them against the intended Android tree before implementation.
