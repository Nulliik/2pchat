package com.example.twopchat.group.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembershipTransitionsTest {
    @Test
    fun reconnectOnlyMatchesAnExistingCryptographicRosterIdentity() {
        assertTrue(
            GroupMembershipTransitions.isReconnectCandidate(
                status = "ACTIVE",
                storedDeviceId = "device-alice",
                storedFingerprint = "fingerprint-alice",
                connectedFingerprint = "fingerprint-alice",
                connectedDeviceId = "device-alice",
            ),
        )
        assertFalse(
            GroupMembershipTransitions.isReconnectCandidate(
                status = "ACTIVE",
                storedDeviceId = "device-alice",
                storedFingerprint = "fingerprint-alice",
                connectedFingerprint = "fingerprint-mallory",
                connectedDeviceId = "device-mallory",
            ),
        )
        assertFalse(
            GroupMembershipTransitions.isReconnectCandidate(
                status = "LEFT",
                storedDeviceId = "device-alice",
                storedFingerprint = "fingerprint-alice",
                connectedFingerprint = "fingerprint-alice",
                connectedDeviceId = "device-alice",
            ),
        )
    }

    @Test
    fun reconnectWakesAnOfflineInvitationWithoutActivatingTheInvitee() {
        assertTrue(
            GroupMembershipTransitions.isReconnectCandidate(
                status = "INVITED",
                storedDeviceId = "device-bob",
                storedFingerprint = "fingerprint-bob",
                connectedFingerprint = "fingerprint-bob",
                connectedDeviceId = "device-bob",
            ),
        )
        assertFalse(GroupMembershipTransitions.isParticipating("INVITED"))
    }

    @Test
    fun onlyActiveAndRestrictedMembersParticipate() {
        assertTrue(GroupMembershipTransitions.isParticipating("ACTIVE"))
        assertTrue(GroupMembershipTransitions.isParticipating("RESTRICTED"))
        listOf("INVITED", "JOINING", "LEFT", "BANNED").forEach {
            assertFalse(GroupMembershipTransitions.isParticipating(it))
        }
    }

    @Test
    fun canonicalAcceptanceIsTheOnlyJoiningToActiveTransition() {
        assertTrue(GroupMembershipTransitions.canApplyMemberAdded("JOINING", "ACTIVE"))
        assertTrue(GroupMembershipTransitions.canApplyMemberAdded("INVITED", "ACTIVE"))
        assertFalse(GroupMembershipTransitions.canApplyMemberAdded("ACTIVE", "ACTIVE"))
        assertFalse(GroupMembershipTransitions.canApplyMemberAdded(null, "ACTIVE"))
    }

    @Test
    fun reinviteOnlyRevivesAbsentLeftOrBannedMember() {
        assertTrue(GroupMembershipTransitions.canApplyMemberAdded(null, "INVITED"))
        assertTrue(GroupMembershipTransitions.canApplyMemberAdded("LEFT", "INVITED"))
        assertTrue(GroupMembershipTransitions.canApplyMemberAdded("BANNED", "INVITED"))
        assertFalse(GroupMembershipTransitions.canApplyMemberAdded("JOINING", "INVITED"))
        assertFalse(GroupMembershipTransitions.canApplyMemberAdded("ACTIVE", "INVITED"))
    }

    @Test
    fun refreshedInviteCannotChangeOwnerOrRollbackEpoch() {
        val common = RefreshArgs(
            incomingCreatedAtMs = 1_000_000 - 300_000 - 1,
        )
        assertFalse(common.copy(ownerMatches = false).allowed())
        assertFalse(common.copy(incomingEpoch = 6).allowed())
        assertTrue(common.copy(incomingEpoch = 8).allowed())
        assertFalse(common.copy(localStatus = "ACTIVE", incomingEpoch = 8).allowed())
    }

    @Test
    fun sameEpochRefreshRequiresSameHeadOrNonStaleOwnerTimestamp() {
        val common = RefreshArgs()
        assertTrue(
            common.copy(
                sameControlHead = true,
                incomingCreatedAtMs =
                    common.existingUpdatedAtMs - common.maxClockSkewMs - 1,
            ).allowed(),
        )
        assertTrue(
            common.copy(
                incomingCreatedAtMs = common.existingUpdatedAtMs - common.maxClockSkewMs,
            ).allowed(),
        )
        assertFalse(
            common.copy(
                incomingCreatedAtMs =
                    common.existingUpdatedAtMs - common.maxClockSkewMs - 1,
            ).allowed(),
        )
    }

    @Test
    fun rejoinRequiresTombstoneSameOwnerCurrentEpochAndFreshControlHead() {
        val common = RejoinArgs()
        assertTrue(common.copy(localStatus = "LEFT").allowed())
        assertTrue(common.copy(localStatus = "BANNED").allowed())
        assertFalse(common.copy(localStatus = "ACTIVE").allowed())
        assertFalse(common.copy(ownerMatches = false).allowed())
        assertFalse(common.copy(addressedAsInvited = false).allowed())
        assertFalse(common.copy(hasControlHead = false).allowed())
        assertFalse(common.copy(incomingEpoch = 8).allowed())
        assertFalse(
            common.copy(
                incomingCreatedAtMs =
                    common.localUpdatedAtMs - common.maxClockSkewMs - 1,
            ).allowed(),
        )
    }

    @Test
    fun ownershipTransferWaitsForInvitesAndRosterDelivery() {
        assertTrue(GroupMembershipTransitions.canTransferOwnership(false, false))
        assertFalse(GroupMembershipTransitions.canTransferOwnership(true, false))
        assertFalse(GroupMembershipTransitions.canTransferOwnership(false, true))
        assertFalse(GroupMembershipTransitions.canTransferOwnership(true, true))
    }

    @Test
    fun pendingInviteExpiresAtFixedLifetimeWithoutSlidingRefresh() {
        val week = 7L * 24L * 60L * 60L * 1_000L
        val invitedAt = 1_000_000L

        assertFalse(
            GroupMembershipTransitions.shouldExpireInvite(
                "INVITED",
                invitedAt,
                invitedAt + week - 1L,
                week,
            ),
        )
        assertTrue(
            GroupMembershipTransitions.shouldExpireInvite(
                "INVITED",
                invitedAt,
                invitedAt + week,
                week,
            ),
        )
        listOf("JOINING", "ACTIVE", "RESTRICTED", "LEFT", "BANNED").forEach { status ->
            assertFalse(
                GroupMembershipTransitions.shouldExpireInvite(
                    status,
                    invitedAt,
                    invitedAt + week,
                    week,
                ),
            )
        }
    }

    private data class RefreshArgs(
        val localStatus: String? = "JOINING",
        val ownerMatches: Boolean = true,
        val existingEpoch: Long = 7,
        val incomingEpoch: Long = 7,
        val sameControlHead: Boolean = false,
        val incomingCreatedAtMs: Long = 1_000_000,
        val existingUpdatedAtMs: Long = 1_000_000,
        val maxClockSkewMs: Long = 300_000,
    ) {
        fun allowed(): Boolean = GroupMembershipTransitions.canRefreshJoiningInvite(
            localStatus,
            ownerMatches,
            existingEpoch,
            incomingEpoch,
            sameControlHead,
            incomingCreatedAtMs,
            existingUpdatedAtMs,
            maxClockSkewMs,
        )
    }

    private data class RejoinArgs(
        val localStatus: String? = "LEFT",
        val ownerMatches: Boolean = true,
        val addressedAsInvited: Boolean = true,
        val hasControlHead: Boolean = true,
        val existingEpoch: Long = 9,
        val removedEpoch: Long? = 9,
        val incomingEpoch: Long = 9,
        val incomingCreatedAtMs: Long = 1_000_000,
        val localUpdatedAtMs: Long = 1_000_000,
        val maxClockSkewMs: Long = 300_000,
    ) {
        fun allowed(): Boolean = GroupMembershipTransitions.canAcceptRejoinInvite(
            localStatus,
            ownerMatches,
            addressedAsInvited,
            hasControlHead,
            existingEpoch,
            removedEpoch,
            incomingEpoch,
            incomingCreatedAtMs,
            localUpdatedAtMs,
            maxClockSkewMs,
        )
    }
}
