package com.example.twopchat.group.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupModelsTest {
    @Test
    fun permissionSetRoundTripsAllKnownBits() {
        val all = GroupPermissionSet.of(*GroupPermission.entries.toTypedArray())

        assertEquals(GroupPermissionSet.ALL, all)
        assertEquals(GroupPermission.entries.toSet(), all.asSet())
        GroupPermission.entries.forEach { permission ->
            assertTrue(permission.name, permission in all)
            assertFalse(permission.name, permission in (all - permission))
        }
    }

    @Test
    fun deviceRevocationEpochIsExclusive() {
        val device = DeviceIdentity(
            id = DeviceId("device"),
            userId = UserId("user"),
            credential = "credential",
            feedPublicKey = "feed-key",
            revokedAtEpoch = 8L,
        )

        assertTrue(device.isActiveAt(7L))
        assertFalse(device.isActiveAt(8L))
        assertFalse(device.isActiveAt(9L))
    }

    @Test
    fun hlcSortsByPhysicalLogicalThenNode() {
        val clocks = listOf(
            HybridLogicalClock(11L, 0, "a"),
            HybridLogicalClock(10L, 1, "b"),
            HybridLogicalClock(10L, 1, "a"),
            HybridLogicalClock(10L, 0, "z"),
        )

        assertEquals(
            listOf(
                HybridLogicalClock(10L, 0, "z"),
                HybridLogicalClock(10L, 1, "a"),
                HybridLogicalClock(10L, 1, "b"),
                HybridLogicalClock(11L, 0, "a"),
            ),
            clocks.sorted(),
        )
    }

    @Test
    fun localTickSurvivesClockRollbackAndResetsAfterClockAdvance() {
        val current = HybridLogicalClock(1_000L, 7, "local")

        assertEquals(HybridLogicalClock(1_000L, 8, "local"), current.tick(900L))
        assertEquals(HybridLogicalClock(1_001L, 0, "local"), current.tick(1_001L))
    }

    @Test
    fun observeImplementsAllHlcMergeBranches() {
        val current = HybridLogicalClock(100L, 3, "local")

        assertEquals(
            HybridLogicalClock(100L, 8, "local"),
            current.observe(HybridLogicalClock(100L, 7, "remote"), nowMs = 99L),
        )
        assertEquals(
            HybridLogicalClock(100L, 4, "local"),
            current.observe(HybridLogicalClock(90L, 20, "remote"), nowMs = 99L),
        )
        assertEquals(
            HybridLogicalClock(120L, 6, "local"),
            current.observe(HybridLogicalClock(120L, 5, "remote"), nowMs = 110L),
        )
        assertEquals(
            HybridLogicalClock(130L, 0, "local"),
            current.observe(HybridLogicalClock(120L, 5, "remote"), nowMs = 130L),
        )
    }

    @Test
    fun hlcLogicalLimitMatchesWireFormatAndTickRollsOverSafely() {
        assertEquals(1_000_000, HybridLogicalClock.MAX_LOGICAL_COUNTER)

        val exhausted = HybridLogicalClock(
            physicalTimeMs = 1_000L,
            logicalCounter = HybridLogicalClock.MAX_LOGICAL_COUNTER,
            nodeId = "local",
        )

        assertEquals(
            HybridLogicalClock(1_001L, 0, "local"),
            exhausted.tick(nowMs = 900L),
        )
    }

    @Test
    fun hlcObserveRollsOverWhenMergedLogicalCounterIsExhausted() {
        val local = HybridLogicalClock(
            physicalTimeMs = 1_000L,
            logicalCounter = HybridLogicalClock.MAX_LOGICAL_COUNTER - 1,
            nodeId = "local",
        )
        val remote = HybridLogicalClock(
            physicalTimeMs = 1_000L,
            logicalCounter = HybridLogicalClock.MAX_LOGICAL_COUNTER,
            nodeId = "remote",
        )

        assertEquals(
            HybridLogicalClock(1_001L, 0, "local"),
            local.observe(remote, nowMs = 900L),
        )
    }

    @Test
    fun eventOrderProvidesStableTotalOrderForEqualHlc() {
        val timestamp = HybridLogicalClock(100L, 1, "hlc-node")
        val orders = listOf(
            order(timestamp, "device-b", 1L, "event-a"),
            order(timestamp, "device-a", 2L, "event-a"),
            order(timestamp, "device-a", 1L, "event-b"),
            order(timestamp, "device-a", 1L, "event-a"),
        )

        assertEquals(
            listOf(
                order(timestamp, "device-a", 1L, "event-a"),
                order(timestamp, "device-a", 1L, "event-b"),
                order(timestamp, "device-a", 2L, "event-a"),
                order(timestamp, "device-b", 1L, "event-a"),
            ),
            orders.sorted(),
        )
    }

    @Test
    fun groupEventExposesCanonicalOrderKey() {
        val event = GroupEvent(
            groupId = ConversationId("group"),
            id = EventId("event"),
            authorUserId = UserId("user"),
            authorDeviceId = DeviceId("device"),
            senderSequence = 42L,
            epoch = 3L,
            timestamp = HybridLogicalClock(1_000L, 4, "device"),
            kind = GroupEventKind.TEXT_MESSAGE,
            state = GroupEventState.QUEUED,
            payloadDigest = "sha256:digest",
        )

        assertEquals(
            GroupEventOrder(
                timestamp = event.timestamp,
                authorDeviceId = event.authorDeviceId,
                senderSequence = 42L,
                eventId = event.id,
            ),
            event.order,
        )
    }

    private fun order(
        timestamp: HybridLogicalClock,
        device: String,
        sequence: Long,
        event: String,
    ) = GroupEventOrder(timestamp, DeviceId(device), sequence, EventId(event))
}
