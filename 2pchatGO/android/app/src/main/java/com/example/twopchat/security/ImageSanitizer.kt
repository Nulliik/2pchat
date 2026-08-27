package com.example.twopchat.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

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
            Log.w(TAG, "Failed to read EXIF orientation for $filePath: ${e.message}")
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
            Log.e(TAG, "Failed to sanitize image EXIF metadata for $sourcePath", e)
            tempFile?.let {
                try { it.delete() } catch (_: Exception) {}
            }
            null
        } finally {
            decodedBitmap?.recycle()
        }
    }
}
