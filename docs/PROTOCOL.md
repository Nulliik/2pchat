# 2PChat Protocol

This document describes the current on-the-wire behavior of the Python
reference implementation.

## Scope

The protocol has four layers:

1. Transport: a bidirectional byte stream such as TCP.
2. Framing: 4-byte big-endian length prefix + frame payload.
3. Session bootstrap: signed handshake exchanged before encrypted chat.
4. Encrypted message payloads: JSON or CBOR payloads wrapped in a versioned
   encrypted packet.

Discovery is intentionally separate from the message/session protocol.

## Wire Versions

- Frame header versioning: none; the frame length is always 4-byte big-endian.
- Current handshake version: `3`.
- Legacy handshake version: `2`.
- Current encrypted packet version byte: `4` for Double Ratchet packets.
- Standalone legacy-helper packet version byte: `2`; unauthenticated-header
  version `1` is rejected.

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
   Used for peer identity and user-visible fingerprint.
2. Ed25519 signing key
   Used to authenticate the signed prekey and handshake transcript.

The default fingerprint format is the Base64-encoded X25519 public key.

## Current Session Bootstrap: Handshake Version 3

The current protocol uses a live, serverless X3DH-style bootstrap:

- both peers are online
- the initiator sends an identity bundle plus a bootstrap ephemeral key
- the responder replies with its identity bundle
- both sides derive an initial root key from 3 DH computations
- message traffic immediately switches to Double Ratchet packets

### Initiator payload

```json
{
  "type": "handshake",
  "version": 3,
  "role": "init",
  "identityPub": "<base64 X25519 identity public key>",
  "verifyPub": "<base64 Ed25519 verify key>",
  "signedPrekeyPub": "<base64 X25519 signed prekey public key>",
  "prekeySignature": "<base64 Ed25519 signature over signedPrekeyPub>",
  "signature": "<base64 Ed25519 signature over the full v3 transcript>",
  "ephPub": "<base64 X25519 bootstrap ephemeral public key>"
}
```

### Responder payload

```json
{
  "type": "handshake",
  "version": 3,
  "role": "reply",
  "identityPub": "<base64 X25519 identity public key>",
  "verifyPub": "<base64 Ed25519 verify key>",
  "signedPrekeyPub": "<base64 X25519 signed prekey public key>",
  "prekeySignature": "<base64 Ed25519 signature over signedPrekeyPub>",
  "signature": "<base64 Ed25519 signature over the full v3 transcript>"
}
```

### Signed prekey signature

`prekeySignature` is Ed25519 over:

```text
SIGNED_PREKEY_CONTEXT || signedPrekeyPub_raw
```

Where `SIGNED_PREKEY_CONTEXT` is ASCII:

```text
p2p-chat-signed-prekey-v1
```

### Transcript signature

`signature` is Ed25519 over:

```text
X3DH_HANDSHAKE_CONTEXT || role || identityPub_raw || verifyPub_raw || signedPrekeyPub_raw || ephPub_raw
```

Where:

- `X3DH_HANDSHAKE_CONTEXT` is ASCII `p2p-chat-x3dh-handshake-v1`
- `role` is ASCII `init` or `reply`
- `ephPub_raw` is empty for the responder reply

## Current X3DH-Style Key Agreement

The version 3 bootstrap uses 3 DH computations:

1. `DH(initiator_identity_priv, responder_signed_prekey_pub)`
2. `DH(initiator_ephemeral_priv, responder_identity_pub)`
3. `DH(initiator_ephemeral_priv, responder_signed_prekey_pub)`

The shared input key material is:

```text
dh1 || dh2 || dh3
```

Then HKDF-SHA256 derives 96 bytes with:

- `salt = b""`
- `info = b"X3DH-INIT"`

Those 128 bytes are split into:

1. initial root key
2. initiator send chain / responder receive chain
3. initiator receive chain / responder send chain
4. a dedicated header-encryption key shared by both directions

## Current Encrypted Packet Format: Version 4

After the v3 bootstrap, application messages are wrapped in a Double Ratchet
packet:

1. `version` - 1 byte, currently `0x04`
2. `flags` - 1 byte
3. `header`
4. `ciphertext`
5. `packet_tag` - 32-byte HMAC-SHA256

### Header

Current header layout:

1. `dh_pub` - 32 bytes X25519 ratchet public key
2. `message_index` - 4-byte unsigned big-endian index inside the current send chain

If `flags & 0x01 != 0`, the header is encrypted with the dedicated header key
derived during bootstrap. Header protection is enabled by default. Keeping this
key separate from the changing root key also lets the receiver decrypt the
header which announces the peer's next DH-ratchet key.

### Ciphertext

The ciphertext is NaCl `SecretBox` output:

- 24-byte nonce
- MAC
- encrypted serialized application message

Each message key is derived from the current send or receive chain key, and
each chain key is advanced after use.

`packet_tag` authenticates the complete version, flags, header, and ciphertext.
Its key is domain-separated from the SecretBox key with
`HMAC-SHA256(message_key, "p2p-chat-packet-auth-v4")`. A receiver performs all
ratchet operations on a temporary state and commits that state only after both
the packet HMAC and SecretBox authentication succeed.

## Legacy Compatibility: Handshake Version 2

Legacy peers still use the older signed handshake:

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

Important historical quirk:

- `ephPub` in v2 is actually the long-lived X25519 identity public key, not a
  throwaway ephemeral key.

The v2 signature input is:

```text
HANDSHAKE_CONTEXT || ephPub_raw || prekeyPub_raw || identityPub_raw
```

Where `HANDSHAKE_CONTEXT` is ASCII:

```text
p2p-chat-handshake-v1
```

## Standalone Helper Packet Version 2

The standalone `crypto.encrypt_message` helper uses:

1. `version` - 1 byte, `0x02`
2. `counter` - 8-byte unsigned big-endian
3. `ephemeral_pub` - 32 bytes X25519 ephemeral public key
4. `ciphertext` - NaCl `SecretBox` output
5. `packet_tag` - 32-byte HMAC-SHA256 over all preceding fields

The old version `0x01` did not authenticate its counter and ephemeral-key
header. It is rejected to prevent counter tampering and replay-window poisoning.

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
- exact legacy and current handshake payloads
- JSON and CBOR encodings of a chat message
- a deterministic legacy packet fixture
- a deterministic first Double Ratchet packet fixture

External clients should target version 3 first, and only add version 2 as a
legacy compatibility mode if needed.
