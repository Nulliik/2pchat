package com.example.twopchat.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Bug18PreferencesConcurrencyTest {
    private lateinit var store: Bug18Prefs

    @Before
    fun setUp() {
        store = Bug18Prefs()
        P2PPreferences.setCachedPrefsForTesting(store.prefs)
    }

    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun updateDuringInitializationCannotBeOverwritten() {
        store.prefs.edit().putString("peer_fingerprint_Legacy", "FP").apply()
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == "all") gate.pause() }
            val lookup = Bug18Call { P2PPreferences.findPeerNameByFingerprint(store.context, "FP") }
            gate.awaitEntry()
            val rename = Bug18Call { P2PPreferences.updateFingerprintCache("FP", "Live") }
            try {
                rename.awaitBlocked()
            } finally {
                gate.close()
            }
            assertEquals("Legacy", lookup.result())
            rename.result()
            assertEquals("Live", P2PPreferences.findPeerNameByFingerprint(store.context, "FP"))
        }
    }

    @Test
    fun updateBeforeInitializationWinsOverPersistedAlias() {
        store.prefs.edit().putString("peer_fingerprint_Legacy", "FP").apply()
        P2PPreferences.updateFingerprintCache("FP", "Live")
        assertEquals("Live", P2PPreferences.findPeerNameByFingerprint(store.context, "FP"))
    }

    @Test
    fun wipeWaitsForInitializationAndInvalidatesItsResult() {
        store.prefs.edit().putString("peer_fingerprint_Old", "FP").apply()
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == "all") gate.pause() }
            val lookup = Bug18Call { P2PPreferences.findPeerNameByFingerprint(store.context, "FP") }
            gate.awaitEntry()
            val wipe = Bug18Call { P2PPreferences.clearInMemoryState() }
            try {
                wipe.awaitBlocked()
            } finally {
                gate.close()
            }
            assertEquals("Old", lookup.result())
            wipe.result()
            store.afterRead = {}
            store.prefs.edit().putString("peer_fingerprint_New", "FP").apply()
            assertEquals("New", P2PPreferences.findPeerNameByFingerprint(store.context, "FP"))
            assertEquals(1, store.commits)
        }
    }

    @Test
    fun firstPendingIdentityAndEndpointStayTogether() {
        store.prefs.edit().putString(P2PPreferences.peerFingerprint("Peer"), "original").apply()
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == P2PPreferences.pendingPeerFingerprint("Peer")) gate.pause() }
            val first = Bug18Call { P2PPreferences.recordPendingPeerIdentity(store.context, "Peer", "first", "endpoint-1") }
            gate.awaitEntry()
            val second = Bug18Call { P2PPreferences.recordPendingPeerIdentity(store.context, "Peer", "second", "endpoint-2") }
            try {
                second.awaitBlocked()
            } finally {
                gate.close()
            }
            first.result()
            second.result()
            assertEquals("first", store.prefs.getString(P2PPreferences.pendingPeerFingerprint("Peer"), null))
            assertEquals("endpoint-1", store.prefs.getString(P2PPreferences.pendingPeerEndpoint("Peer"), null))
            assertTrue(P2PPreferences.isPeerIdentityChangePending(store.context, "Peer"))
        }
    }

    @Test
    fun acceptanceKeepsCommitValidationAndInvalidatesCache() {
        store.prefs.edit().putString(P2PPreferences.peerFingerprint("Peer"), "old").apply()
        assertEquals("Peer", P2PPreferences.findPeerNameByFingerprint(store.context, "old"))
        P2PPreferences.recordPendingPeerIdentity(store.context, "Peer", "new", "endpoint")
        val accepted = P2PPreferences.acceptPendingPeerIdentity(store.context, "Peer")!!
        assertEquals("old", accepted.previousFingerprint)
        assertEquals("new", accepted.acceptedFingerprint)
        assertEquals("endpoint", accepted.endpoint)
        assertEquals(1, store.commits)
        assertEquals("Peer", P2PPreferences.findPeerNameByFingerprint(store.context, "new"))
        assertFalse(P2PPreferences.isPeerIdentityChangePending(store.context, "Peer"))
        P2PPreferences.recordPendingPeerIdentity(store.context, "Peer", "next", "")
        store.commitResult = false
        assertNull(P2PPreferences.acceptPendingPeerIdentity(store.context, "Peer"))
        assertEquals("Peer", P2PPreferences.findPeerNameByFingerprint(store.context, "next"))
    }

    @Test
    fun onionIncrementsSerializeAndKeepConfirmedCommits() {
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == P2PPreferences.TOR_ONION_INDEX) gate.pause() }
            val first = Bug18Call { P2PPreferences.incrementTorOnionIndex(store.context) }
            gate.awaitEntry()
            val second = Bug18Call { P2PPreferences.incrementTorOnionIndex(store.context) }
            try {
                second.awaitBlocked()
            } finally {
                gate.close()
            }
            assertEquals(1, first.result())
            assertEquals(2, second.result())
            assertEquals(2, store.commits)
        }
    }
}
