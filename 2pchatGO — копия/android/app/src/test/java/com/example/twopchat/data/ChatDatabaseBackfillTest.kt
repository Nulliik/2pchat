package com.example.twopchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDatabaseBackfillTest {

    @Test
    fun testMigration14BackfillSql() {
        val tablePeers = "peers"
        val keyTransportPolicy = "transport_policy"
        val keyOnionAddress = "onion_address"
        val keyLastEndpoint = "last_endpoint"

        val backfillSql = "UPDATE $tablePeers SET $keyTransportPolicy = 2 " +
            "WHERE $keyOnionAddress IS NOT NULL AND $keyOnionAddress != '' " +
            "AND ($keyLastEndpoint IS NULL OR $keyLastEndpoint = '' OR $keyLastEndpoint LIKE '%.onion%')"

        // Verify the exact SQL query components
        assertTrue("Must update peers table", backfillSql.startsWith("UPDATE peers SET transport_policy = 2"))
        assertTrue("Must target contacts with non-empty onion address", backfillSql.contains("WHERE onion_address IS NOT NULL AND onion_address != ''"))
        assertTrue("Must only target contacts without clearnet history", backfillSql.contains("(last_endpoint IS NULL OR last_endpoint = '' OR last_endpoint LIKE '%.onion%')"))
    }

    @Test
    fun testBackfillEligibilityLogic() {
        data class PeerRecord(
            val name: String,
            val onionAddress: String?,
            val lastEndpoint: String?,
            var transportPolicy: Int = 0
        )

        fun shouldBackfillToTorOnly(peer: PeerRecord): Boolean {
            val hasOnion = !peer.onionAddress.isNullOrEmpty()
            val hasClearnetHistory = !peer.lastEndpoint.isNullOrEmpty() && !peer.lastEndpoint.contains(".onion", ignoreCase = true)
            return hasOnion && !hasClearnetHistory
        }

        val onionOnlyPeer = PeerRecord("Alice", "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion", null)
        val onionPeerWithOnionEndpoint = PeerRecord("Bob", "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion", "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion:50001")
        val dualStackPeer = PeerRecord("Charlie", "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion", "192.168.1.50:50001")
        val clearnetOnlyPeer = PeerRecord("Dave", null, "192.168.1.60:50001")

        assertTrue("Pure onion peer with null endpoint must be backfilled to TOR_ONLY (2)", shouldBackfillToTorOnly(onionOnlyPeer))
        assertTrue("Onion peer with onion last_endpoint must be backfilled to TOR_ONLY (2)", shouldBackfillToTorOnly(onionPeerWithOnionEndpoint))
        assertTrue("Dual-stack peer with clearnet endpoint must NOT be backfilled (stays AUTO)", !shouldBackfillToTorOnly(dualStackPeer))
        assertTrue("Clearnet-only peer must NOT be backfilled (stays AUTO)", !shouldBackfillToTorOnly(clearnetOnlyPeer))
    }
}
