package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for network transitions, flakiness, handover, and reconnect debouncing.
 */
class NetworkChaosHandoverTest {

    @Test
    fun testNetworkHandoverResetsBackoffsAndInvalidatesStaleEndpoints() {
        val peerBackoffs = mutableMapOf("alice" to 15000L, "bob" to 30000L)
        val activeEndpoints = mutableMapOf("alice" to "192.168.1.50:50001", "bob" to "192.168.1.51:50001")

        // Network change event occurs (e.g. Wi-Fi -> Cellular handover)
        fun onNetworkHandover(isWifiAvailable: Boolean) {
            peerBackoffs.clear()
            if (!isWifiAvailable) {
                // Invalidate local LAN endpoints since Wi-Fi is lost
                activeEndpoints.entries.removeIf { it.value.startsWith("192.168.") || it.value.startsWith("10.") }
            }
        }

        onNetworkHandover(isWifiAvailable = false)

        // Backoffs must be completely cleared so messages can attempt immediate delivery on new network
        assertTrue(peerBackoffs.isEmpty())

        // LAN endpoints must be invalidated
        assertTrue(activeEndpoints.isEmpty())
    }

    @Test
    fun testFlappingNetworkDebouncePreventsConnectionStorm() {
        var connectionAttempts = 0
        var lastAttemptTime = 0L
        val debounceWindowMs = 2000L

        fun attemptConnect(currentTimeMs: Long): Boolean {
            if (currentTimeMs - lastAttemptTime < debounceWindowMs) {
                return false // Debounced / throttled
            }
            lastAttemptTime = currentTimeMs
            connectionAttempts++
            return true
        }

        val baseTime = 10000L

        // Rapid flapping events within 500ms
        assertTrue(attemptConnect(baseTime))
        assertFalse(attemptConnect(baseTime + 100L))
        assertFalse(attemptConnect(baseTime + 300L))
        assertFalse(attemptConnect(baseTime + 500L))
        assertFalse(attemptConnect(baseTime + 1999L))

        // Event after debounce window expires
        assertTrue(attemptConnect(baseTime + 2001L))

        assertEquals(2, connectionAttempts)
    }

    @Test
    fun testSocketTimeoutConfigurationForFlakyConnections() {
        fun getSocketTimeoutMs(isTor: Boolean, isYggdrasil: Boolean): Int {
            return when {
                isTor -> 45_000 // Tor circuits need higher timeout tolerance
                isYggdrasil -> 15_000 // Mesh routing
                else -> 8_000 // Direct LAN
            }
        }

        assertEquals(8_000, getSocketTimeoutMs(isTor = false, isYggdrasil = false))
        assertEquals(15_000, getSocketTimeoutMs(isTor = false, isYggdrasil = true))
        assertEquals(45_000, getSocketTimeoutMs(isTor = true, isYggdrasil = false))
    }
}
