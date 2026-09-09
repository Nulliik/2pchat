# Tracker scheduling and endpoint retention

## Scope and invariants

Discovery supplies untrusted routing hints. A socket connection alone is not
identity proof. Only a completed outbound authenticated handshake promotes the
actual winning endpoint; incoming source ports and relay addresses do not.
Authenticated inbound traffic on that outbound session refreshes its success
timestamp at most once per five minutes. Fingerprint verification, discovery
signatures, replay checks and transport policy intersection remain mandatory.

Endpoint cleanup never deletes a contact, conversation, fingerprint or trust
decision. SQLCipher remains the storage boundary. Local JNI formats are extended;
peer handshake, ratchet and discovery-record wire versions are unchanged.

## Tracker announcements

Go owns one scheduler, with at most six active tracker requests across all entry
points. State is keyed by tracker URL and the decoded info hash. Re-registering a
hash, duplicate URLs, periodic Android observations and manual wake-ups cannot
bypass its schedule. The successful response's interval controls refreshes;
missing/invalid intervals use 15 minutes. A one-minute safety floor and valid
HTTP `min interval` apply. Intervals above 24 hours are treated as invalid input.
Failure retries start at 30 seconds, grow exponentially to one hour, and add up
to 10% jitter. Network changes respect tracker minimums and failure backoff.

Self configuration replaces only the self publication set. Independent peer
lookups expire after 30 minutes unless renewed; at most 256 are retained.
Android checks configuration changes but does not periodically force a publish.
The signed LAN record TTL is distinct from third-party tracker storage lifetime.
No guarantee about external trackers deleting or retaining their records is made.

## Address lifetime

Records are keyed by `(fingerprint, normalized endpoint)`, never by display name.
They track discovery source, first/last observation, last authenticated success,
number of successful UTC days, failures, retry time, signed advertisement expiry
and whether the identity belongs to a saved contact. Day counting prevents fast
reconnect loops from manufacturing a long success history.

| Transport | Never worked: since last discovery | Worked: since last success | Worked on 3+ days |
|---|---|---|---|
| LAN | 30 minutes | 7 days | 30 days |
| Public IP / hostname | 2 hours | 30 days | 90 days |
| Yggdrasil | 7 days | 180 days | 365 days |
| Tor | 7 days | 180 days | 365 days |

Repeated discovery does not extend the success-based lifetime of a previously
working route. An unproven signed candidate is additionally limited by the
advertisement's expiry; independent successful use is separate evidence.

For each saved friend, retain the last successful Tor route and Yggdrasil route
indefinitely as reserves. Authenticated route updates and migrated addresses
provide reserves until a route first succeeds. If neither stable transport is
known, keep one last direct route. Unauthenticated discovery cannot displace a
working reserve. Active routes are protected during cleanup.
Migration keeps alternative legacy Tor/Yggdrasil routes (within the per-peer
limit) until one of that transport's routes succeeds, rather than guessing which
legacy address was last usable.

Expired reserves do not participate in background reconnect loops. Explicit
message/file sending or manual reconnect may try them, after fresh routes fail.
Both groups use the same fingerprint and transport policy. Concurrent probe
requests for one fingerprint share the in-progress fresh/reserve operation.
Failed route attempts back off to six hours but keep their success history.
Device/network outage, unavailable Tor and cancellation do not penalize routes.
Asynchronous stale failure events cannot overwrite a newer success.

At most 16 records are retained per identity. The general cache budget is 8192
records, excluding protected friend/active routes. Cleanup runs on first
maintenance, hourly, and on capacity pressure; reads reject expired candidates
immediately. Transient LAN candidate lists have a 30-minute TTL and bounded LRU
eviction. Legacy CSV/UI projections are bounded and refreshed after cleanup.

## Database and JNI migration

SQLCipher schema 20 adds `peer_endpoint_records` and `peer_endpoint_imports`.
Existing tables and contact rows are not rebuilt or dropped. Legacy preferences
and peer-table addresses are normalized and imported once per fingerprint in a
transaction, with a seven-day initial lifetime and **no invented success**.
Saved friends retain protected reserves after this initial lifetime. Import
markers prevent expired legacy CSV aliases from repeatedly reviving entries.
Duplicate display-name/fingerprint aliases share the same records.

The existing profile-only backup format does not export this database or route
history; this change does not expand that backup format. An ordinary process
restart keeps metadata in SQLCipher and resets transient active-route state.
Rolling back to an older APK requires a compatible database-version migration or
a pre-upgrade database backup; do not drop tables or reset the database to force
a downgrade.

JNI accepts the legacy lookup-hash array and an additive `{ "self": [...] }`
configuration. Probe arrays remain supported; `{ "fresh": [...], "reserve":
[...] }` adds ordered fallback. Requests are bounded before native dialing.
The new endpoint-result callback is packaged with rebuilt Go/JNI libraries; an
APK build using pre-existing native libraries does not validate this integration.

## Regression verification

Go tests exercise tracker scheduling/minimum intervals, deduplication, lookup
expiry, worker limits/cancellation, ordered reserve fallback, and outbound-only
success after fingerprint verification. Kotlin tests exercise transport-specific
lifetimes, successful-day counting, inactivity, friend reserves, capacity limits,
failure backoff, signed expiry, normalization and additive SQLite migration.
A targeted instrumentation test passed on Pixel 7 / Android 16: it loads the
rebuilt JNI library and exercises SQLCipher import, persistence, friend reserve
retention, success refresh and stale-failure rejection. Live Tor/Yggdrasil and
third-party tracker end-to-end behavior were not tested. See
`endpoint-verification.json` for executed checks and limitations.
