package com.example.twopchat

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
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
}
