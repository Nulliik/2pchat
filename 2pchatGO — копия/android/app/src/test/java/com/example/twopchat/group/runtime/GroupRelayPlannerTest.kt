package com.example.twopchat.group.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRelayPlannerTest {
    @Test
    fun everyOnlineMemberIsReachedWithBoundedFanout() {
        for (size in listOf(2, 20, 33, 129, 500, 1_000)) {
            val members = (0 until size).map { "device-$it" }
            val received = linkedSetOf(members.first())
            val queue = java.util.ArrayDeque(received)
            var transmissions = 0
            while (queue.isNotEmpty()) {
                val local = queue.removeFirst()
                val targets = GroupRelayPlanner.successors(local, members)
                assertTrue(targets.size <= 3)
                assertFalse(local in targets)
                transmissions += targets.size
                targets.forEach { if (received.add(it)) queue.add(it) }
            }
            assertEquals(members.toSet(), received)
            assertTrue(transmissions <= size * 3)
        }
    }

    @Test
    fun onlineRingStillConvergesWhenAuthorAndHalfTheRosterAreOffline() {
        val online = (0 until 500).filter { it % 2 == 1 }.map { "device-$it" }
        val received = linkedSetOf(online.last()) // A replica already has the signed event.
        val queue = java.util.ArrayDeque(received)
        while (queue.isNotEmpty()) {
            GroupRelayPlanner.successors(queue.removeFirst(), online).forEach {
                if (received.add(it)) queue.add(it)
            }
        }
        assertEquals(online.toSet(), received)
    }

    @Test
    fun tenThousandMembersDoNotIncreaseSenderFanout() {
        val members = (0 until 10_000).map { "device-$it" }
        for (local in listOf(members.first(), members[5000], members.last())) {
            val targets = GroupRelayPlanner.successors(local, members)
            assertEquals(3, targets.size)
            assertEquals(targets, GroupRelayPlanner.successors(local, members.reversed()))
        }
        assertTrue(GroupRelayPlanner.successors("solo", emptyList()).isEmpty())
    }
}
