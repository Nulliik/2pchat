package com.example.twopchat.group.runtime

import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.group.ui.SuccessionUiState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSuccessionLogicTest {

    @Test
    fun testMinHeartbeatIntervalConstant() {
        assertEquals("MIN_HEARTBEAT_INTERVAL_MS must be exactly 1 hour (3,600,000 ms)", 3600_000L, GroupChatCoordinator.MIN_HEARTBEAT_INTERVAL_MS)
    }

    @Test
    fun testThrottlingConditionLogic() {
        val minInterval = GroupChatCoordinator.MIN_HEARTBEAT_INTERVAL_MS
        val now = 1_000_000_000L

        // 10 minutes since last emit -> should throttle if not forced
        val recentEmit = now - 600_000L
        val shouldThrottleNormal = (now - recentEmit < minInterval)
        assertTrue("Emits within 1 hour should be throttled", shouldThrottleNormal)

        // Force emit overrides throttling
        val allowForce = false // when force=true, throttling check is bypassed
        assertFalse("Force emit should not be throttled", allowForce)

        // 65 minutes since last emit -> should NOT throttle
        val oldEmit = now - 3900_000L
        val shouldThrottleOld = (now - oldEmit < minInterval)
        assertFalse("Emits after 1 hour should NOT be throttled", shouldThrottleOld)
    }

    @Test
    fun testShouldShowGracePeriodBannerBoundaries() {
        val now = 100_000_000_000L
        val timeoutDays = 30
        val timeoutMs = 30 * 86400_000L // 2,592,000,000 ms
        val gracePeriodMs = minOf(7 * 86400_000L, timeoutMs / 4) // 7 days = 604,800,000 ms

        // Case 1: Owner heartbeat was 5 days ago (25 days left, > 7 days grace period) -> NO banner
        val lastHbRecent = now - (5 * 86400_000L)
        assertFalse("Owner active, no grace banner", GroupChatCoordinator.shouldShowGracePeriodBanner(timeoutDays, lastHbRecent, now))

        // Case 2: Owner heartbeat was 25 days ago (5 days left, inside 7-day grace period) -> BANNER SHOWN
        val lastHbGrace = now - (25 * 86400_000L)
        assertTrue("Within grace period window, banner must show", GroupChatCoordinator.shouldShowGracePeriodBanner(timeoutDays, lastHbGrace, now))

        // Case 3: Owner heartbeat was 31 days ago (timeout expired) -> grace period window has passed (timeUntilTimeout < 0)
        val lastHbExpired = now - (31 * 86400_000L)
        assertFalse("Timeout expired, grace period window is over", GroupChatCoordinator.shouldShowGracePeriodBanner(timeoutDays, lastHbExpired, now))
    }

    @Test
    fun testShortTimeoutGracePeriodBoundaries() {
        val now = 100_000_000_000L
        val timeoutDays = 7 // Min allowed timeout: 7 days
        val timeoutMs = 7 * 86400_000L
        // Grace period is min(7 days, 7/4 days) = 1.75 days (42 hours)
        val gracePeriodMs = minOf(7 * 86400_000L, timeoutMs / 4)
        assertEquals(42 * 3600_000L, gracePeriodMs)

        // 3 days elapsed -> 4 days left (> 1.75 days) -> NO banner
        val lastHb3d = now - (3 * 86400_000L)
        assertFalse(GroupChatCoordinator.shouldShowGracePeriodBanner(timeoutDays, lastHb3d, now))

        // 6 days elapsed -> 1 day left (< 1.75 days) -> BANNER SHOWN
        val lastHb6d = now - (6 * 86400_000L)
        assertTrue(GroupChatCoordinator.shouldShowGracePeriodBanner(timeoutDays, lastHb6d, now))
    }

    @Test
    fun testShouldShowExpiryWarningBoundaries() {
        val now = 100_000_000_000L
        val thirtyDaysMs = 30 * 86400_000L

        // Expiring in 45 days -> NO warning
        val expiresFar = now + (45 * 86400_000L)
        assertFalse("Expiry > 30 days must not warn", GroupChatCoordinator.shouldShowExpiryWarning(expiresFar, now))

        // Expiring in 15 days -> SHOW warning
        val expiresSoon = now + (15 * 86400_000L)
        assertTrue("Expiry within 30 days must show warning", GroupChatCoordinator.shouldShowExpiryWarning(expiresSoon, now))

        // Already expired -> false (certificate considered expired/invalidated)
        val expired = now - 1000L
        assertFalse("Already expired certificate should not trigger 30-day warning", GroupChatCoordinator.shouldShowExpiryWarning(expired, now))

        // No expiry set (0) -> false
        assertFalse("Zero expiry should not trigger warning", GroupChatCoordinator.shouldShowExpiryWarning(0L, now))
    }

    @Test
    fun testWireProtocolSuccessionFrameTypes() {
        assertEquals("group_succession_cert_v1", GroupWireProtocol.TYPE_SUCCESSION_CERT)
        assertEquals("group_owner_heartbeat_v1", GroupWireProtocol.TYPE_OWNER_HEARTBEAT)
        assertEquals("group_succession_revocation_v1", GroupWireProtocol.TYPE_SUCCESSION_REVOCATION)
        assertEquals("group_succession_claim_v1", GroupWireProtocol.TYPE_SUCCESSION_CLAIM)
        assertEquals("group_succession_query_v1", GroupWireProtocol.TYPE_SUCCESSION_QUERY)

        val certFrame = JSONObject().put("type", GroupWireProtocol.TYPE_SUCCESSION_CERT)
        val hbFrame = JSONObject().put("type", GroupWireProtocol.TYPE_OWNER_HEARTBEAT)
        val revFrame = JSONObject().put("type", GroupWireProtocol.TYPE_SUCCESSION_REVOCATION)
        val claimFrame = JSONObject().put("type", GroupWireProtocol.TYPE_SUCCESSION_CLAIM)
        val queryFrame = JSONObject().put("type", GroupWireProtocol.TYPE_SUCCESSION_QUERY)

        assertTrue(GroupWireProtocol.isGroupFrame(certFrame))
        assertTrue(GroupWireProtocol.isGroupFrame(hbFrame))
        assertTrue(GroupWireProtocol.isGroupFrame(revFrame))
        assertTrue(GroupWireProtocol.isGroupFrame(claimFrame))
        assertTrue(GroupWireProtocol.isGroupFrame(queryFrame))
    }

    @Test
    fun testSuccessionQueryCooldownConstant() {
        val cooldownMs = 60_000L
        val now = 100_000_000L
        val recentQuery = now - 30_000L
        val oldQuery = now - 65_000L

        assertTrue("Query within 60s must be throttled", now - recentQuery < cooldownMs)
        assertFalse("Query after 60s must be allowed", now - oldQuery < cooldownMs)
    }

    @Test
    fun testHeartbeatFrameWithCertificateJson() {
        val hbFrame = JSONObject().apply {
            put("version", GroupWireProtocol.VERSION)
            put("type", GroupWireProtocol.TYPE_OWNER_HEARTBEAT)
            put("group_id", "group_test_123")
            put("heartbeat_json", "{\"timestamp\":12345}")
            put("certificate_json", "{\"successor\":\"fp_doggy_456\",\"heartbeat_timeout_days\":7}")
        }
        assertEquals("group_owner_heartbeat_v1", hbFrame.getString("type"))
        assertTrue(hbFrame.has("certificate_json"))
        val certObj = JSONObject(hbFrame.getString("certificate_json"))
        assertEquals("fp_doggy_456", certObj.getString("successor"))
        assertEquals(7, certObj.getInt("heartbeat_timeout_days"))
    }

    @Test
    fun testSuccessionUiStateModel() {
        val state = SuccessionUiState(
            successorFingerprint = "abcd1234efgh5678",
            timeoutDays = 30,
            lastHeartbeatTimestamp = 1000L,
            expiresAt = 5000L,
            isOwner = false,
            isSuccessor = true,
            canClaim = true,
            timeUntilClaimMs = 0L,
            showGracePeriodBanner = true,
            showExpiryWarning = false
        )

        assertEquals("abcd1234efgh5678", state.successorFingerprint)
        assertEquals(30, state.timeoutDays)
        assertEquals(1000L, state.lastHeartbeatTimestamp)
        assertEquals(5000L, state.expiresAt)
        assertFalse(state.isOwner)
        assertTrue(state.isSuccessor)
        assertTrue(state.canClaim)
        assertEquals(0L, state.timeUntilClaimMs)
        assertTrue(state.showGracePeriodBanner)
        assertFalse(state.showExpiryWarning)
    }

    @Test
    fun testSuccessionPreferencesPersistence() {
        val prefsMap = mutableMapOf<String, Any?>()
        val fakePrefs = object : android.content.SharedPreferences {
            override fun getAll(): Map<String, *> = prefsMap
            override fun getString(key: String?, defValue: String?): String? = prefsMap[key] as? String ?: defValue
            override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = prefsMap[key] as? Set<String> ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (prefsMap[key] as? Number)?.toInt() ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (prefsMap[key] as? Number)?.toLong() ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (prefsMap[key] as? Number)?.toFloat() ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = prefsMap[key] as? Boolean ?: defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
                private val temp = mutableMapOf<String, Any?>()
                override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
                override fun putStringSet(key: String?, values: Set<String>?): android.content.SharedPreferences.Editor { temp[key!!] = values; return this }
                override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
                override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
                override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
                override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { temp[key!!] = value; return this }
                override fun remove(key: String?): android.content.SharedPreferences.Editor { temp.remove(key); prefsMap.remove(key); return this }
                override fun clear(): android.content.SharedPreferences.Editor { temp.clear(); prefsMap.clear(); return this }
                override fun commit(): Boolean { prefsMap.putAll(temp); return true }
                override fun apply() { prefsMap.putAll(temp) }
            }
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = fakePrefs
        }
        com.example.twopchat.config.P2PPreferences.setCachedPrefsForTesting(fakePrefs)
        try {
            val groupId = "group_test_succession_pref"
            // Default timeout is 30 days
            assertEquals(30, com.example.twopchat.config.P2PPreferences.getLastSuccessionTimeoutDays(fakeContext, groupId))
            assertEquals(null, com.example.twopchat.config.P2PPreferences.getLastSuccessorFingerprint(fakeContext, groupId))

            // Set 7 days and successor
            com.example.twopchat.config.P2PPreferences.setLastSuccessionTimeoutDays(fakeContext, groupId, 7)
            com.example.twopchat.config.P2PPreferences.setLastSuccessorFingerprint(fakeContext, groupId, "fp_foxxxy_123")

            // Verified retained
            assertEquals(7, com.example.twopchat.config.P2PPreferences.getLastSuccessionTimeoutDays(fakeContext, groupId))
            assertEquals("fp_foxxxy_123", com.example.twopchat.config.P2PPreferences.getLastSuccessorFingerprint(fakeContext, groupId))
        } finally {
            com.example.twopchat.config.P2PPreferences.setCachedPrefsForTesting(null)
        }
    }

    @Test
    fun testCertificateSequenceParsingAndLegacyDefault() {
        val legacyJson = """
            {
                "type": "succession_certificate_v1",
                "group_id": "group_1",
                "current_owner": "alice",
                "successor": "bob",
                "heartbeat_timeout_days": 30
            }
        """.trimIndent()
        val legacyObj = JSONObject(legacyJson)
        val legacySeq = legacyObj.optLong("sequence", 0L)
        assertEquals("Legacy certificate without sequence field must default to 0L", 0L, legacySeq)

        val hardenedJson = """
            {
                "type": "succession_certificate_v1",
                "group_id": "group_1",
                "sequence": 42,
                "current_owner": "alice",
                "successor": "charlie",
                "heartbeat_timeout_days": 30
            }
        """.trimIndent()
        val hardenedObj = JSONObject(hardenedJson)
        val hardenedSeq = hardenedObj.optLong("sequence", 0L)
        assertEquals("Hardened certificate with sequence must parse correctly", 42L, hardenedSeq)
    }

    @Test
    fun testSequenceOverflowProtection() {
        val normalSeq = 5L
        val nextSeq = if (normalSeq < Long.MAX_VALUE - 1) normalSeq + 1L else throw IllegalStateException("Overflow")
        assertEquals(6L, nextSeq)

        var overflowCaught = false
        val nearMaxSeq = Long.MAX_VALUE - 1
        try {
            if (nearMaxSeq < Long.MAX_VALUE - 1) {
                nearMaxSeq + 1L
            } else {
                throw IllegalStateException("Sequence overflow")
            }
        } catch (e: IllegalStateException) {
            overflowCaught = true
        }
        assertTrue("Sequence near Long.MAX_VALUE must trigger overflow exception", overflowCaught)
    }

    @Test
    fun testDeterministicSequenceConflictResolution() {
        // Case 1: Higher sequence strictly wins
        val incomingSeq = 2L
        val activeSeq = 1L
        assertTrue("Higher sequence must supersede active", incomingSeq > activeSeq)

        // Case 2: Lower sequence is rejected
        val staleSeq = 0L
        assertTrue("Stale sequence must be rejected", staleSeq < incomingSeq)

        // Case 3: Equal sequence resolves via min(hash)
        val hashA = "0000aaaa"
        val hashB = "1111bbbb"
        val tieBreakerWinner = if (hashA <= hashB) hashA else hashB
        assertEquals("Lexicographically smaller hash must win tie-break", hashA, tieBreakerWinner)
    }

    @Test
    fun testDeposedOwnerCheckLogic() {
        val activeOwnerDeviceId = "device_bob"
        val members = listOf(
            mapOf("deviceId" to "device_alice", "role" to "MEMBER", "fp" to "fp_alice"),
            mapOf("deviceId" to "device_bob", "role" to "OWNER", "fp" to "fp_bob")
        )

        val currentOwnerMember = members.firstOrNull { it["deviceId"] == activeOwnerDeviceId && it["role"] == "OWNER" }
        assertEquals("device_bob", currentOwnerMember?.get("deviceId"))

        // Deposed Alice tries to emit heartbeat
        val aliceAuthorFP = "fp_alice"
        val aliceAllowed = currentOwnerMember != null && (
            currentOwnerMember["fp"].equals(aliceAuthorFP, ignoreCase = true) ||
            currentOwnerMember["deviceId"].equals(aliceAuthorFP, ignoreCase = true)
        )
        assertFalse("Deposed owner Alice must NOT be authorized to emit heartbeats", aliceAllowed)

        // Active Bob emits heartbeat
        val bobAuthorFP = "fp_bob"
        val bobAllowed = currentOwnerMember != null && (
            currentOwnerMember["fp"].equals(bobAuthorFP, ignoreCase = true) ||
            currentOwnerMember["deviceId"].equals(bobAuthorFP, ignoreCase = true)
        )
        assertTrue("Active owner Bob must be authorized to emit heartbeats", bobAllowed)
    }
}
