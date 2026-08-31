package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import com.example.twopchat.config.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTransportTest {
    @Test
    fun `normalizes transport values emitted by the Python bridge`() {
        assertEquals("Direct P2P", canonicalConnectionTransport("DIRECT P2P"))
        assertEquals("Yggdrasil", canonicalConnectionTransport("yggdrasil"))
        assertEquals("Tor Onion", canonicalConnectionTransport("Tor Onion"))
        assertEquals("Tor Onion", canonicalConnectionTransport("onion"))
    }

    @Test
    fun `infers route from endpoint while session metadata catches up`() {
        assertEquals("Direct P2P", canonicalConnectionTransport(null, "192.0.2.12:50001"))
        assertEquals("Direct P2P", canonicalConnectionTransport(null, "[2a00:1450:4001:828::200e]:50001"))
        assertEquals("Yggdrasil", canonicalConnectionTransport(null, "[200:abcd::12]:50001"))
        assertEquals(
            "Tor Onion",
            canonicalConnectionTransport(null, "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"),
        )
    }

    @Test
    fun `resolves correct TransportType enum for UI badges`() {
        assertEquals(TransportType.ONION, resolveTransportType("Tor Onion", isOnline = true))
        assertEquals(
            TransportType.ONION,
            resolveTransportType(null, "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001", isOnline = true),
        )
        assertEquals(TransportType.DIRECT, resolveTransportType("Direct P2P", isOnline = true))
        assertEquals(TransportType.YGGDRASIL, resolveTransportType("Yggdrasil", isOnline = true))
        assertEquals(TransportType.DISCONNECTED, resolveTransportType("Tor Onion", isOnline = false))
        assertEquals(TransportType.DISCONNECTED, resolveTransportType(null, null, isOnline = false))
    }

    @Test
    fun `does not present an unknown route as yggdrasil`() {
        assertNull(canonicalConnectionTransport("UNKNOWN", "resolving..."))
        assertEquals(
            "маршрут определяется",
            connectionTransportLabel("UNKNOWN", null, "Русский"),
        )
        assertEquals(
            "detecting route",
            connectionTransportLabel(null, null, "English"),
        )
    }

    @Test
    fun `parses PeerTransportPreference keys correctly`() {
        assertEquals(P2PPreferences.PeerTransportPreference.AUTO, P2PPreferences.PeerTransportPreference.fromKey("auto"))
        assertEquals(P2PPreferences.PeerTransportPreference.TOR_ONLY, P2PPreferences.PeerTransportPreference.fromKey("tor"))
        assertEquals(P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY, P2PPreferences.PeerTransportPreference.fromKey("yggdrasil"))
        assertEquals(P2PPreferences.PeerTransportPreference.DIRECT_ONLY, P2PPreferences.PeerTransportPreference.fromKey("direct"))
        assertEquals(P2PPreferences.PeerTransportPreference.AUTO, P2PPreferences.PeerTransportPreference.fromKey("invalid"))
        assertEquals(P2PPreferences.PeerTransportPreference.AUTO, P2PPreferences.PeerTransportPreference.fromKey(null))
    }

    @Test
    fun `filters endpoint list by transport preference correctly`() {
        val mixedEndpoints = listOf(
            "192.168.10.247:50001",
            "[2a00:1450:4001:828::200e]:50001",
            "[200:abcd::12]:50001",
            "karndtlkna43am2x6ddv.onion:50001"
        )

        // AUTO should preserve all candidates
        val autoFiltered = P2PPreferences.filterEndpointsByPreference(mixedEndpoints, P2PPreferences.PeerTransportPreference.AUTO)
        assertEquals(4, autoFiltered.size)
        assertEquals(mixedEndpoints, autoFiltered)

        // TOR_ONLY should strictly isolate .onion
        val torFiltered = P2PPreferences.filterEndpointsByPreference(mixedEndpoints, P2PPreferences.PeerTransportPreference.TOR_ONLY)
        assertEquals(listOf("karndtlkna43am2x6ddv.onion:50001"), torFiltered)

        // YGGDRASIL_ONLY should strictly isolate IPv6 mesh (200::/7)
        val yggFiltered = P2PPreferences.filterEndpointsByPreference(mixedEndpoints, P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY)
        assertEquals(listOf("[200:abcd::12]:50001"), yggFiltered)

        // DIRECT_ONLY should strictly isolate IPv4 LAN/clearnet and direct global mobile IPv6
        val directFiltered = P2PPreferences.filterEndpointsByPreference(mixedEndpoints, P2PPreferences.PeerTransportPreference.DIRECT_ONLY)
        assertEquals(listOf("192.168.10.247:50001", "[2a00:1450:4001:828::200e]:50001"), directFiltered)
    }
}
