package com.example.twopchat.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeerSearchInstrumentedTest {
    @Test
    fun qrAndClassicSearchUseSameTrackerNamespace() {
        val classic = classicPeerSearchRequest("Anne Marie#abcd-2345")
        val qr = invitePeerSearchRequest("Anne Marie", "abcd-2345", null)

        assertEquals(classic, qr)
        assertEquals("Anne_Marie", qr?.lookupNickname)
        assertEquals("abcd-2345", qr?.sharedCode)
    }

    @Test
    fun qrDirectCandidatesFormatIpv4AndYggdrasilEndpoints() {
        assertEquals("192.0.2.10:50001", formatInviteEndpoint("192.0.2.10"))
        assertEquals("[200:db8::10]:50001", formatInviteEndpoint("200:db8::10"))
        assertNull(formatInviteEndpoint("not-an-address"))
    }

    @Test
    fun nicknameBoundariesAndSpecialCharactersMatchOnDevice() {
        val maxName = "a".repeat(30) + "#🦊"

        assertEquals(maxName, parsePeerSearchAddress("$maxName#code")?.nickname)
        assertNull(parsePeerSearchAddress("${maxName}x#code"))
    }

    @Test
    fun trackerResultMustPassLiveVerification() {
        assertTrue(isConnectablePeerSearchResult(
            mapOf("verified" to "True", "ownership_verified" to "False"),
            expectedFingerprint = null,
        ))
        assertNull(
            listOf(mapOf<String, Any>("verified" to "False"))
                .firstOrNull { isConnectablePeerSearchResult(it, expectedFingerprint = null) },
        )
    }
}
