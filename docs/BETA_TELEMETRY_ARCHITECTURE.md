# Beta diagnostics for public GitHub reports

Version: 2.0 — 2026-09-09. Replaces the supplied v1.0 proposal for the local
diagnostic reporting feature. Implementation target: `2pchatGO/android`.

## Decision and scope

Users can opt into **local** diagnostic aggregation, reproduce a problem, inspect
the complete report, then copy its text, save a ZIP, or share that ZIP themselves.
The application never creates a GitHub issue, requests GitHub credentials, or
uploads diagnostics automatically. No telemetry server, Tor upload worker,
third-party SDK, installation identifier, or ingestion database is introduced.

The public report is a separate, closed-schema data product. It is **not** a
filtered copy of `app.log`, logcat, Tor logs, native stderr, preferences, databases,
or a system bugreport. Those local diagnostic sources can contain private data and
must not be advertised as suitable for public issues. Existing raw-log export
actions are replaced with the report dialog; the local log display is labeled.

This is data minimization, **not** a mathematical anonymity or zero-knowledge
guarantee. Even coarse counters reveal activity and transport use. A GitHub issue
links an attachment to an account, issue text and upload context; a chosen sharing
or document-provider app also observes the interaction. Tor alone cannot eliminate
these correlations. Users must review the report and their accompanying text.

## Review of v1.0 and current code

These findings apply to the supplied proposal and the inspected code, not to an
assumed deployed telemetry backend. Common attacker model: a peer can influence
names, messages, endpoints and failure text; anyone can read a public issue; a
recipient or platform can retain and correlate attachments. No compromise of the
Android sandbox, OS, signed application binary, or user's GitHub account is assumed.

| Finding | Severity / confidence | Evidence and preconditions | Impact and remediation |
| --- | --- | --- | --- |
| F1: arbitrary exception data reaches reports | High / high | v1.0 §4.2 calls `stackTraceToString()`; the old application crash handler prints the exception and thread name. A thrown exception can contain peer input, filenames and paths. | Publishing this output can disclose content or identities. Export only fixed exception and component categories; never traverse messages, causes or suppressed exceptions. |
| F2: raw-log sharing bypasses any proposed sanitizer | High / high | Settings had two `app.log` sharing paths and Network Diagnostics had another, plus a whole-log copy action. The provider exposed `config/`. Exploitation requires the user to share a log containing sensitive text. | Replace all these report actions, narrow the provider root to `config/downloads/`, and share only a newly generated cache artifact. The provider itself was not publicly exported. |
| F3: identifying metadata and unsupported privacy claims | High / high | v1.0 allows manufacturer/model, time windows, crash stacks and random batch identifiers while claiming to mathematically exclude deanonymization. | Rare combinations and public account context permit correlation. Omit these fields and explicitly document residual correlation; there is no formal privacy proof. |
| F4: consent, retention and bounds are underspecified | Medium / high | v1.0's crash handler writes without a consent check, and its sample queue/counters do not implement a coordinated bound, reset or consent epoch. | Start disabled, persist only consent and one bounded crash category record, saturate counters, and invalidate in-flight observations on disable/reset. |
| F5: backend metrics promise measurements they do not define | Medium / high | §5 suggests per-circuit Nginx limits without an authenticated circuit signal; §6 promises crash percentage per active installation despite excluding installation IDs. No such backend was inspected. | Defer backend delivery. Do not call report counts users, exact latency quantiles, rates, or proof of NAT/Tor failure causes. |

Regression coverage for F1–F4: `PublicReportTest`, `DiagnosticsStoreTest`,
`DiagnosticsExportBoundaryTest`, Go `pkg/diagnostics/collector_test.go`, and
`pkg/session/diagnostics_test.go`. F5 is resolved by scope and measurement definitions.
Wire/protocol compatibility is unchanged. The new optional JNI methods require a
fresh native library; older libraries produce `core.status = unavailable`, never a
fallback to raw logs. The sharing change intentionally retires public raw-log export.

Residual risks: malicious peers can bias finite event counts, rare reports can be
recognizable, local/device compromise defeats application privacy, and a recipient
can retain a report after consent is withdrawn. These controls do not anonymize
screenshots, typed issue descriptions, OS crash reports, or older shared logs.

## Public data contract: report schema 1

