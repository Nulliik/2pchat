package com.example.twopchat.media

import android.graphics.Bitmap
import com.example.twopchat.logging.SafeLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Decoded raw GIF frame containing ARGB pixels, dimensions, and duration in milliseconds.
 * Independent of Android Bitmap framework for testability and portability.
 */
data class RawGifFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val delayMs: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RawGifFrame
        if (!pixels.contentEquals(other.pixels)) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (delayMs != other.delayMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + delayMs
        return result
    }
}

/**
 * Pure Kotlin GIF decoder for animated GIF frame extraction.
 * Operates without external native dependencies and handles transparency,
 * frame disposal methods, and variable frame delays.
 */
object SimpleGifDecoder {
    private const val TAG = "SimpleGifDecoder"

    fun decode(
        file: File,
        maxDimension: Int = StickerSupport.MAX_DIMENSION,
        maxDurationMs: Long = StickerSupport.MAX_ANIMATION_DURATION_MS,
    ): List<Pair<Bitmap, Int>>? {
        if (!file.isFile || file.length() < 14) return null
        return try {
            file.inputStream().buffered().use { input ->
                decodeStream(input, maxDimension, maxDurationMs)
            }
        } catch (e: Throwable) {
            SafeLog.w(TAG, "SimpleGifDecoder failed: ${e.message}")
            null
        }
    }

    fun decodeStream(
        input: InputStream,
        maxDimension: Int = StickerSupport.MAX_DIMENSION,
        maxDurationMs: Long = StickerSupport.MAX_ANIMATION_DURATION_MS,
    ): List<Pair<Bitmap, Int>>? {
        val rawFrames = decodeRawFrames(input, maxDurationMs) ?: return null
        val frames = mutableListOf<Pair<Bitmap, Int>>()
        for (raw in rawFrames) {
            val bmp = createScaledBitmap(raw.pixels, raw.width, raw.height, maxDimension) ?: continue
            frames.add(bmp to raw.delayMs)
        }
        return if (frames.isNotEmpty()) frames else null
    }

