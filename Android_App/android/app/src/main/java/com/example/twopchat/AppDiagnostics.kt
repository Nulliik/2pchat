package com.example.twopchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppLogEntry(
    val timestamp: String,
    val level: String,
    val message: String,
)

object AppDiagnostics {
    private const val MAX_LOG_ENTRIES = 400

    private val _logs = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val logs: StateFlow<List<AppLogEntry>> = _logs.asStateFlow()

    private val _peerStatuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerStatuses: StateFlow<Map<String, String>> = _peerStatuses.asStateFlow()

    fun addLog(level: String, message: String) {
        val entry = AppLogEntry(
            timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
            level = level,
            message = message,
        )
        val next = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)
        _logs.value = next
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun setPeerStatus(peerName: String, status: String) {
        _peerStatuses.value = _peerStatuses.value.toMutableMap().apply {
            this[peerName] = status
        }
        addLog("STATUS", "$peerName: $status")
    }

    fun statusFor(peerName: String): String {
        return _peerStatuses.value[peerName] ?: "Idle"
    }
}
