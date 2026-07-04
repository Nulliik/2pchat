# 2PChat Protocol

This document describes the current on-the-wire behavior of the Python
reference implementation. It is intended to help external clients, including
the Android/Kotlin implementation, interoperate without having to reverse
engineer the Python source.

## Scope

The protocol has four layers:

1. Transport: a bidirectional byte stream such as TCP.
2. Framing: 4-byte big-endian length prefix + frame payload.
3. Session handshake: signed JSON handshake exchanged before encrypted chat.
4. Encrypted message payloads: JSON or CBOR payloads wrapped in a versioned
   encrypted packet.

Discovery is intentionally separate from the message/session protocol. Android
integration can start with direct `connect` / `listen` and add discovery later.

## Wire Versions

- Frame header versioning: none; the frame length is always 4-byte big-endian.
- Handshake payload version: `2`.
- Encrypted message packet version byte: `1`.
- Application message schema version: currently unversioned. The `type` field
  is the primary discriminator.

## Framing

Every frame sent over the stream is:

1. 4-byte unsigned big-endian payload length.
2. Exactly that many payload bytes.

The frame payload is:

- handshake JSON during session setup
- encrypted packet bytes after the handshake completes

## Identity Model

Each peer has two long-lived identities:

1. X25519 identity key
   Used for session key derivation and the user-visible fingerprint.
2. Ed25519 signing key
   Used only to sign the handshake payload.

The default fingerprint format is the Base64-encoded X25519 public key.

## Handshake

The handshake payload is plain JSON encoded as UTF-8 with compact separators.

Example shape:

```json
{
  "type": "handshake",
  "version": 2,
  "ephPub": "<base64 X25519 identity public key>",
  "prekeyPub": "<base64 X25519 prekey public key>",
  "identityPub": "<base64 Ed25519 verify key>",
  "signature": "<base64 Ed25519 signature>"
}
```

### Important note on naming

The current field name `ephPub` is historical. In the Python implementation it
actually contains the long-lived X25519 identity public key, not a throwaway
ephemeral key. External clients must follow the current behavior for
compatibility.

### Signature input

The signature is Ed25519 over:

```text
HANDSHAKE_CONTEXT || ephPub_raw || prekeyPub_raw || identityPub_raw
```

Where:

- `HANDSHAKE_CONTEXT` is the ASCII bytes `p2p-chat-handshake-v1`
- the three `*_raw` values are the decoded binary public key bytes

### Handshake order

- Initiator:
  1. send handshake
  2. receive peer handshake
- Responder:
  1. receive handshake
  2. send handshake

The session is established only after both handshakes validate.

## Session Key Derivation

Encrypted packets use a per-message ephemeral X25519 keypair and HKDF-SHA256.

For encryption, the sender derives three shared secrets:

1. `DH(sender_identity_priv, receiver_identity_pub)` via sender ephemeral key
   path in code
2. `DH(sender_ephemeral_priv, receiver_prekey_pub)`
3. `DH(sender_identity_priv, receiver_prekey_pub)`

The resulting input key material is:

```text
shared1 || shared2 || shared3
```

Then HKDF-SHA256 is applied with:

- `salt = b""` unless a channel key is explicitly supplied
- `info = b"MeshtasticStyleSessionKey"`
- output length `32`

The receiver mirrors this derivation using its identity private key and prekey
private key.

## Encrypted Packet Format

After the handshake, each application payload is wrapped as:

1. `version` - 1 byte, currently `0x01`
2. `counter` - 8-byte unsigned big-endian monotonic send counter
3. `ephemeral_pub` - 32 bytes X25519 ephemeral public key
4. `ciphertext` - NaCl `SecretBox` output, which already includes the 24-byte nonce

The plaintext encrypted by `SecretBox` is the serialized application message.

Replay protection:

- each peer tracks the highest received counter
- packets with `counter <= highest_seen` are rejected as replay

## Application Message Encoding

The default encoding is JSON UTF-8.

The protocol module also supports CBOR, but the Python session currently uses
JSON by default.

### JSON behavior

- Standard `json.dumps(message).encode("utf-8")`
- No canonical field ordering is enforced
- External clients should not rely on key order

### Message Types

The following message types are currently used:

- `chat`
- `ack`
- `status`
- `file_meta`
- `file_chunk`

### Common fields

These fields may appear depending on the message type:

- `type` - required discriminator string
- `id` - reliable message identifier
- `ack_id` - identifier acknowledged by an `ack`
- `timestamp` - Unix seconds
- `nickname` - sender display label
- `body` - chat text

### `chat`

```json
{
  "type": "chat",
  "id": "<string, assigned if absent>",
  "timestamp": 1720080000,
  "nickname": "alice",
  "body": "hello"
}
```

### `ack`

```json
{
  "type": "ack",
  "ack_id": "<message id being acknowledged>",
  "timestamp": 1720080001
}
```

`ack` messages do not currently carry their own `id`.

### `status`

Currently used mainly for offline notification:

```json
{
  "type": "status",
  "state": "offline",
  "timestamp": 1720080002,
  "reason": "optional string"
}
```

### `file_meta`

Contains file metadata plus encrypted file material references:

- `file_id`
- `file_name`
- `file_size`
- `num_chunks`
- `file_hash`
- `file_key`
- `file_nonce_prefix`
- `timestamp`

Binary values are Base64 strings.

### `file_chunk`

```json
{
  "type": "file_chunk",
  "file_id": "<base64>",
  "chunk_index": 0,
  "payload": "<base64 encrypted chunk>"
}
```

## Reliability Rules

- Outgoing reliable messages receive an `id` if one is not already present.
- The sender waits for an `ack` carrying `ack_id == message.id`.
- Default retry settings:
  - ACK timeout: 5 seconds
  - max retries: 3
  - backoff factor: 1.5

## Golden Vectors

Reference vectors live in:

- `messenger/tests/fixtures/protocol_vectors.json`

These vectors include:

- deterministic identity and fingerprint values
- an exact signed handshake payload
- JSON and CBOR encodings of a chat message
- a deterministic encrypted packet fixture

Android/Kotlin code should use those vectors as the first compatibility target.
