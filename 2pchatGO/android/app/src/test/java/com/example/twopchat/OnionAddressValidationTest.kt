package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionAddressValidationTest {

    private val validV3Onion = "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion"
    private val validV3WithPort = "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"

    @Test
    fun testValidV3OnionAddressAccepted() {
        assertTrue(isValidEndpoint(validV3WithPort))
        assertTrue(isValidPeerEndpointList(validV3WithPort))
    }

    @Test
    fun testFormatInviteEndpointFormatsOnionProperly() {
        assertEquals(
            validV3WithPort,
            com.example.twopchat.ui.main.formatInviteEndpoint(validV3Onion, 50001),
        )
        assertEquals(
            validV3WithPort,
            com.example.twopchat.ui.main.formatInviteEndpoint(validV3WithPort, 50001),
        )
    }

    @Test
    fun testFormatInviteEndpointCleansUrlSchemes() {
        // formatInviteEndpoint rejects inputs with raw URL prefixes to prevent malformed hostnames
        assertNull(com.example.twopchat.ui.main.formatInviteEndpoint("http://$validV3Onion", 50001))
        assertNull(com.example.twopchat.ui.main.formatInviteEndpoint("https://$validV3Onion", 50001))
    }

    @Test
    fun testRejectMalformedOnionAddresses() {
        // Missing port
        assertFalse(isValidEndpoint(validV3Onion))
        // Invalid port
        assertFalse(isValidEndpoint("$validV3Onion:0"))
        assertFalse(isValidEndpoint("$validV3Onion:70000"))
        assertFalse(isValidEndpoint("$validV3Onion:abc"))
        assertFalse(isValidEndpoint("$validV3Onion:-1"))
    }

    @Test
    fun testRejectInjectionPayloadsInOnionEndpoint() {
        assertFalse(isValidEndpoint("$validV3WithPort; rm -rf /"))
        assertFalse(isValidEndpoint("$validV3WithPort\nSocksPort 9050"))
        assertFalse(isValidEndpoint("$validV3WithPort\r\nControlPort 9051"))
        assertFalse(isValidEndpoint("<script>$validV3WithPort</script>"))
    }

    @Test
    fun testMultiEndpointListContainsOnion() {
        val multi = "192.168.1.10:50001, [2001:db8::1]:50001, $validV3WithPort"
        assertTrue(isValidPeerEndpointList(multi))
    }
}
