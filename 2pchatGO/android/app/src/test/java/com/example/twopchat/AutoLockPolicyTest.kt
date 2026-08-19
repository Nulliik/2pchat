package com.example.twopchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockPolicyTest {

    private fun shouldLockOnResume(
        hasPasscodeConfigured: Boolean,
        isCurrentlyLocked: Boolean,
        timeoutMinutes: Int,
        now: Long,
        pauseTime: Long,
        lastInteractionTime: Long
    ): Boolean {
        if (!hasPasscodeConfigured || isCurrentlyLocked) return false
        val elapsedSincePause = if (pauseTime > 0L) now - pauseTime else 0L
        val elapsedSinceInteraction = now - lastInteractionTime
        val effectiveElapsed = maxOf(elapsedSincePause, elapsedSinceInteraction)
        return effectiveElapsed >= timeoutMinutes * 60 * 1000L
    }

    private fun shouldLockOnInactivity(
        hasPasscodeConfigured: Boolean,
        isCurrentlyLocked: Boolean,
        timeoutMinutes: Int,
        now: Long,
        lastInteractionTime: Long
    ): Boolean {
        if (!hasPasscodeConfigured || isCurrentlyLocked) return false
        val elapsed = now - lastInteractionTime
        return elapsed >= timeoutMinutes * 60 * 1000L
    }

    @Test
    fun testResumeLocksWhenTimeoutExceeded() {
        val now = 100_000_000L
        val pauseTime = now - (65 * 1000L) // paused 65s ago
        val lastInteraction = now - (70 * 1000L) // last interaction 70s ago

        val locks = shouldLockOnResume(
            hasPasscodeConfigured = true,
            isCurrentlyLocked = false,
            timeoutMinutes = 1,
            now = now,
            pauseTime = pauseTime,
            lastInteractionTime = lastInteraction
        )
        assertTrue("App should lock when background duration exceeds timeout", locks)
    }

    @Test
    fun testResumeDoesNotLockWhenWithinTimeout() {
        val now = 100_000_000L
        val pauseTime = now - (30 * 1000L) // paused 30s ago
        val lastInteraction = now - (40 * 1000L) // last interaction 40s ago

        val locks = shouldLockOnResume(
            hasPasscodeConfigured = true,
            isCurrentlyLocked = false,
            timeoutMinutes = 1,
            now = now,
            pauseTime = pauseTime,
            lastInteractionTime = lastInteraction
        )
        assertFalse("App should not lock when within timeout", locks)
    }

    @Test
    fun testInactivityLocksWhenTimeoutReached() {
        val now = 100_000_000L
        val lastInteraction = now - (60 * 1000L) // 1 minute of inactivity

        val locks = shouldLockOnInactivity(
            hasPasscodeConfigured = true,
            isCurrentlyLocked = false,
            timeoutMinutes = 1,
            now = now,
            lastInteractionTime = lastInteraction
        )
        assertTrue("App should lock on foreground inactivity", locks)
    }

    @Test
    fun testNoLockWhenPasscodeNotConfigured() {
        val now = 100_000_000L
        val pauseTime = now - (300 * 1000L)
        val lastInteraction = now - (300 * 1000L)

        val locks = shouldLockOnResume(
            hasPasscodeConfigured = false,
            isCurrentlyLocked = false,
            timeoutMinutes = 1,
            now = now,
            pauseTime = pauseTime,
            lastInteractionTime = lastInteraction
        )
        assertFalse("App should not lock if passcode is disabled", locks)
    }
}
