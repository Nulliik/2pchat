# P2P Encrypted Messenger

A modular, transport-agnostic encrypted messenger scaffold. Supports direct IPv4/IPv6
and Yggdrasil IPv6 addresses out of the box with a simple CLI chat. Designed so new
transports (Tor, Meshtastic, LoRa, Bluetooth, I2P) and a Kivy GUI can be added later.

## Quick start

```bash
python -m pip install -r requirements.txt
```

### Start a listener

```bash
python -m messenger.app.cli_chat --listen 0.0.0.0 --port 4444 --transport direct
```

### Connect to a peer

```bash
python -m messenger.app.cli_chat --connect 127.0.0.1 --port 4444 --transport direct
```

### Discovery server mode for a VPS

If you want a VPS to stay online, publish itself through a tracker, and wait
for inbound peer connections, run the CLI in discovery listen mode. The first
launch will automatically create `~/.2pchat/identity.key` if you do not
already have one:

```bash
python -m messenger.app.cli_chat \
  --discover-nickname my-vps-contact \
  --discover-key change-this-shared-key \
  --discover-listen \
  --discover-bind YOUR_PUBLIC_VPS_IP \
  --listen 0.0.0.0 \
  --tracker-preset "Torrent.eu.org UDP" \
  --port 4444 \
  --transport direct
```

This mode:

* keeps a background discovery `announce` alive and refreshes it periodically;
* listens on the provided port for incoming encrypted sessions;
* withdraws the published tracker record when the process stops cleanly.

### Connect using Yggdrasil IPv6

```bash
python -m messenger.app.cli_chat --connect 200:abcd:1234::5 --port 4444 --transport ygg
```

Paste the peer's address, start both ends, and chat. If connectivity drops, the CLI
will attempt to reconnect with exponential backoff and will keep unsent messages in
an offline outbox until a session comes back.

Enable ``--verbose`` to surface debug logs (packet sizes, counters, ACK retries)
from the crypto and session layers:

```bash
python -m messenger.app.cli_chat --listen 0.0.0.0 --port 4444 --transport direct --verbose
```

Incoming file transfers are saved automatically to ``~/.2pchat/downloads`` (or a
custom path via ``--downloads-dir``). The CLI prints the destination path once all
chunks arrive and decrypt successfully.

### When nobody knows who should listen or connect (direct transport)

If both sides are unsure who should run as the listener, use the **rendezvous**
mode. Both users run the same command, which listens locally *and* dials the other
side until one direction succeeds. This also works on localhost for quick tests:

```bash
python -m messenger.app.cli_chat --rendezvous 203.0.113.5 --port 4444 --transport direct

# Localhost dry-run (two terminals on the same machine)
python -m messenger.app.cli_chat --rendezvous 127.0.0.1 --port 4444 --transport direct
```

By default the rendezvous listener binds `0.0.0.0`; override with
`--rendezvous-bind` if you want to constrain the interface.

### Embedded Yggdrasil

If you want the application to launch its own Yggdrasil daemon (instead of relying
on a system-wide service), use the `ygg-embedded` transport. Requirements:

1. A Yggdrasil binary available on the system (override with `--yggdrasil-binary`).
2. A JSON config generated via `yggdrasil -genconf -json > yggdrasil.conf`.
3. Optional public peers provided with `--yggdrasil-peer` (repeatable).

Example listener:

```bash
python -m messenger.app.cli_chat \
  --listen :: \
  --port 4444 \
  --transport ygg-embedded \
  --yggdrasil-config ./yggdrasil.conf \
  --yggdrasil-peer tcp://203.0.113.10:65535 \
  --yggdrasil-peer tcp://198.51.100.12:65535
```

The embedded transport starts the daemon using the supplied config and peer list,
then hands connections off to the normal IPv6 transport. This keeps operation
self-contained for users who do not already run Yggdrasil as a service.

## Identity, trust, and encryption

