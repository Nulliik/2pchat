package com.example.twopchat.security

import android.content.Context
import com.example.twopchat.logging.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

/**
 * Utility to sanitize and securely shred temporary unencrypted media files
 * (e.g. edited photos, temp sticker imports/exports, transient cache files) in cacheDir.
 */
object TemporaryCacheSanitizer {
    private const val TAG = "TempCacheSanitizer"

    // Only these app-owned transient artifacts are eligible; arbitrary .tmp files are excluded.
    // Backup/import files are age-gated so active operations are never interrupted.
    private val TEMP_FILE_PREFIXES = listOf(
        "backup_zip_",
        "backup_enc_",
        "import_in_",
        "edited_photo_",
        "sticker_source_",
        "sticker_pack_uri_",
        "sticker_pack_imports",
        "sticker_pack_exports",
        "temp_media_"
    )

    /** Securely shred a temporary file by overwriting its contents with zeroes before deleting. */
    fun shredFile(file: File): Boolean {
        if (!file.exists()) return true
        return try {
            if (file.isFile && file.length() > 0) {
                RandomAccessFile(file, "rws").use { raf ->
                    val length = raf.length()
                    raf.seek(0)
                    val buffer = ByteArray(minOf(length.toInt(), 8192))
                    var written = 0L
                    while (written < length) {
                        val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
                        raf.write(buffer, 0, toWrite)
                        written += toWrite
                    }
                }
            }
            file.delete()
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to securely shred file: ${file.name}", e)
            file.delete()
        }
    }

    /** Purge recognized temporary cache files older than one day on app startup/background. */
    fun sanitizeTempCache(context: Context, maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = context.applicationContext.cacheDir ?: return@launch
                sanitizeDirectory(cacheDir, maxAgeMs, System.currentTimeMillis())
            } catch (e: Exception) {
                SafeLog.e(TAG, "Error sanitizing temp cache", e)
            }
        }
    }

    /** Synchronous implementation keeps eligibility and age policy directly testable. */
    internal fun sanitizeDirectory(cacheDir: File, maxAgeMs: Long, now: Long) {
        require(maxAgeMs >= 0L) { "maxAgeMs must not be negative" }
        cacheDir.listFiles().orEmpty().forEach { file -> sanitizeEntry(file, maxAgeMs, now) }
    }

    private fun sanitizeEntry(file: File, maxAgeMs: Long, now: Long) {
        val isRecognizedTemp = TEMP_FILE_PREFIXES.any { prefix -> file.name.startsWith(prefix) }
        if (file.isDirectory) {
            // A stale directory may contain live files; age each child independently.
            file.listFiles().orEmpty().forEach { child -> sanitizeEntry(child, maxAgeMs, now) }
            if (isRecognizedTemp && file.listFiles().isNullOrEmpty() && isStale(file, maxAgeMs, now)) {
                file.delete()
            }
        } else if (isRecognizedTemp && isStale(file, maxAgeMs, now)) {
            shredFile(file)
        }
    }

    private fun isStale(file: File, maxAgeMs: Long, now: Long): Boolean =
        now - file.lastModified() >= maxAgeMs

    private const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000
}
