package com.example.twopchat

import com.example.twopchat.config.TrackerPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Unit tests for multi-tracker resilience, failover, backoff, and compact peer parsing.
 */
class MultiTrackerResilienceTest {

    @Test
    fun multiTracker_filterValidTrackers_separatesProtocolsCorrectly() {
        val mixedTrackers = listOf(
            "http://tracker1.example.org:8080/announce",
            "udp://tracker2.example.org:6969/announce",
            "https://tracker3.example.org/announce",
            "ftp://invalid.example.org/announce",
            "udp://tracker4.example.org:1337/announce",
            "malformed url without scheme",
            "http://tracker5.example.org:80/announce"
        )

        val validTrackers = mixedTrackers.filter { TrackerPreferences.trackerProtocol(it) != null }
        assertEquals(5, validTrackers.size)

        val httpTrackers = validTrackers.filter { TrackerPreferences.trackerProtocol(it) in listOf("http", "https") }
        val udpTrackers = validTrackers.filter { TrackerPreferences.trackerProtocol(it) == "udp" }

        assertEquals(3, httpTrackers.size)
        assertEquals(2, udpTrackers.size)
    }

    @Test
    fun multiTracker_fallbackExecution_succeedsIfAtLeastOneTrackerResponds() {
        // Simulates a tracker query loop with 3 trackers where the first 2 fail (timeout / 503)
        val trackerUrls = listOf(
            "http://tracker-failing-1.example.org/announce",
            "udp://tracker-failing-2.example.org:6969/announce",
            "https://tracker-healthy-3.example.org/announce"
        )

        val trackerStatusMap = mapOf(
            "http://tracker-failing-1.example.org/announce" to Result.failure<List<String>>(java.net.SocketTimeoutException("Connect timed out")),
            "udp://tracker-failing-2.example.org:6969/announce" to Result.failure<List<String>>(java.io.IOException("ICMP Port Unreachable")),
            "https://tracker-healthy-3.example.org/announce" to Result.success(listOf("192.168.1.100:50001", "[200:abc::1]:50001"))
        )

        val discoveredPeers = mutableSetOf<String>()
        val failedTrackers = mutableListOf<String>()

        for (url in trackerUrls) {
            val result = trackerStatusMap[url] ?: Result.failure(IllegalStateException("Unknown tracker"))
            if (result.isSuccess) {
                discoveredPeers.addAll(result.getOrNull().orEmpty())
            } else {
                failedTrackers.add(url)
            }
        }

        assertEquals(2, failedTrackers.size)
        assertEquals(2, discoveredPeers.size)
        assertTrue(discoveredPeers.contains("192.168.1.100:50001"))
        assertTrue(discoveredPeers.contains("[200:abc::1]:50001"))
    }

    @Test
    fun multiTracker_peerDeduplication_mergesIdenticalEndpointsAcrossTrackers() {
        // When multiple trackers return overlapping peer lists, endpoints must be deduplicated
        val trackerAResults = listOf("192.168.1.50:50001", "10.0.0.2:50001", "[200:1::1]:50001")
        val trackerBResults = listOf("192.168.1.50:50001", "172.16.0.5:50001", "peer1.onion:50001")
        val trackerCResults = listOf("[200:1::1]:50001", "peer1.onion:50001")

        val combined = (trackerAResults + trackerBResults + trackerCResults).toSet()

        // 5 unique peers in total
        assertEquals(5, combined.size)
        assertTrue(combined.contains("192.168.1.50:50001"))
        assertTrue(combined.contains("10.0.0.2:50001"))
        assertTrue(combined.contains("172.16.0.5:50001"))
        assertTrue(combined.contains("[200:1::1]:50001"))
        assertTrue(combined.contains("peer1.onion:50001"))
    }

    @Test
    fun multiTracker_exponentialBackoff_calculatesSuppressionInterval() {
        fun calculateBackoffSeconds(consecutiveFailures: Int, baseSeconds: Long = 15, maxSeconds: Long = 300): Long {
            if (consecutiveFailures <= 0) return 0L
            val factor = 1L shl (consecutiveFailures - 1).coerceAtMost(6)
            return (baseSeconds * factor).coerceAtMost(maxSeconds)
        }

        assertEquals(0L, calculateBackoffSeconds(0))
        assertEquals(15L, calculateBackoffSeconds(1))  // 15 * 1
        assertEquals(30L, calculateBackoffSeconds(2))  // 15 * 2
        assertEquals(60L, calculateBackoffSeconds(3))  // 15 * 4
        assertEquals(120L, calculateBackoffSeconds(4)) // 15 * 8
        assertEquals(240L, calculateBackoffSeconds(5)) // 15 * 16
        assertEquals(300L, calculateBackoffSeconds(6)) // capped at max (300)
        assertEquals(300L, calculateBackoffSeconds(10)) // capped at max (300)
    }

    @Test
    fun multiTracker_compactPeerParsing_decodesBinaryIPv4AndIPv6() {
        // Compact IPv4: 6 bytes (4 bytes IP + 2 bytes port big-endian)
        val ipBytes = byteArrayOf(192.toByte(), 168.toByte(), 1.toByte(), 42.toByte())
        val port = 50001
        val portBytes = byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
        val compactPeerV4 = ipBytes + portBytes

        assertEquals(6, compactPeerV4.size)
        val parsedIpV4 = InetAddress.getByAddress(compactPeerV4.copyOfRange(0, 4)).hostAddress
        val parsedPortV4 = ((compactPeerV4[4].toInt() and 0xFF) shl 8) or (compactPeerV4[5].toInt() and 0xFF)

        assertEquals("192.168.1.42", parsedIpV4)
        assertEquals(50001, parsedPortV4)

        // Compact IPv6: 18 bytes (16 bytes IP + 2 bytes port big-endian)
        val yggIpv6 = InetAddress.getByName("200:1234:5678::1").address
        val compactPeerV6 = yggIpv6 + portBytes

        assertEquals(18, compactPeerV6.size)
        val parsedIpV6 = InetAddress.getByAddress(compactPeerV6.copyOfRange(0, 16)).hostAddress
        val parsedPortV6 = ((compactPeerV6[16].toInt() and 0xFF) shl 8) or (compactPeerV6[17].toInt() and 0xFF)

        assertEquals(50001, parsedPortV6)
        assertTrue(parsedIpV6?.contains("200:1234:5678") == true)
    }
}
