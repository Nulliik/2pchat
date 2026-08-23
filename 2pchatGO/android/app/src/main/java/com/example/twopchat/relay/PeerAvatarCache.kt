package com.example.twopchat.relay

import android.content.Context
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class PeerAvatarCache(
    private val maxBytes: Long = 6L * 1024L * 1024L,
    private val maxCachedDimensionPx: Int = 96,
) {
    val avatars = mutableStateMapOf<String, Bitmap>()
    private val order = ArrayDeque<String>()
    private var sizeBytes = 0L

    fun put(peerName: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val cachedBitmap = bitmap.scaledForCache(maxCachedDimensionPx)
        avatars.remove(peerName)?.let { old ->
            sizeBytes -= old.allocationByteCount.toLong()
        }
        order.remove(peerName)
        avatars[peerName] = cachedBitmap
        order.addLast(peerName)
        sizeBytes += cachedBitmap.allocationByteCount.toLong()
        while (sizeBytes > maxBytes && order.size > 1) {
            val oldestPeer = order.removeFirst()
            avatars.remove(oldestPeer)?.let { evicted ->
                sizeBytes -= evicted.allocationByteCount.toLong()
            }
        }
    }

    fun remove(peerName: String) {
        order.remove(peerName)
        avatars.remove(peerName)?.let { old ->
            sizeBytes -= old.allocationByteCount.toLong()
        }
    }

    fun clear() {
        avatars.clear()
        order.clear()
        sizeBytes = 0L
    }

    fun loadPersisted(context: Context, onError: (Throwable) -> Unit) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val avatarDir = File(appContext.filesDir, AVATAR_DIRECTORY)
                val files = avatarDir.listFiles().orEmpty()
                for (file in files) {
                    if (!file.isFile) continue
                    if (file.name.endsWith(ENCRYPTED_EXTENSION)) {
                        loadEncrypted(file)?.let { (peerName, bitmap) ->
                            Handler(Looper.getMainLooper()).post {
                                put(peerName, bitmap)
                                val resolvedNick = P2PPreferences.findPeerNameByFingerprint(appContext, peerName)
                                if (!resolvedNick.isNullOrBlank() && resolvedNick != peerName) {
                                    put(resolvedNick, bitmap)
                                }
                                val fp = P2PPreferences.prefs(appContext).getString("peer_fingerprint_$peerName", null)
                                if (!fp.isNullOrBlank() && fp != peerName) {
                                    put(fp, bitmap)
                                }
                            }
                        }
                    } else if (file.name.endsWith(LEGACY_EXTENSION)) {
                        // One-time migration from old plaintext avatar files.
                        val options = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: continue
                        val peerName = file.name.removeSuffix(LEGACY_EXTENSION)
                        savePersisted(appContext, peerName, bitmap)
                        if (!file.delete()) onError(IllegalStateException("Could not remove legacy avatar"))
                        Handler(Looper.getMainLooper()).post {
                            put(peerName, bitmap)
                            val resolvedNick = P2PPreferences.findPeerNameByFingerprint(appContext, peerName)
                            if (!resolvedNick.isNullOrBlank() && resolvedNick != peerName) {
                                put(resolvedNick, bitmap)
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }

    fun savePersisted(context: Context, peerName: String, bitmap: Bitmap) {
        require(peerName.isNotBlank()) { "Peer name cannot be blank" }
        if (bitmap.isRecycled) return
        val jpeg = ByteArrayOutputStream().use { output ->
            val compressed = runCatching { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) }.getOrDefault(false)
            if (!compressed) return
            output.toByteArray()
        }
        val nameBytes = peerName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= MAX_PEER_NAME_BYTES) { "Peer name is too long" }
        val clear = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(nameBytes.size)
                output.write(nameBytes)
                output.write(jpeg)
            }
            bytes.toByteArray()
        }
        val directory = File(context.filesDir, AVATAR_DIRECTORY).apply { mkdirs() }
        val encryptedBytes = SecureStorage.encryptBytes(clear)
        avatarFile(directory, peerName).writeBytes(encryptedBytes)
        val fp = P2PPreferences.prefs(context).getString("peer_fingerprint_$peerName", null)
        if (!fp.isNullOrBlank() && fp != peerName && P2PMessageRelay.isRawFingerprint(fp)) {
            avatarFile(directory, fp).writeBytes(encryptedBytes)
        }
        val nick = P2PPreferences.findPeerNameByFingerprint(context, peerName)
        if (!nick.isNullOrBlank() && nick != peerName) {
            avatarFile(directory, nick).writeBytes(encryptedBytes)
        }
    }

    fun deletePersisted(context: Context, peerName: String) {
        val directory = File(context.filesDir, AVATAR_DIRECTORY)
        avatarFile(directory, peerName).delete()
        // Clean up a legacy file without ever constructing a path from an untrusted name.
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(LEGACY_EXTENSION) }
            .firstOrNull { it.name.removeSuffix(LEGACY_EXTENSION) == peerName }
            ?.delete()
    }

    fun getOriginalAvatar(context: Context, peerName: String, maxDimension: Int = 2048): Bitmap? {
        if (peerName.isBlank()) return null
        val directory = File(context.filesDir, AVATAR_DIRECTORY)
        var file = avatarFile(directory, peerName)
        if (!file.isFile) {
            val fp = P2PPreferences.prefs(context).getString("peer_fingerprint_$peerName", null)
            if (!fp.isNullOrBlank() && fp != peerName) {
                file = avatarFile(directory, fp)
            }
        }
        if (!file.isFile) {
            val nick = P2PPreferences.findPeerNameByFingerprint(context, peerName)
            if (!nick.isNullOrBlank() && nick != peerName) {
                file = avatarFile(directory, nick)
            }
        }
        if (!file.isFile) return null
        return try {
            val clear = SecureStorage.decryptBytes(file.readBytes())
            DataInputStream(ByteArrayInputStream(clear)).use { input ->
                val nameLength = input.readInt()
                if (nameLength !in 1..MAX_PEER_NAME_BYTES) return null
                input.skipBytes(nameLength)
                val image = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeByteArray(image, 0, image.size, options)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadEncrypted(file: File): Pair<String, Bitmap>? {
        return try {
            val clear = SecureStorage.decryptBytes(file.readBytes())
            DataInputStream(ByteArrayInputStream(clear)).use { input ->
                val nameLength = input.readInt()
                if (nameLength !in 1..MAX_PEER_NAME_BYTES) return null
                val peerName = String(ByteArray(nameLength).also(input::readFully), Charsets.UTF_8)
                val image = input.readBytes()
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, options) ?: return null
                peerName to bitmap
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun avatarFile(directory: File, peerName: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(peerName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest$ENCRYPTED_EXTENSION")
    }

    private fun Bitmap.scaledForCache(maxDimensionPx: Int): Bitmap {
        require(maxDimensionPx > 0) { "Cached avatar dimension must be positive" }
        if (width <= maxDimensionPx && height <= maxDimensionPx) return this
        val scale = minOf(
            maxDimensionPx.toFloat() / width.toFloat(),
            maxDimensionPx.toFloat() / height.toFloat(),
        )
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        if (scaled !== this && !this.isRecycled) {
            this.recycle()
        }
        return scaled
    }

    private companion object {
        const val AVATAR_DIRECTORY = "avatars"
        const val ENCRYPTED_EXTENSION = ".avatar"
        const val LEGACY_EXTENSION = ".jpg"
        const val MAX_PEER_NAME_BYTES = 1024
    }
}
