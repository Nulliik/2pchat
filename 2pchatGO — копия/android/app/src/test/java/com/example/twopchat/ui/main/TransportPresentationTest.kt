package com.example.twopchat.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPresentationTest {
    @Test
    fun `recognizes bridge direct transport label regardless of case`() {
        assertTrue(isDirectP2pTransport("Direct P2P"))
        assertTrue(isDirectP2pTransport("DIRECT P2P"))
        assertTrue(isDirectP2pTransport(" direct p2p "))
    }

    @Test
    fun `does not classify overlay transports as direct`() {
        assertFalse(isDirectP2pTransport("Yggdrasil"))
        assertFalse(isDirectP2pTransport("UNKNOWN"))
    }
}
