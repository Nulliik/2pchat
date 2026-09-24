package com.example.twopchat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.example.twopchat.logging.SafeLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Handles animated sticker detection, frame extraction, and WebP animation muxing
 * for GIF, MP4, and WebP formats.
 */
object AnimatedStickerConverter {
    private const val TAG = "AnimatedStickerConverter"
    const val MAX_ANIMATION_DURATION_MS = StickerSupport.MAX_ANIMATION_DURATION_MS
    const val MAX_ANIMATION_FRAMES = 120

    fun isMp4(file: File): Boolean {
        if (!file.isFile || file.length() < 12) return false
        val header = ByteArray(12)
        try {
            val count = file.inputStream().use { it.read(header) }
            if (count < 12) return false
            return header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
        } catch (_: Throwable) {
            return false
        }
    }

    fun isGif(file: File): Boolean = StickerSupport.isGif(file)

    fun isAnimatedWebP(file: File): Boolean {
        if (!file.isFile || file.length() < 30) return false
        val header = ByteArray(30)
        try {
            val count = file.inputStream().use { it.read(header) }
            if (count < 30) return false
            return StickerSupport.inspectWebP(header)?.animated == true
        } catch (_: Throwable) {
            return false
        }
    }

    fun isAnimatedSource(file: File): Boolean =
        isGif(file) || isMp4(file) || isAnimatedWebP(file)

