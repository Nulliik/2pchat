package com.example.twopchat.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WallpaperSystemMessageIdTest {
    @Test
    fun idIsDeterministicForPeerActionAndEvent() {
        val id = wallpaperSystemMessageId("peer", "set", 1234L)
        assertEquals(id, wallpaperSystemMessageId("peer", "set", 1234L))
        assertNotEquals(id, wallpaperSystemMessageId("other-peer", "set", 1234L))
        assertNotEquals(id, wallpaperSystemMessageId("peer", "removed", 1234L))
        assertNotEquals(id, wallpaperSystemMessageId("peer", "set", 1235L))
    }
}
