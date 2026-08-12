package com.example.twopchat

object TorStatusFormatter {
    fun formatStatus(isRunning: Boolean, isConnecting: Boolean = false, isRussian: Boolean = false): String {
        return when {
            isRunning -> if (isRussian) "Подключено к Tor" else "Connected to Tor"
            isConnecting -> if (isRussian) "Подключение..." else "Connecting..."
            else -> if (isRussian) "Отключено" else "Disconnected"
        }
    }
}
