package com.example.twopchat.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.data.ProfileBackupManager
import com.example.twopchat.tor.TorManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class TorOnionRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val prefMap = mutableMapOf<String, Any?>()
    private lateinit var fakePrefs: SharedPreferences
    private lateinit var context: Context
    private lateinit var testFilesDir: File
    private lateinit var testCacheDir: File

    private val testMasterSeed = ByteArray(32) { (it + 0x42).toByte() }
    private val testPassword = "Pass12345678"

    @Before
    fun setUp() {
        prefMap.clear()
        testFilesDir = tempFolder.newFolder("files")
        testCacheDir = tempFolder.newFolder("cache")
        fakePrefs = TestSharedPreferences(prefMap)
        P2PPreferences.setCachedPrefsForTesting(fakePrefs)

        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
            override fun getFilesDir(): File = testFilesDir
            override fun getCacheDir(): File = testCacheDir
            override fun getPackageName(): String = "com.example.twopchat.go"
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo {
                return android.content.pm.ApplicationInfo().apply {
                    dataDir = testFilesDir.parentFile?.absolutePath ?: testFilesDir.absolutePath
                    nativeLibraryDir = testFilesDir.absolutePath
                }
            }
            override fun databaseList(): Array<String> = emptyArray()
            override fun deleteDatabase(name: String?): Boolean = true
            override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()
            override fun getExternalCacheDirs(): Array<File> = emptyArray()
        }

        // Configure NativeBridge test overrides
        NativeBridge.testLocalIdentity = NativeBridge.LocalIdentity(
            identityPub = "testIdentityPubBase64",
            verifyPub = "testVerifyPubBase64",
            signedPrekeyPub = "testSignedPrekeyPubBase64",
            prekeySignature = "testPrekeySignatureBase64",
            fingerprint = "ALICE_TEST_FINGERPRINT_1234567890"
        )

        NativeBridge.testDeterministicOnionKeyProvider = { index ->
            deriveMockOnionKey(testMasterSeed, index)
        }

        NativeBridge.testEncryptBackupPayload = { _, payload ->
            // Encrypt simulator: 2PBK magic + 60 padding bytes + payload
            "2PBK".toByteArray(Charsets.US_ASCII) + ByteArray(60) + payload
        }

        NativeBridge.testDecryptBackupPayload = { pwd, encData ->
            if (pwd == testPassword && encData.size >= 64) {
                encData.copyOfRange(64, encData.size)
            } else {
                null
            }
        }

        NativeBridge.testInspectBackupFingerprint = {
            NativeBridge.testLocalIdentity?.fingerprint
        }
    }

    @After
    fun tearDown() {
        NativeBridge.resetTestingOverrides()
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun testTorOnionAddressBasicRecovery() = runBlocking {
        // Setup: Enable deterministic onion with default index (0)
        TorManager.setTorDeterministicOnionEnabled(context, true)
        P2PPreferences.setTorOnionIndex(context, 0)

        // Capture onion address before backup
        val onionBefore = NativeBridge.getOnionAddress()
        assertNotNull("Onion address must be generated", onionBefore)
        assertTrue("Must be valid .onion address", onionBefore!!.endsWith(".onion"))

        // Create backup
        val backupFile = File(tempFolder.newFolder("backup_scenario1"), "test_backup.2pbackup")
        val exportOk = ProfileBackupManager.exportBackup(context, testPassword, backupFile)
        assertTrue("Export backup must succeed", exportOk)
        assertTrue("Backup file must exist", backupFile.exists() && backupFile.length() > 0)

        // Delete account
        AccountDataWiper.wipe(context)

        // Verify wiped state
        assertEquals("Preferences must be wiped", 0, P2PPreferences.getTorOnionIndex(context))
        assertFalse("Deterministic mode must be wiped", P2PPreferences.isTorDeterministicOnionEnabled(context))

        // Restore from backup
        val importResult = ProfileBackupManager.importBackup(context, testPassword, backupFile)
        assertTrue("Import must succeed: ${importResult.errorMessage}", importResult.success)

        // Verify onion address recovered deterministically
        val onionAfter = NativeBridge.getOnionAddress()
        assertEquals(
            "Onion address must recover deterministically (byte-exact match)",
            onionBefore,
            onionAfter
        )
        assertTrue("Deterministic mode must be restored", P2PPreferences.isTorDeterministicOnionEnabled(context))
        assertEquals(0, P2PPreferences.getTorOnionIndex(context))
    }

    @Test
    fun testTorOnionAddressRotationIndexPreservation() = runBlocking {
        // Setup: Enable deterministic onion and rotate to index=3
        TorManager.setTorDeterministicOnionEnabled(context, true)
        repeat(3) {
            TorManager.rotateOnionAddress(context)
        }

        val onionBefore = NativeBridge.getOnionAddress()
        val indexBefore = P2PPreferences.getTorOnionIndex(context)
        assertEquals("Index must be 3 after 3 rotations", 3, indexBefore)
        assertNotNull(onionBefore)

        // Create backup
        val backupFile = File(tempFolder.newFolder("backup_scenario2"), "test_backup.2pbackup")
        val exportOk = ProfileBackupManager.exportBackup(context, testPassword, backupFile)
        assertTrue("Export backup must succeed", exportOk)

        // Delete account
        AccountDataWiper.wipe(context)

        // Restore from backup
        val importResult = ProfileBackupManager.importBackup(context, testPassword, backupFile)
        assertTrue("Import must succeed: ${importResult.errorMessage}", importResult.success)

        // Verify index preserved
        val indexAfter = P2PPreferences.getTorOnionIndex(context)
        assertEquals(
            "Rotation index must be preserved in backup",
            indexBefore,
            indexAfter
        )

        // Verify onion address matches with preserved index
        val onionAfter = NativeBridge.getOnionAddress()
        assertEquals(
            "Onion address must match with preserved index",
            onionBefore,
            onionAfter
        )
    }

    @Test
    fun testTorOnionAddressMultipleRotations() = runBlocking {
        TorManager.setTorDeterministicOnionEnabled(context, true)

        // Test recovery at different rotation indices
        val testIndices = listOf(0, 1, 5, 10, 50)

        for (targetIndex in testIndices) {
            // Set index and derive deterministic key
            P2PPreferences.setTorOnionIndex(context, targetIndex)
            val expectedKey = NativeBridge.getDeterministicTorOnionKey(targetIndex)
            assertNotNull(expectedKey)

            val appTorDir = File(context.filesDir, "app_tor")
            val hsDir = File(appTorDir, "hidden_service_v3")
            TorManager.writeDeterministicOnionKeys(hsDir, expectedKey!!)
            P2PPreferences.setTorOnionHostname(context, expectedKey.hostname)
            NativeBridge.setOnionAddress(expectedKey.hostname)

            val onionBefore = NativeBridge.getOnionAddress()
            assertEquals(expectedKey.hostname, onionBefore)

            val backupFile = File(tempFolder.newFolder("backup_idx_$targetIndex"), "backup.2pbackup")
            val exportOk = ProfileBackupManager.exportBackup(context, testPassword, backupFile)
            assertTrue("Export backup must succeed at index $targetIndex", exportOk)

            // Wipe and restore
            AccountDataWiper.wipe(context)

            val importResult = ProfileBackupManager.importBackup(context, testPassword, backupFile)
            assertTrue("Import must succeed at index $targetIndex: ${importResult.errorMessage}", importResult.success)

            val onionAfter = NativeBridge.getOnionAddress()
            assertEquals("Onion must recover at index $targetIndex", onionBefore, onionAfter)
            assertEquals("Index must match target index $targetIndex", targetIndex, P2PPreferences.getTorOnionIndex(context))
        }
    }

    @Test
    fun testTorOnionAddressEphemeralMode() = runBlocking {
        // Disable deterministic mode
        TorManager.setTorDeterministicOnionEnabled(context, false)
        val ephemeralOnion = "ephemeralabcdefghijklmnopqrstuvwxyz234567abcdefghijklm.onion"
        NativeBridge.setOnionAddress(ephemeralOnion)
        val onionBefore = NativeBridge.getOnionAddress()
        assertEquals(ephemeralOnion, onionBefore)

        // Create backup
        val backupFile = File(tempFolder.newFolder("backup_ephemeral"), "backup.2pbackup")
        val exportOk = ProfileBackupManager.exportBackup(context, testPassword, backupFile)
        assertTrue(exportOk)

        // Delete and restore
        AccountDataWiper.wipe(context)
        val importResult = ProfileBackupManager.importBackup(context, testPassword, backupFile)
        assertTrue(importResult.success)

        // Verify ephemeral mode preserved (deterministic = false)
        assertFalse(
            "Ephemeral mode must be preserved in preferences",
            P2PPreferences.isTorDeterministicOnionEnabled(context)
        )

        // Verify old address not restored
        val onionAfter = NativeBridge.getOnionAddress()
        assertNotEquals(
            "Ephemeral mode must not restore old onion address",
            onionBefore,
            onionAfter
        )
    }

    @Test
    fun testTorOnionAddressRotationIndexOverflow() = runBlocking {
        TorManager.setTorDeterministicOnionEnabled(context, true)

        // Set index near overflow
        P2PPreferences.setTorOnionIndex(context, Int.MAX_VALUE - 1)

        // Attempt rotation should throw
        try {
            TorManager.rotateOnionAddress(context)
            fail("Expected IllegalStateException on rotation index overflow")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("exhausted") == true)
        }

        // Verify index unchanged
        assertEquals(Int.MAX_VALUE - 1, P2PPreferences.getTorOnionIndex(context))
    }

    @Test
    fun testTorOnionAddressCorruptedIndexRecovery() = runBlocking {
        TorManager.setTorDeterministicOnionEnabled(context, true)
        TorManager.rotateOnionAddress(context) // index = 1
        assertEquals(1, P2PPreferences.getTorOnionIndex(context))

        val onionBefore = NativeBridge.getOnionAddress()
        val backupFile = File(tempFolder.newFolder("backup_corrupt"), "backup.2pbackup")
        val exportOk = ProfileBackupManager.exportBackup(context, testPassword, backupFile)
        assertTrue(exportOk)

        // Manually corrupt rotation index in preferences
        P2PPreferences.setTorOnionIndex(context, -1) // Invalid
        assertEquals(-1, P2PPreferences.getTorOnionIndex(context))

        // Restore should recover correct index from backup
        val importResult = ProfileBackupManager.importBackup(context, testPassword, backupFile)
        assertTrue(importResult.success)

        assertEquals(
            "Backup must restore correct rotation index",
            1,
            P2PPreferences.getTorOnionIndex(context)
        )
        assertEquals(onionBefore, NativeBridge.getOnionAddress())
    }

    private fun deriveMockOnionKey(seed: ByteArray, index: Int): NativeBridge.DeterministicTorOnionKey {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(seed)
        val indexBytes = ByteBuffer.allocate(4).putInt(index).array()
        val hash = md.digest(indexBytes)
        val hex = hash.joinToString("") { "%02x".format(it) }
        val host = (hex + hex).take(56) + ".onion"
        val keyBytes = ByteArray(96) { ((it + index * 7) % 256).toByte() }
        return NativeBridge.DeterministicTorOnionKey(hostname = host, secretKeyBytes = keyBytes)
    }

    private class TestSharedPreferences(private val map: MutableMap<String, Any?>) : SharedPreferences, SharedPreferences.Editor {
        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
            val v = map[key]
            return if (v is Set<*>) {
                @Suppress("UNCHECKED_CAST")
                v as Set<String>
            } else defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = this
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) map[key] = values?.toSet()
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) map.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
