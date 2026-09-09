package com.example.twopchat

import com.example.twopchat.relay.ConnectionTransportKind
import com.example.twopchat.relay.TransportType
import com.example.twopchat.relay.canonicalConnectionTransport
import com.example.twopchat.relay.connectionTransportKind
import com.example.twopchat.relay.resolveTransportType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ConnectionTransportSyncTest {

    @Test
    fun testDirectLanAndLoopbackClassification() {
        // Direct LAN IPv4
        assertEquals(
            ConnectionTransportKind.DIRECT,
            connectionTransportKind(null, "192.168.1.100:50001")
        )
        // Local loopback / emulator socket must NOT be classified as Onion
        assertEquals(
            ConnectionTransportKind.DIRECT,
            connectionTransportKind(null, "127.0.0.1:50001")
        )
        assertEquals(
            ConnectionTransportKind.DIRECT,
            connectionTransportKind(null, "localhost:50001")
        )
        // Direct clearnet public IPv4
        assertEquals(
            ConnectionTransportKind.DIRECT,
            connectionTransportKind(null, "203.0.113.195:50001")
        )
        // Standard global IPv6 (non-Yggdrasil)
        assertEquals(
            ConnectionTransportKind.DIRECT,
            connectionTransportKind(null, "[2600:1f18:63fe::1]:50001")
        )
    }

    @Test
    fun testTorOnionClassification() {
        val onion = "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion:50001"
        assertEquals(
            ConnectionTransportKind.ONION,
            connectionTransportKind(null, onion)
        )
        assertEquals(
            ConnectionTransportKind.ONION,
            connectionTransportKind("Tor Onion", "127.0.0.1:9050")
        )
        assertEquals(
            ConnectionTransportKind.ONION,
            connectionTransportKind("TOR", null)
        )
    }

    @Test
    fun testYggdrasilMeshClassification() {
        // Yggdrasil IPv6 with brackets (0200::/7 prefix)
        assertEquals(
            ConnectionTransportKind.YGGDRASIL,
            connectionTransportKind(null, "[200:1234:5678::1]:50001")
        )
        assertEquals(
            ConnectionTransportKind.YGGDRASIL,
            connectionTransportKind(null, "[300:abcd::1]:50001")
        )
        // Raw transport tag
        assertEquals(
            ConnectionTransportKind.YGGDRASIL,
            connectionTransportKind("Yggdrasil", null)
        )
        assertEquals(
            ConnectionTransportKind.YGGDRASIL,
            connectionTransportKind("ygg_mesh", "127.0.0.1:9053")
        )
    }

    @Test
    fun testResolveTransportType() {
        // Offline always produces DISCONNECTED
        assertEquals(
            TransportType.DISCONNECTED,
            resolveTransportType(rawTransport = "Direct P2P", endpoint = "192.168.1.5:50001", isOnline = false)
        )
        assertEquals(
            TransportType.DISCONNECTED,
            resolveTransportType(rawTransport = "Tor Onion", endpoint = "test.onion:50001", isOnline = false)
        )

        // Online resolves accurately to live transport
        assertEquals(
            TransportType.DIRECT,
            resolveTransportType(rawTransport = "Direct P2P", endpoint = "192.168.1.5:50001", isOnline = true)
        )
        assertEquals(
            TransportType.ONION,
            resolveTransportType(rawTransport = null, endpoint = "myonionaddress123.onion:50001", isOnline = true)
        )
        assertEquals(
            TransportType.YGGDRASIL,
            resolveTransportType(rawTransport = null, endpoint = "[200:1111:2222::1]:50001", isOnline = true)
        )
    }

    @Test
    fun testCanonicalConnectionTransportLabels() {
        assertEquals("Tor Onion", canonicalConnectionTransport("Tor Onion", null))
        assertEquals("Direct P2P", canonicalConnectionTransport("Direct P2P", null))
        assertEquals("Yggdrasil", canonicalConnectionTransport("Yggdrasil", null))
        assertEquals("Direct P2P", canonicalConnectionTransport(null, "192.168.0.15:50001"))
        assertEquals("Tor Onion", canonicalConnectionTransport(null, "abcdef.onion:50001"))
        assertEquals("Yggdrasil", canonicalConnectionTransport(null, "[200::1]:50001"))
    }

    @Test
    fun testRouteUpdatePayloadParsing() {
        val payload = JSONObject().apply {
            put("type", "endpoint_update")
            put("routes", JSONArray(listOf(
                "192.168.1.10:50001",
                "[200:abcd:1234::1]:50001",
                "secretpeerhost.onion:50001",
                "203.0.113.50:50001"
            )))
        }

        val routesArray = payload.optJSONArray("routes")
        assertNotNull(routesArray)
        assertEquals(4, routesArray!!.length())

        val endpoints = (0 until routesArray.length()).map { routesArray.getString(it) }
        val onionRoute = endpoints.firstOrNull { it.contains(".onion") }
        assertNotNull(onionRoute)
        assertEquals("secretpeerhost.onion:50001", onionRoute)

        val yggRoute = endpoints.firstOrNull { it.contains("[200:") }
        assertNotNull(yggRoute)
        assertEquals("[200:abcd:1234::1]:50001", yggRoute)
    }
}
