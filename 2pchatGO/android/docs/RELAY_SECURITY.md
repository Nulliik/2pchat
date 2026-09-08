# Group Invite Relay & Succession Synchronization Security Model

## 1. Executive Summary

2PChat implements decentralized peer-to-peer group messaging with end-to-end cryptographic integrity. In a decentralized network topology without central coordination servers, two peers in a group might not have direct network connectivity (e.g., firewall traversal limitations, NAT restrictions, or separate Tor circuits).

When a non-admin member (`doggy`) invites a candidate (`puppy`) into a group created and administered by an owner (`Foxxxy`):
1. The candidate and owner may have no direct transport connection.
2. The owner approves the admission and issues the group invite.
3. The invite must be securely routed across intermediate group members (relayed) without allowing any intermediate peer to modify or forge group state, escalate privileges, or replay expired credentials.

This document details the cryptographic invariants, threat model, defense-in-depth mitigations, and network impact of the Relay Delivery and Succession Synchronization mechanisms.

---

## 2. Relay Security Model

### 2.1 The Problem with Strict Transport Authentication
Originally, `receiveInvite` required:
```kotlin
require(transportFingerprint(senderPeerName) == invite.senderFingerprint)
```
While this prevented third parties from delivering unsolicited invites, it broke non-admin invitation workflows: `Foxxxy` (owner) signed the invite, but only `doggy` had a direct transport connection to `puppy`. When `doggy` delivered `Foxxxy`'s invite to `puppy`, `puppy` rejected it because `transportFingerprint("doggy") != invite.senderFingerprint ("Foxxxy")`.

### 2.2 Cryptographic Guarantee vs. Transport Path
In the revised security model, **trust is anchored in the owner's cryptographic Ed25519 signature, not the transport path**.

```
[ Owner (Foxxxy) ]
       │  1. Signs GroupInvite with local Ed25519 private key
       │  2. Embeds invite_json in MEMBER_ADDED event
       │  3. Signs MEMBER_ADDED event with Ed25519 private key
       ▼
[ Relay Member (doggy) ]
       │  4. Verifies MEMBER_ADDED signature using Foxxxy's public key
       │  5. Verifies GroupInvite signature using Foxxxy's public key
       │  6. Forwards authentic invite_json over direct connection to puppy
       ▼
[ Candidate (puppy) ]
       │  7. Relaxes transport check (sender is doggy, not Foxxxy)
       │  8. Cryptographically verifies Foxxxy's Ed25519 signature on GroupInvite
       │  9. Verifies anti-replay bounds (age <= 7d, skew <= 5m, dedup)
       │ 10. Auto-accepts and joins group
```

### 2.3 Why This Is Cryptographically Sound
1. **End-to-End Authenticity**: The `GroupInvite` canonical byte payload contains the group ID, title, description, current epoch, epoch AEAD secret, member roster, history cursors, owner transitions, and timestamp. The owner signs this canonical representation with their Ed25519 private key (`GroupIdentitySignatures.sign`).
2. **Impossibility of Forgery**: An intermediate relay peer possesses only the group's epoch symmetric key and public verification keys. It does not possess the owner's Ed25519 private key and cannot generate a valid signature for any altered or fabricated invite.
3. **Recipient Binding**: The invite includes the candidate's exact transport fingerprint and device ID in `invite.members`. The candidate verifies that its local device identity matches an authorized recipient entry.
4. **Tamper Detection**: Any alteration by an intermediate peer (modifying group permissions, injecting malicious members, or altering the epoch key) breaks signature verification (`invite.verifySignature() == false`), causing immediate rejection.

---

## 3. Defense-in-Depth: Double Signature Verification

To prevent malicious group participants from injecting unauthorized or fake invites to confuse the relay routing:

