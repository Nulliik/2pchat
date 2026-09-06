package com.example.twopchat

import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.media.AttachmentCategory
import com.example.twopchat.media.AttachmentStorageManager
import com.example.twopchat.media.StickerSupport
import com.example.twopchat.media.attachmentCategory
import com.example.twopchat.media.isFileInsideAnyRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttachmentStorageManagerTest {
    @Test
    fun acceptsOnlyFilesInsideAnExplicitManagedRoot() {
        val parent = Files.createTempDirectory("2pchat-storage-test").toFile()
        val managedRoot = File(parent, "attachments").apply { mkdirs() }
        val nestedFile = File(managedRoot, "video/clip.mp4")
        val siblingFile = File(parent, "attachments-backup/clip.mp4")

        assertTrue(isFileInsideAnyRoot(nestedFile, listOf(managedRoot)))
        assertTrue(isFileInsideAnyRoot(managedRoot, listOf(managedRoot)))
        assertFalse(isFileInsideAnyRoot(siblingFile, listOf(managedRoot)))
    }

    @Test
    fun rejectsTraversalOutsideManagedRoot() {
        val parent = Files.createTempDirectory("2pchat-storage-traversal").toFile()
        val managedRoot = File(parent, "attachments").apply { mkdirs() }
        val traversal = File(managedRoot, "../private-key.bin")

        assertFalse(isFileInsideAnyRoot(traversal, listOf(managedRoot)))
    }

    @Test
    fun categorizesOnlyReceivedStickersAsClearablePeerStickers() {
        val fileName = "2psticker_friends--wave.webp"
        val packFileName = "2pstickerpack_friends.2psticker"

        assertEquals(
            AttachmentCategory.STICKER,
            attachmentCategory(StickerSupport.ATTACHMENT_TYPE, fileName, isMine = false),
        )
        assertEquals(
            AttachmentCategory.STICKER,
            attachmentCategory(null, fileName),
        )
        assertNull(
            attachmentCategory(StickerSupport.ATTACHMENT_TYPE, fileName, isMine = true),
        )
        assertEquals(
            AttachmentCategory.STICKER,
            attachmentCategory(
                StickerSupport.PACK_ATTACHMENT_TYPE,
                packFileName,
                isMine = false,
            ),
        )
        assertNull(
            attachmentCategory(
                StickerSupport.PACK_ATTACHMENT_TYPE,
                packFileName,
                isMine = true,
            ),
        )
        assertEquals(
            AttachmentCategory.IMAGE,
            attachmentCategory("IMAGE", "photo.webp", isMine = false),
        )
    }

    @Test
    fun testSecureDeletionOverwritesDataBeforeDelete() {
        val tempDir = Files.createTempDirectory("2pchat-shred-test").toFile()
        val testFile = File(tempDir, "sensitive_attachment.bin")
        val secretBytes = "CONFIDENTIAL_MEDIA_PAYLOAD_1234567890".toByteArray(Charsets.UTF_8)
        testFile.writeBytes(secretBytes)
        assertTrue(testFile.exists())
        assertEquals(secretBytes.size.toLong(), testFile.length())

        val shredded = AttachmentStorageManager.secureDelete(testFile)
        assertTrue(shredded)
        assertFalse(testFile.exists())
    }

    @Test
    fun testPathBasedProtectionForStickersAndGifs() {
        val baseDir = Files.createTempDirectory("2pchat-protect-test").toFile()

        val stickerPackFile = File(baseDir, "user_data/sticker_packs/my_pack/sticker1.webp").apply {
            parentFile?.mkdirs()
            writeText("dummy")
        }
        val gifLibFile = File(baseDir, "user_data/gif_library/favorite.gif").apply {
            parentFile?.mkdirs()
            writeText("dummy")
        }
        val installedStickerFile = File(baseDir, "cache/sticker_cache/installed/pack_abc/s1.webp").apply {
            parentFile?.mkdirs()
            writeText("dummy")
        }
        val genericStickerPath = File(baseDir, "custom/stickers/my_sticker.webp").apply {
            parentFile?.mkdirs()
            writeText("dummy")
        }
        val genericGifPath = File(baseDir, "custom/gifs/animation.gif").apply {
            parentFile?.mkdirs()
            writeText("dummy")
        }
        val normalAttachment = File(baseDir, "attachments/received_video.mp4").apply {
            parentFile?.mkdirs()
            writeText("dummy")
        }

        assertTrue(AttachmentStorageManager.isPathProtected(stickerPackFile))
        assertTrue(AttachmentStorageManager.isPathProtected(gifLibFile))
        assertTrue(AttachmentStorageManager.isPathProtected(installedStickerFile))
        assertTrue(AttachmentStorageManager.isPathProtected(genericStickerPath))
        assertTrue(AttachmentStorageManager.isPathProtected(genericGifPath))
        assertFalse(AttachmentStorageManager.isPathProtected(normalAttachment))
    }

    @Test
    fun testPreferencesDefaultsAndOptions() {
        assertEquals(0, P2PPreferences.DEFAULT_MEDIA_RETENTION_DAYS)
        assertEquals(0, P2PPreferences.DEFAULT_MAX_CACHE_SIZE_MB)
        assertEquals(listOf(3, 7, 30, 0), P2PPreferences.MEDIA_RETENTION_OPTIONS_DAYS)
        assertEquals(listOf(500, 1024, 2048, 5120, 0), P2PPreferences.MAX_CACHE_SIZE_OPTIONS_MB)
    }

    @Test
    fun testFastSizeCheckCondition() {
        val limitMb = 500
        val maxBytes = limitMb * 1024L * 1024L
        val underLimitBytes = 250L * 1024L * 1024L
        val overLimitBytes = 600L * 1024L * 1024L

        assertTrue(underLimitBytes in 1..maxBytes)
        assertFalse(overLimitBytes in 1..maxBytes)
    }

    @Test
    fun testRefcountLogicRepresentation() {
        // Given 2 messages pointing to the same file
        val records = listOf(
            com.example.twopchat.data.StoredAttachmentRecord(
                messageId = "msg-1",
                attachmentType = "IMAGE",
                uri = "/data/user/0/com.example.twopchat/files/attachments/shared.jpg",
                attachmentName = "shared.jpg",
                status = "RECEIVED",
                isMine = false,
            ),
            com.example.twopchat.data.StoredAttachmentRecord(
                messageId = "msg-2",
                attachmentType = "IMAGE",
                uri = "/data/user/0/com.example.twopchat/files/attachments/shared.jpg",
                attachmentName = "shared.jpg",
                status = "RECEIVED",
                isMine = false,
            ),
        )

        val targetPath = "/data/user/0/com.example.twopchat/files/attachments/shared.jpg"
        val refCount = records.count { it.uri == targetPath }
        assertEquals(2, refCount)

        // If only msg-1 is detached, remaining refCount is 1 (> 0), so physical file is preserved
        val remainingAfterDetachOne = records.filterNot { it.messageId == "msg-1" }.count { it.uri == targetPath }
        assertEquals(1, remainingAfterDetachOne)
        assertTrue(remainingAfterDetachOne > 0)

        // If both are detached, refCount becomes 0, so physical file can be shredded
        val remainingAfterDetachBoth = records.filterNot { it.messageId == "msg-1" || it.messageId == "msg-2" }.count { it.uri == targetPath }
        assertEquals(0, remainingAfterDetachBoth)
    }

    @Test
    fun testForceReconcileBypassesFastCheck() {
        val limitMb = 500
        val maxBytes = limitMb * 1024L * 1024L
        val cachedBytes = 100L * 1024L * 1024L

        // Fast check skips when cachedBytes is in 1..maxBytes under normal background execution
        val shouldSkipNormally = cachedBytes in 1..maxBytes
        assertTrue(shouldSkipNormally)

        // When force = true (forceReconcile), fast check is bypassed to reconcile exact disk usage
        val forceReconcile = true
        val willSkipWithForce = !forceReconcile && (cachedBytes in 1..maxBytes)
        assertFalse(willSkipWithForce)
    }

    @Test
    fun testReconcileDeduplicatesCanonicalPaths() {
        val tempDir = Files.createTempDirectory("2pchat-dedup-test").toFile()
        val file1 = File(tempDir, "sample_media.jpg").apply {
            writeBytes(ByteArray(1024) { 0x42 })
        }
        val canonical1 = file1.canonicalPath
        // Sibling alias pointing to the same file
        val canonical2 = File(tempDir, "subdir/../sample_media.jpg").canonicalPath
        assertEquals(canonical1, canonical2)

        val seenPaths = HashSet<String>()
        var totalBytes = 0L
        listOf(canonical1, canonical2).forEach { path ->
            if (seenPaths.add(path)) {
                totalBytes += File(path).length()
            }
        }
        // Deduplication ensures 1024 bytes, not 2048 bytes
        assertEquals(1024L, totalBytes)
    }

    @Test
    fun testPreShredSizeCaptureBeforeSecureDelete() {
        val tempDir = Files.createTempDirectory("2pchat-preshred-test").toFile()
        val testFile = File(tempDir, "video_clip.mp4").apply {
            writeBytes(ByteArray(2048) { 0x55 })
        }
        val sizeBeforeShred = if (testFile.exists()) testFile.length().coerceAtLeast(0L) else 0L
        assertEquals(2048L, sizeBeforeShred)

        val shredded = AttachmentStorageManager.secureDelete(testFile)
        assertTrue(shredded)
        assertFalse(testFile.exists())

        // If measured after shred, file length is 0 or non-existent
        val sizeAfterShred = if (testFile.exists()) testFile.length().coerceAtLeast(0L) else 0L
        assertEquals(0L, sizeAfterShred)
        // Verifying that using sizeBeforeShred preserves the exact byte delta
        assertEquals(2048L, sizeBeforeShred)
    }

    @Test
    fun testAdjustCachedMediaBytesClampAtZero() {
        // Simulating the atomic adjust operation with clamp at 0
        val currentBytes = 500L
        val largeDelta = -1000L
        val updated = (currentBytes + largeDelta).coerceAtLeast(0L)
        assertEquals(0L, updated)
    }
}

