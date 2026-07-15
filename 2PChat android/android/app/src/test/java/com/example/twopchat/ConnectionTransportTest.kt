package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTransportTest {
    @Test
    fun `normalizes transport values emitted by the Python bridge`() {
        assertEquals("Direct P2P", canonicalConnectionTransport("DIRECT P2P"))
        assertEquals("Yggdrasil", canonicalConnectionTransport("yggdrasil"))
    }

    @Test
    fun `infers route from endpoint while session metadata catches up`() {
        assertEquals("Direct P2P", canonicalConnectionTransport(null, "192.0.2.12:50001"))
        assertEquals("Yggdrasil", canonicalConnectionTransport(null, "[200:abcd::12]:50001"))
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
}
