package com.example.twopchat

object TorStatusFormatter {
    fun formatStatus(isRunning: Boolean, isRussian: Boolean): String {
        return if (isRunning) {
            if (isRussian) "Подключено к Tor" else "Connected to Tor"
        } else {
            if (isRussian) "Отключено" else "Disconnected"
        }
    }
}
