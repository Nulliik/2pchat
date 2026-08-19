package com.example.twopchat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Orders asynchronous presence observations.
 *
 * Session callbacks and the periodic maintenance probe run on different
 * threads. A probe result can therefore be older than a session callback even
 * when it reaches the main thread later. Versions let callers discard that
 * stale result instead of making the UI briefly show the peer as offline.
 */
internal class PeerPresenceVersionTracker {
    private val versions = ConcurrentHashMap<String, AtomicLong>()

    fun current(peerName: String): Long =
        versions.computeIfAbsent(peerName) { AtomicLong() }.get()

    fun advance(peerName: String): Long =
        versions.computeIfAbsent(peerName) { AtomicLong() }.incrementAndGet()

    fun advanceIfCurrent(peerName: String, expectedVersion: Long): Long? {
        val version = versions.computeIfAbsent(peerName) { AtomicLong() }
        return if (version.compareAndSet(expectedVersion, expectedVersion + 1)) {
            expectedVersion + 1
        } else {
            null
        }
    }

    fun remove(peerName: String) {
        versions.remove(peerName)
    }

    fun clear() {
        versions.clear()
    }
}