1. **Outer Control Event Verification (`MEMBER_ADDED`)**:
   - When a peer receives a `MEMBER_ADDED` event containing `invite_json`, it verifies:
     - `event.authorDeviceId == group.ownerDeviceId` (only the verified group owner may author `MEMBER_ADDED` events with embedded invites).
     - `event.verifySignature(owner.signingKeyBase64)` (the control event itself must be signed by the owner's key).
2. **Inner Invite Payload Verification (`GroupInvite`)**:
   - `parsedInvite.verifySignature()` (the invite must be signed with Ed25519).
   - `parsedInvite.ownerFingerprint == owner.transportFingerprint` (the invite's declared owner must match the group's current authenticated owner).

If any check fails, the intermediate node silently drops the relay request and logs a security warning.

---

## 4. Threat Model & Mitigation Matrix

| Threat | Attack Vector | Mitigation / Proof |
| :--- | :--- | :--- |
| **Malicious Relay Peer** | Intermediate member modifies group title, roles, or injects a backdoored epoch key before sending to candidate. | **Ed25519 Signature Verification**: Candidate checks `invite.verifySignature()`. Any modified byte breaks the signature. Candidate rejects the invite. |
| **Forged Invite** | Intermediate member creates an invite for a non-existent or unauthorized group claiming to be from the owner. | **Key Isolation**: Relay peer does not possess owner's private key. Forged signature check fails. |
| **Replay Attack** | Attacker captures a legitimate invite from the past and replays it months later to disrupt group state. | **Strict Temporal Bounds**: Invites older than 7 days (`INVITE_LIFETIME_MS = 7 * 24 * 3600 * 1000`) are rejected. Timestamps more than 5 minutes in the future (`MAX_CLOCK_SKEW_MS = 300_000`) are rejected. Duplicate active memberships are ignored. |
| **Rogue Event Injection** | Non-owner member broadcasts a fake `MEMBER_ADDED` event with their own invite payload. | **Defense-in-Depth**: Relay peers check `event.authorDeviceId == owner.deviceId` and verify the event's Ed25519 signature before relaying. |
| **Relay Response Tampering** | Candidate's acceptance response is altered or intercepted. | **Signed Response**: Candidate signs `GroupInviteResponse` with its own Ed25519 key. Owner verifies signature before acknowledging membership. |

---

## 5. Backward Compatibility & Capability Negotiation

To ensure seamless interoperation with peers on earlier versions of the protocol:
- **`group_invite_relay_v1`**: Declared in `core-go` and Kotlin `ProtocolVersionManager`. Before relaying an invite to a candidate, the intermediate peer verifies:
  ```kotlin
  val session = ProtocolVersionManager.refresh(candidateFingerprint)
  if (session != null && (session.peerIsLegacy || !session.supports(Capability.GROUP_INVITE_RELAY_V1))) {
      // Suppress relay; wait for candidate to connect directly with owner
      return
  }
  ```
- **`group_succession_query_v1`**: Prevents legacy peers from being flooded with unknown control frames. Succession queries are sent only to peers supporting the capability.

---

## 6. Network Performance & Heartbeat Size Impact

### Control Frame Sizing
The succession certificate (`certificate_json`) is ~500 to 1000 bytes. When added to `TYPE_OWNER_HEARTBEAT`:
- **`MAX_WIRE_BYTES` Limit**: `GroupWireProtocol.MAX_WIRE_BYTES = 1536 * 1024` (1.5 MB). The heartbeat frame with certificate is ~1.2 KB, utilizing less than 0.1% of the protocol frame limit.
- **Transport Framing**: 2PChat transports all peer frames over framed TCP, Tor v3 streaming circuits, or chunked Reliable UDP channels. Frames are delimited by 4-byte length prefixes and stream-assembled. An incremental 1 KB payload does not induce MTU-induced packet loss or TCP fragmentation issues.
- **Emission Rate**: Heartbeats are emitted at bounded background intervals (standard cadence is 6 to 12 hours, or on explicit state transitions). The network bandwidth overhead is negligible (< 0.005 KB/s).

---

## 7. Observability & Telemetry

Diagnostic metrics are tracked via `BackgroundDiagnostics.getInviteStats(context)`:
- `invitesGenerated`: Total owner-signed invites issued.
- `invitesRelayed`: Successful relay hops performed by intermediate peers.
- `invitesAccepted`: Auto-accepted or user-accepted invites.
- `invitesExpired`: Invites rejected due to exceeding the 7-day lifetime.
- `invitesRejected`: Invites rejected due to signature failure or clock skew.
- `relayAttemptsWithoutCapability`: Relays withheld because candidate runs a legacy version.
