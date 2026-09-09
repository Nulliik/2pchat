package com.example.twopchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDatabaseSuccessionMigrationTest {

    @Test
    fun testDatabaseVersionIncludesEndpointRetentionMigration() {
        assertEquals("ChatDatabaseHelper DATABASE_VERSION must be 20", 20, ChatDatabaseHelper.DATABASE_VERSION)
    }

    @Test
    fun testGroupSuccessionStateTableSchema() {
        val createSql = """
            CREATE TABLE IF NOT EXISTS group_succession_state (
                group_id TEXT PRIMARY KEY NOT NULL,
                active_certificate_json TEXT,
                last_heartbeat_json TEXT,
                last_heartbeat_hash TEXT,
                is_revoked INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent()

        assertTrue(createSql.contains("group_id TEXT PRIMARY KEY NOT NULL"))
        assertTrue(createSql.contains("active_certificate_json TEXT"))
        assertTrue(createSql.contains("last_heartbeat_json TEXT"))
        assertTrue(createSql.contains("last_heartbeat_hash TEXT"))
        assertTrue(createSql.contains("is_revoked INTEGER NOT NULL DEFAULT 0"))
        assertTrue(createSql.contains("updated_at INTEGER NOT NULL"))
    }

    @Test
    fun testRevokedSuccessionCertificatesTableSchema() {
        val createSql = """
            CREATE TABLE IF NOT EXISTS revoked_succession_certificates (
                cert_hash TEXT PRIMARY KEY NOT NULL,
                group_id TEXT NOT NULL,
                revoked_at INTEGER NOT NULL
            )
        """.trimIndent()

        assertTrue(createSql.contains("cert_hash TEXT PRIMARY KEY NOT NULL"))
        assertTrue(createSql.contains("group_id TEXT NOT NULL"))
        assertTrue(createSql.contains("revoked_at INTEGER NOT NULL"))
    }

    @Test
    fun testStoredGroupSuccessionModel() {
        val record = ChatDatabaseHelper.StoredGroupSuccession(
            groupId = "group-123",
            activeCertJson = "{\"group_id\":\"group-123\"}",
            lastHbJson = "{\"sequence\":1}",
            lastHbHash = "hash123",
            isRevoked = false,
            updatedAt = 1000L
        )

        assertEquals("group-123", record.groupId)
        assertEquals("{\"group_id\":\"group-123\"}", record.activeCertJson)
        assertEquals("{\"sequence\":1}", record.lastHbJson)
        assertEquals("hash123", record.lastHbHash)
        assertEquals(false, record.isRevoked)
        assertEquals(1000L, record.updatedAt)
    }
}
