package com.example.twopchat

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
}
