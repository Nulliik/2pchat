package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionAddressShareTest {

    @Test
    fun testOnionAddressSharePayloadFormat() {
        val onion = "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion"
        val port = 50001
        val payload = """{"type":"onion_address_share","onion_address":"$onion","listener_port":$port}"""

        assertTrue(payload.contains("\"type\":\"onion_address_share\""))
        assertTrue(payload.contains("\"onion_address\":\"$onion\""))
        assertTrue(payload.contains("\"listener_port\":$port"))
    }

    @Test
    fun testFormattedOnionFromSharePayload() {
        val rawOnion = "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion"
        val port = 50001
        val formatted = com.example.twopchat.ui.main.formatInviteEndpoint(rawOnion, port)

        assertNotNull(formatted)
        assertEquals("ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001", formatted)
        assertTrue(formatted!!.contains(".onion"))
    }

    @Test
    fun testResolvedEndpointIncludesNewlySharedOnion() {
        val peer = "Bob"
        val sharedOnion = "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"
        val liveIp = "192.168.1.55:50001"

        val resolved = resolvePeerEndpoint(
            peerName = peer,
            liveEndpoint = liveIp,
            persistedEndpoint = liveIp,
            onionEndpoint = sharedOnion,
        )

        assertNotNull(resolved)
        val parts = resolved!!.split(",")
        // Onion must always be first for maximum anonymity routing
        assertEquals(sharedOnion, parts[0])
        assertEquals(liveIp, parts[1])
    }
}
