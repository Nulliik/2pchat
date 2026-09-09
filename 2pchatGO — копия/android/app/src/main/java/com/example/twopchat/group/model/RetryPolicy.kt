package com.example.twopchat.group.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.roundToLong

data class RetryPolicy(
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 15L * 60L * 1_000L,
    val jitterFraction: Double = 0.20,
    val maxAttempts: Int = 12,
) {
    init {
        require(baseDelayMs > 0L) { "base delay must be positive" }
        require(maxDelayMs >= baseDelayMs) { "max delay must be at least base delay" }
        require(jitterFraction in 0.0..1.0) { "jitter fraction must be between zero and one" }
        require(maxAttempts > 0) { "max attempts must be positive" }
    }

    /**
     * [attempt] is zero-based. [deterministicInput] should contain stable task
     * identity plus the attempt, making tests and persisted retries reproducible
     * without synchronising a process-global random generator.
     */
    fun delayForAttempt(attempt: Int, deterministicInput: String): Long {
        require(attempt >= 0) { "attempt must not be negative" }
        require(deterministicInput.isNotBlank()) { "deterministic input must not be blank" }

        val exponential = cappedExponential(attempt)
        if (jitterFraction == 0.0) return exponential

        val unit = deterministicUnitInterval("$deterministicInput\u0000$attempt")
        val multiplier = (1.0 - jitterFraction) + (2.0 * jitterFraction * unit)
        return (exponential.toDouble() * multiplier)
            .roundToLong()
            .coerceIn(0L, maxDelayMs)
    }

    fun nextAttemptAt(
        nowMs: Long,
        attempt: Int,
        deterministicInput: String,
    ): Long {
        require(nowMs >= 0L) { "current time must not be negative" }
        val delay = delayForAttempt(attempt, deterministicInput)
        return if (Long.MAX_VALUE - nowMs < delay) Long.MAX_VALUE else nowMs + delay
    }

    fun shouldRetry(completedAttempts: Int): Boolean {
        require(completedAttempts >= 0) { "completed attempts must not be negative" }
        return completedAttempts < maxAttempts
    }

    fun unjitteredDelayForAttempt(attempt: Int): Long {
        require(attempt >= 0) { "attempt must not be negative" }
        return cappedExponential(attempt)
    }

    private fun cappedExponential(attempt: Int): Long {
        if (attempt >= 63) return maxDelayMs
        val multiplier = 1L shl attempt
        if (baseDelayMs > maxDelayMs / multiplier) return maxDelayMs
        return (baseDelayMs * multiplier).coerceAtMost(maxDelayMs)
    }

    private fun deterministicUnitInterval(input: String): Double {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        val value = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
        val upper53Bits = value.toULong().shr(11).toLong()
        return upper53Bits.toDouble() / ((1L shl 53) - 1L).toDouble()
    }
}
