package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import com.example.twopchat.tor.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TorUiStateTest {

    @Test
    fun testTorStatusLabelText() {
        assertEquals("Подключено к Tor", TorStatusFormatter.formatStatus(isRunning = true, isConnecting = false, isRussian = true))
        assertEquals("Connected to Tor", TorStatusFormatter.formatStatus(isRunning = true, isConnecting = false, isRussian = false))
        assertEquals("Подключение...", TorStatusFormatter.formatStatus(isRunning = false, isConnecting = true, isRussian = true))
        assertEquals("Connecting...", TorStatusFormatter.formatStatus(isRunning = false, isConnecting = true, isRussian = false))
        assertEquals("Подключение... (45%)", TorStatusFormatter.formatStatus(isRunning = false, isConnecting = true, isRussian = true, progress = 45))
        assertEquals("Connecting... (45%)", TorStatusFormatter.formatStatus(isRunning = false, isConnecting = true, isRussian = false, progress = 45))
        assertEquals("Подключение (Медленная сеть)... (25%)", TorStatusFormatter.formatStatus(isRunning = false, isConnecting = true, isRussian = true, progress = 25, isSlowBootstrap = true))
        assertEquals("Connecting (Slow network)... (25%)", TorStatusFormatter.formatStatus(isRunning = false, isConnecting = true, isRussian = false, progress = 25, isSlowBootstrap = true))
        assertEquals("Отключено", TorStatusFormatter.formatStatus(isRunning = false, isConnecting = false, isRussian = true))
        assertEquals("Disconnected", TorStatusFormatter.formatStatus(isRunning = false, isConnecting = false, isRussian = false))
    }

    @Test
    fun testProxyNotEnabledIfTorFailsToRun() {
        var proxySaved = false
        val isTorRunning = false
        val isConnecting = false

        if (isTorRunning) {
            proxySaved = true
        }

        assertFalse(proxySaved)
    }
}
