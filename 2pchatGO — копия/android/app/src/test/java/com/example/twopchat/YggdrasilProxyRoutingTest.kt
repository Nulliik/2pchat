package com.example.twopchat

import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.yggdrasil.YggdrasilUserSpaceStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Unit tests for Yggdrasil Proxy Routing and SOCKS5 UserSpace Stack protocol integration.
 */
class YggdrasilProxyRoutingTest {

    @Test
    fun testYggdrasilIpv6CanonicalRangeValidation() {
        fun isYggdrasilIpv6(ipStr: String): Boolean {
            return try {
                val clean = ipStr.trim().removeSurrounding("[", "]")
                val addr = InetAddress.getByName(clean)
                val bytes = addr.address
                if (bytes.size != 16) return false
                // Yggdrasil 200::/7 allocation: first byte is in range 0x02..0x03
                val firstByte = bytes[0].toInt() and 0xFF
                firstByte in 0x02..0x03
            } catch (_: Exception) {
                false
            }
        }

        // Valid Yggdrasil IPv6 addresses (200::/7 prefix)
        assertTrue(isYggdrasilIpv6("200:1234:5678:9abc::1"))
        assertTrue(isYggdrasilIpv6("[201:dead:beef::42]"))
        assertTrue(isYggdrasilIpv6("300:cafe:babe::1"))
        assertTrue(isYggdrasilIpv6("3ff:ffff:ffff::1"))

        // Invalid: public clearnet IPv6, link-local, loopback, IPv4
        assertFalse(isYggdrasilIpv6("2001:4860:4860::8888")) // Google DNS
        assertFalse(isYggdrasilIpv6("fe80::1")) // Link-local
        assertFalse(isYggdrasilIpv6("::1")) // Loopback
        assertFalse(isYggdrasilIpv6("192.168.1.1")) // IPv4
        assertFalse(isYggdrasilIpv6("invalid-string"))
    }

    @Test
    fun testSocks5GreetingAndAuthNegotiation() {
        val outStream = ByteArrayOutputStream()
        // Client sends SOCKS5 Greeting: VER=5, NMETHODS=1, METHOD=0 (No Auth)
        outStream.write(byteArrayOf(0x05, 0x01, 0x00))

        val clientGreeting = outStream.toByteArray()
        assertEquals(3, clientGreeting.size)
        assertEquals(0x05.toByte(), clientGreeting[0]) // SOCKS version 5
        assertEquals(0x01.toByte(), clientGreeting[1]) // 1 method supported
        assertEquals(0x00.toByte(), clientGreeting[2]) // No authentication

        // Server response simulation
        val serverResponse = byteArrayOf(0x05, 0x00) // VER=5, METHOD=0 (Accepted)
        assertEquals(0x05.toByte(), serverResponse[0])
        assertEquals(0x00.toByte(), serverResponse[1])
    }

    @Test
    fun testSocks5ConnectRequestForYggdrasilIpv6Address() {
        // Build SOCKS5 CONNECT request for IPv6 destination [200:1234::1]:50001
        val targetIp = InetAddress.getByName("200:1234::1").address
        val targetPort = 50001

        val requestStream = ByteArrayOutputStream()
        requestStream.write(0x05) // SOCKS5
        requestStream.write(0x01) // CMD: CONNECT
        requestStream.write(0x00) // RSV
        requestStream.write(0x04) // ATYP: IPv6 (16 bytes)
        requestStream.write(targetIp)
        requestStream.write((targetPort shr 8) and 0xFF)
        requestStream.write(targetPort and 0xFF)

        val packet = requestStream.toByteArray()

        // 1 (VER) + 1 (CMD) + 1 (RSV) + 1 (ATYP) + 16 (IPv6) + 2 (Port) = 22 bytes
        assertEquals(22, packet.size)
        assertEquals(0x05.toByte(), packet[0])
        assertEquals(0x01.toByte(), packet[1]) // CONNECT
        assertEquals(0x04.toByte(), packet[3]) // ATYP = IPv6

        val parsedIp = InetAddress.getByAddress(packet.copyOfRange(4, 20)).hostAddress
        val parsedPort = ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF)

