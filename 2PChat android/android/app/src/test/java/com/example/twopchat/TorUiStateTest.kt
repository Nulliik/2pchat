package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Test

class TorUiStateTest {

    @Test
    fun testTorStatusLabelText() {
        assertEquals("Подключено к Tor", TorStatusFormatter.formatStatus(isRunning = true, isRussian = true))
        assertEquals("Connected to Tor", TorStatusFormatter.formatStatus(isRunning = true, isRussian = false))
        assertEquals("Отключено", TorStatusFormatter.formatStatus(isRunning = false, isRussian = true))
        assertEquals("Disconnected", TorStatusFormatter.formatStatus(isRunning = false, isRussian = false))
    }
}
