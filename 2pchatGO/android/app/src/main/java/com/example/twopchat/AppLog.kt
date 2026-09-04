package com.example.twopchat

import android.content.Context
import java.io.File

/** Bounded diagnostic log shared by the Kotlin side of the Android app. */
object AppLog {
    private const val MAX_LOG_BYTES = 5L * 1024L * 1024L
    private val bracketedIpv6Pattern = Regex("""\[[0-9a-fA-F:]{3,}](?::\d{1,5})?""")
    private val ipv4Pattern = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?::\d{1,5})?""")
    private val bareIpv6Pattern = Regex("""(?<![\w:])(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}(?::\d{1,5})?""")
    private val onionPattern = Regex("""(?i)\b[a-z2-7]{56}\.onion\b""")
    private val fingerprintPattern = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{32,128}(?![0-9a-f])""")
    private val base64KeyPattern = Regex("""(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_=-]{30,128}={0,2}(?![A-Za-z0-9+/_=-])""")

    fun redactSensitive(text: String, privateRoot: String? = null): String {
        if (text.length < 8) return text
        var redacted = text
        if (!privateRoot.isNullOrBlank() && redacted.contains(privateRoot)) {
            redacted = redacted.replace(privateRoot, "<app-private-dir>")
        }
        redacted = bracketedIpv6Pattern.replace(redacted, "<ip>")
        redacted = ipv4Pattern.replace(redacted, "<ip>")
        redacted = bareIpv6Pattern.replace(redacted, "<ip>")
        redacted = onionPattern.replace(redacted, "<onion>")
        redacted = fingerprintPattern.replace(redacted, "<fingerprint>")
        redacted = base64KeyPattern.replace(redacted, "<key>")
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
            com.example.twopchat.logging.SafeLog.w("AppLog", "Failed to append log", it)
        }
    }
}