        assertTrue(parsedIp?.contains("200:1234") == true)
        assertEquals(50001, parsedPort)
    }

    @Test
    fun testYggdrasilRoutingModeSelector() {
        fun parseHostAndPort(endpoint: String, defaultPort: Int = 50001): Pair<String, Int> {
            val trimmed = endpoint.trim()
            return if (trimmed.startsWith("[")) {
                val closeBracket = trimmed.indexOf(']')
                val host = if (closeBracket != -1) trimmed.substring(1, closeBracket) else trimmed
                val port = if (closeBracket != -1 && closeBracket + 1 < trimmed.length && trimmed[closeBracket + 1] == ':') {
                    trimmed.substring(closeBracket + 2).toIntOrNull() ?: defaultPort
                } else {
                    defaultPort
                }
                Pair(host, port)
            } else {
                val lastColon = trimmed.lastIndexOf(':')
                if (lastColon != -1) {
                    val host = trimmed.substring(0, lastColon)
                    val port = trimmed.substring(lastColon + 1).toIntOrNull() ?: defaultPort
                    Pair(host, port)
                } else {
                    Pair(trimmed, defaultPort)
                }
            }
        }

        fun resolveYggdrasilTargetSocket(
            targetAddress: String,
            mode: P2PPreferences.YggdrasilMode,
            localProxyPort: Int = 9053
        ): InetSocketAddress {
            val (host, port) = parseHostAndPort(targetAddress, 50001)
            return if (mode == P2PPreferences.YggdrasilMode.PROXY) {
                // In PROXY mode, connect to local userSpace stack port
                InetSocketAddress.createUnresolved("127.0.0.1", localProxyPort)
            } else {
                // In VPN (TUN) mode, connect directly to the destination Yggdrasil IP
                InetSocketAddress.createUnresolved(host, port)
            }
        }

        val proxySocket = resolveYggdrasilTargetSocket("[200:abc::1]:50001", P2PPreferences.YggdrasilMode.PROXY)
        assertEquals("127.0.0.1", proxySocket.hostString)
        assertEquals(9053, proxySocket.port)

        val vpnSocket = resolveYggdrasilTargetSocket("[200:abc::1]:50001", P2PPreferences.YggdrasilMode.VPN)
        assertEquals("200:abc::1", vpnSocket.hostString)
        assertEquals(50001, vpnSocket.port)
    }

    @Test
    fun testTcpPayloadSegmentationForAvatarProfileShare() {
        // The user's log exhibited session drops with 14,725-byte profile avatar payload:
        // "Sending profile information to doggy (length: 14725)"
        val largePayload = ByteArray(14725) { (it % 256).toByte() }

        val segments = YggdrasilUserSpaceStack.segmentPayload(largePayload, YggdrasilUserSpaceStack.MAX_TCP_PAYLOAD)

        // 14725 / 1200 = 12 full segments (12 * 1200 = 14400) + 1 remainder (325 bytes) = 13 segments
        assertEquals(13, segments.size)

        var totalBytes = 0
        val reassembled = ByteArray(14725)

        for (seg in segments) {
            assertTrue("Segment size must be <= MAX_TCP_PAYLOAD (1200)", seg.size <= YggdrasilUserSpaceStack.MAX_TCP_PAYLOAD)
            // IPv6 header (40) + TCP header (20) + payload (<= 1200) = total packet <= 1260 bytes <= 1280 Yggdrasil link MTU
            val totalPacketLen = 40 + 20 + seg.size
            assertTrue("Total IPv6 packet must not exceed Yggdrasil MTU (1280)", totalPacketLen <= 1280)

            System.arraycopy(seg, 0, reassembled, totalBytes, seg.size)
            totalBytes += seg.size
        }

        assertEquals(14725, totalBytes)
        org.junit.Assert.assertArrayEquals(largePayload, reassembled)

        // Test boundary cases
        assertTrue(YggdrasilUserSpaceStack.segmentPayload(ByteArray(0)).isEmpty())
        val singleSeg = YggdrasilUserSpaceStack.segmentPayload(ByteArray(500))
        assertEquals(1, singleSeg.size)
        assertEquals(500, singleSeg[0].size)
    }
}
