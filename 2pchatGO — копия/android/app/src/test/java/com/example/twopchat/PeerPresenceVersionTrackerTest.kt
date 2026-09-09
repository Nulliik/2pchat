package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerPresenceVersionTrackerTest {
    @Test
    fun staleMaintenanceObservationCannotAdvancePresence() {
        val tracker = PeerPresenceVersionTracker()
        val maintenanceSnapshot = tracker.current("alice")

        assertEquals(1L, tracker.advance("alice"))
        assertNull(tracker.advanceIfCurrent("alice", maintenanceSnapshot))
        assertEquals(1L, tracker.current("alice"))
    }

    @Test
    fun currentObservationAdvancesExactlyOnce() {
        val tracker = PeerPresenceVersionTracker()
        val snapshot = tracker.current("alice")

        assertEquals(1L, tracker.advanceIfCurrent("alice", snapshot))
        assertNull(tracker.advanceIfCurrent("alice", snapshot))
        assertEquals(1L, tracker.current("alice"))
    }
}
