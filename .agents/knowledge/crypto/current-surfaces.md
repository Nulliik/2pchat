# Current Crypto Surfaces

Observed in `core-go/pkg/crypto`:

- `keys.go`
- `cipher.go`
- `ratchet.go`
- `noise.go`
- `backup.go`
- `group.go`
- `group_transition.go`
- `group_succession.go`
- `sender_keys.go`
- `mnemonic.go`
- `tor_onion.go`

The ratchet implementation currently defines:
- PacketVersion = 4
- HandshakeVersion = 3
- X3DH-derived initialization
- DH ratchet
- skipped message keys
- header obfuscation
- packet authentication.

The pack must verify these claims against current source/tests before treating them as protocol guarantees.
