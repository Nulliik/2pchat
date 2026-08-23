package com.example.twopchat

import android.content.Context
import java.io.File

/** Bounded diagnostic log shared by the Kotlin side of the Android app. */
object AppLog {
    private const val MAX_LOG_BYTES = 5L * 1024L * 1024L
    private val ipv4Pattern = Regex("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?")
    private val bracketedIpv6Pattern = Regex("\\[[0-9a-fA-F:]+](?::\\d{1,5})?")
    private val bareIpv6Pattern = Regex("(?<![\\w:])(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}(?::\\d{1,5})?")
    private val fingerprintPattern = Regex("(?i)(?<![0-9a-f])[0-9a-f]{40,128}(?![0-9a-f])")

    internal fun redactSensitive(text: String, privateRoot: String? = null): String {
        var redacted = text
        if (!privateRoot.isNullOrBlank()) redacted = redacted.replace(privateRoot, "<app-private-dir>")
        redacted = ipv4Pattern.replace(redacted, "<ip>")
        redacted = bracketedIpv6Pattern.replace(redacted, "<ip>")
        redacted = bareIpv6Pattern.replace(redacted, "<ip>")
        redacted = fingerprintPattern.replace(redacted, "<fingerprint>")
        return redacted
    }

    @Synchronized
    fun append(context: Context, text: String) {
        runCatching {
            val logDir = File(context.filesDir, "config")
            if (!logDir.exists()) logDir.mkdirs()

            val logFile = File(logDir, "app.log")
            val safeText = redactSensitive(text, context.filesDir.absolutePath)
            val incomingBytes = safeText.toByteArray(Charsets.UTF_8).size.toLong()
            if (logFile.exists() && logFile.length() + incomingBytes > MAX_LOG_BYTES) {
                val backup = File(logDir, "app.log.1")
                if (backup.exists()) backup.delete()
                if (!logFile.renameTo(backup)) {
                    // Keep the active log bounded even when a platform-specific rename fails.
                    logFile.writeText("")
                }
            }
            logFile.appendText(safeText)
        }.onFailure {
            android.util.Log.w("AppLog", "Failed to append log", it)
        }
    }
}
