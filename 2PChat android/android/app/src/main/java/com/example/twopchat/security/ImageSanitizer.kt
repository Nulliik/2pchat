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

/**
 * Strips all EXIF metadata (such as GPS coordinates, camera model, timestamps)
 * from outbound images before transmission by decoding the raw pixel buffer and
 * re-encoding it to a clean image file.
 */
object ImageSanitizer {
    private const val TAG = "ImageSanitizer"
    const val DEFAULT_JPEG_QUALITY = 95

    private val SANITIZABLE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif"
    )

    /**
     * Determines whether the given file path has an image extension that
     * should undergo EXIF sanitization.
     */
    fun isSanitizableImage(filePath: String): Boolean {
        if (filePath.isBlank()) return false
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
     * Re-encodes a Bitmap to an output stream as JPEG with EXIF metadata stripped.
     */
    fun sanitizeBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        quality: Int = DEFAULT_JPEG_QUALITY,
        outStream: OutputStream
    ): Boolean {
        var toRecycle: Bitmap? = null
        val processedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                toRecycle = rotated
            }
            rotated
        } else {
            bitmap
        }

        return try {
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), outStream)
        } finally {
            toRecycle?.recycle()
        }
    }

    /**
     * Sanitizes an outbound image by decoding pixel data and writing a clean,
     * EXIF-free JPEG to a temporary file in the application's cache directory.
     *
     * The original file remains completely unmodified. The returned temporary file
     * MUST be shredded/deleted by the caller once transmission finishes or fails.
     *
     * Returns the temporary sanitized File, or null if the file cannot or should not be sanitized.
     */
    fun sanitizeImageExif(context: Context, sourcePath: String): File? {
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
            decodedBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return null

            val cacheDir = context.applicationContext.cacheDir ?: return null
            tempFile = File.createTempFile("temp_media_sanitized_", ".jpg", cacheDir)

            FileOutputStream(tempFile).use { fos ->
                val success = sanitizeBitmap(
                    bitmap = decodedBitmap,
                    rotationDegrees = rotationDegrees,
                    quality = DEFAULT_JPEG_QUALITY,
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
