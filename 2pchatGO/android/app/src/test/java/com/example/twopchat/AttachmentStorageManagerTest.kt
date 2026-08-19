package com.example.twopchat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
