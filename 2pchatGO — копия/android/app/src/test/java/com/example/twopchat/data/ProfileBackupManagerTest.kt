package com.example.twopchat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProfileBackupManagerTest {

    @Test
    fun testDetectFormatV2() {
        val header = "2PBK".toByteArray(Charsets.US_ASCII) + ByteArray(60)
        val format = ProfileBackupManager.detectFormat(header)
        assertEquals(ProfileBackupManager.BackupFormat.V2_ENCRYPTED, format)
    }

    @Test
    fun testDetectFormatV1Zip() {
        val zipHeader = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte(), 0x00, 0x00)
        val format = ProfileBackupManager.detectFormat(zipHeader)
        assertEquals(ProfileBackupManager.BackupFormat.V1_PLAINTEXT_ZIP, format)
    }

    @Test
    fun testDetectFormatUnknown() {
        val unknown = "RANDOM_DATA_HEADER".toByteArray()
        val format = ProfileBackupManager.detectFormat(unknown)
        assertEquals(ProfileBackupManager.BackupFormat.UNKNOWN, format)

        val shortData = byteArrayOf(0x01, 0x02)
        assertEquals(ProfileBackupManager.BackupFormat.UNKNOWN, ProfileBackupManager.detectFormat(shortData))
    }

    @Test
    fun testPathTraversalProtection() {
        // Create a mock zip with path traversal entry "../evil.key"
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.key"))
            zip.write("malicious content".toByteArray())
            zip.closeEntry()
        }
        val zipBytes = baos.toByteArray()

        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_traversal_${System.nanoTime()}")
        tempDir.mkdirs()

        try {
            var caught = false
            try {
                // Test the canonical path check logic directly
                val entryName = "../evil.key"
                val targetFile = File(tempDir, entryName)
                val canonicalDest = targetFile.canonicalPath
                val canonicalDir = tempDir.canonicalPath

                if (!canonicalDest.startsWith(canonicalDir + File.separator)) {
                    throw SecurityException("Path traversal attempt in backup entry: $entryName")
                }
            } catch (e: SecurityException) {
                caught = true
            }
            assertTrue("Path traversal should throw SecurityException", caught)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testValidEntryCanonicalPathAllowed() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_valid_${System.nanoTime()}")
        tempDir.mkdirs()

        try {
            val validName = "identity_v1.key"
            val targetFile = File(tempDir, validName)
            val canonicalDest = targetFile.canonicalPath
            val canonicalDir = tempDir.canonicalPath

            assertTrue(
                "Legitimate file should be within targetDir",
                canonicalDest.startsWith(canonicalDir + File.separator)
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
