package com.example.twopchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import kotlin.concurrent.thread

internal class PeerAvatarCache(
    private val maxBytes: Long = 16L * 1024L * 1024L,
) {
    val avatars = mutableStateMapOf<String, Bitmap>()
    private val order = ArrayDeque<String>()
    private var sizeBytes = 0L

    fun put(peerName: String, bitmap: Bitmap) {
        avatars.remove(peerName)?.let { sizeBytes -= it.allocationByteCount.toLong() }
        order.remove(peerName)
        avatars[peerName] = bitmap
        order.addLast(peerName)
        sizeBytes += bitmap.allocationByteCount.toLong()
        while (sizeBytes > maxBytes && order.size > 1) {
            val oldestPeer = order.removeFirst()
            avatars.remove(oldestPeer)?.let { sizeBytes -= it.allocationByteCount.toLong() }
        }
    }

    fun remove(peerName: String) {
        order.remove(peerName)
        avatars.remove(peerName)?.let { sizeBytes -= it.allocationByteCount.toLong() }
    }

    fun clear() {
        avatars.clear()
        order.clear()
        sizeBytes = 0L
    }

    fun loadPersisted(context: Context, onError: (Throwable) -> Unit) {
        val appContext = context.applicationContext
        thread(start = true, name = "LoadAvatarsThread") {
            try {
                val files = File(appContext.filesDir, "avatars").listFiles().orEmpty()
                for (file in files) {
                    if (!file.isFile || !file.name.endsWith(".jpg")) continue
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                    val peerName = file.name.substringBeforeLast(".jpg")
                    Handler(Looper.getMainLooper()).post { put(peerName, bitmap) }
                }
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }
}
