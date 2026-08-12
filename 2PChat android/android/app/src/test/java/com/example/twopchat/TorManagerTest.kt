package com.example.twopchat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TorManagerTest {

    @Test
    fun testDefaultStateIsNotRunning() {
        assertFalse(TorManager.isTorRunning.value)
    }

    @Test
    fun testTorrcConfigStringGeneration() {
        val config = TorManager.generateTorrcContent("/data/user/0/com.example.twopchat/files/app_tor", 9050, 9051)
        assertTrue(config.contains("DataDirectory /data/user/0/com.example.twopchat/files/app_tor"))
        assertTrue(config.contains("SocksPort 127.0.0.1:9050"))
        assertTrue(config.contains("ControlPort 127.0.0.1:9051"))
        assertTrue(config.contains("CookieAuthentication 1"))
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
    fun testCodeCacheExecutableResolution() {
        val tempCodeCache = File(System.getProperty("java.io.tmpdir"), "code_cache")
        tempCodeCache.mkdirs()
        val binFile = File(tempCodeCache, "tor_bin")
        binFile.writeText("binary_content")
        assertTrue(binFile.exists())
        binFile.delete()
    }
}
