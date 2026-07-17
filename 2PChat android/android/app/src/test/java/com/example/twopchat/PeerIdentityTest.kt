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
}
