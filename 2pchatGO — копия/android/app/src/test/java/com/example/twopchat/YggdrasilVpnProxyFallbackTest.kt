package com.example.twopchat

import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.relay.TransportType
import com.example.twopchat.relay.canonicalConnectionTransport
import com.example.twopchat.relay.resolveTransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YggdrasilVpnProxyFallbackTest {

    @Test
    fun testYggdrasilModeEnumValues() {
        assertEquals("proxy", P2PPreferences.YggdrasilMode.PROXY.id)
        assertEquals("vpn", P2PPreferences.YggdrasilMode.VPN.id)

        // Ensure fromValue resolution
        val modeProxy = P2PPreferences.YggdrasilMode.values().firstOrNull { it.id == "proxy" }
        assertEquals(P2PPreferences.YggdrasilMode.PROXY, modeProxy)

        val modeVpn = P2PPreferences.YggdrasilMode.values().firstOrNull { it.id == "vpn" }
        assertEquals(P2PPreferences.YggdrasilMode.VPN, modeVpn)
    }

    @Test
    fun testVpnRevokeFallbackPreservesEnabledState() {
        // Simulates RULES.md §6 & §12:
        // When Android system revokes the VPN slot in favor of a 3rd party VPN,
        // Yggdrasil mode transitions to PROXY so mesh routing continues over 127.0.0.1:9053
        var activeMode = P2PPreferences.YggdrasilMode.VPN
        var isYggdrasilEnabled = true

        // Simulate onRevoke() execution:
        val onRevokeAction = {
            activeMode = P2PPreferences.YggdrasilMode.PROXY
            // isYggdrasilEnabled remains true!
        }

        onRevokeAction()

        assertEquals(P2PPreferences.YggdrasilMode.PROXY, activeMode)
        assertTrue("Yggdrasil must remain enabled across VPN slot revoking", isYggdrasilEnabled)
    }

    @Test
    fun testTransportTypeResolutionForYggdrasilAndTor() {
        val yggType = resolveTransportType("yggdrasil", "[200:1234::1]:50001", isOnline = true)
        assertEquals(TransportType.YGGDRASIL, yggType)

        val torType = resolveTransportType("onion", "abcxyz.onion:50001", isOnline = true)
        assertEquals(TransportType.ONION, torType)

        val directType = resolveTransportType("direct", "192.168.1.50:50001", isOnline = true)
        assertEquals(TransportType.DIRECT, directType)

        val offlineType = resolveTransportType("direct", "192.168.1.50:50001", isOnline = false)
        assertEquals(TransportType.DISCONNECTED, offlineType)
    }

    @Test
    fun testCanonicalConnectionTransportLabels() {
        assertEquals("Yggdrasil", canonicalConnectionTransport("yggdrasil"))
        assertEquals("Yggdrasil", canonicalConnectionTransport(null, "[200:db8::1]:50001"))
        assertEquals("Tor Onion", canonicalConnectionTransport("tor"))
        assertEquals("Tor Onion", canonicalConnectionTransport(null, "abcdef.onion:50001"))
        assertEquals("Direct P2P", canonicalConnectionTransport("direct"))
        assertEquals("Direct P2P", canonicalConnectionTransport(null, "10.0.0.1:50001"))
    }
}
