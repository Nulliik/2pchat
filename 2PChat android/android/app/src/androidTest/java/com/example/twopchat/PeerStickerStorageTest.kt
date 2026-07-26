package com.example.twopchat

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerStickerStorageTest {
    @Test
    fun peerStickerCleanupDoesNotDeleteOwnedStickerCache() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "2psticker_test--storage.webp").apply {
            writeBytes(Base64.decode(ANIMATED_WEBP_BASE64, Base64.DEFAULT))
        }
        val sticker = BuiltinSticker(
            packId = "test",
            stickerId = "storage",
            emoji = "🎭",
            backgroundColor = 0L,
            localFilePath = source.absolutePath,
        )

        val peerCopy = requireNotNull(StickerSupport.cacheIncomingSticker(context, source))
        val ownedCopy = StickerSupport.prepareSticker(context, sticker)
        try {
            assertEquals("received", peerCopy.parentFile?.name)
            assertTrue(StickerSupport.validateWebP(peerCopy)?.animated == true)
            assertTrue(ownedCopy.isFile)
            assertTrue(peerCopy.canonicalPath != ownedCopy.canonicalPath)

            val usage = AttachmentStorageManager.calculateUsage(context)
                .getValue(AttachmentCategory.STICKER)
            assertTrue(usage.fileCount >= 1)
            assertTrue(usage.bytes >= peerCopy.length())

            val result = AttachmentStorageManager.clear(
                context,
                setOf(AttachmentCategory.STICKER),
            )
            assertTrue(result.deletedFiles >= 1)
            assertFalse(peerCopy.exists())
            assertTrue(ownedCopy.exists())
        } finally {
            source.delete()
            peerCopy.delete()
            ownedCopy.delete()
        }
    }

    private companion object {
        const val ANIMATED_WEBP_BASE64 =
            "UklGRoQAAABXRUJQVlA4WAoAAAASAAAADwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAA8AAA8AAHgAAAJWUDhMDwAAAC8PwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAHgAAAJWUDhMDwAAAC8PwAMQBxDR/wIGIqL/AQA="
    }
}