* Each node uses an X25519 identity key stored under `~/.2pchat/identity.key`
  (override with `--identity`). The base64-encoded public key is the
  **fingerprint** shown when connecting. This long-lived identity never ships in
  packet headers; it lives only on disk and inside the encrypted session. You can
  add a friendly label for a peer with `--peer-label`; if the label later
  resolves to a different fingerprint you will see a warning before proceeding
  (TOFU with a nudge). Social mappings stay local—there is no published directory
  of IDs.
* A companion Ed25519 signing identity is stored at
  `~/.2pchat/identity_signing.key`. During the handshake each side signs the
  tuple `(context || ephemeral X25519 pub || Ed25519 pub)` with that key, and the
  peer verifies the signature before accepting the session. This binds the
  ephemeral session secret to a stable identity key and blocks first-contact
  MITM by requiring signed handshakes that bind ephemeral and long-term identity keys.
* Channel passwords use a **memory-hard KDF** (Argon2id by default, scrypt
  fallback if Argon2 is unavailable) with 64MiB+ memory cost. Legacy PBKDF2 is
  no longer accepted for new derivations—rotate old channels to gain GPU/ASIC
  resistance.
* A trust-on-first-use (TOFU) store at `~/.2pchat/trust.json` records peer
  fingerprints. The first time you connect to a peer their fingerprint is
  saved; on subsequent connections the stored fingerprint is required and any
  label conflicts are surfaced.

### Using peer labels to add a contact

The `--peer-label` flag lets you pin a friendly name to the fingerprint you
see on first contact. That label lives only in your local trust store and will
warn you if a different fingerprint later tries to reuse it.

Typical flow when you and a friend want to chat:

1. Each person opens **Identity → Show** (GUI) or runs `python -m
   messenger.app.cli_chat --command show-identity --port 4444` (CLI) and shares
   the displayed fingerprint/QR out of band.
2. You start listening or rendezvous and dial your friend using their address,
   providing a label for them:

   ```bash
   python -m messenger.app.cli_chat \
     --listen 0.0.0.0 \
     --port 4444 \
     --transport direct \
     --peer-label "alice" \
     --expect-fingerprint <friends_fingerprint>
   ```

3. When the handshake completes the trust store records
   `fingerprint -> peer-label (alice)`. Future sessions will refuse a mismatched
   fingerprint under the same label to catch impersonation attempts.

You can reuse `--peer-label` any time you connect to the same friend; if the
fingerprint ever changes you will be prompted to re-verify instead of silently
accepting a new identity.
* Public keys are exchanged in plaintext at connection start. The shared secret
  derived via Diffie-Hellman seeds a `SecretBox` for symmetric encryption
  (24-byte nonce, encrypted frames). JSON payloads are encrypted end-to-end;
  only length prefixes remain visible. The Double Ratchet packets obfuscate
  their headers (DH key + counter) under a header key derived from the root
  secret so packets carry no persistent identifiers on the wire.
* Reliability: each chat frame carries an ID, is acknowledged by the receiver,
  and will be retried with a tunable backoff (`--ack-timeout`, `--ack-backoff`,
  `--max-retries`) before the sender surfaces a timeout. If a message still
  cannot be delivered it is written to an offline outbox and replayed after the
  next reconnect. When a peer disconnects, the session surfaces a `Peer is
  offline` status so the app can warn the user and trigger a reconnect loop.

## Architecture overview

```
messenger/
  core/
    transport_base.py  # abstract interface
    transport_direct.py  # asyncio streams IPv4/IPv6
    transport_yggdrasil.py  # IPv6 validator wrapper for Yggdrasil
    transport_yggdrasil_embedded.py  # optional embedded daemon launcher
    crypto.py  # key generation + SecretBox helpers
    protocol.py  # message encoding (JSON/CBOR)
    session.py  # handshake + encrypted frames
    transport_manager.py  # registry for transports
  app/
    cli_chat.py  # minimal CLI chat
  utils/
    logger.py  # shared logging helper
  tests/
```

## External client integration

