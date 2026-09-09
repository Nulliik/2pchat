package com.example.twopchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerLoggingRateLimitTest {

    @Test
    fun testTrackerSpamSuppression() {
        val trackerUrl = "udp://tracker.opentrackr.org:1337/announce"
        val baseTime = 1000000L

        // 1. Initial failure -> SHOULD log
        val firstLog = NativeBridge.shouldLogTrackerEvent(
            trackerUrl = trackerUrl,
            success = false,
            peerCount = 0,
            detail = "i/o timeout",
            currentTimeMs = baseTime
        )
        assertTrue("First tracker failure must be logged", firstLog)

        // 2. Repeated failure 10 seconds later -> MUST NOT log (suppressed spam)
        val secondLog = NativeBridge.shouldLogTrackerEvent(
            trackerUrl = trackerUrl,
            success = false,
            peerCount = 0,
            detail = "i/o timeout",
            currentTimeMs = baseTime + 10_000L
        )
        assertFalse("Repeated failure after 10s must be suppressed", secondLog)

        // 3. Repeated failure 1 minute later -> MUST NOT log (suppressed spam)
        val thirdLog = NativeBridge.shouldLogTrackerEvent(
            trackerUrl = trackerUrl,
            success = false,
            peerCount = 0,
            detail = "i/o timeout",
            currentTimeMs = baseTime + 60_000L
        )
        assertFalse("Repeated failure after 60s must be suppressed", thirdLog)

        // 4. Transition to success -> MUST log immediately
        val successLog = NativeBridge.shouldLogTrackerEvent(
            trackerUrl = trackerUrl,
            success = true,
            peerCount = 3,
            detail = "",
            currentTimeMs = baseTime + 70_000L
        )
        assertTrue("Transition from fail to success must be logged immediately", successLog)

        // 5. Change in discovered peers count -> MUST log
        val peerCountChangeLog = NativeBridge.shouldLogTrackerEvent(
            trackerUrl = trackerUrl,
            success = true,
            peerCount = 5,
            detail = "",
            currentTimeMs = baseTime + 80_000L
        )
        assertTrue("Change in peer count on success must be logged", peerCountChangeLog)

        // 6. Transition from success to failure -> MUST log immediately
        val failAgainLog = NativeBridge.shouldLogTrackerEvent(
            trackerUrl = trackerUrl,
            success = false,
            peerCount = 0,
            detail = "connection refused",
            currentTimeMs = baseTime + 90_000L
        )
        assertTrue("Transition from success to fail must be logged immediately", failAgainLog)

        // 7. Same failure after 5 minutes (300,001 ms) -> SHOULD log periodic heartbeat
        val periodicLog = NativeBridge.shouldLogTrackerEvent(
            trackerUrl = trackerUrl,
            success = false,
            peerCount = 0,
            detail = "connection refused",
            currentTimeMs = baseTime + 90_000L + 300_001L
        )
        assertTrue("Periodic failure after 5 minutes must be logged", periodicLog)
    }
}
