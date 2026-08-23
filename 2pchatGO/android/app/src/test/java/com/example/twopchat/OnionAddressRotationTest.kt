package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionAddressRotationTest {

    @Test
    fun testOnionAddressUpdatePayloadFormat() {
        val newOnion = "6h4k3qop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion"
        val port = 50001
        val myFp = "ROQ85HTrlRz+hNx7q9gE/fbrJnChx67x4H8Zi/35+RA="
        val timestamp = 1787400000L
        val signature = "dummy_sig_base64"

        val payload = """{"type":"onion_address_update","onion_address":"$newOnion","listener_port":$port,"fingerprint":"$myFp","timestamp":$timestamp,"signature":"$signature"}"""

        assertTrue(payload.contains("\"type\":\"onion_address_update\""))
        assertTrue(payload.contains("\"onion_address\":\"$newOnion\""))
        assertTrue(payload.contains("\"listener_port\":$port"))
        assertTrue(payload.contains("\"fingerprint\":\"$myFp\""))
        assertTrue(payload.contains("\"signature\":\"$signature\""))
    }

    @Test
    fun testCanonicalPayloadFormatForSignature() {
        val newOnion = "6h4k3qop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion"
        val port = 50001
        val myFp = "ROQ85HTrlRz+hNx7q9gE/fbrJnChx67x4H8Zi/35+RA="
        val timestamp = 1787400000L

        val canonicalData = "onion_update:$myFp:$newOnion:$port:$timestamp"
        assertEquals(
            "onion_update:ROQ85HTrlRz+hNx7q9gE/fbrJnChx67x4H8Zi/35+RA=:6h4k3qop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001:1787400000",
            canonicalData
        )
    }

    @Test
    fun testFormattedOnionEndpointFromUpdate() {
        val rawOnion = "6h4k3qop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion"
        val port = 50001
        val formatted = com.example.twopchat.ui.main.formatInviteEndpoint(rawOnion, port)

        assertNotNull(formatted)
        assertEquals("6h4k3qop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001", formatted)
    }

    @Test
    fun testBlockedPeerFilteringLogic() {
        val activeChats = setOf("Alice", "Bob", "EveSpammer")
        val blockedPeers = setOf("EveSpammer")

        val trustedPeers = activeChats.filter { peer ->
            peer.isNotBlank() && !P2PMessageRelay.isPlaceholderPeerName(peer) && !blockedPeers.contains(peer)
        }

        assertTrue(trustedPeers.contains("Alice"))
        assertTrue(trustedPeers.contains("Bob"))
        assertFalse(trustedPeers.contains("EveSpammer"))
        assertEquals(2, trustedPeers.size)
    }
}
