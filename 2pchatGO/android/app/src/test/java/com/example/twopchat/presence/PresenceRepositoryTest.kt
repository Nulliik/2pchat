package com.example.twopchat.presence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the core desync invariants of the versioned presence ingest:
 * - stale (lower-seq) Go events cannot overwrite newer state, in both
 *   directions (connect after disconnect, disconnect after connect);
 * - a stale snapshot/pull schedules at most a debounced grace flip, and a
 *   fresher event cancels it, so stale pulls can never beat fresh pushes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresenceRepositoryTest {

    // 40-char fingerprint-like key: the reconcile clear pass only correlates
    // fingerprint-shaped keys with the Go snapshot.
    private val fp = "a3f5b9c1d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2"

    private val testMain = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testMain)
        PresenceRepository.clearAll()
    }

    @After
    fun tearDown() {
        PresenceRepository.clearAll()
        Dispatchers.resetMain()
    }

    private fun advanceGrace() {
        testMain.scheduler.advanceTimeBy(3_000)
    }

    @Test
    fun staleDisconnectCannotOverwriteNewerConnect() {
        // Session-replacement race: the NEW session's connect (seq 2) lands
        // before the OLD session's disconnect (seq 1).
        PresenceRepository.observeOnline(fp, "tor", "onion", seq = 2)
        PresenceRepository.observeOffline(fp, immediate = true, seq = 1)
        assertTrue("stale disconnect (seq 1 < 2) must be dropped", PresenceRepository.isOnline(fp))
    }

    @Test
    fun staleConnectCannotOverwriteNewerDisconnect() {
        PresenceRepository.observeOnline(fp, "direct", "1.2.3.4:50001", seq = 5)
        PresenceRepository.observeOffline(fp, immediate = true, seq = 6)
        // A late-arriving connect with a smaller seq must not resurrect the peer.
        PresenceRepository.observeOnline(fp, "tor", "onion", seq = 4)
        val state = PresenceRepository.state(fp)
        assertFalse("stale connect (seq 4 < 6) must be dropped", PresenceRepository.isOnline(fp))
        assertTrue(state is PresenceRepository.PeerPresence.Offline)
    }

    @Test
    fun freshEventCancelsGraceFlipScheduledByStaleSnapshot() {
        PresenceRepository.observeOnline(fp, "direct", "1.2.3.4:50001", seq = 2)
        // Stale snapshot without the peer schedules a debounced offline flip...
        PresenceRepository.reconcileWithSnapshot("[]")
        // ...which a newer connect must cancel before the grace elapses.
        PresenceRepository.observeOnline(fp, "direct", "1.2.3.4:50001", seq = 3)
        advanceGrace()
        assertTrue("fresh connect (seq 3) must win over the stale snapshot", PresenceRepository.isOnline(fp))
    }

    @Test
    fun staleSnapshotFlipsOfflineOnlyAfterGrace() {
        PresenceRepository.observeOnline(fp, seq = 1)
        PresenceRepository.reconcileWithSnapshot("[]")
        assertTrue("offline flip is debounced during the grace window", PresenceRepository.isOnline(fp))
        advanceGrace()
        assertFalse("stale snapshot must take effect after the grace window", PresenceRepository.isOnline(fp))
    }

    @Test
    fun ingestWithFingerprintLandsStateUnderCanonicalKey() {
        // A first connect for a fresh nickname must not leave state under a
        // nickname key that later dangles: the alias and the state land in
        // one serialized block.
        PresenceRepository.observeOnline("NewPeer", "tor", "onion", seq = 1, fingerprint = fp)
        assertTrue("state must land under the fingerprint", PresenceRepository.isOnline(fp))
        assertTrue("nickname must resolve to the same canonical state", PresenceRepository.isOnline("NewPeer"))
    }

    @Test
    fun offlineIngestWithFingerprintLandsUnderCanonicalKey() {
        PresenceRepository.observeOnline("NewPeer", "tor", "onion", seq = 1, fingerprint = fp)
        PresenceRepository.observeOffline("NewPeer", immediate = true, seq = 2, fingerprint = fp)
        assertFalse("canonical key must be offline", PresenceRepository.isOnline(fp))
        assertFalse("nickname alias must follow the canonical state", PresenceRepository.isOnline("NewPeer"))
    }

    @Test
    fun stalePullWriteIsDroppedWhenNewerEventLanded() {
        // The review scenario: the pull captured its version, then a fresh
        // session event landed before the pull result came back.
        val versionAtPullStart = PresenceRepository.observeOnline(fp, "direct", "1.2.3.4:50001", seq = 1)
        PresenceRepository.observeOffline(fp, immediate = true, seq = 2)
        val applied = PresenceRepository.observeOnlineIfVersion(
            peerNameOrFp = fp,
            transport = "direct",
            endpoint = "1.2.3.4:50001",
            expectedVersion = versionAtPullStart,
        )
        assertFalse("stale pull result must not overwrite the newer disconnect", applied)
        assertFalse(PresenceRepository.isOnline(fp))
    }

    @Test
    fun pullWriteAppliesWhenNoNewerEventLanded() {
        PresenceRepository.observeOnline(fp, "direct", "1.2.3.4:50001", seq = 1)
        val versionAtPullStart = PresenceRepository.currentVersion(fp)
        val applied = PresenceRepository.observeOnlineIfVersion(
            peerNameOrFp = fp,
            transport = "tor",
            endpoint = "onion",
            expectedVersion = versionAtPullStart,
        )
        assertTrue(applied)
        assertTrue(PresenceRepository.isOnline(fp))
    }

    @Test
    fun pullOfflineFlipHonoursGraceWindow() {
        PresenceRepository.observeOnline(fp, seq = 1)
        val versionAtPullStart = PresenceRepository.currentVersion(fp)
        assertTrue(
            PresenceRepository.observeOfflineIfVersion(
                peerNameOrFp = fp,
                expectedVersion = versionAtPullStart,
            ),
        )
        assertTrue("grace window still applies to version-gated pull flips", PresenceRepository.isOnline(fp))
        advanceGrace()
        assertFalse("pull offline flip must land after the grace window", PresenceRepository.isOnline(fp))
    }

    @Test
    fun pullOfflineFlipIsDroppedWhenConnectRaced() {
        PresenceRepository.observeOnline(fp, seq = 1)
        val versionAtPullStart = PresenceRepository.currentVersion(fp)
        PresenceRepository.observeOnline(fp, "tor", "onion", seq = 2)
        val applied = PresenceRepository.observeOfflineIfVersion(
            peerNameOrFp = fp,
            expectedVersion = versionAtPullStart,
        )
        assertFalse("stale pull offline must be dropped", applied)
        advanceGrace()
        assertTrue(PresenceRepository.isOnline(fp))
    }
}
