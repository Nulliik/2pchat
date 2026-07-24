package com.example.twopchat.relay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.example.twopchat.PeerAvatarCache
import java.io.ByteArrayOutputStream

internal class AvatarManager {
    private val avatarCache = PeerAvatarCache()
    val peerAvatars = avatarCache.avatars

    fun loadPersistedAvatars(context: Context, log: (Context, String, String, Throwable?) -> Unit) {
        avatarCache.loadPersisted(context) { error ->
            log(context, "Error loading avatars: ${error.message}", "ERROR", error)
        }
    }

    fun handleAvatarShare(
        context: Context,
        sender: String,
        b64: String,
        log: (Context, String, String, Throwable?) -> Unit
    ) {
        if (b64.isEmpty() || b64.length > 2_000_000) return
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L) return
            var sample = 1
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
            if (bitmap != null) {
                Handler(Looper.getMainLooper()).post {
                    avatarCache.put(sender, bitmap)
                }
                log(context, "Received and cached an authenticated peer avatar", "INFO", null)
                try {
                    avatarCache.savePersisted(context, sender, bitmap)
                    log(context, "Saved an encrypted peer avatar", "INFO", null)
                } catch (saveEx: Exception) {
                    log(context, "Failed to save avatar file: ${saveEx.message}", "ERROR", saveEx)
                }
            }
        } catch (e: Exception) {
            log(context, "Error decoding avatar: ${e.message}", "ERROR", e)
        }
    }

    fun clear() {
        avatarCache.clear()
    }
}
