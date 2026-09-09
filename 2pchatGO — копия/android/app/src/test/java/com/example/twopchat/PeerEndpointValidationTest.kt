package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerEndpointValidationTest {
    @Test
    fun `accepts the endpoint forms supported by the Python transport`() {
        assertTrue(isValidPeerEndpointList("192.0.2.10:50001"))
        assertTrue(isValidPeerEndpointList("[2001:db8::10]:50001"))
        assertTrue(isValidPeerEndpointList("ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"))
        assertTrue(isValidPeerEndpointList("chat.example.test:443, 192.0.2.10:50001"))
    }

    @Test
    fun `rejects peer names and peer ids used as transport endpoints`() {
        assertFalse(isValidPeerEndpointList("foxy"))
        assertFalse(isValidPeerEndpointList("<peer:55aff0ff>"))
        assertFalse(isValidPeerEndpointList("peer-id:unknown"))
    }

    @Test
    fun `group and invite delivery keeps contact identity while resolving its route`() {
        assertEquals(
            "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001,192.0.2.55:50001,192.0.2.10:50001",
            resolvePeerEndpoint(
                peerName = "foxy",
                liveEndpoint = "192.0.2.55:50001",
                persistedEndpoint = "192.0.2.10:50001",
                onionEndpoint = "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001",
            ),
        )
        assertEquals(
            "192.0.2.55:50001,192.0.2.10:50001",
            resolvePeerEndpoint(
                peerName = "foxy",
                liveEndpoint = "192.0.2.55:50001",
                persistedEndpoint = "192.0.2.10:50001",
            ),
        )
        assertEquals(
            "192.0.2.10:50001",
            resolvePeerEndpoint(
                peerName = "foxy",
                liveEndpoint = null,
                persistedEndpoint = "192.0.2.10:50001",
            ),
        )
        assertNull(
            resolvePeerEndpoint(
                peerName = "foxy",
                liveEndpoint = "<peer:55aff0ff>",
                persistedEndpoint = "foxy",
            ),
        )
    }
}
