# 2PChat Discovery Layer Threat Model & Security Architecture

## 1. Overview & Objectives

2PChat's discovery layer coordinates peer address discovery across heterogeneous networks:
- Local Area Network (LAN via UDP beacons)
- BitTorrent BEP 15 UDP/HTTP Trackers
- DHT (Distributed Hash Tables)
- Direct peer address exchanges

The discovery layer operates under a zero-trust network assumption where local networks, trackers, and relay routers are treated as untrusted and potentially adversarial.

---

## 2. Threat Model & Attacker Capabilities

### A. Threat Agents
1. **Malicious or Compromised Trackers**: Third-party BitTorrent trackers may return forged endpoints or attempt replay attacks.
2. **Untrusted LAN Observers**: Adversaries on the same Wi-Fi subnet (e.g. coffee shops, airports) sniffing or spoofing UDP broadcast packets.
3. **Sybil/Eclipse Attackers on DHT**: Attackers attempting to flood peer tables with false routes.

### B. Attack Vectors & Mitigations

| Attack Vector | Impact | Mitigation |
| :--- | :--- | :--- |
| **Endpoint Spoofing** | Adversary claims ownership of victim's identity and redirects traffic | **Ed25519 Signed Discovery Records**: Every `DiscoveryRecord` is cryptographically signed by the peer's private key with domain separation `2pchat-discovery-record-v1\n`. |
| **Replay Attacks** | Adversary re-broadcasts old, stale endpoints of a peer | **Monotonic Sequence Numbers (`Seq`)**: Monotonically increasing counter; peers reject records with `Seq <= lastSeenSeq`. |
| **Crash Window Regression** | Crash causes local counter to reset to stale state, causing liveness blackout | **Zero-lag Persistence**: Counter is persisted on every increment to `SharedPreferences` via asynchronous `apply()`. |
| **Sequence Gap DoS** | Attacker broadcasts `Seq = 2^64 - 1` to permanently exhaust legitimate announces | **Gap Protection**: Records with `Seq > lastSeenSeq + 1000` are rejected immediately with `ErrSequenceGapTooLarge`. |
| **Future/Past Dating** | Adversary crafts records with manipulated timestamps | **Bounded Validity & Clock Skew Tolerance**: Max record TTL is 1 hour; `DefaultClockSkewAllowance = 5 * time.Minute` protects against clock drift while bounding validity. |
| **Anti-SSRF & Bogon Scanning** | Malicious records force clients to probe internal loopback/LAN router ports | **FilterCandidates Pipeline**: Rejects loopback, link-local, unroutable bogons, and sensitive ports (e.g. port 80/443 on routers) before socket creation. |
| **Verification Resource Exhaustion** | Flooding clients with massive JSON payloads to exhaust CPU/RAM | **Pre-crypto Resource Limits**: Max payload size 4096 bytes, max 16 endpoints, max 256 characters per endpoint address checked prior to Ed25519 verification. |

---

## 3. Deployment Modes (`DiscoverySecurityMode`)

- **`STRICT`**: Only signed `DiscoveryRecord` payloads are accepted across all transports (LAN, Direct, Trackers). Unsigned responses are rejected unconditionally.
- **`TRANSITIONAL` (Default)**: Signed records are preferred and validated with full anti-replay. Unsigned endpoints from legacy BEP 15 trackers are accepted as fallback with a security warning.
- **`LEGACY`**: Unsigned endpoints allowed everywhere (for isolated test suites).

---

## 4. Multi-Device Limitation & Operational Guidance

> [!WARNING]
> **Simultaneous Multi-Device Limitation with Identical Identity**
> Because identities in 2PChat are derived deterministically from BIP-39 recovery mnemonics, two devices (e.g. old phone and new phone) can theoretically share the same identity key and fingerprint.
>
> In such a configuration, their discovery sequence counters (`Seq`) operate independently. If Device A announces with `Seq = 500`, contacts will store `lastSeen = 500`. Subsequent announcements from Device B with `Seq = 10` will be rejected by contacts as stale replays until Device B's sequence counter increments past 500.
>
> **Recommendation:** Users should avoid running multiple active devices concurrently with the same identity mnemonic. For future protocol extensions, a signed `device_nonce` per device session will isolate sequence spaces.
