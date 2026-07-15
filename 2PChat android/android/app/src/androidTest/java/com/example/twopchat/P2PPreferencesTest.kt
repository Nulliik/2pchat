package com.example.twopchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            .commit()
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
    fun peerVerificationPersistsOutsideChatUiLifetime() {
        assertFalse(P2PPreferences.isPeerVerified(context, "Alice"))

        P2PPreferences.setPeerVerified(context, "Alice", true)
        assertTrue(P2PPreferences.isPeerVerified(context, "Alice"))

        P2PPreferences.setPeerVerified(context, "Alice", false)
        assertFalse(P2PPreferences.isPeerVerified(context, "Alice"))
    }
}
