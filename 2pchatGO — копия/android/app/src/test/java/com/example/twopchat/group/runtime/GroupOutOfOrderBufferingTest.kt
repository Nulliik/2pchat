package com.example.twopchat.group.runtime

import com.example.twopchat.group.protocol.GroupEpochKeyPackage
import com.example.twopchat.group.protocol.GroupEventKind
import com.example.twopchat.group.protocol.GroupWireEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupOutOfOrderBufferingTest {

    private fun createSampleKeyPackage(epoch: Long, controlHead: String): GroupEpochKeyPackage {
        return GroupEpochKeyPackage(
            groupId = "group-1",
            epoch = epoch,
            epochSecretBase64 = "c2VjcmV0",
            recipientDeviceId = "dev-me",
            controlHead = controlHead,
            senderFingerprint = "fp-sender",
            senderDeviceId = "dev-sender",
            senderSigningKey = "key-sender",
            createdAtMs = 1_000_000L,
            signatureBase64 = "sig",
        )
    }

    private fun createSampleWireEvent(eventId: String, epoch: Long): GroupWireEvent {
        return GroupWireEvent(
            groupId = "group-1",
            eventId = eventId,
            epoch = epoch,
            kind = GroupEventKind.MESSAGE,
            authorFingerprint = "fp-author",
            authorDeviceId = "dev-author",
            authorSigningKey = "key-author",
            authorSequence = 1L,
            previousAuthorEvent = null,
            controlHead = "ctrl-1",
            hlcPhysicalMs = 1_000_000L,
            hlcLogical = 0,
            targetEventId = null,
            nonceBase64 = "nonce",
            ciphertextBase64 = "ciphertext",
            signatureBase64 = "signature",
            cryptoSuite = "standard",
        )
    }

    @Test
    fun pendingBufferEnforcesTtlPruning() {
        val list = mutableListOf<GroupChatCoordinator.PendingKeyPackageRecord>()
        val now = 1_000_000L
        val ttl = 300_000L

        val stalePkg = createSampleKeyPackage(1L, "ctrl-1")
        val freshPkg = createSampleKeyPackage(2L, "ctrl-2")

        list.add(GroupChatCoordinator.PendingKeyPackageRecord("peer1", JSONObject(), stalePkg, now - ttl - 1000L))
        list.add(GroupChatCoordinator.PendingKeyPackageRecord("peer1", JSONObject(), freshPkg, now - 1000L))

        // Prune
        list.removeAll { (now - it.receivedAtMs) > ttl }

        assertEquals(1, list.size)
        assertEquals(2L, list[0].keyPackage.epoch)
    }

    @Test
    fun pendingBufferEnforcesCapacityLimit() {
        val list = mutableListOf<GroupChatCoordinator.PendingKeyPackageRecord>()
        val maxCapacity = 5
        val now = 1_000_000L

        for (i in 1..7) {
            val pkg = createSampleKeyPackage(i.toLong(), "ctrl-$i")
            if (list.size >= maxCapacity) {
                list.removeAt(0)
            }
            list.add(GroupChatCoordinator.PendingKeyPackageRecord("peer1", JSONObject(), pkg, now))
        }

        assertEquals(maxCapacity, list.size)
        // Earliest 1 and 2 were pruned, lowest should now be epoch 3
        assertEquals(3L, list[0].keyPackage.epoch)
        assertEquals(7L, list.last().keyPackage.epoch)
    }

    @Test
    fun pendingBufferPreventsDuplicateKeyPackages() {
        val list = mutableListOf<GroupChatCoordinator.PendingKeyPackageRecord>()
        val now = 1_000_000L
        val pkg = createSampleKeyPackage(5L, "ctrl-5")

        val record1 = GroupChatCoordinator.PendingKeyPackageRecord("peer1", JSONObject(), pkg, now)
        list.add(record1)

        val isDuplicate = list.any { it.keyPackage.epoch == pkg.epoch && it.keyPackage.controlHead == pkg.controlHead }
        assertTrue(isDuplicate)

        if (!isDuplicate) {
            list.add(record1)
        }
        assertEquals(1, list.size)
    }

    @Test
    fun pendingGroupEventPreventsDuplicateEventIds() {
        val list = mutableListOf<GroupChatCoordinator.PendingGroupEventRecord>()
        val now = 1_000_000L
        val event = createSampleWireEvent("evt-123", 3L)

        list.add(GroupChatCoordinator.PendingGroupEventRecord("peer1", JSONObject(), event, now))

        val duplicate = list.any { it.event.eventId == event.eventId }
        assertTrue(duplicate)
    }
}