| Field | Allowed values / provenance |
| --- | --- |
| `report_schema` | Integer constant `1` |
| `app_version` | Numeric dotted build version, optionally a numeric parenthesized suffix, at most 32 characters; otherwise `unavailable` |
| `app_version_code` | Build constant in `1..10000000`; otherwise `0` |
| `android_api_range` | `24-28`, `29-32`, `33-35`, `36+`, `unknown` |
| `collection` | Constant `opt_in_local_current_process` |
| `core.status` | `available`, `disabled`, `unavailable` |
| `core.outbound[].transport` | Exactly one row each, in order: `direct`, `tor`, `yggdrasil`, `mixed`, `unknown` |
| `core.outbound[].outcomes` | Fixed keys: `success`, `rejected`, `dial_failed`, `handshake_failed`, `timeout` |
| `core.outbound[].latency` | Fixed keys: `under_1s`, `1_to_10s`, `10_to_60s`, `60s_plus` |
| Counter values | Strings `0`, `1`, `2-5`, `6-20`, `21-100`, `101+`; no exact counts above one |
| `previous_crash.status` | `none_recorded`, `different_build`, `recorded` |
| `previous_crash.category` | `null_pointer`, `illegal_state`, `invalid_argument`, `io`, `security`, `out_of_memory`, `linkage`, `stack_overflow`, `other` |
| `previous_crash.component` | `core_bridge`, `transport`, `tor`, `yggdrasil`, `group`, `storage`, `ui`, `app`, `other` |

Crash category/component appear only for `recorded`. A crash from a different
version code is not attributed to the current build. Unknown runtime exception
types map to `other`, even if their class names contain plausible error words.

Forbidden: arbitrary strings from runtime sources; exception messages, causes,
suppressed exceptions, thread names or raw frames; IPs, DNS/onion names, ports,
network names, proxy/relay configuration; identity/contact/group/message IDs,
hashes or pseudonyms of them; filenames/paths; message text/ciphertext; byte counts;
manufacturer/model/serial/advertising ID; locale/timezone; wall-clock timestamps,
uptime, exact durations, batch IDs, installation IDs and user IDs. No database,
directory or existing archive is recursively copied into a report.

## Measurement semantics

Go records a **completed outbound connection operation**, after the existing
online-session reuse check. The sample spans endpoint filtering, dialing/fallback,
and the initiator handshake. Successful authentication is `success`; it is not
proof of retained-session registration, delivery or peer reachability thereafter.
Inbound connections, cached session reuse, individual racing candidates, file
transfers, disconnects, native crashes and ANRs are not counted as outbound attempts.

The current dialer's local classifier maps candidate classes to fixed categories,
without DNS or network calls for telemetry. Before a winner exists, heterogeneous
candidate sets use `mixed`; after a winner, its classification is used. Relay
fallback successes use `unknown`, since their endpoint is not the original peer
transport. Pre-filter rejection uses `unknown`. `tor` includes Tor SOCKS routing,
not only onion destinations. A concurrent routing-policy change can make this
classification approximate; it is not packet-level route tracing.

Outcomes are set at known control-flow boundaries. Only typed timeout errors
produce `timeout`; wrapped errors without a typed timeout remain in their stage
category. Error strings never enter the collector. Latency is a monotonic elapsed
duration binned at 1, 10 and 60 seconds, for all completed outcomes. There are no
raw timing samples, min/max/median, denominators for user rates, or sequence logs.

Go memory consists of fixed arrays (5 transports × 5 outcome counters and 5 × 4
latency counters). Each counter saturates at 101. A mutex coordinates snapshots,
updates and consent. A non-exported consent generation rejects operations which
began before opt-in, Clear, or opt-out/re-enable. No peer map or event queue exists.

## Android storage, lifecycle and export

1. Main-process application startup installs the crash handler once. Consent
   initialization runs on IO; until complete, crash collection is disabled. The
   separate `:yggdrasil` process must not load the main Go runtime for diagnostics.
2. Consent defaults off and is persisted as a single validated byte in
   `noBackupFilesDir/public_diagnostics/consent`. The Go setter does not initialize
   networking, discovery, or identity. A missing/corrupt consent record means off.
3. Counters exist only in Go memory. Clear resets the current window. Disable
   resets it, invalidates previews and persists opt-out before deleting artifacts.
4. On an uncaught managed exception, capture only its fixed type category and the
   first recognized component among at most 24 frames. Do not format the exception
   or walk causes. Store one 10-byte versioned record: magic, build code, category,
   component. Use a temporary file, sync and rename; interrupted/corrupt records
   are rejected. A platform replacement fallback may lose a record, never expose
   a partial one. Capture never waits for a busy report-store lock or does network
   IO; it is best effort, especially during OOM/storage failure. The original
   uncaught handler is always delegated to; absent one, terminate the process.
