package com.example.twopchat

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
}
