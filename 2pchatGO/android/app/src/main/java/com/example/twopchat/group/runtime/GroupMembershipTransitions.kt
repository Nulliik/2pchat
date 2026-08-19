package com.example.twopchat.group.runtime

/**
 * Pure membership-state rules shared by the durable coordinator and JVM
 * regression tests. Network handlers may perform stronger signature and
 * control-chain checks before consulting these transitions.
 */
internal object GroupMembershipTransitions {
    fun isParticipating(status: String): Boolean =
        status == "ACTIVE" || status == "RESTRICTED"

    /**
     * A reconnect may wake durable delivery only for an identity already
     * present in the accepted roster. Peer names are mutable labels and must
     * never be used as a membership credential.
     */
    fun isReconnectCandidate(
        status: String,
        storedDeviceId: String,
        storedFingerprint: String,
        connectedFingerprint: String,
        connectedDeviceId: String,
    ): Boolean =
        status in setOf("ACTIVE", "RESTRICTED", "INVITED", "JOINING") &&
            storedFingerprint.isNotBlank() &&
            storedFingerprint == connectedFingerprint &&
            storedDeviceId == connectedDeviceId

    fun canApplyMemberAdded(currentStatus: String?, requestedStatus: String): Boolean =
        when (requestedStatus) {
            "INVITED" -> currentStatus == null || currentStatus in setOf("LEFT", "BANNED")
            "ACTIVE" -> currentStatus in setOf("INVITED", "JOINING")
            else -> false
        }

    fun canRefreshJoiningInvite(
        localStatus: String?,
        ownerMatches: Boolean,
        existingEpoch: Long,
        incomingEpoch: Long,
        sameControlHead: Boolean,
        incomingCreatedAtMs: Long,
        existingUpdatedAtMs: Long,
        maxClockSkewMs: Long,
    ): Boolean {
        if (localStatus != "JOINING" || !ownerMatches) return false
        if (incomingEpoch < existingEpoch) return false
        return incomingEpoch > existingEpoch ||
            sameControlHead ||
            incomingCreatedAtMs >= existingUpdatedAtMs - maxClockSkewMs
    }

    fun canAcceptRejoinInvite(
        localStatus: String?,
        ownerMatches: Boolean,
        addressedAsInvited: Boolean,
        hasControlHead: Boolean,
        existingEpoch: Long,
        removedEpoch: Long?,
        incomingEpoch: Long,
        incomingCreatedAtMs: Long,
        localUpdatedAtMs: Long,
        maxClockSkewMs: Long,
    ): Boolean {
        if (
            localStatus !in setOf("LEFT", "BANNED") ||
            !ownerMatches ||
            !addressedAsInvited ||
            !hasControlHead
        ) {
            return false
        }
        val minimumEpoch = maxOf(existingEpoch, removedEpoch ?: existingEpoch)
        return incomingEpoch >= minimumEpoch &&
            incomingCreatedAtMs >= localUpdatedAtMs - maxClockSkewMs
    }

    fun canTransferOwnership(
        hasPendingInvite: Boolean,
        hasPendingRosterDelivery: Boolean,
    ): Boolean = !hasPendingInvite && !hasPendingRosterDelivery

    fun shouldExpireInvite(
        status: String,
        invitedAtMs: Long,
        nowMs: Long,
        lifetimeMs: Long,
    ): Boolean =
        status == "INVITED" &&
            invitedAtMs >= 0L &&
            lifetimeMs > 0L &&
            nowMs >= invitedAtMs &&
            nowMs - invitedAtMs >= lifetimeMs
}
