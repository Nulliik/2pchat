package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedMessageSyncTest {
    @Test
    fun newerPinnedStateWins() {
        val current = PinnedMessageStateVersion(counter = 4, actor = "alice")

        assertTrue(
            shouldApplyPinnedMessageState(
                current,
                PinnedMessageStateVersion(counter = 5, actor = "bob"),
            )
        )
        assertFalse(
            shouldApplyPinnedMessageState(
                current,
                PinnedMessageStateVersion(counter = 3, actor = "bob"),
            )
        )
    }

    @Test
    fun concurrentPinnedStatesConvergeByActor() {
        val alice = PinnedMessageStateVersion(counter = 7, actor = "alice")
        val bob = PinnedMessageStateVersion(counter = 7, actor = "bob")

        assertTrue(shouldApplyPinnedMessageState(alice, bob))
        assertFalse(shouldApplyPinnedMessageState(bob, alice))
    }

    @Test
    fun localPinnedStateAdvancesObservedVersion() {
        assertEquals(
            PinnedMessageStateVersion(counter = 10, actor = "local-device"),
            nextPinnedMessageStateVersion(
                current = PinnedMessageStateVersion(counter = 9, actor = "remote-device"),
                actor = "local-device",
            ),
        )
    }
}