    /**
     * Extracts the first frame of an MP4 video file as a scaled bitmap.
     */
    fun extractVideoFirstFrame(file: File, maxDim: Int = StickerSupport.MAX_DIMENSION): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, maxDim, maxDim)
            } else {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: return null
            scaleBitmapWithin(raw, maxDim)
        } catch (e: Throwable) {
            SafeLog.w(TAG, "Failed to extract first frame from MP4: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Converts an animated GIF, MP4 video, or non-compliant animated WebP file
     * into a compliant animated WebP sticker matching StickerSupport constraints.
     */
    fun convertToAnimatedSticker(source: File, target: File): Boolean {
        val frames = when {
            isGif(source) -> extractGifFrames(source)
            isMp4(source) -> extractMp4Frames(source)
            isAnimatedWebP(source) -> extractWebPFrames(source)
            else -> null
        } ?: return false

        if (frames.size < 2) {
            frames.forEach { it.first.recycle() }
            return false
        }

        try {
            val qualities = intArrayOf(80, 65, 50)
            val parent = target.parentFile ?: return false
            for (q in qualities) {
                val temp = File(parent, "${target.name}.anim_tmp_${System.nanoTime()}")
                try {
                    if (muxBitmapsToAnimatedWebP(frames, temp, quality = q)) {
                        if (StickerSupport.validateWebP(temp) != null &&
                            temp.length() <= StickerSupport.MAX_ANIMATED_BYTES
                        ) {
                            if (target.exists()) target.delete()
                            if (temp.renameTo(target)) {
                                return true
                            }
                        }
                    }
                } finally {
                    temp.delete()
                }
            }

            // If still exceeds size limit, downsample frames (halve count) and retry
            if (frames.size >= 4) {
                val subsampled = mutableListOf<Pair<Bitmap, Int>>()
                for (i in 0 until frames.size step 2) {
                    val combinedDuration = frames[i].second +
                        if (i + 1 < frames.size) frames[i + 1].second else 0
                    subsampled.add(frames[i].first to combinedDuration)
                }
                val temp = File(parent, "${target.name}.anim_sub_${System.nanoTime()}")
                try {
                    if (muxBitmapsToAnimatedWebP(subsampled, temp, quality = 50)) {
                        if (StickerSupport.validateWebP(temp) != null &&
                            temp.length() <= StickerSupport.MAX_ANIMATED_BYTES
                        ) {
                            if (target.exists()) target.delete()
                            if (temp.renameTo(target)) {
                                return true
                            }
                        }
                    }
                } finally {
                    temp.delete()
                }
            }

            return false
        } finally {
            frames.forEach { it.first.recycle() }
        }
    }

    /**
     * Muxes a list of bitmap frames and their durations into an Animated WebP file.
     */
    fun muxBitmapsToAnimatedWebP(
        frames: List<Pair<Bitmap, Int>>,
        target: File,
        quality: Int = 80,
    ): Boolean {
        if (frames.isEmpty()) return false
        val canvasWidth = frames[0].first.width
        val canvasHeight = frames[0].first.height
        if (canvasWidth !in 1..StickerSupport.MAX_DIMENSION ||
            canvasHeight !in 1..StickerSupport.MAX_DIMENSION
        ) {
            return false
        }

        var hasAlpha = false
        val encodedFrames = mutableListOf<Pair<ByteArray, Int>>()
        for ((bitmap, durationMs) in frames) {
            if (bitmap.hasAlpha()) hasAlpha = true
            val frameBos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            if (!bitmap.compress(format, quality, frameBos)) {
                return false
            }
            val singleWebP = frameBos.toByteArray()
            val frameData = extractFrameData(singleWebP) ?: return false
            encodedFrames.add(frameData to durationMs)
        }

        val animatedBytes = muxFrameDataToAnimatedWebP(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            hasAlpha = hasAlpha,
            frames = encodedFrames,
        )

        target.outputStream().use { it.write(animatedBytes) }
        return target.length() <= StickerSupport.MAX_ANIMATED_BYTES &&
            StickerSupport.validateWebP(target) != null
    }

    /**
     * Assembles raw frame data sub-chunks into an Animated WebP container.
     */
    fun muxFrameDataToAnimatedWebP(
        canvasWidth: Int,
        canvasHeight: Int,
        hasAlpha: Boolean,
        frames: List<Pair<ByteArray, Int>>,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        // 1. RIFF header placeholder (12 bytes)
        bos.write("RIFF".toByteArray(Charsets.US_ASCII))
        bos.write(ByteArray(4)) // placeholder for uint32 file_size - 8
        bos.write("WEBP".toByteArray(Charsets.US_ASCII))

        // 2. VP8X chunk (18 bytes total)
        bos.write("VP8X".toByteArray(Charsets.US_ASCII))
        bos.writeUInt32Le(10L)
        val flags = if (hasAlpha) 0x12 else 0x02 // animation + alpha if hasAlpha
        bos.write(flags)
        bos.write(0); bos.write(0); bos.write(0) // 3 bytes reserved
        bos.writeUInt24Le(canvasWidth - 1)
        bos.writeUInt24Le(canvasHeight - 1)

        // 3. ANIM chunk (14 bytes total)
        bos.write("ANIM".toByteArray(Charsets.US_ASCII))
        bos.writeUInt32Le(6L)
        bos.write(0); bos.write(0); bos.write(0); bos.write(0) // bg color
        bos.writeUInt16Le(0) // loop count: 0 (infinite)

        // 4. ANMF chunks
        for ((frameData, durationMs) in frames) {
            val anmfPayloadSize = 16 + frameData.size
            bos.write("ANMF".toByteArray(Charsets.US_ASCII))
            bos.writeUInt32Le(anmfPayloadSize.toLong())

            bos.writeUInt24Le(0) // frameX = 0
            bos.writeUInt24Le(0) // frameY = 0
            bos.writeUInt24Le(canvasWidth - 1)
            bos.writeUInt24Le(canvasHeight - 1)
            bos.writeUInt24Le(durationMs.coerceIn(10, StickerSupport.MAX_ANIMATION_DURATION_MS.toInt()))
            bos.write(0x02) // flags: do not blend, dispose to background

            bos.write(frameData)
            if ((anmfPayloadSize and 1) != 0) {
                bos.write(0)
            }
        }

        val finalBytes = bos.toByteArray()
        val riffSize = finalBytes.size - 8
        finalBytes[4] = (riffSize and 0xff).toByte()
        finalBytes[5] = ((riffSize shr 8) and 0xff).toByte()
        finalBytes[6] = ((riffSize shr 16) and 0xff).toByte()
        finalBytes[7] = ((riffSize shr 24) and 0xff).toByte()
        return finalBytes
    }

    /**
     * Extracts frame image data (VP8L, ALPH+VP8, or VP8) from a single-frame WebP file.
     */
    fun extractFrameData(webpBytes: ByteArray): ByteArray? {
        if (webpBytes.size < 12 ||
            !webpBytes.matchesAscii(0, "RIFF") ||
            !webpBytes.matchesAscii(8, "WEBP")
        ) {
            return null
        }
        var offset = 12
        val frameDataStream = ByteArrayOutputStream()
        var foundImageChunk = false

        while (offset + 8 <= webpBytes.size) {
            val chunkId = String(webpBytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = webpBytes.readUInt32Le(offset + 4).toInt()
            val payloadOffset = offset + 8
            val paddedSize = chunkSize + (chunkSize and 1)
            if (payloadOffset + chunkSize > webpBytes.size) break

            when (chunkId) {
                "VP8L", "VP8 " -> {
                    val chunkTotal = 8 + paddedSize
                    frameDataStream.write(webpBytes, offset, minOf(chunkTotal, webpBytes.size - offset))
                    foundImageChunk = true
                }
                "ALPH" -> {
                    val chunkTotal = 8 + paddedSize
                    frameDataStream.write(webpBytes, offset, minOf(chunkTotal, webpBytes.size - offset))
                }
            }
            offset = payloadOffset + paddedSize
        }
        return if (foundImageChunk) frameDataStream.toByteArray() else null
    }

    /**
     * Extracts frames from an MP4 video using Android's MediaMetadataRetriever.
     */
    fun extractMp4Frames(
        file: File,
        maxDimension: Int = StickerSupport.MAX_DIMENSION,
        maxDurationMs: Long = MAX_ANIMATION_DURATION_MS,
        targetFps: Int = 20,
    ): List<Pair<Bitmap, Int>>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val videoDurationMs = durationStr?.toLongOrNull() ?: return null
            if (videoDurationMs <= 0L) return null

            val effectiveDurationMs = minOf(videoDurationMs, maxDurationMs)
            val frameIntervalMs = (1000L / targetFps).coerceIn(40L, 100L)
            val numFrames = (effectiveDurationMs / frameIntervalMs).toInt().coerceIn(2, MAX_ANIMATION_FRAMES)
            val actualIntervalMs = (effectiveDurationMs / numFrames).toInt().coerceAtLeast(16)

            val frames = mutableListOf<Pair<Bitmap, Int>>()
            for (i in 0 until numFrames) {
                val timeUs = i * actualIntervalMs * 1000L
                val frameBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        maxDimension,
                        maxDimension,
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: continue

                val scaled = scaleBitmapWithin(frameBmp, maxDimension)
                frames.add(scaled to actualIntervalMs)
            }
            if (frames.size >= 2) frames else null
        } catch (e: Throwable) {
            SafeLog.w(TAG, "Failed to extract MP4 frames: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Extracts frames from an animated WebP using awebp WebPDecoder.
     */
    fun extractWebPFrames(
        file: File,
        maxDimension: Int = StickerSupport.MAX_DIMENSION,
        maxDurationMs: Long = MAX_ANIMATION_DURATION_MS,
    ): List<Pair<Bitmap, Int>>? {
        return try {
            val loader = com.github.penfeizhou.animation.loader.FileLoader(file.absolutePath)
            val decoder = com.github.penfeizhou.animation.webp.decode.WebPDecoder(loader, null)
            val totalCount = decoder.frameCount
            if (totalCount < 2) return null

            val step = if (totalCount > MAX_ANIMATION_FRAMES) {
                (totalCount + MAX_ANIMATION_FRAMES - 1) / MAX_ANIMATION_FRAMES
            } else 1
            val frames = mutableListOf<Pair<Bitmap, Int>>()
            var accumulatedDuration = 0L

            for (i in 0 until totalCount step step) {
                val bmp = decoder.getFrameBitmap(i) ?: continue
                val frameInfo = decoder.getFrame(i)
                val duration = (frameInfo?.frameDuration ?: 100) * step
                val effectiveDuration = duration.coerceIn(10, 1000)

                if (accumulatedDuration + effectiveDuration > maxDurationMs) {
                    val remaining = (maxDurationMs - accumulatedDuration).toInt()
                    if (remaining >= 20) {
                        frames.add(scaleBitmapWithin(bmp, maxDimension) to remaining)
                    } else {
                        bmp.recycle()
                    }
                    break
                }

                accumulatedDuration += effectiveDuration
                frames.add(scaleBitmapWithin(bmp, maxDimension) to effectiveDuration)
                if (frames.size >= MAX_ANIMATION_FRAMES) break
            }
            if (frames.size >= 2) frames else null
        } catch (e: Throwable) {
            SafeLog.w(TAG, "Failed to extract WebP frames: ${e.message}")
            null
        }
    }

    /**
     * Extracts frames from an animated GIF using SimpleGifDecoder (or Movie fallback).
     */
    @Suppress("DEPRECATION")
    fun extractGifFrames(
        file: File,
        maxDimension: Int = StickerSupport.MAX_DIMENSION,
        maxDurationMs: Long = MAX_ANIMATION_DURATION_MS,
    ): List<Pair<Bitmap, Int>>? {
        // 1. First try pure Kotlin SimpleGifDecoder
        val decoded = runCatching {
            SimpleGifDecoder.decode(file, maxDimension, maxDurationMs)
        }.getOrNull()
        if (decoded != null && decoded.size >= 2) {
            return decoded
        }

        // 2. Fallback to android.graphics.Movie if available
        return runCatching {
            val movie = android.graphics.Movie.decodeFile(file.absolutePath)
            if (movie == null || movie.duration() <= 0) return@runCatching null
            val totalDuration = minOf(movie.duration().toLong(), maxDurationMs)
            val w = movie.width().coerceAtLeast(1)
            val h = movie.height().coerceAtLeast(1)
            val scale = minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h, 1f)
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)

            val frameIntervalMs = 50L
            val numFrames = (totalDuration / frameIntervalMs).toInt().coerceIn(2, MAX_ANIMATION_FRAMES)
            val actualIntervalMs = (totalDuration / numFrames).toInt().coerceAtLeast(16)

            val frames = mutableListOf<Pair<Bitmap, Int>>()
            for (i in 0 until numFrames) {
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.scale(scale, scale)
                movie.setTime(i * actualIntervalMs)
                movie.draw(canvas, 0f, 0f)
                frames.add(bmp to actualIntervalMs)
            }
            if (frames.size >= 2) frames else null
        }.getOrNull()
    }

    private fun scaleBitmapWithin(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) {
            return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
        value.indices.all { index -> offset + index < size && this[offset + index].toInt() == value[index].code }

    private fun ByteArray.readUInt16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.readUInt24Le(offset: Int): Int =
        readUInt16Le(offset) or ((this[offset + 2].toInt() and 0xff) shl 16)

    private fun ByteArray.readUInt32Le(offset: Int): Long =
        (readUInt24Le(offset).toLong() and 0x00ff_ffffL) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)

    private fun ByteArrayOutputStream.writeUInt16Le(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeUInt24Le(value: Int) {
        write(value and 0xff)
        write((value shr 8) and 0xff)
        write((value shr 16) and 0xff)
    }

    private fun ByteArrayOutputStream.writeUInt32Le(value: Long) {
        write((value and 0xffL).toInt())
        write(((value shr 8) and 0xffL).toInt())
        write(((value shr 16) and 0xffL).toInt())
        write(((value shr 24) and 0xffL).toInt())
    }
}
