package com.example.twopchat

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.twopchat.media.AttachmentCategory
import com.example.twopchat.media.AttachmentStorageManager
import com.example.twopchat.media.BuiltinSticker
import com.example.twopchat.media.StickerSupport
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun peerPackIsCachedForFullPreviewBeforeExplicitInstallation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packId = "peer_preview_${System.nanoTime()}"
        val archive = createPackArchive(context, packId)

        try {
            val preview = requireNotNull(StickerSupport.cachePeerPackPreview(context, archive))

            assertEquals(packId, preview.id)
            assertEquals("Alice", preview.author)
            assertEquals(2, preview.stickers.size)
            assertTrue(preview.stickers.all { File(requireNotNull(it.localFilePath)).isFile })
            assertNull(StickerSupport.findPack(context, packId))
            assertEquals(
                2,
                requireNotNull(StickerSupport.findPeerPackPreview(context, packId)).stickers.size,
            )

            val usage = AttachmentStorageManager.calculateUsage(context)
                .getValue(AttachmentCategory.STICKER)
            val previewBytes = preview.stickers.sumOf {
                File(requireNotNull(it.localFilePath)).length()
            } + File(
                requireNotNull(File(requireNotNull(preview.stickers.first().localFilePath)).parentFile),
                "pack.json",
            ).length()
            assertTrue(usage.fileCount >= 3)
            assertTrue(usage.bytes >= previewBytes)

            val installed = requireNotNull(
                StickerSupport.installPeerPackPreview(context, packId),
            )
            assertEquals(2, installed.stickers.size)
            assertEquals(2, requireNotNull(StickerSupport.findPack(context, packId)).stickers.size)
            assertNull(StickerSupport.findPeerPackPreview(context, packId))
        } finally {
            StickerSupport.clearPeerPackPreview(context, packId)
            StickerSupport.deleteManagedPack(context, packId)
            archive.delete()
        }
    }

    private fun createPackArchive(
        context: android.content.Context,
        packId: String,
    ): File {
        val stickerBytes = Base64.decode(ANIMATED_WEBP_BASE64, Base64.DEFAULT)
        val source = File(context.cacheDir, "2psticker_fixture--source.webp").apply {
            writeBytes(stickerBytes)
        }
        val stickerFiles = listOf(
            "2psticker_${packId}--hello.webp",
            "2psticker_${packId}--party.webp",
        )
        val manifest = JSONObject().apply {
            put("format", 1)
            put("pack_id", packId)
            put("title", "Peer Pack")
            put("author", "Alice")
            put(
                "stickers",
                JSONArray().apply {
                    stickerFiles.forEachIndexed { index, fileName ->
                        put(
                            JSONObject().apply {
                                put("sticker_id", if (index == 0) "hello" else "party")
                                put("emoji", if (index == 0) "👋" else "🥳")
                                put("file", fileName)
                                put("sha256", StickerSupport.sha256(source))
                            },
                        )
                    }
                },
            )
        }
        val archive = File(
            context.cacheDir,
            "${StickerSupport.PACK_FILE_PREFIX}$packId${StickerSupport.PACK_FILE_EXTENSION}",
        )
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            stickerFiles.forEach { fileName ->
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(stickerBytes)
                zip.closeEntry()
            }
        }
        source.delete()
        return archive
    }

    private companion object {
        const val ANIMATED_WEBP_BASE64 =
            "UklGRoQAAABXRUJQVlA4WAoAAAASAAAADwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAA8AAA8AAHgAAAJWUDhMDwAAAC8PwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAHgAAAJWUDhMDwAAAC8PwAMQBxDR/wIGIqL/AQA="
    }
}
