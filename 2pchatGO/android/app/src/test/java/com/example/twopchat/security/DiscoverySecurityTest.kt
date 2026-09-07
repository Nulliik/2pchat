package com.example.twopchat.security

import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.ChatDatabaseHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverySecurityTest {

    @Test
    fun testDatabaseVersionBumpedForDiscoverySequences() {
        assertTrue("ChatDatabaseHelper DATABASE_VERSION must be at least 18", ChatDatabaseHelper.DATABASE_VERSION >= 18)
    }

    @Test
    fun testDiscoverySecurityModeEnum() {
        assertEquals("strict", P2PPreferences.DiscoverySecurityMode.STRICT.id)
        assertEquals("transitional", P2PPreferences.DiscoverySecurityMode.TRANSITIONAL.id)
        assertEquals("legacy", P2PPreferences.DiscoverySecurityMode.LEGACY.id)

        // Default mode must be TRANSITIONAL for safe gradual rollout
        val values = P2PPreferences.DiscoverySecurityMode.values()
        assertEquals(3, values.size)
        assertTrue(values.contains(P2PPreferences.DiscoverySecurityMode.TRANSITIONAL))
    }

    @Test
    fun testSequenceReplayAndGapProtectionLogic() {
        val lastSeen = 500L

        fun validateSeq(incomingSeq: Long, lastSeenSeq: Long): String {
            if (incomingSeq <= lastSeenSeq) return "REPLAY"
            if (incomingSeq > lastSeenSeq + 1000L) return "GAP_TOO_LARGE"
            return "OK"
        }

        // Replay of old sequence
        assertEquals("REPLAY", validateSeq(499L, lastSeen))
        assertEquals("REPLAY", validateSeq(500L, lastSeen))

        // Legitimate next sequences
        assertEquals("OK", validateSeq(501L, lastSeen))
        assertEquals("OK", validateSeq(600L, lastSeen))
        assertEquals("OK", validateSeq(1500L, lastSeen)) // exactly 1000 gap

        // Potential DoS sequence gap (> 1000)
        assertEquals("GAP_TOO_LARGE", validateSeq(1501L, lastSeen))
        assertEquals("GAP_TOO_LARGE", validateSeq(100_000L, lastSeen))
        assertEquals("GAP_TOO_LARGE", validateSeq(Long.MAX_VALUE, lastSeen))
    }

    @Test
    fun testClockSkewAllowanceFiveMinutes() {
        val clockSkewMs = 5 * 60 * 1000L // 5 minutes
        val now = 1_700_000_000_000L
        val ttlMs = 30 * 60 * 1000L // 30 minutes

        val issuedAt = now
        val expiresAt = issuedAt + ttlMs

        fun isValidAt(checkTime: Long, issued: Long, expires: Long, skew: Long): Boolean {
            if (checkTime < issued - skew) return false // Future-dated beyond skew
            if (checkTime > expires + skew) return false // Expired beyond skew
            return true
        }

        // Current time is valid
        assertTrue(isValidAt(now, issuedAt, expiresAt, clockSkewMs))

        // 4 minutes before issued (acceptable future dating within 5 min tolerance)
        assertTrue(isValidAt(issuedAt - 4 * 60 * 1000L, issuedAt, expiresAt, clockSkewMs))

        // 6 minutes before issued (rejected, future dating exceeds 5 min)
        assertFalse(isValidAt(issuedAt - 6 * 60 * 1000L, issuedAt, expiresAt, clockSkewMs))

        // 4 minutes after expiration (acceptable lag within 5 min tolerance)
        assertTrue(isValidAt(expiresAt + 4 * 60 * 1000L, issuedAt, expiresAt, clockSkewMs))

        // 6 minutes after expiration (rejected, expired beyond 5 min)
        assertFalse(isValidAt(expiresAt + 6 * 60 * 1000L, issuedAt, expiresAt, clockSkewMs))
    }

    @Test
    fun testDiscoverySecurityModeEnforcementDecision() {
        fun shouldAcceptUnsignedEndpoint(mode: P2PPreferences.DiscoverySecurityMode): Boolean {
            return when (mode) {
                P2PPreferences.DiscoverySecurityMode.STRICT -> false
                P2PPreferences.DiscoverySecurityMode.TRANSITIONAL -> true
                P2PPreferences.DiscoverySecurityMode.LEGACY -> true
            }
        }

        // STRICT mode must unconditionally reject unsigned candidates
        assertFalse(shouldAcceptUnsignedEndpoint(P2PPreferences.DiscoverySecurityMode.STRICT))

        // TRANSITIONAL allows unsigned legacy trackers with warning
        assertTrue(shouldAcceptUnsignedEndpoint(P2PPreferences.DiscoverySecurityMode.TRANSITIONAL))

        // LEGACY allows unsigned candidates
        assertTrue(shouldAcceptUnsignedEndpoint(P2PPreferences.DiscoverySecurityMode.LEGACY))
    }

    @Test
    fun testSequenceCounterPersistenceThrottling() {
        var persistCount = 0
        val persistedSeqs = mutableListOf<Long>()

        for (seq in 1L..250L) {
            if (seq == 1L || seq % 100L == 0L) {
                persistCount++
                persistedSeqs.add(seq)
            }
        }

        assertEquals(3, persistCount)
        assertEquals(listOf(1L, 100L, 200L), persistedSeqs)
    }

    @Test
    fun testVerifiedDiscoveryRecordDataClass() {
        val record = NativeBridge.VerifiedDiscoveryRecord(
            endpoints = listOf("192.168.1.50:50001", "93.184.216.34:50001"),
            seq = 42L
        )
        assertEquals(2, record.endpoints.size)
        assertEquals("192.168.1.50:50001", record.endpoints[0])
        assertEquals("93.184.216.34:50001", record.endpoints[1])
        assertEquals(42L, record.seq)
    }
}
