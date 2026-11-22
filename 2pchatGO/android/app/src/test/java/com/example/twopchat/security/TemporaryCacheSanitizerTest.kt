package com.example.twopchat.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemporaryCacheSanitizerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sanitizeDirectory_preservesFreshAndUnrecognizedActiveTmpFiles() {
        val cache = temporaryFolder.newFolder("cache")
        val freshMedia = File(cache, "temp_media_fresh").apply { writeText("active media") }
        val freshBackup = File(cache, "backup_zip_fresh.tmp").apply { writeText("active backup") }
        val freshEncryptedBackup = File(cache, "backup_enc_fresh.tmp").apply { writeText("active encrypted backup") }
        val freshImport = File(cache, "import_in_fresh.tmp").apply { writeText("active import") }
        val arbitraryTmp = File(cache, "unrelated.tmp").apply { writeText("not managed here") }

        TemporaryCacheSanitizer.sanitizeDirectory(cache, MAX_AGE_MS, System.currentTimeMillis())

        assertTrue(freshMedia.exists())
        assertTrue(freshBackup.exists())
        assertTrue(freshEncryptedBackup.exists())
        assertTrue(freshImport.exists())
        assertTrue(arbitraryTmp.exists())
    }

    @Test
    fun sanitizeDirectory_removesStaleBackupAndImportTempsButNotArbitraryTmp() {
        val cache = temporaryFolder.newFolder("cache")
        val staleBackup = File(cache, "backup_zip_stale.tmp").apply { writeText("plaintext archive") }
        val staleEncryptedBackup = File(cache, "backup_enc_stale.tmp").apply { writeText("encrypted archive") }
        val staleImport = File(cache, "import_in_stale.tmp").apply { writeText("import contents") }
        val arbitraryTmp = File(cache, "unrelated.tmp").apply { writeText("unmanaged") }
        val staleTime = System.currentTimeMillis() - MAX_AGE_MS - 1
        listOf(staleBackup, staleEncryptedBackup, staleImport, arbitraryTmp).forEach { it.setLastModified(staleTime) }

        TemporaryCacheSanitizer.sanitizeDirectory(cache, MAX_AGE_MS, System.currentTimeMillis())

        assertFalse(staleBackup.exists())
        assertFalse(staleEncryptedBackup.exists())
        assertFalse(staleImport.exists())
        assertTrue(arbitraryTmp.exists())
    }

    @Test
    fun sanitizeDirectory_keepsFreshChildInsideStaleRecognizedDirectory() {
        val cache = temporaryFolder.newFolder("cache")
        val staleRoot = File(cache, "sticker_pack_imports").apply { mkdirs() }
        val freshChild = File(staleRoot, "active-import.tmp").apply { writeText("active import") }
        staleRoot.setLastModified(System.currentTimeMillis() - MAX_AGE_MS - 1)

        TemporaryCacheSanitizer.sanitizeDirectory(cache, MAX_AGE_MS, System.currentTimeMillis())

        assertTrue(staleRoot.exists())
        assertTrue(freshChild.exists())
    }

    @Test
    fun sanitizeDirectory_shredsRecognizedStaleTempFile() {
        val cache = temporaryFolder.newFolder("cache")
        val stale = File(cache, "temp_media_stale").apply { writeText("stale sensitive bytes") }
        stale.setLastModified(System.currentTimeMillis() - MAX_AGE_MS - 1)

        TemporaryCacheSanitizer.sanitizeDirectory(cache, MAX_AGE_MS, System.currentTimeMillis())

        assertFalse(stale.exists())
    }

    private companion object {
        const val MAX_AGE_MS = 60_000L
    }
}
