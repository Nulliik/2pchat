package com.example.twopchat.group.model

import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun retryDelayIsDeterministicForStableTaskAndAttempt() {
        val policy = RetryPolicy()

        (0..30).forEach { attempt ->
            val first = policy.delayForAttempt(attempt, "group:event:replica")
            repeat(10) {
                assertEquals(first, policy.delayForAttempt(attempt, "group:event:replica"))
            }
        }
    }

    @Test
    fun unjitteredBackoffDoublesAndSaturatesWithoutOverflow() {
        val policy = RetryPolicy(
            baseDelayMs = 1_000L,
            maxDelayMs = 10_000L,
            jitterFraction = 0.0,
        )

        assertEquals(1_000L, policy.delayForAttempt(0, "task"))
        assertEquals(2_000L, policy.delayForAttempt(1, "task"))
        assertEquals(4_000L, policy.delayForAttempt(2, "task"))
        assertEquals(8_000L, policy.delayForAttempt(3, "task"))
        assertEquals(10_000L, policy.delayForAttempt(4, "task"))
        assertEquals(10_000L, policy.delayForAttempt(1_000, "task"))
    }

    @Test
    fun everyJitteredDelayStaysWithinConfiguredBounds() {
        val policy = RetryPolicy(
            baseDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            jitterFraction = 0.25,
        )

        (0..100).forEach { attempt ->
            (0..20).forEach { task ->
                val unjittered = policy.unjitteredDelayForAttempt(attempt)
                val lower = floor(unjittered * 0.75).toLong()
                val upper = ceil(unjittered * 1.25).toLong().coerceAtMost(60_000L)
                val actual = policy.delayForAttempt(attempt, "task-$task")
                assertTrue("attempt=$attempt actual=$actual lower=$lower", actual >= lower)
                assertTrue("attempt=$attempt actual=$actual upper=$upper", actual <= upper)
                assertTrue(actual <= policy.maxDelayMs)
            }
        }
    }

    @Test
    fun deterministicInputSpreadsRetryTimes() {
        val policy = RetryPolicy(jitterFraction = 0.50)
        val delays = (0 until 100)
            .map { policy.delayForAttempt(4, "task-$it") }
            .toSet()

        assertTrue("deterministic jitter did not spread tasks", delays.size > 90)
    }

    @Test
    fun retryBudgetHasAnExplicitBoundary() {
        val policy = RetryPolicy(maxAttempts = 5)

        (0 until 5).forEach { assertTrue(policy.shouldRetry(it)) }
        assertFalse(policy.shouldRetry(5))
        assertFalse(policy.shouldRetry(100))
    }

    @Test
    fun nextAttemptTimestampSaturatesAtLongMaxValue() {
        val policy = RetryPolicy(jitterFraction = 0.0)

        assertEquals(
            Long.MAX_VALUE,
            policy.nextAttemptAt(Long.MAX_VALUE - 10L, attempt = 0, deterministicInput = "task"),
        )
    }

    @Test
    fun invalidConfigurationAndInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(baseDelayMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy(baseDelayMs = 2_000L, maxDelayMs = 1_000L)
        }
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(jitterFraction = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(jitterFraction = 1.1) }
        assertThrows(IllegalArgumentException::class.java) { RetryPolicy(maxAttempts = 0) }

        val policy = RetryPolicy()
        assertThrows(IllegalArgumentException::class.java) {
            policy.delayForAttempt(-1, "task")
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.delayForAttempt(0, "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            policy.nextAttemptAt(-1L, 0, "task")
        }
    }
}
