package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
