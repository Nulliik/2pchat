package com.example.twopchat.group.simulation

import com.example.twopchat.group.model.DeviceId
import com.example.twopchat.group.model.GroupEventKind
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.model.GroupMember
import com.example.twopchat.group.model.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupEpochRekeyingSimulationTest {

    @Test
    fun antiEntropyConvergesAcrossMembershipChangesAndEpochProgression() {
        val simulator = DeterministicGroupNetworkSimulator(
            participantCount = 6,
            seed = 77_777L,
            faults = NetworkFaultProfile(lossEvery = 4, duplicateEvery = 5, maxDelayTicks = 7),
        )

        val owner = simulator.participantIds[0]
        val memberA = simulator.participantIds[1]
        val memberB = simulator.participantIds[2]
        val memberC = simulator.participantIds[3]
        val memberD = simulator.participantIds[4]
        val memberE = simulator.participantIds[5]

        // Epoch 1: All 6 participants send messages
        simulator.appendEvent(owner, GroupEventKind.TEXT_MESSAGE, "Epoch 1 message from Owner")
        simulator.appendEvent(memberA, GroupEventKind.TEXT_MESSAGE, "Epoch 1 message from Member A")
        simulator.appendEvent(memberB, GroupEventKind.TEXT_MESSAGE, "Epoch 1 message from Member B")

        simulator.runUntilIdle()
        simulator.gossipUntilConverged(maxRounds = 10)
        assertTrue(simulator.hasConverged())

        // Member E goes offline before member removal event
        simulator.setOnline(memberE, false)

        // Epoch 2: Owner removes member D (MEMBER_REMOVED event)
        val removeEvent = simulator.appendEvent(
            author = owner,
            kind = GroupEventKind.MEMBER_REMOVED,
            payload = "member=${memberD.value};epoch=2",
        )

        // Epoch 2 messages authored by remaining online members
        simulator.appendEvent(owner, GroupEventKind.TEXT_MESSAGE, "Epoch 2 message from Owner")
        simulator.appendEvent(memberA, GroupEventKind.TEXT_MESSAGE, "Epoch 2 message from Member A")
        simulator.appendEvent(memberC, GroupEventKind.TEXT_MESSAGE, "Epoch 2 message from Member C")

        simulator.runUntilIdle()

        // Member E comes back online and syncs via Anti-Entropy
        simulator.setOnline(memberE, true)
        val convergenceRounds = simulator.gossipUntilConverged(maxRounds = 15)

        assertTrue("Anti-entropy must converge after member E reconnects", convergenceRounds in 1..15)
        assertTrue(simulator.hasConverged())

        // Verify Member E received all events including the epoch transition
        val memberEEvents = simulator.canonicalEventIds(memberE)
        assertTrue(memberEEvents.contains(removeEvent.id))
        assertEquals(simulator.eventCount(owner), simulator.eventCount(memberE))
    }

    @Test
    fun memberRoleAndEpochConstraintsHold() {
        val activeMember = GroupMember(
            groupId = com.example.twopchat.group.model.ConversationId("group-1"),
            userId = UserId("user-1"),
            role = GroupRole.MEMBER,
            permissions = com.example.twopchat.group.model.GroupPermissionSet.ALL,
            joinedEpoch = 1,
            removedEpoch = null,
        )
        assertTrue(activeMember.isActive)

        val removedMember = GroupMember(
            groupId = com.example.twopchat.group.model.ConversationId("group-1"),
            userId = UserId("user-2"),
            role = GroupRole.BANNED,
            permissions = com.example.twopchat.group.model.GroupPermissionSet.NONE,
            joinedEpoch = 1,
            removedEpoch = 2,
        )
        assertFalse(removedMember.isActive)
        assertEquals(2L, removedMember.removedEpoch)
    }
}
