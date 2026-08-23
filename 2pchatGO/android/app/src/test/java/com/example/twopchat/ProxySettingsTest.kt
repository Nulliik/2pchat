package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import com.example.twopchat.config.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySettingsTest {

    @Test
    fun testDefaultProxySettingsValues() {
        assertEquals("127.0.0.1", P2PPreferences.DEFAULT_PROXY_HOST)
        assertEquals(9050, P2PPreferences.DEFAULT_PROXY_PORT)
    }

    @Test
    fun testProxyConfigJsonPayloadFormatting() {
        val json = ProxyConfig.toJson(
            enabled = true,
            host = "127.0.0.1",
            port = 9050
        )
        assertTrue(json.contains("\"proxy_enabled\":true"))
        assertTrue(json.contains("\"proxy_host\":\"127.0.0.1\""))
        assertTrue(json.contains("\"proxy_port\":9050"))
    }

    @Test
    fun testProxyHostAndPortValidation() {
        assertTrue(ProxyConfig.isValidHost("127.0.0.1"))
        assertTrue(ProxyConfig.isValidHost("localhost"))
        assertFalse(ProxyConfig.isValidHost(""))
        assertFalse(ProxyConfig.isValidHost("   "))

        assertTrue(ProxyConfig.isValidPort(9050))
        assertTrue(ProxyConfig.isValidPort(1080))
        assertFalse(ProxyConfig.isValidPort(0))
        assertFalse(ProxyConfig.isValidPort(70000))
    }

    @Test
    fun testLegacyEmbeddedTorPreferenceDetection() {
        assertTrue(P2PPreferences.isLegacyTorConfiguration(true, "127.0.0.1", 9050))
        assertTrue(P2PPreferences.isLegacyTorConfiguration(true, "localhost", 9050))
        assertTrue(P2PPreferences.isLegacyTorConfiguration(true, "::1", 9050))
        assertFalse(P2PPreferences.isLegacyTorConfiguration(false, "127.0.0.1", 9050))
        assertFalse(P2PPreferences.isLegacyTorConfiguration(true, "proxy.example", 9050))
        assertFalse(P2PPreferences.isLegacyTorConfiguration(true, "127.0.0.1", 1080))
    }

    @Test
    fun testHeroWidgetCollapsedDefaultKey() {
        assertEquals("settings_hero_widget_collapsed", P2PPreferences.HERO_WIDGET_COLLAPSED_DEFAULT)
    }

    @Test
    fun testCustomSocks5PreferenceKeys() {
        assertEquals("settings_socks5_enabled", P2PPreferences.SOCKS5_ENABLED)
        assertEquals("settings_socks5_host", P2PPreferences.SOCKS5_HOST)
        assertEquals("settings_socks5_port", P2PPreferences.SOCKS5_PORT)
        assertEquals("127.0.0.1", P2PPreferences.DEFAULT_SOCKS5_HOST)
        assertEquals(1080, P2PPreferences.DEFAULT_SOCKS5_PORT)
    }

    @Test
    fun testIncognitoKeyboardPreferenceKeys() {
        assertEquals("settings_incognito_keyboard", P2PPreferences.INCOGNITO_KEYBOARD)
    }

    @Test
    fun testProxyPriorityResolution() {
        // 1. Tor active takes precedence over custom SOCKS5
        val torPriority = ProxyConfig.resolveProxyConfig(
            isTorEnabled = true,
            isTorRunning = true,
            customSocks5Enabled = true,
            customHost = "10.0.0.1",
            customPort = 1080
        )
        assertTrue(torPriority.enabled)
        assertEquals("127.0.0.1", torPriority.host)
        assertEquals(9050, torPriority.port)

        // 2. Tor disabled/not running -> fallback to Custom SOCKS5
        val customSocks5 = ProxyConfig.resolveProxyConfig(
            isTorEnabled = false,
            isTorRunning = false,
            customSocks5Enabled = true,
            customHost = "10.0.0.1",
            customPort = 1080
        )
        assertTrue(customSocks5.enabled)
        assertEquals("10.0.0.1", customSocks5.host)
        assertEquals(1080, customSocks5.port)

        // 3. Both disabled -> Direct connection
        val direct = ProxyConfig.resolveProxyConfig(
            isTorEnabled = false,
            isTorRunning = false,
            customSocks5Enabled = false,
            customHost = "10.0.0.1",
            customPort = 1080
        )
        assertFalse(direct.enabled)
    }

    @Test
    fun testInvalidCustomValuesFallBackToSocks5Defaults() {
        val resolved = ProxyConfig.resolveProxyConfig(
            isTorEnabled = false,
            isTorRunning = false,
            customSocks5Enabled = true,
            customHost = " invalid host ",
            customPort = 0,
        )

        assertEquals(P2PPreferences.DEFAULT_SOCKS5_HOST, resolved.host)
        assertEquals(P2PPreferences.DEFAULT_SOCKS5_PORT, resolved.port)
    }
}


