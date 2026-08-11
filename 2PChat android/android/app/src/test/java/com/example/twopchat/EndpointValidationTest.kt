package com.example.twopchat

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
    }
}
