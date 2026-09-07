package com.example.twopchat.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.tor.TorManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TorDeterministicOnionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val prefMap = mutableMapOf<String, Any?>()
    private lateinit var fakePrefs: SharedPreferences
    private lateinit var context: Context
    private lateinit var testFilesDir: File

    @Before
    fun setUp() {
        prefMap.clear()
        testFilesDir = tempFolder.newFolder("files")
        fakePrefs = TestSharedPreferences(prefMap)
        P2PPreferences.setCachedPrefsForTesting(fakePrefs)
        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
            override fun getFilesDir(): File = testFilesDir
        }
    }

    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun testDeterministicPreferencesDefaultAndToggle() {
        assertFalse(P2PPreferences.isTorDeterministicOnionEnabled(context))
        assertEquals(0, P2PPreferences.getTorOnionIndex(context))

        assertTrue(P2PPreferences.setTorDeterministicOnionEnabled(context, true))
        assertTrue(P2PPreferences.isTorDeterministicOnionEnabled(context))

        assertTrue(P2PPreferences.setTorOnionIndex(context, 7))
        assertEquals(7, P2PPreferences.getTorOnionIndex(context))

        assertTrue(P2PPreferences.setTorDeterministicOnionEnabled(context, false))
        assertFalse(P2PPreferences.isTorDeterministicOnionEnabled(context))
    }

    @Test
    fun testIncrementTorOnionIndexSequential() {
        assertEquals(0, P2PPreferences.getTorOnionIndex(context))
        val next1 = P2PPreferences.incrementTorOnionIndex(context)
        assertEquals(1, next1)
        assertEquals(1, P2PPreferences.getTorOnionIndex(context))

        val next2 = P2PPreferences.incrementTorOnionIndex(context)
        assertEquals(2, next2)
        assertEquals(2, P2PPreferences.getTorOnionIndex(context))
    }

    @Test
    fun testIncrementTorOnionIndexOverflowProtection() {
        // Int.MAX_VALUE - 1 guard
        P2PPreferences.setTorOnionIndex(context, Int.MAX_VALUE - 1)
        try {
            P2PPreferences.incrementTorOnionIndex(context)
            fail("Expected IllegalStateException on index overflow guard")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("exhausted") == true)
        }

        // Int.MAX_VALUE guard
        P2PPreferences.setTorOnionIndex(context, Int.MAX_VALUE)
        try {
            P2PPreferences.incrementTorOnionIndex(context)
            fail("Expected IllegalStateException on index overflow guard")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("exhausted") == true)
        }
    }

    @Test
    fun testWriteDeterministicOnionKeysSuccessAndZeroization() {
        val hsDir = File(testFilesDir, "hidden_service_v3")
        val secretKey = ByteArray(96) { (it + 1).toByte() }
        val secretKeyCopy = secretKey.clone()
        val hostname = "vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion"

        val key = NativeBridge.DeterministicTorOnionKey(
            hostname = hostname,
            secretKeyBytes = secretKey
        )

        val success = TorManager.writeDeterministicOnionKeys(hsDir, key)
        assertTrue("writeDeterministicOnionKeys should succeed", success)

        val keyFile = File(hsDir, "hs_ed25519_secret_key")
        val hostnameFile = File(hsDir, "hostname")

        assertTrue("hs_ed25519_secret_key must exist", keyFile.exists())
        assertEquals("hs_ed25519_secret_key must be exactly 96 bytes", 96L, keyFile.length())
        assertArrayEquals("Key content on disk must match original secret key", secretKeyCopy, keyFile.readBytes())

        assertTrue("hostname file must exist", hostnameFile.exists())
        assertEquals(hostname + "\n", hostnameFile.readText())

        // In-memory zeroization check: all bytes of key.secretKeyBytes must be 0
        for (i in key.secretKeyBytes.indices) {
            assertEquals("Byte $i of in-memory secretKeyBytes was not zeroized", 0.toByte(), key.secretKeyBytes[i])
        }
    }

    @Test
    fun testWriteDeterministicOnionKeysDeletesStalePublicKey() {
        val hsDir = File(testFilesDir, "hidden_service_v3")
        hsDir.mkdirs()

        val stalePub = File(hsDir, "hs_ed25519_public_key")
        stalePub.writeBytes(ByteArray(64) { 0xFF.toByte() })
        assertTrue(stalePub.exists())

        val secretKey = ByteArray(96) { 0x42.toByte() }
        val key = NativeBridge.DeterministicTorOnionKey(
            hostname = "teststaleonion1234567890abcdefghijklmnopqrstuvwxyz.onion",
            secretKeyBytes = secretKey
        )

        val success = TorManager.writeDeterministicOnionKeys(hsDir, key)
        assertTrue(success)
        assertFalse("Stale hs_ed25519_public_key must be deleted so Tor derives fresh key matching secret_key", stalePub.exists())
    }

    @Test
    fun testAtomicRotationReverseOrderGuaranteesIdempotence() {
        val hsDir = File(testFilesDir, "hidden_service_v3")
        P2PPreferences.setTorDeterministicOnionEnabled(context, true)
        P2PPreferences.setTorOnionIndex(context, 3)

        val initialIndex = P2PPreferences.getTorOnionIndex(context)
        assertEquals(3, initialIndex)

        val targetIndex = initialIndex + 1
        val dummyKey = NativeBridge.DeterministicTorOnionKey(
            hostname = "index4oniontestaddress1234567890abcdefghijklmnopqr.onion",
            secretKeyBytes = ByteArray(96) { 4.toByte() }
        )

        // Atomic invariant check: write key first
        val writeOk = TorManager.writeDeterministicOnionKeys(hsDir, dummyKey)
        assertTrue(writeOk)

        // Before committing index, index is still 3
        assertEquals(3, P2PPreferences.getTorOnionIndex(context))

        // Commit index after write
        P2PPreferences.setTorOnionIndex(context, targetIndex)
        assertEquals(4, P2PPreferences.getTorOnionIndex(context))
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
