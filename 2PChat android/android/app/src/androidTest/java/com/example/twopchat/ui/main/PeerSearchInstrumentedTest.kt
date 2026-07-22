package com.example.twopchat.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.example.twopchat.orderedDirectEndpoints
import com.example.twopchat.P2PMessageRelay

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
        assertEquals(
            listOf("10.0.2.15:50001", "198.51.100.7:50001", "[200:db8::7]:50001"),
            orderedDirectEndpoints(listOf(
                "[200:db8::7]:50001",
                "198.51.100.7:50001",
                "10.0.2.15:50001",
            )),
        )
    }

    @Test
    fun oneQrContainsLocalPublicAndIpv6RoutesInOrder() {
        val payload = buildContactQrPayload(
            nickname = "Anne_Marie",
            discoveryCode = "abcd-2345",
            fingerprint = "fingerprint",
            localIpv4 = "192.168.1.20",
            publicIpv4 = "198.51.100.20",
            ipv6 = "200:db8::20",
            listenerPort = 4242,
        )
        val uri = android.net.Uri.parse(payload)

        assertEquals("192.168.1.20:4242", uri.getQueryParameter("ip"))
        assertEquals("198.51.100.20:4242", uri.getQueryParameter("public_ip"))
        assertEquals("[200:db8::20]:4242", uri.getQueryParameter("ygg"))
    }

    @Test
    fun routesWithOneFingerprintDoNotOverwriteEachOther() {
        val name = "qr-route-storage-test"
        val fingerprint = "same-fingerprint"
        val expected = listOf(
            "192.168.1.20:4242",
            "198.51.100.20:4242",
            "[200:db8::20]:4242",
        )

        expected.forEach { endpoint ->
            P2PMessageRelay.injectLocalDiscoveryCandidate(name, fingerprint, endpoint)
        }

        assertEquals(expected, P2PMessageRelay.localDiscoveryEndpoints(name))
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
