package com.example.twopchat.data

import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Test

class EndpointSchemaTest {
    @Test fun additiveMigrationAndRouteCleanupPreserveContacts() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                sql.execute("CREATE TABLE peers(peer_name TEXT PRIMARY KEY, fingerprint TEXT, onion_address TEXT)")
                sql.execute("INSERT INTO peers VALUES ('friend', 'identity', 'saved.onion')")
                // Exercise the same statements used by ChatDatabaseHelper v20,
                // including their idempotence after a partially restored schema.
                repeat(2) { EndpointSchema.statements.forEach(sql::execute) }
                sql.execute("INSERT INTO peer_endpoint_records(fingerprint,endpoint,source,first_seen,last_seen,saved_contact) VALUES ('identity','saved.onion:50001','MIGRATED',100,100,1)")
                sql.executeQuery("SELECT last_success,success_days FROM peer_endpoint_records").use {
                    assertTrue(it.next()); assertEquals(0L, it.getLong(1)); assertEquals(0, it.getInt(2))
                }
                sql.execute("DELETE FROM peer_endpoint_records")
                sql.executeQuery("SELECT peer_name,fingerprint,onion_address FROM peers").use {
                    assertTrue(it.next()); assertEquals("friend", it.getString(1))
                    assertEquals("identity", it.getString(2)); assertEquals("saved.onion", it.getString(3))
                    assertFalse(it.next())
                }
            }
        }
    }

    @Test fun sameIdentityAndEndpointHaveOneRowRegardlessOfAlias() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { sql ->
                EndpointSchema.statements.forEach(sql::execute)
                repeat(2) { index ->
                    sql.execute("INSERT OR REPLACE INTO peer_endpoint_records(fingerprint,endpoint,source,first_seen,last_seen) VALUES ('identity','[200::1]:50001','AUTHENTICATED',100,${100 + index})")
                }
                sql.executeQuery("SELECT COUNT(*),MAX(last_seen) FROM peer_endpoint_records").use {
                    assertTrue(it.next()); assertEquals(1, it.getInt(1)); assertEquals(101, it.getInt(2))
                }
            }
        }
    }
}
