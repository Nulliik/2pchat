package com.example.twopchat

import com.example.twopchat.relay.isSelfDiscoveryEndpoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfDiscoveryEndpointTest {
    @Test
    fun rejectsOwnIpv4Ipv6AndOnionEndpoints() {
        val local = listOf("10.0.2.16", "200:fccd:3456:d054:92a0:ad85:de55:c871", "examplehiddenservice.onion")
        assertTrue(isSelfDiscoveryEndpoint("10.0.2.16:50001", local))
        assertTrue(isSelfDiscoveryEndpoint("[200:fccd:3456:d054:92a0:ad85:de55:c871]:50001", local))
        assertTrue(isSelfDiscoveryEndpoint("examplehiddenservice.onion:50001", local))
    }

    @Test
    fun acceptsRemoteEndpoints() {
        val local = listOf("10.0.2.16", "200:fccd:3456:d054:92a0:ad85:de55:c871")
        assertFalse(isSelfDiscoveryEndpoint("10.0.2.17:50001", local))
        assertFalse(isSelfDiscoveryEndpoint("[203:fe89:96f6:fcfb:27d6:f6ac:4204:88e8]:50001", local))
    }
}
