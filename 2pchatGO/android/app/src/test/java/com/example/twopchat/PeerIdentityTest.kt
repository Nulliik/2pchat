package com.example.twopchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIdentityTest {
    @Test
    fun `first authenticated fingerprint may be pinned`() {
        assertTrue(isExpectedPeerFingerprint(null, "fingerprint-a"))
        assertTrue(isExpectedPeerFingerprint("", "fingerprint-a"))
    }

    @Test
    fun `pinned fingerprint cannot be silently replaced`() {
        assertTrue(isExpectedPeerFingerprint("fingerprint-a", "fingerprint-a"))
        assertFalse(isExpectedPeerFingerprint("fingerprint-a", "fingerprint-b"))
    }

    @Test
    fun `traffic remains paused until an identity decision is explicit`() {
        assertTrue(shouldBlockPeerTraffic(mismatch = true, pendingFingerprint = "fingerprint-b"))
        assertTrue(shouldBlockPeerTraffic(mismatch = false, pendingFingerprint = "fingerprint-b"))
        assertFalse(shouldBlockPeerTraffic(mismatch = false, pendingFingerprint = null))
    }

    @Test
    fun `only a different complete pending fingerprint can be accepted`() {
        assertTrue(canAcceptPendingPeerFingerprint("fingerprint-a", "fingerprint-b"))
        assertFalse(canAcceptPendingPeerFingerprint("fingerprint-a", "fingerprint-a"))
        assertFalse(canAcceptPendingPeerFingerprint("fingerprint-a", ""))
    }

    @Test
    fun `account data is wiped only after runtime shutdown`() {
        val events = mutableListOf<String>()

        val deleted = performAccountDeletion(
            shutdownRuntime = {
                events += "shutdown"
                true
            },
            wipePersistentData = {
                events += "wipe"
                true
            },
        )

        assertTrue(deleted)
        assertTrue(events == listOf("shutdown", "wipe"))
    }

    @Test
    fun `account data remains when old runtime cannot stop`() {
        var wiped = false

        val deleted = performAccountDeletion(
            shutdownRuntime = { false },
            wipePersistentData = {
                wiped = true
                true
            },
        )

        assertFalse(deleted)
        assertFalse(wiped)
    }

    @Test
    fun `account wipe runs every cleanup step even when one fails`() {
        val events = mutableListOf<String>()

        val completed = runAccountDataWipe(
            listOf(
                "preferences" to { events += "preferences"; true },
                "messages" to { events += "messages"; false },
                "stickers" to { events += "stickers"; true },
            ),
            onFailure = { _, _ -> },
        )

        assertFalse(completed)
        assertTrue(events == listOf("preferences", "messages", "stickers"))
    }
}
