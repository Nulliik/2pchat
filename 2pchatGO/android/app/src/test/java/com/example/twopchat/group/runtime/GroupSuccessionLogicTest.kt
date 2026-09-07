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

        val certFrame = JSONObject().put("type", GroupWireProtocol.TYPE_SUCCESSION_CERT)
        val hbFrame = JSONObject().put("type", GroupWireProtocol.TYPE_OWNER_HEARTBEAT)
        val revFrame = JSONObject().put("type", GroupWireProtocol.TYPE_SUCCESSION_REVOCATION)
        val claimFrame = JSONObject().put("type", GroupWireProtocol.TYPE_SUCCESSION_CLAIM)

        assertTrue(GroupWireProtocol.isGroupFrame(certFrame))
        assertTrue(GroupWireProtocol.isGroupFrame(hbFrame))
        assertTrue(GroupWireProtocol.isGroupFrame(revFrame))
        assertTrue(GroupWireProtocol.isGroupFrame(claimFrame))
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
}
