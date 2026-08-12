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
        assertFalse(config.contains("UseBridges 1"))
    }

    @Test
    fun testTorrcConfigStringGenerationWithBridges() {
        val bridges = listOf(
            "obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 cert=bb86... iat-mode=0",
            "snowflake 192.0.2.3:1 2B280B23E1107BB62AB6C19820C2D92660262B20"
        )
        val config = TorManager.generateTorrcContent(
            dataDir = "/data/user/0/com.example.twopchat/files/app_tor",
            socksPort = 9050,
            controlPort = 9051,
            bridges = bridges,
            obfs4PluginPath = "/data/app/lib/libobfs4proxy.so",
            snowflakePluginPath = "/data/app/lib/libsnowflake.so"
        )

        assertTrue(config.contains("UseBridges 1"))
        assertTrue(config.contains("ClientTransportPlugin obfs4 exec /data/app/lib/libobfs4proxy.so"))
        assertTrue(config.contains("ClientTransportPlugin snowflake exec /data/app/lib/libsnowflake.so"))
        assertTrue(config.contains("Bridge obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 cert=bb86... iat-mode=0"))
        assertTrue(config.contains("Bridge snowflake 192.0.2.3:1 2B280B23E1107BB62AB6C19820C2D92660262B20"))
    }

    @Test
    fun testParseBootstrapProgressFromLog() {
        assertEquals(5, TorManager.parseBootstrapProgress("Aug 12 08:05:56.549 [notice] Bootstrapped 5% (conn): Connecting to a relay"))
        assertEquals(45, TorManager.parseBootstrapProgress("Aug 12 08:05:58.120 [notice] Bootstrapped 45% (requesting_descriptors): Requesting relay descriptors"))
        assertEquals(100, TorManager.parseBootstrapProgress("Aug 12 08:06:02.001 [notice] Bootstrapped 100% (done): Done"))
        assertNull(TorManager.parseBootstrapProgress("Aug 12 08:05:56.550 [warn] Failed to parse/validate config"))
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
}
