package com.example.twopchat.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapEndpointTest {
    @Test
    fun selectedSearchEndpointIsAvailableForInitialHandshakeOnly() {
        val name = "bootstrap-${System.nanoTime()}"
        assertTrue(P2PMessageRelay.rememberBootstrapPeerEndpoint(name, "[201:e59e:412d:f8f7:d6e6:bcd7:80e5:a58f]:50001"))

        assertEquals(
            listOf("[201:e59e:412d:f8f7:d6e6:bcd7:80e5:a58f]:50001"),
            P2PMessageRelay.bootstrapEndpointsForInitialHandshake(name),
        )
        assertTrue(P2PMessageRelay.bootstrapEndpointsForInitialHandshake(name, Long.MAX_VALUE).isEmpty())
    }
}