    /**
     * Decodes the raw pixel data and timings for all frames from a GIF input stream.
     */
    fun decodeRawFrames(
        input: InputStream,
        maxDurationMs: Long = StickerSupport.MAX_ANIMATION_DURATION_MS,
    ): List<RawGifFrame>? {
        val header = ByteArray(6)
        if (input.read(header) != 6) return null
        val sig = String(header, Charsets.US_ASCII)
        if (sig != "GIF87a" && sig != "GIF89a") return null

        val screenWidth = readUInt16Le(input)
        val screenHeight = readUInt16Le(input)
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val packed = input.read()
        if (packed < 0) return null
        val hasGlobalColorTable = (packed and 0x80) != 0
        val globalColorTableSize = 2 shl (packed and 0x07)

        val bgColorIndex = input.read()
        val pixelAspectRatio = input.read()
        if (bgColorIndex < 0 || pixelAspectRatio < 0) return null

        val globalColorTable = if (hasGlobalColorTable) {
            readColorTable(input, globalColorTableSize) ?: return null
        } else {
            null
        }

        val rawFrames = mutableListOf<RawGifFrame>()
        var canvasPixels = IntArray(screenWidth * screenHeight)
        var previousCanvasPixels: IntArray? = null

        var frameDelayMs = 100
        var transparentColorIndex = -1
        var hasTransparency = false
        var disposalMethod = 0
        var accumulatedDuration = 0L

        while (true) {
            val blockType = input.read()
            if (blockType < 0 || blockType == 0x3B) break // EOF or Trailer

            when (blockType) {
                0x21 -> { // Extension
                    val extFunc = input.read()
                    if (extFunc == 0xF9) { // Graphic Control Extension
                        val blockSize = input.read()
                        if (blockSize == 4) {
                            val gcPacked = input.read()
                            disposalMethod = (gcPacked shr 2) and 0x07
                            hasTransparency = (gcPacked and 0x01) != 0

                            val delayHundredths = readUInt16Le(input)
                            frameDelayMs = if (delayHundredths <= 1) 100 else delayHundredths * 10

                            transparentColorIndex = input.read()
                            input.read() // Terminator (0x00)
                        } else {
                            skipSubBlocks(input)
                        }
                    } else {
                        skipSubBlocks(input)
                    }
                }
                0x2C -> { // Image Descriptor
                    val frameLeft = readUInt16Le(input)
                    val frameTop = readUInt16Le(input)
                    val frameWidth = readUInt16Le(input)
                    val frameHeight = readUInt16Le(input)
                    val imgPacked = input.read()
                    if (frameWidth <= 0 || frameHeight <= 0 || imgPacked < 0) break

                    val hasLocalColorTable = (imgPacked and 0x80) != 0
                    val isInterlaced = (imgPacked and 0x40) != 0
                    val localColorTableSize = 2 shl (imgPacked and 0x07)

                    val activeColorTable = if (hasLocalColorTable) {
                        readColorTable(input, localColorTableSize) ?: return null
                    } else {
                        globalColorTable
                    } ?: return null

                    val minCodeSize = input.read()
                    if (minCodeSize < 0) break
                    val lzwData = readSubBlocks(input) ?: break

                    val pixelIndices = lzwDecode(minCodeSize, lzwData, frameWidth * frameHeight)

                    // Save state for disposal method 3 (restore to previous)
                    if (disposalMethod == 3) {
                        previousCanvasPixels = canvasPixels.clone()
                    }

                    // Render current frame pixels onto canvas
                    renderFramePixels(
                        canvas = canvasPixels,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        frameLeft = frameLeft,
                        frameTop = frameTop,
                        frameWidth = frameWidth,
                        frameHeight = frameHeight,
                        pixelIndices = pixelIndices,
                        colorTable = activeColorTable,
                        hasTransparency = hasTransparency,
                        transparentIndex = transparentColorIndex,
                        isInterlaced = isInterlaced,
                    )

                    // Save frame snapshot
                    rawFrames.add(
                        RawGifFrame(
                            pixels = canvasPixels.clone(),
                            width = screenWidth,
                            height = screenHeight,
                            delayMs = frameDelayMs,
                        )
                    )
                    accumulatedDuration += frameDelayMs

                    // Handle disposal for subsequent frame
                    when (disposalMethod) {
                        2 -> { // Restore to background
                            clearFrameArea(
                                canvas = canvasPixels,
                                screenWidth = screenWidth,
                                left = frameLeft,
                                top = frameTop,
                                width = frameWidth,
                                height = frameHeight,
                            )
                        }
                        3 -> { // Restore to previous
                            if (previousCanvasPixels != null) {
                                canvasPixels = previousCanvasPixels.clone()
                            }
                        }
                    }

                    // Reset graphic control state
                    frameDelayMs = 100
                    transparentColorIndex = -1
                    hasTransparency = false
                    disposalMethod = 0

                    if (rawFrames.size >= AnimatedStickerConverter.MAX_ANIMATION_FRAMES ||
                        accumulatedDuration >= maxDurationMs
                    ) {
                        break
                    }
                }
            }
        }

        return if (rawFrames.isNotEmpty()) rawFrames else null
    }

    private fun readUInt16Le(input: InputStream): Int {
        val b1 = input.read()
        val b2 = input.read()
        if (b1 < 0 || b2 < 0) return 0
        return (b1 and 0xff) or ((b2 and 0xff) shl 8)
    }

