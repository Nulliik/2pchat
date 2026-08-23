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
        val hash = discoveryInfoHash("060fa7de")
        assertEquals(40, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{40}")))
        assertEquals(hash, discoveryInfoHash("060fa7de"))
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