5. Validate the record again before export: exact length, magic, finite enum
   values, valid build code, and local file age of 0–7 days. Future/expired or
   corrupt records are deleted. Age metadata never enters the report. Cleanup is
   performed on startup/report access; there is no periodic background worker.
6. Native snapshot JSON is capped at 8 KiB and reconstructed using an exact schema
   and finite values. Unknown fields/schema, malformed input, or unavailable ABI
   give an explicit `unavailable` status. Never substitute a NAT snapshot or logs.
7. Settings → **Diagnostic report** provides opt-in, Clear, and Preview. Public
   copy/share actions in Network Diagnostics open this same dialog. The preview
   is an immutable snapshot, at most 16 KiB, and shows the exact `report.txt` bytes
   placed in the ZIP. No collection refresh may change a reviewed attachment.
   A preview expires after 15 minutes or when its crash record expires; create a
   new preview after that. These local expiry clocks are never serialized.
8. Copy, Save ZIP and Share ZIP require an explicit action. ZIP contains only
   `report.txt`, with fixed entry name and epoch-zero entry time, no source metadata
   or comments. A constant outer filename contains no timestamp/device name.
9. Share uses a new random **local URI capability directory** per export under
   `cacheDir/public_reports/`; this token is not inside the ZIP or its display
   filename. At most one export is retained. Old grants are revoked and old
   archives removed before another share, on Clear/disable, and on cold startup.
   An old recipient cannot reopen a fixed URI to read a future report. A saved
   document or recipient's copy is outside this app's deletion control.
10. Existing `FileProvider` stays non-exported and grants only a specific URI with
    read permission and ClipData. Its broad `config/` root is replaced by the
    existing attachment-only `config/downloads/` root plus `public_reports/` cache.
    There is no permission to share the private crash/consent directory.

No user database, stored identity, packet, cryptographic primitive, network-policy
decision or wire format is changed. Observers do not alter errors, returns,
handshake decisions, callback ordering or dialing retries. No JNI callback is
made while a diagnostics lock is held. The two new native calls take a boolean
or return a small copied string; they retain no JVM references or handles.

The initial scope deliberately does not add succession/group counters, full stack
traces, secondary-process/native crash capture, crash upload, dashboards or server
aggregation. Adding a field requires a named producer, a finite vocabulary,
privacy review, schema compatibility rules, and regression coverage.

## Verification and acceptance

- Poison runtime inputs with contact names, Unicode, IP variants, onion names,
  email, paths, tokens, fabricated frames and exception chains. None may appear
  in preview, saved report, ZIP payload, entry name, comments or timestamps.
- Test unknown fields/types/oversized native JSON, corrupt/expired crash records,
  different build attribution, disabled startup, retained crash after restart,
  reset/revocation and in-flight observation invalidation.
- Test concurrent record/snapshot/reset, counter saturation, mixed classification,
  real local handshake success and failure, and cached-session exclusion.
- Test no direct log sharing/provider exposure, translations in all seven current
  app languages, handler delegation/termination and crash capture under contention.
- Run Go build/test/race/vet; Android unit tests, lint, debug assembly; verify fresh
  `buildGoCoreBinaries` execution; run Python compatibility tests. Report unavailable
  environments as UNVERIFIED, not PASS.
- On a device/emulator: opt in, reproduce a connection outcome, preview, save and
  share, inspect both files, disable and try old content URIs, trigger a managed
  crash and restart, and verify no diagnostics-originated traffic. Unit/build
  checks alone do not establish device behavior or fresh native packaging.

## Deferred server work

v1.0's automatic Tor delivery, ingestion API, PostgreSQL and Grafana are a separate
future proposal. They need explicit user demand and a threat model for transport
metadata, opt-in/reset, versioned strict ingestion, quotas, spam/Sybil resistance,
retention/deletion, service authentication, circuit isolation, outage behavior and
deployment verification. Clearnet fallback must never be silently introduced. Do
not infer unique active users, crash rates per installation, or circuit identity
from anonymous report counts or an ordinary loopback HTTP connection.

## References

- [Android: sharing files securely](https://developer.android.com/training/secure-file-sharing)
- [Android: FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)
- [Android: Throwable](https://developer.android.com/reference/java/lang/Throwable)
- [GitHub: attaching files](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/attaching-files)

GitHub supports ZIP/text attachments and uploads an attachment when it is added
to the editor, before the issue is necessarily submitted. Review locally first.
