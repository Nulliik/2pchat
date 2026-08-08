package com.example.twopchat

import com.example.twopchat.yggdrasil.CustomYggdrasilPeer
import com.example.twopchat.yggdrasil.YggdrasilPeerPreferences
import com.example.twopchat.yggdrasil.YggdrasilPeerSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YggdrasilPeerPreferencesTest {
    @Test
    fun peerUriAcceptsSupportedTcpAndTlsEndpoints() {
        assertEquals(
            "tls://peer.example:443",
            YggdrasilPeerPreferences.normalizedPeerUri(" tls://peer.example:443 "),
        )
        assertEquals(
            "tcp://[200:db8::1]:65535",
            YggdrasilPeerPreferences.normalizedPeerUri("tcp://[200:db8::1]:65535"),
        )
    }

    @Test
    fun peerUriRejectsUnsupportedOrUnsafeEndpoints() {
        assertNull(YggdrasilPeerPreferences.normalizedPeerUri("udp://peer.example:1234"))
        assertNull(YggdrasilPeerPreferences.normalizedPeerUri("tls://peer.example"))
        assertNull(YggdrasilPeerPreferences.normalizedPeerUri("tls://user:pass@peer.example:443"))
        assertNull(YggdrasilPeerPreferences.normalizedPeerUri("tls://peer.example:443#fragment"))
        assertNull(YggdrasilPeerPreferences.normalizedPeerUri("not a peer"))
    }

    @Test
    fun publicPeersCanBeSortedByStatusAddressOrProtocol() {
        val peers = listOf(
            "tls://b.example:443",
            "tcp://c.example:1234",
            "tls://a.example:443",
        )
        val disabled = setOf("tls://b.example:443")

        assertEquals(
            listOf("tcp://c.example:1234", "tls://a.example:443", "tls://b.example:443"),
            YggdrasilPeerPreferences.sortedPublicPeers(peers, disabled, YggdrasilPeerSort.STATUS),
        )
        assertEquals(
            listOf("tls://a.example:443", "tls://b.example:443", "tcp://c.example:1234"),
            YggdrasilPeerPreferences.sortedPublicPeers(peers, disabled, YggdrasilPeerSort.ADDRESS),
        )
        assertEquals(
            listOf("tcp://c.example:1234", "tls://a.example:443", "tls://b.example:443"),
            YggdrasilPeerPreferences.sortedPublicPeers(peers, disabled, YggdrasilPeerSort.PROTOCOL),
        )
    }

    @Test
    fun customStatusSortPlacesEnabledPeersFirstThenUsesName() {
        val peers = listOf(
            CustomYggdrasilPeer("1", "Zulu", "tls://z.example:443", enabled = true),
            CustomYggdrasilPeer("2", "Alpha", "tls://a.example:443", enabled = false),
            CustomYggdrasilPeer("3", "Beta", "tcp://b.example:1234", enabled = true),
        )

        assertEquals(
            listOf("3", "1", "2"),
            YggdrasilPeerPreferences.sortedCustomPeers(peers, YggdrasilPeerSort.STATUS).map { it.id },
        )
    }

    @Test
    fun effectivePeersRespectPublicAndIndividualTogglesAndIncludeEnabledCustomPeers() {
        val custom = listOf(
            CustomYggdrasilPeer("1", "Enabled", "tls://mine.example:443", enabled = true),
            CustomYggdrasilPeer("2", "Disabled", "tcp://off.example:1234", enabled = false),
        )

        assertEquals(
            listOf("tls://public-a.example:443", "tls://mine.example:443"),
            YggdrasilPeerPreferences.selectEffectivePeerUris(
                publicCandidates = listOf(
                    "tls://public-a.example:443",
                    "tcp://public-b.example:1234",
                ),
                publicEnabled = true,
                disabledPublicPeers = setOf("tcp://public-b.example:1234"),
                customPeers = custom,
            ),
        )
        assertEquals(
            listOf("tls://mine.example:443"),
            YggdrasilPeerPreferences.selectEffectivePeerUris(
                publicCandidates = listOf("tls://public-a.example:443"),
                publicEnabled = false,
                disabledPublicPeers = emptySet(),
                customPeers = custom,
            ),
        )
    }

    @Test
    fun tlsBypassPeersListContainsValidTlsEndpoints() {
        val peers = YggdrasilPeerPreferences.TLS_BYPASS_PEERS
        assert(peers.isNotEmpty())
        peers.forEach { uri ->
            val normalized = YggdrasilPeerPreferences.normalizedPeerUri(uri)
            assertEquals(uri, normalized)
            assert(uri.startsWith("tls://"))
        }
    }
}
