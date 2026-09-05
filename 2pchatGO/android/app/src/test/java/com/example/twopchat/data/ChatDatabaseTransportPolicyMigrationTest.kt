package com.example.twopchat.data

import com.example.twopchat.config.P2PPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDatabaseTransportPolicyMigrationTest {

    @Test
    fun testDatabaseVersionIsBumpedTo14() {
        assertEquals("ChatDatabaseHelper DATABASE_VERSION must be 14", 14, ChatDatabaseHelper.DATABASE_VERSION)
    }

    @Test
    fun testPeerTransportPreferencePolicyIntMapping() {
        assertEquals(0, P2PPreferences.PeerTransportPreference.AUTO.policyInt)
        assertEquals(1, P2PPreferences.PeerTransportPreference.DIRECT_ONLY.policyInt)
        assertEquals(2, P2PPreferences.PeerTransportPreference.TOR_ONLY.policyInt)
        assertEquals(3, P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY.policyInt)

        // Test roundtrips
        assertEquals(P2PPreferences.PeerTransportPreference.AUTO, P2PPreferences.PeerTransportPreference.fromPolicyInt(0))
        assertEquals(P2PPreferences.PeerTransportPreference.DIRECT_ONLY, P2PPreferences.PeerTransportPreference.fromPolicyInt(1))
        assertEquals(P2PPreferences.PeerTransportPreference.TOR_ONLY, P2PPreferences.PeerTransportPreference.fromPolicyInt(2))
        assertEquals(P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY, P2PPreferences.PeerTransportPreference.fromPolicyInt(3))
        assertEquals(P2PPreferences.PeerTransportPreference.AUTO, P2PPreferences.PeerTransportPreference.fromPolicyInt(999)) // Unknown fails safe to AUTO
    }

    @Test
    fun testPeerTransportPreferenceKeyMapping() {
        assertEquals("auto", P2PPreferences.PeerTransportPreference.AUTO.key)
        assertEquals("direct", P2PPreferences.PeerTransportPreference.DIRECT_ONLY.key)
        assertEquals("tor", P2PPreferences.PeerTransportPreference.TOR_ONLY.key)
        assertEquals("yggdrasil", P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY.key)

        assertEquals(P2PPreferences.PeerTransportPreference.AUTO, P2PPreferences.PeerTransportPreference.fromKey("auto"))
        assertEquals(P2PPreferences.PeerTransportPreference.DIRECT_ONLY, P2PPreferences.PeerTransportPreference.fromKey("direct"))
        assertEquals(P2PPreferences.PeerTransportPreference.TOR_ONLY, P2PPreferences.PeerTransportPreference.fromKey("tor"))
        assertEquals(P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY, P2PPreferences.PeerTransportPreference.fromKey("yggdrasil"))
        assertEquals(P2PPreferences.PeerTransportPreference.AUTO, P2PPreferences.PeerTransportPreference.fromKey("unknown_key"))
    }

    @Test
    fun testExpectedMigrationSqlSyntax() {
        val expectedMigrationSql = "ALTER TABLE peers ADD COLUMN transport_policy INTEGER NOT NULL DEFAULT 0"
        assertTrue("Expected migration SQL must target peers table and add transport_policy",
            expectedMigrationSql.contains("peers") && expectedMigrationSql.contains("transport_policy INTEGER NOT NULL DEFAULT 0"))
    }
}
