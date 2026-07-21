package com.example.twopchat

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PeerAvatarCacheTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun persistedPeerAvatarIsEncryptedAndUsesSafeFilename() {
        val directory = File(context.filesDir, "avatars").apply {
            deleteRecursively()
            mkdirs()
        }
        val cache = PeerAvatarCache()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        cache.savePersisted(context, "../Alice", bitmap)

        val files = directory.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue(files.single().name.endsWith(".avatar"))
        assertFalse(files.single().name.contains("Alice"))
        val encrypted = files.single().readBytes()
        assertFalse(encrypted.size > 1 && encrypted[0] == 0xff.toByte() && encrypted[1] == 0xd8.toByte())

        cache.deletePersisted(context, "../Alice")
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        bitmap.recycle()
    }

    @Test
    fun largeAvatarIsDownscaledInMemoryWithoutChangingAspectRatio() {
        val cache = PeerAvatarCache(maxCachedDimensionPx = 128)
        val bitmap = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)

        cache.put("Alice", bitmap)

        val cached = cache.avatars.getValue("Alice")
        assertEquals(128, cached.width)
        assertEquals(64, cached.height)
        assertFalse(cached === bitmap)

        cached.recycle()
        bitmap.recycle()
    }
}
