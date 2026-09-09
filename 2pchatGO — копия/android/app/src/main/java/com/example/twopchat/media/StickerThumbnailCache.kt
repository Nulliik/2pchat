package com.example.twopchat.media

import android.graphics.Bitmap
import com.example.twopchat.security.ImageSanitizer
import java.io.File

/**
 * In-memory LRU cache for static first-frame sticker thumbnails.
 *
 * Decoding only the first frame takes ~1-2ms and consumes minimal RAM,
 * enabling instant placeholder display and 120 FPS scrolling without
 * invoking heavy multi-frame animated WebP decoders until the list comes to rest.
 */
object StickerThumbnailCache {
    private const val MAX_ENTRIES = 120

    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun get(filePath: String): Bitmap? {
        synchronized(cache) {
            return cache[filePath]
        }
    }

    fun put(filePath: String, bitmap: Bitmap) {
        synchronized(cache) {
            cache[filePath] = bitmap
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }

    /**
     * Quickly decodes the first frame of a WebP sticker as a downsampled static bitmap.
     */
    fun loadFirstFrame(filePath: String, targetSizePx: Int = 420): Bitmap? {
        if (filePath.isBlank()) return null
        get(filePath)?.let { return it }
        val file = File(filePath)
        if (!file.isFile || file.length() == 0L) return null
        return try {
            val bmp = ImageSanitizer.decodeSampledBitmap(
                filePath = file.absolutePath,
                maxDim = targetSizePx,
                preferRgb565 = false,
            )
            bmp?.also { put(filePath, it) }
        } catch (_: Throwable) {
            null
        }
    }
}
