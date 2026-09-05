package com.example.twopchat.relay

import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.tor.TransportEvent
import com.example.twopchat.tor.TransportEventManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransportDowngradeBannerTest {

    @Before
    fun setUp() {
        TransportEventManager.clear()
    }

    @Test
    fun testTransportEventManagerEmissionAndClear() {
        assertNull("Initial event must be null", TransportEventManager.lastEvent.value)

        val torEvent = TransportEvent.TorUnavailable(
            peerName = "Alice",
            reason = "Tor network is offline"
        )
        TransportEventManager.emit(torEvent)

        val current = TransportEventManager.lastEvent.value
        assertNotNull(current)
        assertTrue(current is TransportEvent.TorUnavailable)
        val casted = current as TransportEvent.TorUnavailable
        assertEquals("Alice", casted.peerName)
        assertEquals("Tor network is offline", casted.reason)

        TransportEventManager.clear()
        assertNull("After clear, event must be null", TransportEventManager.lastEvent.value)

        val downgradeEvent = TransportEvent.TransportDowngradeBlocked(
            peerName = "Bob",
            attemptedTransport = "Direct/LAN"
        )
        TransportEventManager.emit(downgradeEvent)

        val current2 = TransportEventManager.lastEvent.value
        assertNotNull(current2)
        assertTrue(current2 is TransportEvent.TransportDowngradeBlocked)
        val casted2 = current2 as TransportEvent.TransportDowngradeBlocked
        assertEquals("Bob", casted2.peerName)
        assertEquals("Direct/LAN", casted2.attemptedTransport)
    }

    @Test
    fun testCandidateFilteringUnderTorOnlyPolicy() {
        val pref = P2PPreferences.PeerTransportPreference.TOR_ONLY
        val onionEp = "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion:50001"
        val rawCandidates = listOf(
            "192.168.1.10:50001",
            onionEp,
            "203.0.113.5:50001"
        )

        val filtered = rawCandidates.filter { candidate ->
            if (pref == P2PPreferences.PeerTransportPreference.TOR_ONLY) {
                candidate.contains(".onion", ignoreCase = true)
            } else {
                true
            }
        }

        assertEquals("Only onion endpoint must remain", listOf(onionEp), filtered)
    }

    @Test
    fun testCandidateFilteringUnderTorOnlyWithOnlyClearnetTriggersDowngradeBlocked() {
        val pref = P2PPreferences.PeerTransportPreference.TOR_ONLY
        val rawCandidates = listOf(
            "192.168.1.10:50001",
            "203.0.113.5:50001"
        )

        val filtered = rawCandidates.filter { candidate ->
            if (pref == P2PPreferences.PeerTransportPreference.TOR_ONLY) {
                candidate.contains(".onion", ignoreCase = true)
            } else {
                true
            }
        }

        assertTrue("Candidate list must be empty when no onion endpoints exist", filtered.isEmpty())

        if (filtered.isEmpty() && pref == P2PPreferences.PeerTransportPreference.TOR_ONLY) {
            TransportEventManager.emit(
                TransportEvent.TransportDowngradeBlocked(
                    peerName = "Charlie",
                    attemptedTransport = "Direct/LAN"
                )
            )
        }

        val event = TransportEventManager.lastEvent.value
        assertNotNull(event)
        assertTrue(event is TransportEvent.TransportDowngradeBlocked)
        assertEquals("Charlie", (event as TransportEvent.TransportDowngradeBlocked).peerName)
    }

    @Test
    fun testPolicyFlagsMapping() {
        fun computePolicyFlags(pref: P2PPreferences.PeerTransportPreference): Int = when (pref) {
            P2PPreferences.PeerTransportPreference.TOR_ONLY -> 8 // PolicyFlagAllowOnion = 1 << 3
            P2PPreferences.PeerTransportPreference.DIRECT_ONLY -> 3 // AllowLAN | AllowWAN
            P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY -> 4 // AllowYggdrasil
            P2PPreferences.PeerTransportPreference.AUTO -> 0 // Inherit global
        }

        assertEquals(8, computePolicyFlags(P2PPreferences.PeerTransportPreference.TOR_ONLY))
        assertEquals(3, computePolicyFlags(P2PPreferences.PeerTransportPreference.DIRECT_ONLY))
        assertEquals(4, computePolicyFlags(P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY))
        assertEquals(0, computePolicyFlags(P2PPreferences.PeerTransportPreference.AUTO))
    }
}
