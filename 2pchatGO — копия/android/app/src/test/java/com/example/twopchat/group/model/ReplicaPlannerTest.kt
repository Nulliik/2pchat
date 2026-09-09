package com.example.twopchat.group.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplicaPlannerTest {
    @Test
    fun hrwSelectionIsDeterministicAndIndependentOfInputOrder() {
        val candidates = candidates(20)
        val expected = ReplicaPlanner.selectReplicas("segment-1", candidates, 5)

        repeat(10) { iteration ->
            val reordered = candidates.shuffled(java.util.Random(iteration.toLong()))
            assertEquals(expected, ReplicaPlanner.selectReplicas("segment-1", reordered, 5))
        }
        assertEquals(5, expected.size)
        assertEquals(5, expected.distinct().size)
    }

    @Test
    fun duplicateAndUnavailableCandidatesAreNeverSelected() {
        val duplicate = ReplicaCandidate(DeviceId("device-1"))
        val candidates = listOf(
            duplicate,
            duplicate,
            ReplicaCandidate(DeviceId("device-2"), available = false),
            ReplicaCandidate(DeviceId("device-3")),
        )

        assertEquals(
            listOf(DeviceId("device-1"), DeviceId("device-3")).toSet(),
            ReplicaPlanner.selectReplicas("segment", candidates, 10).toSet(),
        )
    }

    @Test
    fun failureReplacementExcludesFailedNodeAndMinimallyChangesPlan() {
        val candidates = candidates(20)
        val initial = ReplicaPlanner.selectReplicas("segment-2", candidates, 5)
        val failed = initial[2]

        val repaired = ReplicaPlanner.replaceFailedReplicas(
            objectKey = "segment-2",
            candidates = candidates,
            replicaCount = 5,
            failedDeviceIds = setOf(failed),
        )

        assertFalse(failed in repaired)
        assertEquals(5, repaired.size)
        assertEquals(initial.toSet() - failed, repaired.toSet().intersect(initial.toSet()))
    }

    @Test
    fun insufficientCandidatesReturnsEveryEligibleCandidate() {
        val selected = ReplicaPlanner.selectReplicas("segment", candidates(2), replicaCount = 5)

        assertEquals(2, selected.size)
        assertEquals(candidates(2).map { it.deviceId }.toSet(), selected.toSet())
    }

    @Test
    fun objectKeyChangesPlacement() {
        val candidates = candidates(50)

        val first = ReplicaPlanner.selectReplicas("segment-a", candidates, 5)
        val second = ReplicaPlanner.selectReplicas("segment-b", candidates, 5)

        assertFalse("different objects unexpectedly received identical placements", first == second)
    }

    @Test
    fun deliveryAggregateProgressesFromPendingToReplicated() {
        val planned = candidates(5).map { it.deviceId }

        assertAggregate(
            expectedStatus = ReplicationStatus.PENDING,
            expectedStored = 0,
            ReplicaPlanner.aggregateDelivery(planned, emptyList(), requiredQuorum = 3),
        )
        assertAggregate(
            expectedStatus = ReplicationStatus.REPLICATING,
            expectedStored = 1,
            ReplicaPlanner.aggregateDelivery(
                planned,
                listOf(ReplicaAck(planned[0], ReplicaAckState.STORED)),
                requiredQuorum = 3,
            ),
        )
        assertAggregate(
            expectedStatus = ReplicationStatus.REPLICATED,
            expectedStored = 3,
            ReplicaPlanner.aggregateDelivery(
                planned,
                planned.take(3).map { ReplicaAck(it, ReplicaAckState.STORED) },
                requiredQuorum = 3,
            ),
        )
    }

    @Test
    fun failuresDistinguishDegradedFromImpossibleQuorum() {
        val planned = candidates(5).map { it.deviceId }
        val degraded = ReplicaPlanner.aggregateDelivery(
            planned,
            listOf(
                ReplicaAck(planned[0], ReplicaAckState.STORED),
                ReplicaAck(planned[1], ReplicaAckState.FAILED),
            ),
            requiredQuorum = 3,
        )
        val impossible = ReplicaPlanner.aggregateDelivery(
            planned,
            listOf(
                ReplicaAck(planned[0], ReplicaAckState.STORED),
                ReplicaAck(planned[1], ReplicaAckState.FAILED),
                ReplicaAck(planned[2], ReplicaAckState.FAILED),
                ReplicaAck(planned[3], ReplicaAckState.FAILED),
            ),
            requiredQuorum = 3,
        )

        assertEquals(ReplicationStatus.DEGRADED, degraded.status)
        assertEquals(ReplicationStatus.FAILED, impossible.status)
        assertFalse(impossible.quorumReached)
    }

    @Test
    fun duplicateAndUnknownAcksCannotInflateQuorum() {
        val planned = candidates(3).map { it.deviceId }
        val aggregate = ReplicaPlanner.aggregateDelivery(
            planned,
            listOf(
                ReplicaAck(planned[0], ReplicaAckState.FAILED),
                ReplicaAck(planned[0], ReplicaAckState.STORED),
                ReplicaAck(planned[0], ReplicaAckState.STORED),
                ReplicaAck(DeviceId("unknown"), ReplicaAckState.STORED),
            ),
            requiredQuorum = 2,
        )

        assertEquals(1, aggregate.storedReplicaCount)
        assertEquals(0, aggregate.failedReplicaCount)
        assertEquals(2, aggregate.pendingReplicaCount)
        assertFalse(aggregate.quorumReached)
    }

    @Test
    fun invalidQuorumIsRejected() {
        val planned = candidates(2).map { it.deviceId }

        assertThrows(IllegalArgumentException::class.java) {
            ReplicaPlanner.aggregateDelivery(planned, emptyList(), requiredQuorum = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplicaPlanner.aggregateDelivery(planned, emptyList(), requiredQuorum = -1)
        }
    }

    private fun candidates(count: Int): List<ReplicaCandidate> =
        (0 until count).map { ReplicaCandidate(DeviceId("device-$it")) }

    private fun assertAggregate(
        expectedStatus: ReplicationStatus,
        expectedStored: Int,
        actual: DeliveryAggregate,
    ) {
        assertEquals(expectedStatus, actual.status)
        assertEquals(expectedStored, actual.storedReplicaCount)
        assertEquals(expectedStatus == ReplicationStatus.REPLICATED, actual.quorumReached)
        assertTrue(actual.plannedReplicaCount >= actual.requiredQuorum)
    }
}
