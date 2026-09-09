package com.example.twopchat.group.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ReplicaCandidate(
    val deviceId: DeviceId,
    val available: Boolean = true,
)

enum class ReplicaAckState {
    PENDING,
    STORED,
    FAILED,
}

data class ReplicaAck(
    val deviceId: DeviceId,
    val state: ReplicaAckState,
)

enum class ReplicationStatus {
    PENDING,
    REPLICATING,
    DEGRADED,
    REPLICATED,
    FAILED,
}

data class DeliveryAggregate(
    val plannedReplicaCount: Int,
    val requiredQuorum: Int,
    val storedReplicaCount: Int,
    val pendingReplicaCount: Int,
    val failedReplicaCount: Int,
    val status: ReplicationStatus,
) {
    val quorumReached: Boolean
        get() = storedReplicaCount >= requiredQuorum && requiredQuorum > 0
}

object ReplicaPlanner {
    /**
     * Highest-random-weight selection. The result is stable across candidate
     * enumeration order and changes minimally when a candidate disappears.
     */
    fun selectReplicas(
        objectKey: String,
        candidates: Collection<ReplicaCandidate>,
        replicaCount: Int,
        excludedDeviceIds: Set<DeviceId> = emptySet(),
    ): List<DeviceId> {
        require(objectKey.isNotBlank()) { "object key must not be blank" }
        require(replicaCount >= 0) { "replica count must not be negative" }
        if (replicaCount == 0) return emptyList()

        val eligible = candidates
            .asSequence()
            .filter(ReplicaCandidate::available)
            .filterNot { it.deviceId in excludedDeviceIds }
            .distinctBy(ReplicaCandidate::deviceId)
            .map { candidate ->
                candidate.deviceId to rendezvousScore(objectKey, candidate.deviceId)
            }
            .sortedWith { left, right ->
                val byScore = java.lang.Long.compareUnsigned(right.second, left.second)
                if (byScore != 0) byScore else left.first.value.compareTo(right.first.value)
            }
            .take(replicaCount)
            .map(Pair<DeviceId, Long>::first)
            .toList()

        return eligible
    }

    fun replaceFailedReplicas(
        objectKey: String,
        candidates: Collection<ReplicaCandidate>,
        replicaCount: Int,
        failedDeviceIds: Set<DeviceId>,
    ): List<DeviceId> =
        selectReplicas(objectKey, candidates, replicaCount, failedDeviceIds)

    fun aggregateDelivery(
        plannedReplicas: Collection<DeviceId>,
        acknowledgements: Collection<ReplicaAck>,
        requiredQuorum: Int,
    ): DeliveryAggregate {
        val planned = plannedReplicas.distinct()
        require(requiredQuorum >= 0) { "required quorum must not be negative" }
        require(requiredQuorum <= planned.size) {
            "required quorum cannot exceed planned replica count"
        }

        val strongestAckByDevice = acknowledgements
            .asSequence()
            .filter { it.deviceId in planned }
            .groupBy(ReplicaAck::deviceId)
            .mapValues { (_, deviceAcks) ->
                deviceAcks.maxBy { ackPrecedence(it.state) }.state
            }

        val stored = planned.count { strongestAckByDevice[it] == ReplicaAckState.STORED }
        val failed = planned.count { strongestAckByDevice[it] == ReplicaAckState.FAILED }
        val pending = planned.size - stored - failed
        val status = when {
            planned.isEmpty() || requiredQuorum == 0 -> ReplicationStatus.FAILED
            stored >= requiredQuorum -> ReplicationStatus.REPLICATED
            stored + pending < requiredQuorum -> ReplicationStatus.FAILED
            failed > 0 -> ReplicationStatus.DEGRADED
            stored > 0 -> ReplicationStatus.REPLICATING
            else -> ReplicationStatus.PENDING
        }
        return DeliveryAggregate(
            plannedReplicaCount = planned.size,
            requiredQuorum = requiredQuorum,
            storedReplicaCount = stored,
            pendingReplicaCount = pending,
            failedReplicaCount = failed,
            status = status,
        )
    }

    private fun rendezvousScore(objectKey: String, deviceId: DeviceId): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(
                "$objectKey\u0000${deviceId.value}"
                    .toByteArray(StandardCharsets.UTF_8)
            )
        return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
    }

    private fun ackPrecedence(state: ReplicaAckState): Int = when (state) {
        ReplicaAckState.PENDING -> 0
        ReplicaAckState.FAILED -> 1
        ReplicaAckState.STORED -> 2
    }
}
