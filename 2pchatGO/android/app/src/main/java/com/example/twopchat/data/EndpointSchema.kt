package com.example.twopchat.data

/** Additive SQLCipher migration. Kept separate so the exact production schema
 * can also be exercised with SQLite in host-side migration tests. */
internal object EndpointSchema {
    val statements = listOf(
        """CREATE TABLE IF NOT EXISTS peer_endpoint_records(
            fingerprint TEXT NOT NULL, endpoint TEXT NOT NULL, source TEXT NOT NULL,
            first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
            last_success INTEGER NOT NULL DEFAULT 0, success_days INTEGER NOT NULL DEFAULT 0,
            last_failure INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0,
            retry_after INTEGER NOT NULL DEFAULT 0, advertised_expires INTEGER NOT NULL DEFAULT 0,
            saved_contact INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(fingerprint, endpoint))""",
        """CREATE TABLE IF NOT EXISTS peer_endpoint_imports(
            fingerprint TEXT PRIMARY KEY NOT NULL)""",
    )
}
