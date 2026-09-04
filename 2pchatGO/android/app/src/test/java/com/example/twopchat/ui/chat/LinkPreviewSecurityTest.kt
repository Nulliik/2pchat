package com.example.twopchat.ui.chat

import com.example.twopchat.config.P2PPreferences
import java.net.InetAddress
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewSecurityTest {

    @Test
    fun testDefaultLinkPreviewsPreferenceIsDisabled() {
        assertFalse("Link previews must be disabled by default for privacy", P2PPreferences.DEFAULT_LINK_PREVIEWS_ENABLED)
        assertEquals("settings_link_previews", P2PPreferences.SETTINGS_LINK_PREVIEWS)
    }

    @Test
    fun testNumericIpDetectionWithoutDns() {
        assertTrue(LinkPreviewFetcher.isNumericIpAddress("127.0.0.1"))
        assertTrue(LinkPreviewFetcher.isNumericIpAddress("192.168.1.100"))
        assertTrue(LinkPreviewFetcher.isNumericIpAddress("10.0.0.1"))
        assertTrue(LinkPreviewFetcher.isNumericIpAddress("8.8.8.8"))
        assertTrue(LinkPreviewFetcher.isNumericIpAddress("::1"))
        assertTrue(LinkPreviewFetcher.isNumericIpAddress("[::1]"))
        assertTrue(LinkPreviewFetcher.isNumericIpAddress("2001:db8::1"))
        assertTrue(LinkPreviewFetcher.isNumericIpAddress("[2001:db8::1]"))

        assertFalse(LinkPreviewFetcher.isNumericIpAddress("example.com"))
        assertFalse(LinkPreviewFetcher.isNumericIpAddress("localhost"))
        assertFalse(LinkPreviewFetcher.isNumericIpAddress("google.com"))
        assertFalse(LinkPreviewFetcher.isNumericIpAddress("my-router.lan"))
        assertFalse(LinkPreviewFetcher.isNumericIpAddress("duckduckgo.onion"))
    }

    @Test
    fun testPrivateAndInternalAddressesAreBlocked() {
        // Loopback
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalAddress(InetAddress.getByName("::1")))

        // RFC 1918 Private ranges
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalAddress(InetAddress.getByName("10.0.0.1")))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalAddress(InetAddress.getByName("192.168.1.1")))

        // Link-local
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalAddress(InetAddress.getByName("169.254.1.1")))

        // Public IPs should not be marked private
        assertFalse(LinkPreviewFetcher.isPrivateOrInternalAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(LinkPreviewFetcher.isPrivateOrInternalAddress(InetAddress.getByName("1.1.1.1")))
    }

    @Test
    fun testPrivateOrInternalHostRejection() {
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("localhost"))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("LOCALHOST"))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("service.local"))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("gateway.internal"))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("router.lan"))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("127.0.0.1"))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("192.168.1.1"))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("10.0.0.5"))
        assertTrue(LinkPreviewFetcher.isPrivateOrInternalHost("[::1]"))
    }

    @Test
    fun testInvalidOrDangerousProtocolsRejected() {
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("file:///etc/passwd"))
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("javascript:alert(1)"))
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("content://media/external/images"))
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("ftp://ftp.example.com"))
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("data:text/html,hello"))
    }

    @Test
    fun testPrivateIpUrlsRejected() {
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("http://127.0.0.1/admin"))
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("http://192.168.1.1/setup"))
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("http://10.0.0.1/status"))
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("http://localhost:8080/"))
        assertFalse(LinkPreviewFetcher.isSafeHttpUrl("http://router.local/api"))
    }

    @Test
    fun testOpenSafeConnectionRejectsDangerousUrls() {
        assertNull(LinkPreviewFetcher.openSafeConnection(URL("http://127.0.0.1:8080/")))
        assertNull(LinkPreviewFetcher.openSafeConnection(URL("http://192.168.0.1/")))
        assertNull(LinkPreviewFetcher.openSafeConnection(URL("http://localhost/")))
        assertNull(LinkPreviewFetcher.openSafeConnection(URL("http://printer.lan/")))
        assertNull(LinkPreviewFetcher.openSafeConnection(URL("http://device.internal/")))
    }
}
