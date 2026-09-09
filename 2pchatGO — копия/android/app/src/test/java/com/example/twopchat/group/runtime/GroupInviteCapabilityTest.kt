package com.example.twopchat.group.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GroupInviteCapabilityTest {
    @Test
    fun capabilityEncodesAtLeast256BitsWithoutUnsafeUriCharacters() {
        val firstBytes = ByteArray(32) { it.toByte() }
        val secondBytes = ByteArray(32) { (it + 1).toByte() }
        val first = encodeGroupInviteCapability(firstBytes)

        assertEquals(first, encodeGroupInviteCapability(firstBytes))
        assertNotEquals(first, encodeGroupInviteCapability(secondBytes))
        assertEquals(64, first.length)
        assertEquals(first, java.net.URLDecoder.decode(first, Charsets.UTF_8.name()))
    }
}
