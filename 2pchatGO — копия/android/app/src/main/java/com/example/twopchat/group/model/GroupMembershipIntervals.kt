package com.example.twopchat.group.model

/**
 * Pure policy for membership intervals: [joinedEpoch, removedEpoch).
 *
 * Boundary semantics:
 * A control-event in epoch E with next_epoch = E+1 closes the interval as [joined, E+1).
 * Messages of epoch E are accepted, messages of epoch E+1 and beyond are rejected.
 */
object GroupMembershipIntervals {
    /**
     * Returns true iff the given [epoch] falls within [joinedEpoch, removedEpoch).
     */
    fun isEpochActive(joinedEpoch: Long, removedEpoch: Long?, epoch: Long): Boolean {
        return joinedEpoch <= epoch && (removedEpoch == null || epoch < removedEpoch)
    }

    /**
     * Checks whether the target epoch is active in any of the recorded intervals.
     */
    fun isAnyIntervalActive(
        intervals: List<Pair<Long, Long?>>,
        epoch: Long,
    ): Boolean {
        return intervals.any { (joined, removed) -> isEpochActive(joined, removed, epoch) }
    }
}
