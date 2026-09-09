package com.example.twopchat.arch

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architectural gate for logging hygiene.
 * Ensures that sensitive components use SafeLog/AppLog instead of raw android.util.Log
 * to prevent leaking P2P addresses, identities, and session keys to logcat.
 */
class LoggingArchitectureTest {

    // Maximum legacy files permitted with raw logging (ratcheting baseline).
    // As legacy files are refactored to SafeLog, this threshold should only decrease.
    private val maxLegacyFilesAllowed = 45

    private val securityCriticalPackages = listOf(
        "update",
        "relay",
        "group/protocol",
        "group/simulation",
        "tor"
    )

    private fun findSourceRoot(): File {
        val candidates = listOf(
            File("app/src/main/java"),
            File("src/main/java"),
            File("../app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: File("app/src/main/java")
    }

    @Test
    fun securityCriticalPackagesDoNotUseRawAndroidLog() {
        val srcRoot = findSourceRoot()
        if (!srcRoot.exists()) return

        val violations = mutableListOf<String>()

        securityCriticalPackages.forEach { pkgPath ->
            val pkgDir = File(srcRoot, "com/example/twopchat/$pkgPath")
            if (pkgDir.exists() && pkgDir.isDirectory) {
                pkgDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                    val text = file.readText()
                    if (text.contains("android.util.Log") || Regex("""\bLog\.(d|i|v|w|e|wtf)\(""").containsMatchIn(text)) {
                        violations.add(file.relativeTo(srcRoot).path)
                    }
                }
            }
        }

        assertTrue(
            "Security-critical packages must use SafeLog/AppLog, but raw Log calls were found in:\n" +
                    violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun legacyRawLoggingCountDoesNotGrow() {
        val srcRoot = findSourceRoot()
        if (!srcRoot.exists()) return

        val filesWithRawLog = srcRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/logging/") }
            .filter { file ->
                val text = file.readText()
                text.contains("android.util.Log") || Regex("""\bLog\.(d|i|v|w|e|wtf)\(""").containsMatchIn(text)
            }
            .map { it.relativeTo(srcRoot).path }
            .toList()

        assertTrue(
            "Legacy raw Log usage increased! Baseline is $maxLegacyFilesAllowed, but found ${filesWithRawLog.size} files:\n" +
                    filesWithRawLog.joinToString("\n"),
            filesWithRawLog.size <= maxLegacyFilesAllowed
        )
    }
}