    private fun readColorTable(input: InputStream, size: Int): IntArray? {
        val bytes = ByteArray(size * 3)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return null
            offset += count
        }
        val colors = IntArray(size)
        for (i in 0 until size) {
            val r = bytes[i * 3].toInt() and 0xff
            val g = bytes[i * 3 + 1].toInt() and 0xff
            val b = bytes[i * 3 + 2].toInt() and 0xff
            colors[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return colors
    }

    private fun skipSubBlocks(input: InputStream) {
        while (true) {
            val len = input.read()
            if (len <= 0) break
            var skipped = 0L
            while (skipped < len) {
                val count = input.skip(len - skipped)
                if (count <= 0) break
                skipped += count
            }
        }
    }

    private fun readSubBlocks(input: InputStream): ByteArray? {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (true) {
            val len = input.read()
            if (len < 0) return null
            if (len == 0) break
            var read = 0
            while (read < len) {
                val count = input.read(buf, read, len - read)
                if (count < 0) return null
                read += count
            }
            bos.write(buf, 0, len)
        }
        return bos.toByteArray()
    }

    private fun lzwDecode(minCodeSize: Int, lzwData: ByteArray, pixelCount: Int): ByteArray {
        val pixels = ByteArray(pixelCount)
        if (minCodeSize !in 2..8 || lzwData.isEmpty()) return pixels

        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        var available = clearCode + 2
        var oldCode = -1
        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1

        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val pixelStack = ByteArray(4097)

        for (i in 0 until clearCode) {
            prefix[i] = 0
            suffix[i] = i.toByte()
        }

        var datum = 0
        var bits = 0
        var first = 0
        var top = 0
        var pi = 0
        var bi = 0

        while (pi < pixelCount && bi < lzwData.size) {
            while (bits < codeSize && bi < lzwData.size) {
                datum = datum or ((lzwData[bi].toInt() and 0xff) shl bits)
                bits += 8
                bi++
            }
            if (bits < codeSize) break
            val code = datum and codeMask
            datum = datum shr codeSize
            bits -= codeSize

            if (code == eoiCode) break
            if (code == clearCode) {
                codeSize = minCodeSize + 1
                codeMask = (1 shl codeSize) - 1
                available = clearCode + 2
                oldCode = -1
                continue
            }

            if (oldCode == -1) {
                pixelStack[top++] = suffix[code]
                oldCode = code
                first = code
                continue
            }

            var inCode = code
            if (code >= available) {
                pixelStack[top++] = first.toByte()
                inCode = oldCode
            }

            while (inCode >= clearCode && inCode < 4096) {
                pixelStack[top++] = suffix[inCode]
                inCode = prefix[inCode]
            }
            first = suffix[inCode].toInt() and 0xff
            pixelStack[top++] = first.toByte()

            if (available < 4096) {
                prefix[available] = oldCode
                suffix[available] = first.toByte()
                available++
                if (available and codeMask == 0 && available < 4096) {
                    codeSize++
                    codeMask = (1 shl codeSize) - 1
                }
            }
            oldCode = code

            while (top > 0 && pi < pixelCount) {
                pixels[pi++] = pixelStack[--top]
            }
        }
        return pixels
    }

    private fun renderFramePixels(
        canvas: IntArray,
        screenWidth: Int,
        screenHeight: Int,
        frameLeft: Int,
        frameTop: Int,
        frameWidth: Int,
        frameHeight: Int,
        pixelIndices: ByteArray,
        colorTable: IntArray,
        hasTransparency: Boolean,
        transparentIndex: Int,
        isInterlaced: Boolean,
    ) {
        var srcIndex = 0
        val rowOffsets = if (isInterlaced) {
            val rows = IntArray(frameHeight)
            var destRow = 0
            for (r in 0 until frameHeight step 8) rows[destRow++] = r
            for (r in 4 until frameHeight step 8) rows[destRow++] = r
            for (r in 2 until frameHeight step 4) rows[destRow++] = r
            for (r in 1 until frameHeight step 2) rows[destRow++] = r
            rows
        } else null

        for (y in 0 until frameHeight) {
            val actualY = rowOffsets?.get(y) ?: y
            val canvasY = frameTop + actualY
            if (canvasY >= screenHeight) break

            val rowOffset = canvasY * screenWidth + frameLeft
            for (x in 0 until frameWidth) {
                if (srcIndex >= pixelIndices.size) break
                val colorIdx = pixelIndices[srcIndex++].toInt() and 0xff
                if (frameLeft + x < screenWidth) {
                    if (!hasTransparency || colorIdx != transparentIndex) {
                        if (colorIdx < colorTable.size) {
                            canvas[rowOffset + x] = colorTable[colorIdx]
                        }
                    }
                }
            }
        }
    }

    private fun clearFrameArea(
        canvas: IntArray,
        screenWidth: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) {
        for (y in 0 until height) {
            val rowStart = (top + y) * screenWidth + left
            if (rowStart < canvas.size) {
                val count = minOf(width, screenWidth - left)
                for (x in 0 until count) {
                    canvas[rowStart + x] = 0 // Transparent
                }
            }
        }
    }

    private fun createScaledBitmap(
        pixels: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        maxDim: Int,
    ): Bitmap? {
        val original = runCatching {
            Bitmap.createBitmap(pixels, srcWidth, srcHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        if (srcWidth <= maxDim && srcHeight <= maxDim) {
            return original
        }
        val scale = minOf(maxDim.toFloat() / srcWidth, maxDim.toFloat() / srcHeight)
        val targetW = (srcWidth * scale).toInt().coerceAtLeast(1)
        val targetH = (srcHeight * scale).toInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(original, targetW, targetH, true)
        }.getOrNull() ?: original
        if (scaled !== original) {
            original.recycle()
        }
        return scaled
    }
}
