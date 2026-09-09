package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class P2POutboundMessengerTest {

    private lateinit var messenger: P2POutboundMessenger

    @Before
    fun setUp() {
        messenger = P2POutboundMessenger(
            peerEndpoints = emptyMap(),
            log = { _, _, _, _ -> },
            onMessageStatusChanged = { _, _, _ -> },
        )
    }

    @Test
    fun testPeerKeyNormalization() {
        assertEquals("alice", messenger.normalizePeerKeyForTest("Alice"))
        assertEquals("alice", messenger.normalizePeerKeyForTest("  ALICE  "))
        assertEquals("gremlin", messenger.normalizePeerKeyForTest("Gremlin"))
        assertEquals("direct peer", messenger.normalizePeerKeyForTest("Direct Peer"))
    }

    @Test
    fun testBackoffProgressionAndCaseInsensitiveSharing() {
        val now = System.currentTimeMillis()
        messenger.recordFailureForTest("Alice", now)

        // Verify backoff is set and shared across different case representations
        val backoff = messenger.getFailureBackoffMs("alice")
        assertNotNull(backoff)
        assertTrue("Backoff should be around 2000ms with jitter, got: $backoff", backoff!! in 1500L..2500L)

        // Both uppercase and whitespace variations must report backoff is active
        assertTrue(messenger.isPeerInBackoff("Alice", now + 500L))
        assertTrue(messenger.isPeerInBackoff(" ALICE ", now + 500L))
        assertTrue(messenger.isPeerInBackoff("alice", now + 500L))

        // After backoff duration expires, peer should not be in backoff
        assertFalse(messenger.isPeerInBackoff("alice", now + 5000L))
    }

    @Test
    fun testConsecutiveFailuresIncreaseBackoff() {
        val now = System.currentTimeMillis()
        messenger.recordFailureForTest("Bob", now)
        val firstBackoff = messenger.getFailureBackoffMs("Bob")!!

        messenger.recordFailureForTest("Bob", now)
        val secondBackoff = messenger.getFailureBackoffMs("Bob")!!

        assertTrue("Second backoff ($secondBackoff) should be greater than first ($firstBackoff)", secondBackoff > firstBackoff)
    }

    @Test
    fun testBackoffCappedAtThirtySeconds() {
        val now = System.currentTimeMillis()
        // Simulate 20 consecutive failures
        repeat(20) {
            messenger.recordFailureForTest("Foxy", now)
        }
        val maxBackoff = messenger.getFailureBackoffMs("Foxy")!!
        assertTrue("Max backoff with jitter should not exceed 35,000ms, got: $maxBackoff", maxBackoff <= 35_000L)
    }

    @Test
    fun testResetPeerBackoffsForSinglePeer() {
        val now = System.currentTimeMillis()
        messenger.recordFailureForTest("Alice", now)
        messenger.recordFailureForTest("Bob", now)

        assertTrue(messenger.isPeerInBackoff("Alice", now))
        assertTrue(messenger.isPeerInBackoff("Bob", now))

        // Reset Alice specifically with different casing
        messenger.resetPeerBackoffs(" ALICE ")

        assertFalse(messenger.isPeerInBackoff("Alice", now))
        assertNull(messenger.getFailureBackoffMs("Alice"))
        // Bob must remain in backoff
        assertTrue(messenger.isPeerInBackoff("Bob", now))
    }

    @Test
    fun testResetAllPeerBackoffsOnNetworkChange() {
        val now = System.currentTimeMillis()
        messenger.recordFailureForTest("Alice", now)
        messenger.recordFailureForTest("Bob", now)
        messenger.recordFailureForTest("Gremlin", now)

        assertTrue(messenger.isPeerInBackoff("Alice", now))
        assertTrue(messenger.isPeerInBackoff("Bob", now))
        assertTrue(messenger.isPeerInBackoff("Gremlin", now))

        // Global reset (invoked on network change or pull-to-refresh)
        messenger.resetPeerBackoffs(null)

        assertFalse(messenger.isPeerInBackoff("Alice", now))
        assertFalse(messenger.isPeerInBackoff("Bob", now))
        assertFalse(messenger.isPeerInBackoff("Gremlin", now))
    }

    @Test
    fun testReconnectDebounceThrottlesRapidConcurrentCalls() {
        val now = System.currentTimeMillis()
        assertTrue(messenger.canAttemptReconnectForTest("Alice", now))

        // Record first attempt
        messenger.recordReconnectAttemptForTest("Alice", now)

        // Immediate subsequent calls within 3000ms must be throttled
        assertFalse(messenger.canAttemptReconnectForTest("Alice", now + 500L))
        assertFalse(messenger.canAttemptReconnectForTest(" ALICE ", now + 1500L))
        assertFalse(messenger.canAttemptReconnectForTest("alice", now + 2999L))

        // Different peer must not be throttled
        assertTrue(messenger.canAttemptReconnectForTest("Bob", now + 500L))

        // After 3000ms window expires, reconnect becomes available again
        assertTrue(messenger.canAttemptReconnectForTest("Alice", now + 3000L))
        assertTrue(messenger.canAttemptReconnectForTest("Alice", now + 5000L))
    }
}
