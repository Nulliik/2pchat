package com.example.twopchat.group.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembershipIntervalsTest {

    @Test
    fun removedMemberOldEpochMessageAcceptedIfActiveInThatEpoch() {
        // Alice joined at epoch 1, removed at epoch 3.
        // Boundary semantics: [1, 3). She is active at epoch 1 and 2, but not 3.
        val intervals = listOf(1L to 3L)

        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 1))
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 2))
        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 3))
        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 4))
    }

    @Test
    fun messageBeforeJoinEpochRejected() {
        // Bob joined at epoch 3 and is still active (removedEpoch == null)
        val intervals = listOf(3L to null)

        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 0))
        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 1))
        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 2))
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 3))
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 4))
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 100))
    }

    @Test
    fun reAddedMemberIntervalsHandled() {
        // Charlie joined at epoch 1, removed at epoch 3 ([1, 3)).
        // Re-added at epoch 5, still active ([5, null)).
        val intervals = listOf(
            1L to 3L,
            5L to null,
        )

        // Epoch 0: Not joined yet
        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 0))

        // First membership interval [1, 3)
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 1))
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 2))

        // Inactive gap between epoch 3 and 5: [3, 5)
        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 3))
        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 4))

        // Second membership interval [5, null)
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 5))
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 6))
        assertTrue(GroupMembershipIntervals.isAnyIntervalActive(intervals, 999))
    }

    @Test
    fun removedMemberInSameEpochAsJoinRejected() {
        // Edge case: joined at epoch 2, removed at epoch 2 ([2, 2))
        val intervals = listOf(2L to 2L)
        assertFalse(GroupMembershipIntervals.isAnyIntervalActive(intervals, 2))
    }
}
