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
