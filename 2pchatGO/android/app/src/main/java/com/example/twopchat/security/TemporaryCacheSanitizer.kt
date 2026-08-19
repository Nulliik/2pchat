package com.example.twopchat.security

import android.content.Context
import android.util.Log
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

    private val TEMP_FILE_PREFIXES = listOf(
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
            Log.w(TAG, "Failed to securely shred file: ${file.name}", e)
            file.delete()
        }
    }

    /** Purge temporary files in cacheDir on app startup or in background. */
    fun sanitizeTempCache(context: Context, maxAgeMs: Long = 0L) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = context.applicationContext.cacheDir ?: return@launch
                val now = System.currentTimeMillis()
                cacheDir.listFiles().orEmpty().forEach { file ->
                    val isTempFile = TEMP_FILE_PREFIXES.any { prefix -> file.name.startsWith(prefix) } || file.name.endsWith(".tmp")
                    if (isTempFile) {
                        val fileAge = now - file.lastModified()
                        if (fileAge >= maxAgeMs) {
                            if (file.isDirectory) {
                                file.deleteRecursively()
                            } else {
                                shredFile(file)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sanitizing temp cache", e)
            }
        }
    }
}
