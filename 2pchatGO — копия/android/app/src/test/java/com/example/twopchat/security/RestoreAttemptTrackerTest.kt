package com.example.twopchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreAttemptTrackerTest {

    @Test
    fun testFreeAttemptsNoLockout() {
        val tracker = RestoreAttemptTracker()
        val now = 100_000L

        assertEquals(0L, tracker.getRemainingLockoutMs(now))
        assertFalse(tracker.isLockedOut(now))

        tracker.recordFailure(now)
        assertEquals(0L, tracker.getRemainingLockoutMs(now))

        tracker.recordFailure(now)
        assertEquals(0L, tracker.getRemainingLockoutMs(now))

        tracker.recordFailure(now)
        assertEquals(0L, tracker.getRemainingLockoutMs(now))
    }

    @Test
    fun testExponentialBackoffProgression() {
        val tracker = RestoreAttemptTracker()
        var now = 100_000L

        // 3 free attempts
        tracker.recordFailure(now)
        tracker.recordFailure(now)
        tracker.recordFailure(now)

        // 4th attempt: 1000ms lockout
        now += 100
        tracker.recordFailure(now)
        assertEquals(1000L, tracker.getRemainingLockoutMs(now))
        assertTrue(tracker.isLockedOut(now))

        // After 500ms, 500ms remaining
        assertEquals(500L, tracker.getRemainingLockoutMs(now + 500))

        // After 1000ms, lockout expired
        assertEquals(0L, tracker.getRemainingLockoutMs(now + 1000))
        assertFalse(tracker.isLockedOut(now + 1000))

        // 5th attempt: 2000ms lockout
        now += 1000
        tracker.recordFailure(now)
        assertEquals(2000L, tracker.getRemainingLockoutMs(now))

        // 6th attempt: 4000ms lockout
        now += 2000
        tracker.recordFailure(now)
        assertEquals(4000L, tracker.getRemainingLockoutMs(now))
    }

    @Test
    fun testMaxLockoutCap() {
        val tracker = RestoreAttemptTracker()
        val now = 100_000L

        // Record 20 failures
        repeat(20) {
            tracker.recordFailure(now)
        }

        // Must not exceed maxDelayMs (60,000 ms)
        assertEquals(60_000L, tracker.getRemainingLockoutMs(now))
    }

    @Test
    fun testSuccessResetsTracker() {
        val tracker = RestoreAttemptTracker()
        val now = 100_000L

        repeat(5) {
            tracker.recordFailure(now)
        }
        assertTrue(tracker.isLockedOut(now))

        tracker.recordSuccess()
        assertEquals(0L, tracker.getRemainingLockoutMs(now))
        assertEquals(0, tracker.getFailedAttempts())
        assertFalse(tracker.isLockedOut(now))
    }
}
