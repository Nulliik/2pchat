package com.example.twopchat

import android.content.Context
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
    private val maxBytes: Long = 16L * 1024L * 1024L,
    private val maxCachedDimensionPx: Int = 128,
) {
    val avatars = mutableStateMapOf<String, Bitmap>()
    private val order = ArrayDeque<String>()
    private var sizeBytes = 0L

    fun put(peerName: String, bitmap: Bitmap) {
        val cachedBitmap = bitmap.scaledForCache(maxCachedDimensionPx)
        avatars.remove(peerName)?.let { sizeBytes -= it.allocationByteCount.toLong() }
        order.remove(peerName)
        avatars[peerName] = cachedBitmap
        order.addLast(peerName)
        sizeBytes += cachedBitmap.allocationByteCount.toLong()
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val avatarDir = File(appContext.filesDir, AVATAR_DIRECTORY)
                val files = avatarDir.listFiles().orEmpty()
                for (file in files) {
                    if (!file.isFile) continue
                    if (file.name.endsWith(ENCRYPTED_EXTENSION)) {
                        loadEncrypted(file)?.let { (peerName, bitmap) ->
                            Handler(Looper.getMainLooper()).post { put(peerName, bitmap) }
                        }
                    } else if (file.name.endsWith(LEGACY_EXTENSION)) {
                        // One-time migration from old plaintext avatar files.
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                        val peerName = file.name.removeSuffix(LEGACY_EXTENSION)
                        savePersisted(appContext, peerName, bitmap)
                        if (!file.delete()) onError(IllegalStateException("Could not remove legacy avatar"))
                        Handler(Looper.getMainLooper()).post { put(peerName, bitmap) }
                    }
                }
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }

    fun savePersisted(context: Context, peerName: String, bitmap: Bitmap) {
        require(peerName.isNotBlank()) { "Peer name cannot be blank" }
        val jpeg = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Avatar encoding failed" }
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
        avatarFile(directory, peerName).writeBytes(SecureStorage.encryptBytes(clear))
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

    private fun loadEncrypted(file: File): Pair<String, Bitmap>? {
        val clear = SecureStorage.decryptBytes(file.readBytes())
        return DataInputStream(ByteArrayInputStream(clear)).use { input ->
            val nameLength = input.readInt()
            require(nameLength in 1..MAX_PEER_NAME_BYTES) { "Invalid encrypted avatar name" }
            val peerName = String(ByteArray(nameLength).also(input::readFully), Charsets.UTF_8)
            val image = input.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return null
            peerName to bitmap
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
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private companion object {
        const val AVATAR_DIRECTORY = "avatars"
        const val ENCRYPTED_EXTENSION = ".avatar"
        const val LEGACY_EXTENSION = ".jpg"
        const val MAX_PEER_NAME_BYTES = 1024
    }
}
