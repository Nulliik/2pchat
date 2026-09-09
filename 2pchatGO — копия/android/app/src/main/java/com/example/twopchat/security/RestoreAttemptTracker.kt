package com.example.twopchat.security

import android.content.SharedPreferences
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tracks account restoration attempts with persistent exponential backoff rate limiting.
 *
 * - Attempts 1-3: No delay.
 * - Attempts 4+: Exponential delay starting at 1s, doubling up to 60s max.
 * - Thread-safe via ReentrantLock.
 * - Persisted in SharedPreferences so app restarts cannot bypass lockout.
 */
class RestoreAttemptTracker(
    private val prefs: SharedPreferences? = null,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L,
    private val maxFreeAttempts: Int = 3,
) {
    private val lock = ReentrantLock()

    private var inMemoryAttempts: Int = 0
    private var inMemoryLastAttemptMs: Long = 0L

    init {
        if (prefs != null) {
            inMemoryAttempts = prefs.getInt(KEY_ATTEMPTS, 0)
            inMemoryLastAttemptMs = prefs.getLong(KEY_LAST_ATTEMPT_MS, 0L)
        }
    }

    /**
     * Records a failed restore attempt and persists state.
     */
    fun recordFailure(currentTimeMs: Long = System.currentTimeMillis()) {
        lock.withLock {
            inMemoryAttempts++
            inMemoryLastAttemptMs = currentTimeMs

            prefs?.edit()
                ?.putInt(KEY_ATTEMPTS, inMemoryAttempts)
                ?.putLong(KEY_LAST_ATTEMPT_MS, inMemoryLastAttemptMs)
                ?.apply()
        }
    }

    /**
     * Resets the attempt counter upon successful restore.
     */
    fun recordSuccess() {
        lock.withLock {
            inMemoryAttempts = 0
            inMemoryLastAttemptMs = 0L

            prefs?.edit()
                ?.remove(KEY_ATTEMPTS)
                ?.remove(KEY_LAST_ATTEMPT_MS)
                ?.apply()
        }
    }

    /**
     * Returns remaining lockout milliseconds, or 0 if user is currently allowed to try.
     */
    fun getRemainingLockoutMs(currentTimeMs: Long = System.currentTimeMillis()): Long {
        lock.withLock {
            if (inMemoryAttempts <= maxFreeAttempts) return 0L

            val penaltyExponent = minOf(inMemoryAttempts - maxFreeAttempts - 1, 10)
            val requiredDelay = minOf(baseDelayMs * (1L shl penaltyExponent), maxDelayMs)
            val elapsed = currentTimeMs - inMemoryLastAttemptMs

            return maxOf(0L, requiredDelay - elapsed)
        }
    }

    /**
     * Convenience method returning true if rate limiting is currently active.
     */
    fun isLockedOut(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        return getRemainingLockoutMs(currentTimeMs) > 0L
    }

    /**
     * Returns current failure count.
     */
    fun getFailedAttempts(): Int {
        lock.withLock { return inMemoryAttempts }
    }

    companion object {
        private const val KEY_ATTEMPTS = "restore_failed_attempts"
        private const val KEY_LAST_ATTEMPT_MS = "restore_last_attempt_timestamp_ms"
    }
}