For native clients such as an Android/Kotlin app, use these repository
artifacts as the current source of truth:

- Protocol spec: [../docs/PROTOCOL.md](../docs/PROTOCOL.md)
- Android integration guide: [../docs/ANDROID_INTEGRATION.md](../docs/ANDROID_INTEGRATION.md)
- Golden vectors: `messenger/tests/fixtures/protocol_vectors.json`

## Adding new transports

Create a class that implements `connect` and `listen` in `messenger/core/transport_base.py`.
Register it in `transport_manager.py`. Examples:

* Tor: wrap socks proxy or stem client to obtain stream, then present as reader/writer.
* Meshtastic/LoRa/Bluetooth: expose their sockets/serial links via asyncio streams.
* I2P: use an I2P library to obtain a TCP-like stream and wrap into a transport class.

Keep the transport interface stable so higher layers (session/protocol/app) remain
unchanged.

## Roadmap

See [ROADMAP.md](./ROADMAP.md) for the current status and planned work.


## Web UI (React + TypeScript + FastAPI)

A minimal browser UI is available for automated frontend testing. It includes a
Telegram-style header/presence chip, destination/settings controls (host/bind/port), chat log, and composer
backed by a FastAPI WebSocket endpoint.

One-command launcher (recommended):

```bash
python -m messenger.app.web_launcher
```

This starts both backend (`:8000`) and frontend (`:5173`) together.
Use `Ctrl+C` to stop both. Add `--install` to auto-run `npm install` first.

Manual backend start (if needed):

```bash
python -m messenger.app.web_api
```

Windows notes:

- If you run the file directly (for example `python messenger/app/web_api.py`) and see
  `ModuleNotFoundError: No module named "messenger"`, start it as a module from the
  repository root instead:

  ```powershell
  cd C:\path\to\2pchat
  python -m messenger.app.web_api
  ```

- One-command launcher on Windows (from repo root):

  ```powershell
  python -m messenger.app.web_launcher --install
  ```

- Alternative with uvicorn:

  ```powershell
  cd C:\path\to\2pchat
  python -m uvicorn messenger.app.web_api:app --host 0.0.0.0 --port 8000
  ```

Start frontend (new terminal):

```bash
cd webui
npm install
npm run dev -- --host 0.0.0.0 --port 5173
```

Open `http://localhost:5173`. The UI connects to `ws://localhost:8000/ws/chat`.

## Kivy GUI

Install the GUI dependency and start the client **from the project root**
so the `messenger` package is discoverable:

```bash
python -m pip install kivy
python -m messenger.app.kivy_gui
```

The GUI now mirrors a simple Telegram-like layout: a colored header with presence
indicators, a central chat log, and a bottom composer. Hit **Settings** to open a
dialog where you can:

* Set your nickname (shown to the peer and in your local echo).
* Choose transport (`direct`, `ygg`, `ygg-embedded`) and mode (`connect`,
  `listen`, or **rendezvous** when neither side knows who should listen).
* Provide host/port plus optional bind address for listening or rendezvous.
* Configure embedded Yggdrasil (binary, config path, comma-separated peers).
* Toggle verbose logging to surface packet/counter debug traces in the console.
* Attach files (images, videos, documents). On desktop the **Attach** button
  opens the native file picker (e.g., Windows Explorer dialog) and you can also
  drag & drop files into the window. Files are end-to-end encrypted and saved to
  your local downloads directory. Inline media previews are enabled by default, and you can still open saved files
  with your OS viewer.

Click **Connect** after saving settings to start the session; **Reconnect** will
reuse the last configuration. The presence chip will flip between **Online** and
**Offline** whenever the peer connects, disconnects, or fails. Localhost
rendezvous also works from the UI by setting host `127.0.0.1`, bind `0.0.0.0`,
mode `rendezvous`, and transport `direct`.

Fingerprints remain stable between GUI sessions because the client automatically
reuses your stored identity key from `~/.2pchat/identity.key` (or
`$P2PCHAT_CONFIG_DIR` if set). Delete or replace that file only when you want to
rotate your long-term identity; otherwise your peer will keep seeing the same
fingerprint on every reconnect.

