package com.example.twopchat.security

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/** Helper to detect ROOT privileges / su binaries on Android devices without blocking execution. */
object RootDetectionHelper {

    fun isRooted(): Boolean {
        return checkBuildTags() || checkSuPaths() || checkWhichSu()
    }

    private fun checkBuildTags(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkSuPaths(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkWhichSu(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            line != null && line.isNotBlank()
        } catch (_: Exception) {
            false
        } finally {
            process?.destroy()
        }
    }
}
