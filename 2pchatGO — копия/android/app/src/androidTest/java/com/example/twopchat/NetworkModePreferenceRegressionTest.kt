package com.example.twopchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.config.TrackerPreferences
import com.example.twopchat.yggdrasil.PREF_KEY_ENABLED
import com.example.twopchat.yggdrasil.yggdrasilPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

@RunWith(AndroidJUnit4::class)
class NetworkModePreferenceRegressionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun configureDirectIpv4Only() {
        configureOverlayModes(tor = false, yggdrasil = false, wifiDiscovery = true)
        configureTrackers(clearnet = true, yggdrasil = false, protocols = setOf("http", "https", "udp"), ipv4 = "always")
        setKnownPeers(P2PPreferences.PeerTransportPreference.DIRECT_ONLY)

        val active = TrackerPreferences.getActiveTrackerUrls(context)
        assertTrue(active.isNotEmpty())
        assertFalse(active.any(::hasYggdrasilHost))
        assertEquals("always", TrackerPreferences.ipv4AnnounceMode(context))
    }

    @Test
    fun configureYggdrasilOnly() {
        configureOverlayModes(tor = false, yggdrasil = true, wifiDiscovery = false)
        configureTrackers(clearnet = false, yggdrasil = true, protocols = setOf("http", "https", "udp"), ipv4 = "never")
        setKnownPeers(P2PPreferences.PeerTransportPreference.YGGDRASIL_ONLY)

        val active = TrackerPreferences.getActiveTrackerUrls(context)
        assertEquals(2, active.size)
        assertTrue(active.all(::hasYggdrasilHost))
        assertEquals("never", TrackerPreferences.ipv4AnnounceMode(context))
    }

    @Test
    fun configureTorOnly() {
        configureOverlayModes(tor = true, yggdrasil = false, wifiDiscovery = false)
        // HTTP(S) trackers use the adaptive SOCKS dialer. UDP and DHT are
        // deliberately disabled because neither can be carried over Tor.
        configureTrackers(clearnet = true, yggdrasil = false, protocols = setOf("https"), ipv4 = "never")
        setKnownPeers(P2PPreferences.PeerTransportPreference.TOR_ONLY)

        val active = TrackerPreferences.getActiveTrackerUrls(context)
        assertTrue(active.isNotEmpty())
        assertTrue(active.all { URI(it).scheme == "https" })
        assertEquals("never", TrackerPreferences.ipv4AnnounceMode(context))
    }

    private fun configureOverlayModes(tor: Boolean, yggdrasil: Boolean, wifiDiscovery: Boolean) {
        P2PPreferences.prefs(context).edit()
            .putBoolean(P2PPreferences.TOR_ENABLED, tor)
            .putBoolean(P2PPreferences.WIFI_DISCOVERY, wifiDiscovery)
            .commit()
        yggdrasilPrefs(context).edit().putBoolean(PREF_KEY_ENABLED, yggdrasil).commit()
    }

    private fun configureTrackers(clearnet: Boolean, yggdrasil: Boolean, protocols: Set<String>, ipv4: String) {
        TrackerPreferences.setAnnounceEnabled(context, true)
        TrackerPreferences.setClearnetTrackersEnabled(context, clearnet)
        TrackerPreferences.setYggTrackersEnabled(context, yggdrasil)
        TrackerPreferences.setDhtEnabled(context, false)
        TrackerPreferences.setIpv4AnnounceMode(context, ipv4)
        TrackerPreferences.supportedProtocols.forEach { protocol ->
            TrackerPreferences.setProtocolEnabled(context, protocol, protocol in protocols)
        }
    }

    private fun setKnownPeers(preference: P2PPreferences.PeerTransportPreference) {
        listOf("Alice", "Alice · ", "Bob", "Bob · ").forEach { peer ->
            P2PPreferences.setPeerTransportPreference(context, peer, preference)
        }
    }

    private fun hasYggdrasilHost(url: String): Boolean {
        val bytes = URI(url).host?.let(java.net.InetAddress::getByName)?.address ?: return false
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0x02
    }
}
