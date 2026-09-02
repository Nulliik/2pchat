package com.example.twopchat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.twopchat.config.P2PPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class DirectWallpaperPreferencesTest {

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

    @Test
    fun getDirectWallpaper_resolvesBlurAcrossCasingAndSuffixAliases() {
        val peerFingerprint = "35564afd8da7c5fb537074e3d64d68e1f7e26900c640d890a1f8a7fb82aaeb92"
        prefMap["peer_fingerprint_Foxxxy"] = peerFingerprint

        val wpDir = File(testFilesDir, "direct_wallpapers").also { it.mkdirs() }
        val wpFile = File(wpDir, "wallpaper_Foxxxy.jpg").apply { writeText("fake-image-bytes") }

        // Save wallpaper under base name "Foxxxy" with blur = true
        P2PPreferences.setDirectWallpaper(context, "Foxxxy", wpFile.absolutePath, 40, true)

        // 1. Direct query with exact name
        val exact = P2PPreferences.getDirectWallpaper(context, "Foxxxy")
        assertEquals(wpFile.absolutePath, exact.path)
        assertEquals(40, exact.dimming)
        assertTrue("Blur should be true for exact name", exact.blur)

        // 2. Query with lowercase
        val lower = P2PPreferences.getDirectWallpaper(context, "foxxxy")
        assertEquals(wpFile.absolutePath, lower.path)
        assertEquals(40, lower.dimming)
        assertTrue("Blur should be true for lowercase name", lower.blur)

        // 3. Query with address suffix (e.g. Foxxxy#35564afd)
        val suffixedHash = P2PPreferences.getDirectWallpaper(context, "Foxxxy#35564afd")
        assertEquals(wpFile.absolutePath, suffixedHash.path)
        assertEquals(40, suffixedHash.dimming)
        assertTrue("Blur should be true for # suffixed address", suffixedHash.blur)

        // 4. Query with contact collision suffix (e.g. Foxxxy · 35564afd)
        val suffixedDot = P2PPreferences.getDirectWallpaper(context, "Foxxxy · 35564afd")
        assertEquals(wpFile.absolutePath, suffixedDot.path)
        assertEquals(40, suffixedDot.dimming)
        assertTrue("Blur should be true for · suffixed address", suffixedDot.blur)

        // 5. Query with raw fingerprint
        val fpQuery = P2PPreferences.getDirectWallpaper(context, peerFingerprint)
        assertEquals(wpFile.absolutePath, fpQuery.path)
        assertEquals(40, fpQuery.dimming)
        assertTrue("Blur should be true when queried by fingerprint", fpQuery.blur)
    }

    @Test
    fun getDirectWallpaper_resolvesBlurWhenFileExistsOnDiskUnderDifferentAlias() {
        val peerFingerprint = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        prefMap["peer_fingerprint_Alice"] = peerFingerprint

        // Simulate incoming receiver where file was written as wallpaper_Alice.jpg on disk
        val wpDir = File(testFilesDir, "direct_wallpapers").also { it.mkdirs() }
        val wpFile = File(wpDir, "wallpaper_Alice.jpg").apply { writeText("img") }

        // Blur was saved under the fingerprint
        prefMap["direct_wallpaper_blur_$peerFingerprint"] = true
        prefMap["direct_wallpaper_dimming_$peerFingerprint"] = 50

        // When queried with "Alice", it finds the file on disk and aggregates blur from fingerprint
        val result = P2PPreferences.getDirectWallpaper(context, "Alice")
        assertEquals(wpFile.absolutePath, result.path)
        assertEquals(50, result.dimming)
        assertTrue("Blur must be resolved from fingerprint alias even when file matches peer name", result.blur)
    }

    @Test
    fun setDirectWallpaper_cleansUpAllAliasesWhenPathIsNull() {
        val peerFingerprint = "fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"
        prefMap["peer_fingerprint_Bob"] = peerFingerprint

        val wpDir = File(testFilesDir, "direct_wallpapers").also { it.mkdirs() }
        val wpFile = File(wpDir, "wallpaper_Bob.jpg").apply { writeText("img") }

        P2PPreferences.setDirectWallpaper(context, "Bob", wpFile.absolutePath, 35, true)
        assertTrue(P2PPreferences.getDirectWallpaper(context, "Bob").blur)

        // Clear wallpaper
        P2PPreferences.setDirectWallpaper(context, "Bob", null, 0, false)
        val cleared = P2PPreferences.getDirectWallpaper(context, "Bob")
        assertNull(cleared.path)
        assertFalse(cleared.blur)
        assertFalse(P2PPreferences.getDirectWallpaper(context, "bob").blur)
        assertFalse(P2PPreferences.getDirectWallpaper(context, peerFingerprint).blur)
    }

    @org.junit.After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    private class TestSharedPreferences(private val map: MutableMap<String, Any?>) : SharedPreferences, SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun getAll(): Map<String, *> = synchronized(map) { map.toMap() }
        override fun getString(key: String?, defValue: String?): String? = synchronized(map) { (map[key] as? String) ?: defValue }
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = synchronized(map) {
            @Suppress("UNCHECKED_CAST")
            (map[key] as? Set<String>) ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = synchronized(map) { (map[key] as? Int) ?: defValue }
        override fun getLong(key: String?, defValue: Long): Long = synchronized(map) { (map[key] as? Long) ?: defValue }
        override fun getFloat(key: String?, defValue: Float): Float = synchronized(map) { (map[key] as? Float) ?: defValue }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(map) { (map[key] as? Boolean) ?: defValue }
        override fun contains(key: String?): Boolean = synchronized(map) { map.containsKey(key) }
        override fun edit(): SharedPreferences.Editor = this

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value != null) {
                    pending[key] = value
                    removals.remove(key)
                } else {
                    removals.add(key)
                    pending.remove(key)
                }
            }
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) {
                if (values != null) {
                    pending[key] = values
                    removals.remove(key)
                } else {
                    removals.add(key)
                    pending.remove(key)
                }
            }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                removals.add(key)
                pending.remove(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            synchronized(map) {
                removals.addAll(map.keys)
                pending.clear()
            }
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            synchronized(map) {
                removals.forEach { map.remove(it) }
                map.putAll(pending)
                removals.clear()
                pending.clear()
            }
        }
    }
}
