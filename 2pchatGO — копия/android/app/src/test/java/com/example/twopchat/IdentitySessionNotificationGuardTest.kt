package com.example.twopchat

import com.example.twopchat.bridge.shouldPublishIdentitySessionEstablished
import com.example.twopchat.bridge.discoveryInfoHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentitySessionNotificationGuardTest {
    private val fingerprint = "peer-fingerprint"
    private val nickname = "Alice"

    @Test
    fun discoveryHashIsStableTrackerCompatibleTwentyByteHex() {
        val hash = discoveryInfoHash("Null", "36571c05")
        assertEquals(40, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{40}")))
        assertEquals("4725456c9bc18c138f2066366fcf09bfe6ecdc34", hash)
        assertEquals(hash, discoveryInfoHash("  nuLL  ", "36571c05"))
    }

    @Test
    fun repeatedProfileFrameDoesNotRepublishEstablishedSession() {
        assertFalse(
            shouldPublishIdentitySessionEstablished(
                wasNameOnline = true,
                existingNameForFingerprint = nickname,
                existingFingerprintForName = fingerprint,
                remoteNick = nickname,
                peerFP = fingerprint,
            )
        )
    }

    @Test
    fun firstAuthenticatedNameBindingPublishesEstablishedSession() {
        assertTrue(
            shouldPublishIdentitySessionEstablished(
                wasNameOnline = false,
                existingNameForFingerprint = null,
                existingFingerprintForName = null,
                remoteNick = nickname,
                peerFP = fingerprint,
            )
        )
    }

    @Test
    fun incompleteBidirectionalMappingIsRepairedAndPublished() {
        assertTrue(
            shouldPublishIdentitySessionEstablished(
                wasNameOnline = true,
                existingNameForFingerprint = nickname,
                existingFingerprintForName = null,
                remoteNick = nickname,
                peerFP = fingerprint,
            )
        )
    }
}
