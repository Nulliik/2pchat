package com.example.twopchat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.example.twopchat.security.KeystoreProvider
import java.io.File
import java.util.UUID
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.tor.TorBridgeCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2PPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetPort() {
        P2PPreferences.prefs(context).edit()
            .remove(P2PPreferences.LISTENER_PORT)
            .remove(P2PPreferences.verifiedPeer("Alice"))
            .remove(P2PPreferences.TOR_BRIDGES)
            .remove(P2PPreferences.TOR_PUBLIC_BRIDGES_ENABLED)
            .remove(P2PPreferences.TOR_TRANSPORT)
            .commit()
    }

    @Test
    fun torBridgeLinesRoundTripThroughEncryptedPreferences() {
        val bridges = listOf(
            "obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 " +
                "cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0"
        )

        assertTrue(P2PPreferences.setTorBridgeLines(context, bridges))
        assertEquals(bridges, P2PPreferences.getTorBridgeLines(context))
    }

    @Test
    fun automaticPublicTorBridgesAreDefaultAndCustomLinesOverrideThem() {
        val custom = listOf(
            "obfs4 192.0.2.1:443 75263E44B1D414D3C6086716091A39DE46FDF1D0 " +
                "cert=bW9jay1vYmZzNC1jZXJ0 iat-mode=0"
        )

        assertTrue(P2PPreferences.publicTorBridgesEnabled(context))
        assertEquals(
            TorBridgeCatalog.PUBLIC_OBFS4_BRIDGES + TorBridgeCatalog.PUBLIC_SNOWFLAKE_BRIDGES,
            P2PPreferences.getEffectiveTorBridgeLines(context),
        )

        assertTrue(P2PPreferences.setTorBridgeLines(context, custom))
        assertEquals(custom, P2PPreferences.getEffectiveTorBridgeLines(context))

        assertTrue(P2PPreferences.setTorBridgeLines(context, emptyList()))
        assertTrue(P2PPreferences.setPublicTorBridgesEnabled(context, false))
        assertTrue(P2PPreferences.getEffectiveTorBridgeLines(context).isEmpty())
    }

    @Test
    fun listenerPortUsesConfiguredValueAndRejectsOutOfRangeState() {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit().putInt(P2PPreferences.LISTENER_PORT, 54321).commit()
        assertEquals(54321, P2PPreferences.listenerPort(context))

        prefs.edit().putInt(P2PPreferences.LISTENER_PORT, 99999).commit()
        assertEquals(P2PPreferences.MAX_LISTENER_PORT, P2PPreferences.listenerPort(context))
    }

    @Test
    fun appLanguagePersistsCorrectly() {
        val prefs = P2PPreferences.prefs(context)

        P2PPreferences.setAppLanguage(context, "Русский")
        assertEquals("Русский", P2PPreferences.getAppLanguage(context))
        assertEquals("Русский", prefs.getString("app_language", null))
        assertEquals("Русский", prefs.getString("settings_language", null))

        P2PPreferences.setAppLanguage(context, "English")
        assertEquals("English", P2PPreferences.getAppLanguage(context))
        assertEquals("English", prefs.getString("app_language", null))
        assertEquals("English", prefs.getString("settings_language", null))
    }

    @Test
    fun directWallpaperDoesNotLeakWithoutPreference() {
        val testDir = java.io.File(context.filesDir, "direct_wallpapers").also { it.mkdirs() }
        val dummyFile = java.io.File(testDir, "wallpaper_UnconfiguredPeer.jpg").apply { writeText("dummy") }

        // Preference not set -> must return null despite file presence on disk
        org.junit.Assert.assertNull(P2PPreferences.getDirectWallpaperPath(context, "UnconfiguredPeer"))

        // Preference set -> returns file path
        P2PPreferences.setDirectWallpaper(context, "UnconfiguredPeer", dummyFile.absolutePath, 40, true)
        assertEquals(dummyFile.absolutePath, P2PPreferences.getDirectWallpaperPath(context, "UnconfiguredPeer"))
        assertEquals(40, P2PPreferences.getDirectWallpaperDimming(context, "UnconfiguredPeer"))
        assertTrue(P2PPreferences.getDirectWallpaperBlur(context, "UnconfiguredPeer"))

        // Cleared -> returns null and removes file
        P2PPreferences.setDirectWallpaper(context, "UnconfiguredPeer", null, 0, false)
        org.junit.Assert.assertNull(P2PPreferences.getDirectWallpaperPath(context, "UnconfiguredPeer"))
        assertFalse(dummyFile.exists())
    }
}

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class P2PPreferencesInitializationTest {
    @Test
    fun corruptValueFailsInitializationWithoutDeletingPreferencesOrReplacingKeys() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "com.example.twopchat.qa.bug17")
        val prefix = "bug17_${UUID.randomUUID()}"
        val secureName = "${prefix}_secure"
        val legacyName = "${prefix}_legacy"
        var deletes = 0
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(isolatedName(name), mode)

            override fun deleteSharedPreferences(name: String): Boolean {
                deletes++
                return super.deleteSharedPreferences(isolatedName(name))
            }

            private fun isolatedName(name: String): String = when (name) {
                "2pchat_secure_prefs" -> secureName
                P2PPreferences.FILE_NAME -> legacyName
                else -> error("Unexpected preferences file: $name")
            }
        }
        val masterKey = KeystoreProvider.getOrBuildMasterKey(context)
        try {
            val encrypted = EncryptedSharedPreferences.create(
                isolatedContext,
                "2pchat_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            assertTrue(encrypted.edit().putString("sentinel", "retained").commit())
            val raw = context.getSharedPreferences(secureName, Context.MODE_PRIVATE)
            val originalEntries = raw.all.toMap()
            val encryptedEntry = originalEntries.keys.single { !it.startsWith("__androidx_security_crypto_") }
            val originalValue = raw.getString(encryptedEntry, null)
            assertTrue(raw.edit().putString(encryptedEntry, "AAAA").commit())
            assertThrows(SecurityException::class.java) { encrypted.all }
            val corruptedEntries = raw.all.toMap()
            val file = File(context.applicationInfo.dataDir, "shared_prefs/$secureName.xml")
            val corruptedBytes = file.readBytes()
            val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            assertTrue(legacy.edit().putString("legacy_sentinel", "unmigrated").commit())
            P2PPreferences.setCachedPrefsForTesting(null)

            repeat(2) {
                val failure = assertThrows(IllegalStateException::class.java) {
                    P2PPreferences.prefs(isolatedContext)
                }
                assertEquals(
                    "EncryptedSharedPreferences initialization failed: Keystore unavailable",
                    failure.message,
                )
                assertTrue(failure.cause is SecurityException)
                assertEquals(0, deletes)
                assertEquals(corruptedEntries, raw.all)
                assertArrayEquals(corruptedBytes, file.readBytes())
                assertEquals("unmigrated", legacy.getString("legacy_sentinel", null))
            }

            assertTrue(raw.edit().putString(encryptedEntry, originalValue).commit())
            assertEquals(originalEntries, raw.all)
            val recovered = P2PPreferences.prefs(isolatedContext)
            assertEquals("retained", recovered.getString("sentinel", null))
            assertEquals("unmigrated", recovered.getString("legacy_sentinel", null))
            assertTrue(legacy.all.isEmpty())
            assertEquals(0, deletes)
        } finally {
            P2PPreferences.setCachedPrefsForTesting(null)
            context.deleteSharedPreferences(secureName)
            context.deleteSharedPreferences(legacyName)
        }
    }
}
