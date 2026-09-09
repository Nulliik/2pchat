package com.example.twopchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.config.P2PPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeerIdentityPreferencesInstrumentedTest {
    private val peerName = "identity-transition-test-peer"
    private val context by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }

    @Before
    fun prepare() = clearState()

    @After
    fun cleanup() = clearState()

    @Test
    fun unexpectedKeyStaysPausedUntilItIsExplicitlyAccepted() {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit()
            .putString(P2PPreferences.peerFingerprint(peerName), "old-key")
            .putBoolean(P2PPreferences.verifiedPeer(peerName), true)
            .commit()

        P2PPreferences.recordPendingPeerIdentity(context, peerName, "new-key", "10.0.0.2:50001")
        P2PPreferences.recordPendingPeerIdentity(context, peerName, "attacker-key", "10.0.0.3:50001")

        assertTrue(P2PPreferences.isPeerIdentityChangePending(context, peerName))
        assertEquals("old-key", prefs.getString(P2PPreferences.peerFingerprint(peerName), null))
        assertEquals("new-key", prefs.getString(P2PPreferences.pendingPeerFingerprint(peerName), null))

        val accepted = P2PPreferences.acceptPendingPeerIdentity(context, peerName)

        assertEquals("old-key", accepted?.previousFingerprint)
        assertEquals("new-key", accepted?.acceptedFingerprint)
        assertEquals("10.0.0.2:50001", accepted?.endpoint)
        assertFalse(P2PPreferences.isPeerIdentityChangePending(context, peerName))
        assertFalse(P2PPreferences.isPeerVerified(context, peerName))
        assertEquals("new-key", prefs.getString(P2PPreferences.peerFingerprint(peerName), null))
    }

    @Test
    fun rejectingUnexpectedKeyKeepsTheOldPin() {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit().putString(P2PPreferences.peerFingerprint(peerName), "old-key").commit()
        P2PPreferences.recordPendingPeerIdentity(context, peerName, "new-key", "10.0.0.2:50001")

        assertTrue(P2PPreferences.rejectPendingPeerIdentity(context, peerName))

        assertFalse(P2PPreferences.isPeerIdentityChangePending(context, peerName))
        assertEquals("old-key", prefs.getString(P2PPreferences.peerFingerprint(peerName), null))
    }

    private fun clearState() {
        P2PPreferences.prefs(context).edit()
            .remove(P2PPreferences.peerFingerprint(peerName))
            .remove(P2PPreferences.fingerprintMismatch(peerName))
            .remove(P2PPreferences.pendingPeerFingerprint(peerName))
            .remove(P2PPreferences.pendingPeerEndpoint(peerName))
            .remove(P2PPreferences.lastEndpoint(peerName))
            .remove(P2PPreferences.verifiedPeer(peerName))
            .commit()
    }
}
