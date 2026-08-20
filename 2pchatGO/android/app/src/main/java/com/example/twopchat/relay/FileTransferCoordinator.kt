package com.example.twopchat.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.mutableStateMapOf
import java.util.concurrent.ConcurrentHashMap

internal class FileTransferCoordinator {
    enum class FileTransferState {
        TRANSFERRING,
        COMPLETED,
        CANCELLED,
        FAILED,
    }

    data class FileProgressInfo(
        val bytesTransferred: Long,
        val totalBytes: Long,
        val speedKbps: Double,
        val state: FileTransferState = FileTransferState.TRANSFERRING,
    )

    val fileProgressStates = mutableStateMapOf<String, FileProgressInfo>()
    val fileTransferPreviews = mutableStateMapOf<String, Bitmap>()
    val incomingFileOffers = ConcurrentHashMap.newKeySet<String>()

    fun decodeFileTransferPreview(encoded: String): Bitmap? {
        if (encoded.isBlank() || encoded.length > 96 * 1024) return null
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size > 64 * 1024) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512 ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > 262_144L
            ) {
                return null
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) {
            null
        }
    }

    fun updateProgress(
        key: String,
        messageId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        speedKbps: Double,
        state: FileTransferState = FileTransferState.TRANSFERRING
    ) {
        Handler(Looper.getMainLooper()).post {
            val info = FileProgressInfo(bytesTransferred, totalBytes, speedKbps, state)
            fileProgressStates[key] = info
            if (messageId.isNotBlank()) {
                fileProgressStates[messageId] = info
            }
        }
    }

    fun updateTransferState(
        key: String,
        messageId: String,
        state: FileTransferState
    ) {
        Handler(Looper.getMainLooper()).post {
            val current = fileProgressStates[key] ?: fileProgressStates[messageId] ?: FileProgressInfo(0L, 0L, 0.0)
            val updated = current.copy(state = state, speedKbps = 0.0)
            fileProgressStates[key] = updated
            if (messageId.isNotBlank()) {
                fileProgressStates[messageId] = updated
            }
        }
    }
}
