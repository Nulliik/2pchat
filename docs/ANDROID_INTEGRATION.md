# Android / Kotlin Integration Plan

This guide is for a native Android client that wants to interoperate with the
Python reference implementation without embedding Python.

## Recommended Implementation Order

Implement the protocol in the following order:

1. Identity primitives
   - X25519 identity key
   - Ed25519 signing key
   - Base64 fingerprint formatting
2. Stream framing
   - 4-byte big-endian frame length
3. Handshake
   - signed JSON handshake version `2`
   - fingerprint extraction
4. Direct transport chat
   - connect
   - listen
   - encrypted JSON `chat` messages
   - `ack` handling
5. Session lifecycle
   - disconnect
   - offline status behavior
   - reconnect policy chosen by the Android app
6. Files
   - `file_meta`
   - `file_chunk`
7. Discovery
   - optional later phase
   - start with direct IP interoperability first

## Minimum Interop Scope

The minimum success bar for the first Kotlin milestone should be:

1. Kotlin can compute the same fingerprint as Python for a fixed X25519 key.
2. Kotlin can parse and validate the Python handshake vector.
3. Kotlin can produce a handshake Python accepts.
4. Kotlin can decrypt a Python `chat` message.
5. Kotlin can send a `chat` message Python decrypts.
6. Kotlin can send and receive `ack`.

Do not start with discovery. Start with direct `connect` / `listen`.

## Suggested Kotlin Module Split

Use three Android-side modules or packages:

1. `protocol`
   - frame encoder/decoder
   - JSON/CBOR payload serializers
   - message models
2. `crypto`
   - X25519
   - Ed25519
   - HKDF-SHA256
   - SecretBox-compatible encryption
3. `session`
   - handshake orchestration
   - replay counters
   - reliable send / ACK tracking

Only after these are stable should the app layer add:

- contacts
- trust UX
- discovery
- background services

## Message Schema Guidance

Model message types as explicit sealed classes / discriminated unions:

- `ChatMessage`
- `AckMessage`
- `StatusMessage`
- `FileMetaMessage`
- `FileChunkMessage`

Rules to keep:

- preserve unknown JSON fields where practical
- tolerate additional fields from newer peers
- reject unknown encrypted packet version
- reject unsupported handshake version

## Identity and Trust UX

The Android app should mirror the Python trust model:

- fingerprint = Base64 X25519 public key
- TOFU is acceptable for first contact
- mismatch must be loud and block the session
- label reuse across different fingerprints should warn the user

## Discovery Advice

Discovery should be treated as routing assistance, not identity.

- tracker-based discovery may only return endpoint candidates
- identity validation still happens in the session handshake
- Android should store:
  - peer fingerprint
  - last known endpoint
  - transport
  - last seen time

This matches the direction already used in the Python GUI contact flow.

## Interop Checklist

Before shipping the Kotlin client, verify:

1. Python listen -> Kotlin connect
2. Kotlin listen -> Python connect
3. Python direct chat roundtrip
4. ACK roundtrip both ways
5. Kotlin rejects tampered handshake signature
6. Kotlin rejects replayed encrypted packet counter
7. Fingerprint mismatch is surfaced correctly
8. Kotlin can read the fixtures in `messenger/tests/fixtures/protocol_vectors.json`

## Repository Artifacts to Reuse

Use these files as the current source of truth:

- `docs/PROTOCOL.md`
- `messenger/tests/fixtures/protocol_vectors.json`
- `messenger/tests/test_protocol_vectors.py`

If behavior changes, update the vectors and tests in the same pull request so
the Android client can track protocol evolution safely.
