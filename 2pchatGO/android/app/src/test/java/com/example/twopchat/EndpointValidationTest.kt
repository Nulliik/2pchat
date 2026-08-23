package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointValidationTest {

    @Test
    fun testMalformedEndpointsRejected() {
        assertFalse(isValidEndpoint(""))
        assertFalse(isValidEndpoint("192.168.1.1"))
        assertFalse(isValidEndpoint("abc:def"))
        assertFalse(isValidEndpoint("[::1]"))
        assertFalse(isValidEndpoint("127.0.0.1:0"))
        assertFalse(isValidEndpoint("127.0.0.1:70000"))
        assertFalse(isValidEndpoint("192.168.1.1: 50001"))
        assertFalse(isValidEndpoint("a".repeat(300)))
    }

    @Test
    fun testValidIPv4AndIPv6EndpointsAccepted() {
        assertTrue(isValidEndpoint("192.168.1.1:50001"))
        assertTrue(isValidEndpoint("10.0.0.2:8080"))
        assertTrue(isValidEndpoint("[::1]:50001"))
        assertTrue(isValidEndpoint("[200:1234::abcd]:50001"))
        assertTrue(isValidEndpoint("zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion:50001"))
    }

    @Test
    fun testCommaSeparatedMultiEndpointsAccepted() {
        assertTrue(
            isValidEndpoint("[200:f1d1:906:eabc:f83c:9899:db49:6177]:50001,zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion:50001")
        )
        assertTrue(
            isValidEndpoint("zen5hgdvf6erkha2pz4c2f43qs7s3sd2uoujulol5t365ji7yhjbzqqd.onion:50001,[200:f1d1:906:eabc:f83c:9899:db49:6177]:50001")
        )
        assertTrue(
            isValidEndpoint("192.168.1.50:50001, [200:1234::abcd]:50001, test.onion:8080")
        )
        assertFalse(
            isValidEndpoint("[200:f1d1:906:eabc:f83c:9899:db49:6177]:50001, invalid_endpoint")
        )
    }
}
