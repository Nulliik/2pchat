package com.example.twopchat

import android.content.Context
import java.io.File

/** Bounded diagnostic log shared by the Kotlin side of the Android app. */
object AppLog {
    private const val MAX_LOG_BYTES = 5L * 1024L * 1024L

    @Synchronized
    fun append(context: Context, text: String) {
        val logDir = File(context.filesDir, "config")
        if (!logDir.exists()) logDir.mkdirs()

        val logFile = File(logDir, "app.log")
        val incomingBytes = text.toByteArray(Charsets.UTF_8).size.toLong()
        if (logFile.exists() && logFile.length() + incomingBytes > MAX_LOG_BYTES) {
            val backup = File(logDir, "app.log.1")
            if (backup.exists()) backup.delete()
            if (!logFile.renameTo(backup)) {
                // Keep the active log bounded even when a platform-specific rename fails.
                logFile.writeText("")
            }
        }
        logFile.appendText(text)
    }
}
