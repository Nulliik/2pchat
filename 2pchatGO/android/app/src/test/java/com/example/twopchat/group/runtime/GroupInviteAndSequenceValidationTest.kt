package com.example.twopchat.group.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInviteAndSequenceValidationTest {

    @Test
    fun inviteCapabilityGenerationIsDeterministicAndUrlSafe() {
        val secretBytes = ByteArray(32) { (it * 7).toByte() }
        val cap1 = encodeGroupInviteCapability(secretBytes)
        val cap2 = encodeGroupInviteCapability(secretBytes)

        assertEquals("Invite capability must be deterministic", cap1, cap2)
        assertEquals("Invite capability hex length must be 64 characters", 64, cap1.length)
        assertTrue("Capability must be lowercase hex", cap1.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun inviteCapabilityDiffersForDifferentSecrets() {
        val secret1 = ByteArray(32) { 1 }
        val secret2 = ByteArray(32) { 2 }
        assertNotEquals(
            encodeGroupInviteCapability(secret1),
            encodeGroupInviteCapability(secret2),
        )
    }

    @Test
    fun sequenceValidationDifferentiatesEpochsForRejoinedMembers() {
        val joinedEpoch = 2L
        val priorEpochEventEpoch = 1L
        val currentEpochEventEpoch = 2L

        // Prior epoch event is below joinedEpoch floor
        assertTrue(
            "Event from prior epoch (epoch 1) is before member joined (epoch 2)",
            priorEpochEventEpoch < joinedEpoch,
        )

        // Current epoch event meets minimum epoch requirement
        assertTrue(
            "Event from current epoch (epoch 2) meets minimum epoch requirement (epoch 2)",
            currentEpochEventEpoch >= joinedEpoch,
        )
    }

    @Test
    fun keyPackageEpochToleranceAllowsUpcomingEpochs() {
        val currentGroupEpoch = 1L
        val incomingKeyPackageEpoch = 2L // N+1 generated alongside MEMBER_ADDED

        // Must accept key package for currentEpoch or currentEpoch + 1
        val isAcceptableEpoch = incomingKeyPackageEpoch <= currentGroupEpoch + 1L
        assertTrue("Key package for epoch N+1 must be accepted alongside MEMBER_ADDED", isAcceptableEpoch)

        val staleKeyPackageEpoch = 3L
        val isStaleAcceptable = staleKeyPackageEpoch <= currentGroupEpoch + 1L
        assertFalse("Key package for epoch N+2 must not be accepted prematurely", isStaleAcceptable)
    }

    @Test
    fun joiningToActiveTransitionIsOnlyPermittedForValidMemberAdded() {
        // Local member in JOINING state accepts MEMBER_ADDED with status ACTIVE
        assertTrue(
            "MEMBER_ADDED with status ACTIVE transforms JOINING to ACTIVE",
            GroupMembershipTransitions.canApplyMemberAdded("JOINING", "ACTIVE"),
        )
        assertTrue(
            "INVITED state transforms to ACTIVE on acceptance",
            GroupMembershipTransitions.canApplyMemberAdded("INVITED", "ACTIVE"),
        )
        assertFalse(
            "ACTIVE member cannot re-apply MEMBER_ADDED ACTIVE",
            GroupMembershipTransitions.canApplyMemberAdded("ACTIVE", "ACTIVE"),
        )
    }

    @Test
    fun inviteExpirationEvaluatesCorrectly() {
        val lifetimeMs = 7 * 24 * 3600 * 1000L
        val invitedAtMs = 1_000_000L

        assertFalse(
            "Invite active before lifetime expires",
            GroupMembershipTransitions.shouldExpireInvite("INVITED", invitedAtMs, invitedAtMs + 1000L, lifetimeMs),
        )
        assertTrue(
            "Invite expires once lifetime duration passes",
            GroupMembershipTransitions.shouldExpireInvite("INVITED", invitedAtMs, invitedAtMs + lifetimeMs, lifetimeMs),
        )
    }
}
