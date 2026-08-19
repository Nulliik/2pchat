package com.example.twopchat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

internal object FileTransferPreview {
    private const val MAX_WIDTH = 240
    private const val MAX_HEIGHT = 160
    private const val JPEG_QUALITY = 42
    private const val MAX_ENCODED_CHARS = 96 * 1024

    fun createVideoPreviewBase64(filePath: String): String {
        val file = File(filePath)
        if (!file.isFile || VoiceMessageSupport.attachmentType(file.name, "") != "VIDEO") return ""

        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            retriever.setDataSource(file.absolutePath)
            frame = retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: return ""
            val source = checkNotNull(frame)
            val scale = minOf(
                1f,
                MAX_WIDTH.toFloat() / source.width.coerceAtLeast(1),
                MAX_HEIGHT.toFloat() / source.height.coerceAtLeast(1),
            )
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            scaled = if (width == source.width && height == source.height) {
                source
            } else {
                Bitmap.createScaledBitmap(source, width, height, true)
            }
            val bytes = ByteArrayOutputStream().use { output ->
                checkNotNull(scaled).compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
                .takeIf { it.length <= MAX_ENCODED_CHARS }
                .orEmpty()
        } catch (_: Exception) {
            ""
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            scaled?.takeIf { it !== frame && !it.isRecycled }?.recycle()
            frame?.takeIf { !it.isRecycled }?.recycle()
        }
    }
}