### QR/text identity verification (GUI + CLI)

Identity payloads contain **only public data** (fingerprints and optional
labels). They can be scanned, forwarded, or logged safely—they never include
private keys, session keys, or passwords.

* **GUI:** Click **Identity** in the header to open a popup showing your
  fingerprint (Base64 + hex), a JSON payload suitable for QR/text sharing (the
  payload carries the Base64 fingerprint plus a hex compatibility copy), and
  the numeric SAS if you are currently connected. Share that payload with your
  contact; if they scan/compare it and the SAS matches, mark them verified.
* **CLI:**

  ```bash
  # Show your fingerprint and ASCII QR in the terminal
  python -m messenger.app.cli_chat --show-identity

  # Export the JSON payload to send via another channel
  python -m messenger.app.cli_chat --export-identity > my_identity.json

  # Verify a peer while connected (payload pasted from them)
  python -m messenger.app.cli_chat --verify-identity "<payload or fingerprint>"
  ```

In all cases the QR/text payload is just an identity binding helper; leaking it
does not expose secrets or allow message decryption.


## Desktop release builds (Windows/macOS)

This repository now includes a concrete PyInstaller setup for the Kivy GUI:

- Spec file: `messenger_kivy.spec`
- Local build scripts:
  - Windows: `scripts/build_windows.ps1`
  - macOS: `scripts/build_macos.sh`
- CI workflow: `.github/workflows/kivy-release.yml`

### Local build (Windows)

```powershell
./scripts/build_windows.ps1
```

### Local build (macOS)

```bash
./scripts/build_macos.sh
```

The CI workflow runs on tag pushes like `v1.0.0` (or manual dispatch), builds
for both Windows and macOS, and uploads release artifacts.

### Install macOS release artifact (simplest path)

1. Download the **`2PChat-macos.zip`** artifact from the workflow run/release.
2. In Finder, double-click the zip to extract **`2PChat.app`**.
3. Drag **`2PChat.app`** into **Applications**.
4. First launch:
   - Right-click **2PChat.app** in Applications and choose **Open**.
   - Click **Open** again in the prompt (only needed once).

If macOS still blocks launch, run:

```bash
xattr -dr com.apple.quarantine /Applications/2PChat.app
open /Applications/2PChat.app
```

This keeps installation to one zip download plus drag-and-drop.

## Security posture and gaps

The current stack delivers X25519-based forward secrecy with HKDF key
derivation, replay protection, TOFU fingerprints, and header obfuscation to keep
identifiers off the wire. The **ROADMAP.md** includes a gap list against
standard “must-have” criteria; notable items to evaluate or add include:

- An **AES-GCM** AEAD path for environments that require NIST-standard
  primitives alongside the existing SecretBox/XSalsa20-Poly1305.
- **Identity-bound signatures** (e.g., Ed25519/ECDSA) on session setup to bind
  long-term keys beyond TOFU fingerprints and improve MITM resistance.
- **Session IDs/key versioning** and explicit salts for downgrade detection plus
  clearer out-of-band verification UX (QR/safety numbers).
- **DoS/rate limiting** during handshakes and reconnect storms, and hooks for
  hardware-backed/non-extractable identity keys where available.
- **Logging hygiene** reviews to ensure verbose mode never emits key material or
  plaintext.

Upcoming hardening (tracked in the roadmap) includes identity-signed
handshakes, explicit session IDs with key-versioning, pre-crypto rate limiting
for inbound handshakes, reconnect backoff to avoid storming peers, and a logging
pass that keeps packet sizes/versions visible without leaking key material or
plaintext.

## Testing and linting

Run linting and unit tests locally before changes:

```bash
flake8 messenger
pytest
```

`pytest` already covers cryptographic key exchange and transport loopback; add new
tests alongside the existing ones under `messenger/tests` as functionality grows.
