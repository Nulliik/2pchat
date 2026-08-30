package com.example.twopchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.tor.TorBridgeCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertTrue(P2PPreferences.setAppLanguage(context, "Русский"))
        assertEquals("Русский", P2PPreferences.getAppLanguage(context))

        assertTrue(P2PPreferences.setAppLanguage(context, "English"))
        assertEquals("English", P2PPreferences.getAppLanguage(context))
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
