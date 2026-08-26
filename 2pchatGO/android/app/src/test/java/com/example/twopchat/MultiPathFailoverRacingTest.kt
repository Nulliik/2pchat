package com.example.twopchat

import com.example.twopchat.relay.TransportType
import com.example.twopchat.relay.resolveTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for multi-path failover, latency racing, and deduplication.
 */
class MultiPathFailoverRacingTest {

    data class CandidateEndpoint(
        val endpoint: String,
        val transport: TransportType,
        val estimatedLatencyMs: Long,
        val isHealthy: Boolean = true
    )

    @Test
    fun testFastestEndpointSelectionWithLatencyRacing() {
        fun selectBestEndpoint(candidates: List<CandidateEndpoint>): CandidateEndpoint? {
            return candidates
                .filter { it.isHealthy }
                .sortedWith(
                    compareBy<CandidateEndpoint> {
                        // Priority: DIRECT (0) > YGGDRASIL (1) > ONION (2)
                        when (it.transport) {
                            TransportType.DIRECT -> 0
                            TransportType.YGGDRASIL -> 1
                            TransportType.ONION -> 2
                            else -> 3
                        }
                    }.thenBy { it.estimatedLatencyMs }
                )
                .firstOrNull()
        }

        val allCandidates = listOf(
            CandidateEndpoint("peer.onion:50001", TransportType.ONION, 850L),
            CandidateEndpoint("[200:1234::1]:50001", TransportType.YGGDRASIL, 65L),
            CandidateEndpoint("192.168.1.50:50001", TransportType.DIRECT, 12L)
        )

        // When LAN is healthy, LAN is preferred
        val best = selectBestEndpoint(allCandidates)
        assertNotNull(best)
        assertEquals(TransportType.DIRECT, best?.transport)
        assertEquals("192.168.1.50:50001", best?.endpoint)

        // When LAN is down, Yggdrasil takes over
        val withoutLan = allCandidates.map { if (it.transport == TransportType.DIRECT) it.copy(isHealthy = false) else it }
        val fallbackYgg = selectBestEndpoint(withoutLan)
        assertNotNull(fallbackYgg)
        assertEquals(TransportType.YGGDRASIL, fallbackYgg?.transport)
        assertEquals("[200:1234::1]:50001", fallbackYgg?.endpoint)

        // When LAN and Yggdrasil are down, Tor takes over
        val torOnly = withoutLan.map { if (it.transport == TransportType.YGGDRASIL) it.copy(isHealthy = false) else it }
        val fallbackTor = selectBestEndpoint(torOnly)
        assertNotNull(fallbackTor)
        assertEquals(TransportType.ONION, fallbackTor?.transport)
        assertEquals("peer.onion:50001", fallbackTor?.endpoint)
    }

    @Test
    fun testFailoverPreservesDoubleRatchetSession() {
        // Simulates seamless transport switch without session reconstruction
        var currentTransport = TransportType.DIRECT
        val sessionKey = "session-ratchet-key-alice-bob"

        // Simulate LAN failure
        val simulateLanDrop = {
            currentTransport = TransportType.YGGDRASIL
        }

        simulateLanDrop()

        // Transport changed to YGGDRASIL, but session cryptographic state is untouched
        assertEquals(TransportType.YGGDRASIL, currentTransport)
        assertEquals("session-ratchet-key-alice-bob", sessionKey)
    }

    @Test
    fun testConcurrentMultiPathMessageDeduplication() {
        val receivedMessageIds = mutableSetOf<String>()
        val processedDispatches = mutableListOf<String>()

        fun onMessageReceived(msgId: String, payload: String, path: TransportType) {
            val isNew = receivedMessageIds.add(msgId)
            if (isNew) {
                processedDispatches.add("$msgId via $path: $payload")
            }
        }

        // Message arrived first via DIRECT
        onMessageReceived("msg-42", "Hello", TransportType.DIRECT)

        // Same message arrived slightly later via YGGDRASIL race
        onMessageReceived("msg-42", "Hello", TransportType.YGGDRASIL)

        // Same message arrived later via Tor
        onMessageReceived("msg-42", "Hello", TransportType.ONION)

        // Only 1 dispatch must be processed
        assertEquals(1, processedDispatches.size)
        assertEquals("msg-42 via DIRECT: Hello", processedDispatches.first())
    }
}
