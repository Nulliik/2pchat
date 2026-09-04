package com.example.twopchat.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.example.twopchat.logging.SafeLog
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

import android.net.Uri
import com.example.twopchat.media.*

/**
 * Strips all EXIF metadata (such as GPS coordinates, camera model, timestamps)
 * from outbound images before transmission by decoding the raw pixel buffer and
 * re-encoding it to a clean image file.
 */
object ImageSanitizer {
    private const val TAG = "ImageSanitizer"
    const val DEFAULT_JPEG_QUALITY = 82
    const val MAX_IMAGE_DIMENSION = 2048

    private val SANITIZABLE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif"
    )

    /**
     * Determines whether the given file path has an image extension that
     * should undergo EXIF sanitization. Stickers and sticker packs are excluded
     * so their WebP/pack format, transparency, and filename prefix are preserved.
     */
    fun isSanitizableImage(filePath: String): Boolean {
        if (filePath.isBlank()) return false
        val fileName = File(filePath).name.lowercase()
        if (fileName.contains("sticker") || StickerSupport.isStickerFileName(fileName) || StickerSupport.isStickerPackFileName(fileName)) {
            return false
        }
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return ext in SANITIZABLE_EXTENSIONS
    }

    /**
     * Extracts the rotation angle from EXIF metadata so orientation is preserved
     * when pixel data is re-encoded.
     */
    fun extractExifRotationDegrees(filePath: String): Int {
        return try {
            val exif = ExifInterface(filePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to read EXIF orientation for $filePath: ${e.message}")
            0
        }
    }

    /**
     * Scales a bitmap down proportionally if either width or height exceeds maxDimension.
     * Returns the scaled bitmap (or the original if already within bounds).
     */
    fun scaleBitmapToMaxDimension(bitmap: Bitmap, maxDim: Int = MAX_IMAGE_DIMENSION): Bitmap {
        if (maxDim <= 0) return bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDim && height <= maxDim) {
            return bitmap
        }

        val ratio = width.toFloat() / height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (width >= height) {
            targetWidth = maxDim
            targetHeight = (maxDim / ratio).toInt().coerceAtLeast(1)
        } else {
            targetHeight = maxDim
            targetWidth = (maxDim * ratio).toInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Re-encodes a Bitmap to an output stream as JPEG with EXIF metadata stripped,
     * downscaled to maxDimension, and compressed with optimal quality.
     */
    fun sanitizeBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        quality: Int = DEFAULT_JPEG_QUALITY,
        maxDimension: Int = MAX_IMAGE_DIMENSION,
        outStream: OutputStream
    ): Boolean {
        var rotatedBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null

        val currentBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                rotatedBitmap = rotated
            }
            rotated
        } else {
            bitmap
        }

        val finalBitmap = if (maxDimension > 0 && (currentBitmap.width > maxDimension || currentBitmap.height > maxDimension)) {
            val scaled = scaleBitmapToMaxDimension(currentBitmap, maxDimension)
            if (scaled != currentBitmap && scaled != bitmap) {
                scaledBitmap = scaled
            }
            scaled
        } else {
            currentBitmap
        }

        return try {
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), outStream)
        } finally {
            rotatedBitmap?.recycle()
            scaledBitmap?.recycle()
        }
    }

    /**
     * Calculates an inSampleSize for BitmapFactory to safely decode high-resolution
     * images without exhausting device heap memory.
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, maxDim: Int = MAX_IMAGE_DIMENSION): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (maxDim > 0 && (height > maxDim || width > maxDim)) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= maxDim && (halfWidth / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Safely decodes a bitmap from a file path using bounds inspection and computed subsampling,
     * protecting against OutOfMemoryError and heap exhaustion.
     */
    fun decodeSampledBitmap(
        filePath: String,
        maxDim: Int = MAX_IMAGE_DIMENSION,
        preferRgb565: Boolean = false,
    ): Bitmap? = try {
        val file = File(filePath)
        if (!file.isFile || file.length() <= 0) null
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
            else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(bounds, maxDim)
                    if (preferRgb565) inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Safely decodes a bitmap from an Android content/file Uri using bounds inspection and computed subsampling,
     * protecting against OutOfMemoryError and heap exhaustion.
     */
    fun decodeSampledBitmap(
        context: Context,
        uri: Uri,
        maxDim: Int = MAX_IMAGE_DIMENSION,
        preferRgb565: Boolean = false,
    ): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, maxDim)
                if (preferRgb565) inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Sanitizes an outbound image by decoding pixel data safely, downscaling to HD messenger
     * resolution, and writing a clean, EXIF-free JPEG to a temporary file in the application's cache.
     *
     * The original file remains completely unmodified. The returned temporary file
     * MUST be shredded/deleted by the caller once transmission finishes or fails.
     *
     * Returns the temporary sanitized File, or null if the file cannot or should not be sanitized.
     */
    fun sanitizeImageExif(
        context: Context,
        sourcePath: String,
        maxDimension: Int = MAX_IMAGE_DIMENSION,
        quality: Int = DEFAULT_JPEG_QUALITY
    ): File? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() == 0L) {
            return null
        }
        if (!isSanitizableImage(sourcePath)) {
            return null
        }

        var tempFile: File? = null
        var decodedBitmap: Bitmap? = null
        return try {
            val rotationDegrees = extractExifRotationDegrees(sourceFile.absolutePath)

            // Step 1: Read image bounds safely to prevent OOM
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, boundsOptions)

            // Step 2: Decode bitmap with computed subsampling
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOptions, maxDimension)
            }
            decodedBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions) ?: return null

            val cacheDir = context.applicationContext.cacheDir ?: return null
            tempFile = File.createTempFile("temp_media_sanitized_", ".jpg", cacheDir)

            FileOutputStream(tempFile).use { fos ->
                val success = sanitizeBitmap(
                    bitmap = decodedBitmap,
                    rotationDegrees = rotationDegrees,
                    quality = quality,
                    maxDimension = maxDimension,
                    outStream = fos
                )
                fos.flush()
                if (!success) {
                    tempFile.delete()
                    return null
                }
            }

            tempFile
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to sanitize image EXIF metadata for $sourcePath", e)
            tempFile?.let {
                try { it.delete() } catch (_: Exception) {}
            }
            null
        } finally {
            decodedBitmap?.recycle()
        }
    }

    /**
     * Applies a fast software StackBlur to a Bitmap.
     * Guaranteed to work across all Android versions (API 26..35+), emulators,
     * and OEM builds without relying on RenderEffect or GPU shader support.
     */
    fun fastBlur(sentBitmap: Bitmap, radius: Int = 20): Bitmap {
        if (radius < 1) return sentBitmap
        val w = sentBitmap.width
        val h = sentBitmap.height
        if (w <= 0 || h <= 0) return sentBitmap

        val scale = 0.5f
        val scaledW = (w * scale).toInt().coerceAtLeast(1)
        val scaledH = (h * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createScaledBitmap(sentBitmap, scaledW, scaledH, true)
        val pix = IntArray(scaledW * scaledH)
        bitmap.getPixels(pix, 0, scaledW, 0, 0, scaledW, scaledH)

        val r = (radius * scale).toInt().coerceAtLeast(1)
        val wm = scaledW - 1
        val hm = scaledH - 1
        val wh = scaledW * scaledH
        val div = r + r + 1

        val rAcc = IntArray(wh)
        val gAcc = IntArray(wh)
        val bAcc = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(scaledW, scaledH))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val routsum = IntArray(3)
        val rinsum = IntArray(3)

        for (yIdx in 0 until scaledH) {
            rinsum[0] = 0; rinsum[1] = 0; rinsum[2] = 0
            routsum[0] = 0; routsum[1] = 0; routsum[2] = 0
            rsum = 0; gsum = 0; bsum = 0
            for (iIdx in -r..r) {
                p = pix[yi + minOf(wm, maxOf(iIdx, 0))]
                sir = stack[iIdx + r]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r + 1 - kotlin.math.abs(iIdx)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (iIdx > 0) {
                    rinsum[0] += sir[0]
                    rinsum[1] += sir[1]
                    rinsum[2] += sir[2]
                } else {
                    routsum[0] += sir[0]
                    routsum[1] += sir[1]
                    routsum[2] += sir[2]
                }
            }
            stackpointer = r

            for (xIdx in 0 until scaledW) {
                rAcc[yi] = dv[rsum]
                gAcc[yi] = dv[gsum]
                bAcc[yi] = dv[bsum]

                rsum -= routsum[0]
                gsum -= routsum[1]
                bsum -= routsum[2]

                stackstart = stackpointer - r + div
                sir = stack[stackstart % div]

                routsum[0] -= sir[0]
                routsum[1] -= sir[1]
                routsum[2] -= sir[2]

                if (yIdx == 0) {
                    vmin[xIdx] = minOf(xIdx + r + 1, wm)
                }
                p = pix[yw + vmin[xIdx]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum[0] += sir[0]
                rinsum[1] += sir[1]
                rinsum[2] += sir[2]

                rsum += rinsum[0]
                gsum += rinsum[1]
                bsum += rinsum[2]

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum[0] += sir[0]
                routsum[1] += sir[1]
                routsum[2] += sir[2]

                rinsum[0] -= sir[0]
                rinsum[1] -= sir[1]
                rinsum[2] -= sir[2]

                yi++
            }
            yw += scaledW
        }

        for (xIdx in 0 until scaledW) {
            rinsum[0] = 0; rinsum[1] = 0; rinsum[2] = 0
            routsum[0] = 0; routsum[1] = 0; routsum[2] = 0
            rsum = 0; gsum = 0; bsum = 0
            yp = -r * scaledW
            for (iIdx in -r..r) {
                yi = maxOf(0, yp) + xIdx
                sir = stack[iIdx + r]
                sir[0] = rAcc[yi]
                sir[1] = gAcc[yi]
                sir[2] = bAcc[yi]
                rbs = r + 1 - kotlin.math.abs(iIdx)
                rsum += rAcc[yi] * rbs
                gsum += gAcc[yi] * rbs
                bsum += bAcc[yi] * rbs
                if (iIdx > 0) {
                    rinsum[0] += sir[0]
                    rinsum[1] += sir[1]
                    rinsum[2] += sir[2]
                } else {
                    routsum[0] += sir[0]
                    routsum[1] += sir[1]
                    routsum[2] += sir[2]
                }
                if (iIdx < hm) {
                    yp += scaledW
                }
            }
            yi = xIdx
            stackpointer = r
            for (yIdx in 0 until scaledH) {
                pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum[0]
                gsum -= routsum[1]
                bsum -= routsum[2]

                stackstart = stackpointer - r + div
                sir = stack[stackstart % div]

                routsum[0] -= sir[0]
                routsum[1] -= sir[1]
                routsum[2] -= sir[2]

                if (xIdx == 0) {
                    vmin[yIdx] = minOf(yIdx + r + 1, hm) * scaledW
                }
                p = xIdx + vmin[yIdx]

                sir[0] = rAcc[p]
                sir[1] = gAcc[p]
                sir[2] = bAcc[p]

                rinsum[0] += sir[0]
                rinsum[1] += sir[1]
                rinsum[2] += sir[2]

                rsum += rinsum[0]
                gsum += rinsum[1]
                bsum += rinsum[2]

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum[0] += sir[0]
                routsum[1] += sir[1]
                routsum[2] += sir[2]

                rinsum[0] -= sir[0]
                rinsum[1] -= sir[1]
                rinsum[2] -= sir[2]

                yi += scaledW
            }
        }

        bitmap.setPixels(pix, 0, scaledW, 0, 0, scaledW, scaledH)
        val result = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (result != bitmap) {
            bitmap.recycle()
        }
        return result
    }
}
