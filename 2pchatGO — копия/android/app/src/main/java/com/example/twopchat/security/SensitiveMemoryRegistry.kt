package com.example.twopchat.security

import com.example.twopchat.logging.SafeLog
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central registry for managing and purging in-memory sensitive artifacts.
 *
 * Implements Observer pattern:
 * - Thread-safe registration & unregistration via [CopyOnWriteArraySet].
 * - Concurrent execution protection via [AtomicBoolean] fast-path guard.
 * - Per-holder exception isolation to prevent cascading failures.
 */
object SensitiveMemoryRegistry {
    private const val TAG = "SensitiveMemoryRegistry"

    private val holders = CopyOnWriteArraySet<SensitiveMemoryHolder>()
    private val isClearing = AtomicBoolean(false)

    /**
     * Registers a [SensitiveMemoryHolder] to receive cleanup notifications.
     */
    fun register(holder: SensitiveMemoryHolder) {
        holders.add(holder)
    }

    /**
     * Unregisters a [SensitiveMemoryHolder] when component lifecycle ends.
     */
    fun unregister(holder: SensitiveMemoryHolder) {
        holders.remove(holder)
    }

    /**
     * Purges all sensitive in-memory artifacts across all registered holders.
     * Protected against concurrent re-entrant executions.
     */
    fun clearAll() {
        if (!isClearing.compareAndSet(false, true)) {
            SafeLog.d(TAG, "clearAll() skipped: cleanup is already in progress on another thread")
            return
        }

        try {
            SafeLog.i(TAG, "Purging sensitive memory across ${holders.size} registered holders")
            for (holder in holders) {
                try {
                    holder.clearSensitiveMemory()
                } catch (e: Throwable) {
                    SafeLog.e(TAG, "Failed to clear sensitive memory in ${holder.javaClass.name}", e)
                }
            }
        } finally {
            isClearing.set(false)
        }
    }

    /**
     * Returns current number of registered holders (useful in tests).
     */
    @androidx.annotation.VisibleForTesting
    fun getHoldersCount(): Int = holders.size

    /**
     * Clears registered holders set (useful in tests).
     */
    @androidx.annotation.VisibleForTesting
    fun clearHoldersForTesting() {
        holders.clear()
        isClearing.set(false)
    }
}
