package com.example.twopchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Semaphore

data class StoredGif(
    val id: String,
    val filePath: String,
    val previewPath: String?,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
)

data class GifInfo(
    val width: Int,
    val height: Int,
)

object GifStorageManager {
    const val ATTACHMENT_TYPE = "GIF"
    const val MAX_GIF_BYTES = 100L * 1024L * 1024L
    const val MAX_DIMENSION = 4096
    const val PREVIEW_SIZE = 192
    const val MAX_PREVIEW_CACHE_BYTES = 100L * 1024L * 1024L
    private const val PREVIEW_ACCESS_TOUCH_INTERVAL_MS = 15L * 60L * 1_000L
    private const val PREVIEW_TRIM_INTERVAL_MS = 60L * 1_000L
    private val previewLocks = Array(16) { Any() }
    private val previewDecodeSlots = Semaphore(2, true)
    private val previewTrimLock = Any()
    @Volatile
    private var lastPreviewTrimAtMs = 0L

    fun inspectGifHeader(bytes: ByteArray): GifInfo? {
        if (bytes.size < 10) return null
        val signature = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") return null
        val width = (bytes[6].toInt() and 0xff) or ((bytes[7].toInt() and 0xff) shl 8)
        val height = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION ||
            width.toLong() * height.toLong() > MAX_DIMENSION.toLong() * MAX_DIMENSION
        ) {
            return null
        }
        return GifInfo(width, height)
    }

    fun validateGif(file: File): GifInfo? {
        if (!file.isFile || file.length() !in 10..MAX_GIF_BYTES) return null
        val header = ByteArray(10)
        val count = file.inputStream().use { it.read(header) }
        if (count != header.size) return null
        val hasTrailer = runCatching {
            RandomAccessFile(file, "r").use {
                it.seek(file.length() - 1L)
                it.read() == 0x3b
            }
        }.getOrDefault(false)
        return inspectGifHeader(header).takeIf { hasTrailer }
    }

    fun save(context: Context, source: File): StoredGif? {
        if (validateGif(source) == null) return null
        val id = StickerSupport.sha256(source)
        val library = libraryDirectory(context)
        val target = File(library, "gif_$id.gif")
        if (!target.isFile || validateGif(target) == null) {
            val temporary = File(library, "${target.name}.tmp")
            source.inputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (validateGif(temporary) == null) {
                temporary.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) return null
        }
        target.setLastModified(System.currentTimeMillis())
        val preview = ensurePreview(context, target, id) ?: return null
        return target.toStoredGif(id, preview)
    }

    fun list(context: Context): List<StoredGif> =
        libraryDirectory(context)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("gif", ignoreCase = true) }
            .filter { validateGif(it) != null }
            .map { file ->
                val id = file.name.removePrefix("gif_").removeSuffix(".gif")
                val preview = File(previewDirectory(context), "$id.webp").takeIf { it.isFile }
                file.toStoredGif(id, preview)
            }
            .sortedByDescending { it.addedAtEpochMs }
            .toList()

    fun ensurePreview(context: Context, gif: StoredGif): String? =
        ensurePreview(context, File(gif.filePath), gif.id)?.absolutePath

    private fun ensurePreview(context: Context, gif: File, id: String): File? =
        synchronized(previewLocks[(id.hashCode() and Int.MAX_VALUE) % previewLocks.size]) {
            ensurePreviewLocked(context, gif, id)
        }

    private fun ensurePreviewLocked(context: Context, gif: File, id: String): File? {
        val previewDirectory = previewDirectory(context)
        val target = File(previewDirectory, "$id.webp")
        if (target.isFile && target.length() > 0L) {
            touchPreviewIfStale(target)
            return target
        }

        try {
            previewDecodeSlots.acquire()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        try {
            if (target.isFile && target.length() > 0L) {
                touchPreviewIfStale(target)
                return target
            }
            val bitmap = decodeFirstFrame(gif) ?: return null
            val temporary = File(previewDirectory, "${target.name}.tmp")
            try {
                temporary.outputStream().use { output ->
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    if (!bitmap.compress(format, 78, output)) return null
                }
            } finally {
                bitmap.recycle()
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) return null
            trimPreviewCacheIfNeeded(previewDirectory)
            return target
        } finally {
            previewDecodeSlots.release()
        }
    }

    private fun decodeFirstFrame(file: File): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val width = info.size.width.coerceAtLeast(1)
                val height = info.size.height.coerceAtLeast(1)
                val scale = minOf(PREVIEW_SIZE.toFloat() / width, PREVIEW_SIZE.toFloat() / height, 1f)
                decoder.setTargetSize(
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > PREVIEW_SIZE * 2 ||
                bounds.outHeight / sample > PREVIEW_SIZE * 2
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun File.toStoredGif(id: String, preview: File?): StoredGif = StoredGif(
        id = id,
        filePath = absolutePath,
        previewPath = preview?.absolutePath,
        sizeBytes = length(),
        addedAtEpochMs = lastModified(),
    )

    private fun libraryDirectory(context: Context): File =
        File(context.filesDir, "gif_library").apply { mkdirs() }

    private fun previewDirectory(context: Context): File =
        File(context.cacheDir, "gif_previews").apply { mkdirs() }

    private fun touchPreviewIfStale(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() >= PREVIEW_ACCESS_TOUCH_INTERVAL_MS) {
            file.setLastModified(now)
        }
    }

    private fun trimPreviewCacheIfNeeded(directory: File) {
        val now = System.currentTimeMillis()
        if (lastPreviewTrimAtMs != 0L &&
            now - lastPreviewTrimAtMs < PREVIEW_TRIM_INTERVAL_MS
        ) {
            return
        }
        synchronized(previewTrimLock) {
            if (lastPreviewTrimAtMs != 0L &&
                now - lastPreviewTrimAtMs < PREVIEW_TRIM_INTERVAL_MS
            ) {
                return
            }
            trimPreviewCache(directory)
            lastPreviewTrimAtMs = now
        }
    }

    private fun trimPreviewCache(directory: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        var total = files.sumOf { it.length() }
        files.forEach { file ->
            if (total <= MAX_PREVIEW_CACHE_BYTES) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}
