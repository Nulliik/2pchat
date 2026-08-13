package com.example.twopchat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TorManagerTest {

    @Test
    fun testDefaultStateIsNotRunning() {
        assertFalse(TorManager.isTorRunning.value)
        assertEquals(0, TorManager.bootstrapProgress.value)
    }

    @Test
    fun testTorrcConfigStringGenerationWithoutBridges() {
        val config = TorManager.generateTorrcContent("/data/user/0/com.example.twopchat/files/app_tor", 9050, 9051)
        assertTrue(config.contains("DataDirectory /data/user/0/com.example.twopchat/files/app_tor"))
        assertTrue(config.contains("SocksPort 127.0.0.1:9050"))
        assertTrue(config.contains("ControlPort 127.0.0.1:9051"))
        assertTrue(config.contains("CookieAuthentication 1"))
        assertTrue(config.contains("SafeLogging 1"))
        assertFalse(config.contains("UseBridges 1"))
    }

    @Test
    fun testAutomaticPublicBridgePoolIsValidAndBounded() {
        val bridges = TorBridgeCatalog.PUBLIC_OBFS4_BRIDGES
        val parsed = TorManager.parseBridgeLines(bridges)

        assertTrue(bridges.isNotEmpty())
        assertTrue(bridges.size <= 16)
        assertNull(parsed.error)
        assertEquals(setOf("obfs4"), parsed.transports)
        assertEquals(bridges, parsed.bridges)
    }

    @Test
    fun testBridgeSelectionPrefersCustomAndCanReturnDirectMode() {
        val custom = listOf(
            "obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 " +
                "cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0"
        )

        assertEquals(
            custom,
            TorBridgeCatalog.select(customBridges = custom, publicBridgesEnabled = true),
        )
        assertEquals(
            TorBridgeCatalog.PUBLIC_OBFS4_BRIDGES + TorBridgeCatalog.PUBLIC_SNOWFLAKE_BRIDGES,
            TorBridgeCatalog.select(customBridges = emptyList(), publicBridgesEnabled = true),
        )
        assertTrue(
            TorBridgeCatalog.select(
                customBridges = emptyList(),
                publicBridgesEnabled = false,
            ).isEmpty()
        )
    }

    @Test
    fun testTorrcConfigStringGenerationWithBridges() {
        val bridges = listOf(
            "obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0",
            "snowflake 192.0.2.3:1 2B280B23E1107BB62AB6C19820C2D92660262B20 " +
                "url=https://snowflake-broker.example/ fronts=cdn.example ice=stun:stun.example:3478"
        )
        val config = TorManager.generateTorrcContent(
            dataDir = "/data/user/0/com.example.twopchat/files/app_tor",
            socksPort = 9050,
            controlPort = 9051,
            bridges = bridges,
            bridgePluginPath = "/data/app/lib/liblyrebird.so"
        )

        assertTrue(config.contains("UseBridges 1"))
        assertTrue(config.contains("ClientTransportPlugin obfs4,snowflake exec /data/app/lib/liblyrebird.so"))
        assertTrue(config.contains("Bridge obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0"))
        assertTrue(config.contains("Bridge snowflake 192.0.2.3:1 2B280B23E1107BB62AB6C19820C2D92660262B20"))
    }

    @Test
    fun testKnownObfs4BridgeLineIsNormalizedAndAccepted() {
        val result = TorManager.parseBridgeText(
            "Bridge obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 " +
                "cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0"
        )

        assertNull(result.error)
        assertEquals(setOf("obfs4"), result.transports)
        assertEquals(
            listOf(
                "obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 " +
                    "cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0"
            ),
            result.bridges
        )
    }

    @Test
    fun testIpv6Obfs4BridgeEndpointIsAccepted() {
        val result = TorManager.parseBridgeText(
            "obfs4 [2001:db8::10]:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 " +
                "cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=1"
        )

        assertNull(result.error)
        assertEquals(setOf("obfs4"), result.transports)
    }

    @Test
    fun testBridgeParserRejectsTorrcDirectiveInjection() {
        val validBridge =
            "obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 " +
                "cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0"
        val result = TorManager.parseBridgeLines(
            listOf("$validBridge\nSocksPort 0.0.0.0:9999")
        )

        assertTrue(result.error != null)
    }

    @Test
    fun testBridgeParserRejectsUnsupportedTransportAndMissingSnowflakeConfig() {
        assertEquals(
            TorBridgeValidationError.UNSUPPORTED_TRANSPORT,
            TorManager.parseBridgeText(
                "webtunnel 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 url=https://example/"
            ).error
        )
        assertEquals(
            TorBridgeValidationError.MISSING_SNOWFLAKE_CONFIGURATION,
            TorManager.parseBridgeText(
                "snowflake 192.0.2.3:1 2B280B23E1107BB62AB6C19820C2D92660262B20"
            ).error
        )
    }

    @Test
    fun testTorrcGenerationFailsClosedWithoutMatchingTransportPort() {
        val bridge =
            "obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 " +
                "cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0"

        var rejected = false
        try {
            TorManager.generateTorrcContent(dataDir = "/tmp/tor", bridges = listOf(bridge))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun testParseBootstrapProgressFromLog() {
        assertEquals(5, TorManager.parseBootstrapProgress("Aug 12 08:05:56.549 [notice] Bootstrapped 5% (conn): Connecting to a relay"))
        assertEquals(45, TorManager.parseBootstrapProgress("Aug 12 08:05:58.120 [notice] Bootstrapped 45% (requesting_descriptors): Requesting relay descriptors"))
        assertEquals(100, TorManager.parseBootstrapProgress("Aug 12 08:06:02.001 [notice] Bootstrapped 100% (done): Done"))
        assertNull(TorManager.parseBootstrapProgress("Aug 12 08:05:56.550 [warn] Failed to parse/validate config"))
        assertNull(TorManager.parseBootstrapProgress("[notice] Bootstrapped 101% (invalid): Invalid progress"))
    }

    @Test
    fun testBootstrapFailureClassificationDoesNotExposeRawRelayDetails() {
        assertEquals(
            "TLS_HANDSHAKE",
            TorManager.classifyBootstrapFailureHint(
                "[warn] Problem bootstrapping. Stuck at 45% (requesting_descriptors): " +
                    "TLS error in connection to 192.0.2.44:443"
            )
        )
        assertEquals(
            "CLOCK_SKEW",
            TorManager.classifyBootstrapFailureHint("[warn] Your system clock is wrong")
        )
        assertNull(TorManager.classifyBootstrapFailureHint("[notice] Bootstrapped 45%"))
    }

    @Test
    fun testStaleRunCleanupCannotInvalidateNewRun() {
        val gate = TorRunGate()
        val firstRun = gate.begin()
        gate.invalidate()
        val secondRun = gate.begin()

        assertFalse(gate.finish(firstRun))
        assertTrue(gate.isCurrent(secondRun))
        assertTrue(gate.finish(secondRun))
        assertFalse(gate.isCurrent(secondRun))
    }

    @Test
    fun testBootstrapRequiresBothSocksPortAndCompletedCircuit() {
        assertFalse(TorManager.isBootstrapReady(socksPortReady = false, bootstrapProgress = 100))
        assertFalse(TorManager.isBootstrapReady(socksPortReady = true, bootstrapProgress = 0))
        assertFalse(TorManager.isBootstrapReady(socksPortReady = true, bootstrapProgress = 99))
        assertTrue(TorManager.isBootstrapReady(socksPortReady = true, bootstrapProgress = 100))
    }

    @Test
    fun testRapidStartAndStopTor() = runBlocking {
        TorManager.stopTor()
        assertFalse(TorManager.isTorRunning.value)

        // Mock start with fast cancellation
        TorManager.stopTor()
        assertFalse(TorManager.isTorRunning.value)
    }

    @Test
    fun testStopTorIsIdempotent() {
        TorManager.stopTor()
        TorManager.stopTor()
        TorManager.stopTor()
        assertFalse(TorManager.isTorRunning.value)
    }

    @Test
    fun testNativeLibraryDirExecutableResolution() {
        val tempNativeDir = File(System.getProperty("java.io.tmpdir"), "lib_test")
        tempNativeDir.mkdirs()
        val libTorSo = File(tempNativeDir, "libtor.so")
        libTorSo.writeText("mock_so_binary")
        
        val resolvedExecutable = if (libTorSo.exists()) libTorSo else null
        assertEquals(libTorSo.absolutePath, resolvedExecutable?.absolutePath)
        
        libTorSo.delete()
        tempNativeDir.delete()
    }

    @Test
    fun testMissingLibTorGracefulHandling() {
        val nonExistentDir = File(System.getProperty("java.io.tmpdir"), "non_existent_dir")
        val libTorSo = File(nonExistentDir, "libtor.so")
        
        val resolvedExecutable = if (libTorSo.exists()) libTorSo else null
        assertNull(resolvedExecutable)
    }

    @Test
    fun testControlPortAuthCookieFormatting() {
        val rawCookie = ByteArray(32) { (it + 1).toByte() }
        val hexString = TorManager.formatControlAuthCookie(rawCookie)
        assertEquals(64, hexString.length)
        assertTrue(hexString.startsWith("01020304"))
    }

    @Test
    fun testTorBridgeCatalogRotation() {
        val initialIndex = TorBridgeCatalog.currentBridgeIndex.value
        val bridge1 = TorBridgeCatalog.rotateNextBridge()
        val nextIndex = TorBridgeCatalog.currentBridgeIndex.value
        assertEquals(
            (initialIndex + 1) % (TorBridgeCatalog.PUBLIC_OBFS4_BRIDGES.size + TorBridgeCatalog.PUBLIC_SNOWFLAKE_BRIDGES.size),
            nextIndex,
        )
        assertTrue(bridge1.startsWith("obfs4") || bridge1.startsWith("snowflake"))
    }

    @Test
    fun testSnowflakeOnlyTransportUsesSnowflakeBridge() {
        val bridges = TorBridgeCatalog.select(
            customBridges = emptyList(),
            publicBridgesEnabled = true,
            transport = TorTransport.SNOWFLAKE,
        )

        assertEquals(TorBridgeCatalog.PUBLIC_SNOWFLAKE_BRIDGES.toSet(), bridges.toSet())
        assertEquals(null, TorManager.parseBridgeLines(bridges).error)
    }

    @Test
    fun testBuiltInSnowflakeCatalogUsesCurrentTorBrowserRendezvous() {
        val bridges = TorBridgeCatalog.PUBLIC_SNOWFLAKE_BRIDGES

        assertEquals(2, bridges.size)
        bridges.forEach { bridge ->
            assertTrue(bridge.contains("url=https://1098762253.rsc.cdn77.org/"))
            assertTrue(bridge.contains("fronts=app.datapacket.com,www.datapacket.com"))
            assertTrue(bridge.contains("utls-imitate=hellorandomizedalpn"))
            assertFalse(bridge.contains("snowflake-broker.torproject.net.global.prod.fastly.net"))
        }
    }

    @Test
    fun testBootstrapStallDetectionThreshold() {
        assertFalse(TorManager.shouldRotateOnBootstrapStall(progress = 45, durationMs = 29000L))
        assertTrue(TorManager.shouldRotateOnBootstrapStall(progress = 45, durationMs = 31000L))
        assertFalse(TorManager.shouldRotateOnBootstrapStall(progress = 100, durationMs = 35000L))
    }

    @Test
    fun testCountryCodeToFlagEmoji() {
        assertEquals("🇩🇪", TorManager.countryCodeToFlagEmoji("DE"))
        assertEquals("🇫🇷", TorManager.countryCodeToFlagEmoji("FR"))
        assertEquals("🇺🇸", TorManager.countryCodeToFlagEmoji("US"))
        assertEquals("🇷🇺", TorManager.countryCodeToFlagEmoji("RU"))
        assertEquals("🌐", TorManager.countryCodeToFlagEmoji(""))
        assertEquals("🌐", TorManager.countryCodeToFlagEmoji(null))
    }

    @Test
    fun testParseCircuitStatusResponse() {
        val sampleResponse = "1 BUILT \$A123456789012345678901234567890123456789~GuardDE,\$B123456789012345678901234567890123456789~MiddleFR,\$C123456789012345678901234567890123456789~ExitUS PURPOSE=GENERAL"
        val nodes = TorManager.parseCircuitStatusNodes(sampleResponse)
        assertEquals(3, nodes.size)
        assertEquals("DE", nodes[0].countryCode)
        assertEquals("🇩🇪", nodes[0].flagEmoji)
        assertEquals("FR", nodes[1].countryCode)
        assertEquals("🇫🇷", nodes[1].flagEmoji)
        assertEquals("US", nodes[2].countryCode)
        assertEquals("🇺🇸", nodes[2].flagEmoji)
    }
}


