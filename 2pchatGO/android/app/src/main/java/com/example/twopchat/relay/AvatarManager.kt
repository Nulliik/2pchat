package com.example.twopchat.relay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.twopchat.relay.PeerAvatarCache
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class AvatarManager(
    private val sharedAvatarCache: PeerAvatarCache,
) {
    val peerAvatars = sharedAvatarCache.avatars
    private val ioScope = CoroutineScope(Dispatchers.IO)

    fun loadPersistedAvatars(context: Context, log: (Context, String, String, Throwable?) -> Unit) {
        sharedAvatarCache.loadPersisted(context) { error ->
            log(context, "Error loading avatars: ${error.message}", "ERROR", error)
        }
    }

    fun putAlias(fromKey: String, toKey: String, context: Context? = null) {
        if (fromKey.isBlank() || toKey.isBlank() || fromKey == toKey) return
        val bitmap = peerAvatars[fromKey] ?: return
        P2PMessageRelay.runOnMain {
            sharedAvatarCache.put(toKey, bitmap)
        }
        if (context != null) {
            ioScope.launch {
                try {
                    sharedAvatarCache.savePersisted(context, toKey, bitmap)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    com.example.twopchat.logging.SafeLog.w("AvatarManager", "Failed to persist avatar for alias $toKey", e)
                }
            }
        }
    }

    fun handleAvatarShare(
        context: Context,
        sender: String,
        b64: String,
        effectiveName: String? = null,
        fingerprint: String? = null,
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
            val rawBitmap = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
            if (rawBitmap != null) {
                val scale = minOf(96f / rawBitmap.width.toFloat(), 96f / rawBitmap.height.toFloat())
                val bitmap = if (rawBitmap.width <= 96 && rawBitmap.height <= 96) {
                    rawBitmap
                } else {
                    val targetW = (rawBitmap.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (rawBitmap.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)
                }
                val keys = listOfNotNull(
                    sender.takeIf { it.isNotBlank() },
                    effectiveName?.takeIf { it.isNotBlank() },
                    fingerprint?.takeIf { it.isNotBlank() }
                ).distinct()
                P2PMessageRelay.runOnMain {
                    for (k in keys) {
                        sharedAvatarCache.put(k, bitmap)
                    }
                }
                log(context, "Received and cached an authenticated peer avatar for ${effectiveName ?: sender}", "INFO", null)
                ioScope.launch {
                    for (k in keys) {
                        try {
                            sharedAvatarCache.savePersisted(context, k, rawBitmap)
                        } catch (saveEx: Exception) {
                            log(context, "Failed to save avatar file for $k: ${saveEx.message}", "ERROR", saveEx)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log(context, "Error decoding avatar: ${e.message}", "ERROR", e)
        }
    }

    fun clear() {
        sharedAvatarCache.clear()
    }
}
