package com.example.twopchat.group.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupControlStorageInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "twopchat-group-control-test.db"
    private lateinit var database: GroupDatabaseHelper

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        database = GroupDatabaseHelper(context, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun incomingInviteCanBeStoredBeforeGroupExists() {
        database.savePendingInvite(
            StoredPendingInvite(
                inviteId = "invite-1",
                groupId = "not-created-yet",
                inviterDeviceId = "owner",
                token = "{}".toByteArray(),
                expiresAtMs = System.currentTimeMillis() + 60_000,
            ),
        )

        assertEquals("invite-1", database.listPendingInvites().single().inviteId)
    }

    @Test
    fun compareAndSwapControlHeadRejectsStaleFork() {
        createGroup()

        assertTrue(database.advanceControlHead(GROUP_ID, null, "control-a"))
        assertFalse(database.advanceControlHead(GROUP_ID, null, "control-b"))
        assertTrue(database.advanceControlHead(GROUP_ID, "control-a", "control-c", currentEpoch = 2))
        assertEquals("control-c", database.getGroup(GROUP_ID)?.controlHead)
        assertEquals(2L, database.getGroup(GROUP_ID)?.currentEpoch)
    }

    @Test
    fun updatingGroupDoesNotCascadeDeleteEventLog() {
        createGroup()
        database.ingestEvent(event("event-1", 1, 1), countAsUnread = false)

        val group = requireNotNull(database.getGroup(GROUP_ID))
        database.upsertGroup(group.copy(title = "Renamed", metadataVersion = 2))

        assertEquals("Renamed", database.getGroup(GROUP_ID)?.title)
        assertNotNull(database.getEvent(GROUP_ID, "event-1"))
        assertEquals(1, database.loadTimeline(GROUP_ID, 20).size)
    }

    @Test
    fun nonEventControlOutboxCanBeDurablyAcknowledged() {
        createGroup()
        val task = StoredOutboxTask(
            taskId = "invite-task",
            groupId = GROUP_ID,
            eventId = "invite:123",
            recipientDeviceId = "remote-device",
            payload = "{}".toByteArray(),
        )

        assertTrue(database.enqueueOutbox(task))
        assertTrue(database.acknowledgeOutbox(task.taskId))
        assertEquals(1, database.listReceipts(GROUP_ID, task.eventId).size)
    }

    @Test
    fun epochFilteredSyncSkipsHistoryHiddenFromNewMember() {
        createGroup()
        database.ingestEvent(event("old", 1, 1), countAsUnread = false)
        database.ingestEvent(event("new", 2, 2), countAsUnread = false)

        val visible = database.listEventsSince(
            GROUP_ID,
            "owner-device",
            afterAuthorSeq = 0,
            limit = 20,
            minimumEpoch = 2,
        )

        assertEquals(listOf("new"), visible.map { it.eventId })
    }

    @Test
    fun contiguousCursorDoesNotSkipDelayedGap() {
        createGroup()
        database.ingestEvent(event("second", 1, 2), countAsUnread = false)
        assertEquals(0L, database.contiguousAuthorSequence(GROUP_ID, "owner-device"))

        database.ingestEvent(event("first", 1, 1), countAsUnread = false)
        assertEquals(2L, database.contiguousAuthorSequence(GROUP_ID, "owner-device"))
        // A snapshot baseline is independent from older local rows.
        database.ingestEvent(event("snapshot-101", 2, 101), false)
        database.ingestEvent(event("snapshot-102", 2, 102), false)
        assertEquals(
            102L,
            database.contiguousAuthorSequence(GROUP_ID, "owner-device", 100),
        )
    }

    @Test
    fun recoveryPagesAuthorFeedWithOriginalWirePayload() {
        createGroup()
        database.ingestEvent(
            event("wire-1", 1, 1, "frame-1".toByteArray()),
            countAsUnread = false,
        )
        database.ingestEvent(event("projection-only", 1, 2), countAsUnread = false)
        database.ingestEvent(
            event("wire-3", 1, 3, "frame-3".toByteArray()),
            countAsUnread = false,
        )

        val first = database.listRecoverableAuthorEvents(
            GROUP_ID,
            "owner-device",
            afterAuthorSeq = 0,
            limit = 1,
        )
        val second = database.listRecoverableAuthorEvents(
            GROUP_ID,
            "owner-device",
            afterAuthorSeq = first.single().authorSeq,
            limit = 1,
        )

        assertEquals(listOf(1L), first.map { it.authorSeq })
        assertEquals(listOf(3L), second.map { it.authorSeq })
        assertArrayEquals("frame-1".toByteArray(), first.single().payload)
        assertArrayEquals("frame-3".toByteArray(), second.single().payload)
        assertTrue(
            database.listRecoverableAuthorEvents(
                GROUP_ID,
                "owner-device",
                afterAuthorSeq = 3,
                limit = 10,
            ).isEmpty(),
        )
    }

    @Test
    fun outboxExistenceUsesEventAndRecipientIdentity() {
        createGroup()
        val task = StoredOutboxTask(
            taskId = "recovery-task",
            groupId = GROUP_ID,
            eventId = "event-for-recovery",
            recipientDeviceId = "remote-device",
            payload = "{}".toByteArray(),
        )

        assertFalse(database.hasOutboxTask(GROUP_ID, task.eventId, task.recipientDeviceId))
        assertTrue(database.enqueueOutbox(task))
        assertTrue(database.hasOutboxTask(GROUP_ID, task.eventId, task.recipientDeviceId))
        assertFalse(database.hasOutboxTask(GROUP_ID, task.eventId, "another-device"))
        assertTrue(database.acknowledgeOutbox(task.taskId))
        assertTrue(database.hasOutboxTask(GROUP_ID, task.eventId, task.recipientDeviceId))
    }

    @Test
    fun controlMutationCommitsAtomicallyAndStaleCasHasNoSideEffects() {
        createGroup()
        val acceptedControl = event(
            "control-accepted",
            epoch = 1,
            sequence = 1,
            payload = "{\"kind\":\"control\"}".toByteArray(),
        ).copy(kind = StoredGroupEventKind.CONTROL.name)
        database.ingestEvent(acceptedControl, countAsUnread = false)
        val remote = requireNotNull(database.getMember(GROUP_ID, "remote-device"))

        assertTrue(
            database.applyControlMutation(
                groupId = GROUP_ID,
                expectedHead = null,
                newHead = acceptedControl.eventId,
                currentEpoch = 2,
                title = "Committed title",
                members = listOf(remote.copy(role = "MODERATOR", permissions = 7)),
                updatedAtMs = 200,
            ),
        )
        assertEquals(acceptedControl.eventId, database.getCanonicalControlEvent(GROUP_ID)?.eventId)
        assertEquals(2L, database.getGroup(GROUP_ID)?.currentEpoch)
        assertEquals("Committed title", database.getGroup(GROUP_ID)?.title)
        assertEquals("MODERATOR", database.getMember(GROUP_ID, "remote-device")?.role)

        assertFalse(
            database.applyControlMutation(
                groupId = GROUP_ID,
                expectedHead = null,
                newHead = "stale-control",
                currentEpoch = 9,
                title = "Must roll back",
                members = listOf(remote.copy(role = "BANNED", permissions = 0)),
                updatedAtMs = 300,
            ),
        )
        assertEquals(acceptedControl.eventId, database.getCanonicalControlEvent(GROUP_ID)?.eventId)
        assertEquals(2L, database.getGroup(GROUP_ID)?.currentEpoch)
        assertEquals("Committed title", database.getGroup(GROUP_ID)?.title)
        assertEquals("MODERATOR", database.getMember(GROUP_ID, "remote-device")?.role)
    }

    @Test
    fun eventMaterializationUnreadAndOutboxCommitTogether() {
        createGroup()
        val incoming = event(
            "atomic-event",
            epoch = 1,
            sequence = 1,
            payload = "{\"type\":\"group_event_v1\"}".toByteArray(),
        ).copy(authorDeviceId = "remote-device", body = "Atomic message")
        val tasks = listOf(
            outboxTask("atomic-task-owner", incoming, "owner-device"),
            outboxTask("atomic-task-remote", incoming, "remote-device"),
        )

        assertTrue(database.ingestEventWithOutbox(incoming, countAsUnread = true, tasks))

        assertEquals("Atomic message", database.loadMessage(GROUP_ID, incoming.eventId)?.body)
        assertEquals(1, database.getGroup(GROUP_ID)?.unreadCount)
        assertTrue(database.hasOutboxTask(GROUP_ID, incoming.eventId, "owner-device"))
        assertTrue(database.hasOutboxTask(GROUP_ID, incoming.eventId, "remote-device"))
    }

    @Test
    fun eventConflictRollsBackEveryOutboxTask() {
        createGroup()
        val original = event(
            "original-event",
            epoch = 1,
            sequence = 1,
            payload = "{}".toByteArray(),
        )
        assertTrue(database.ingestEvent(original, countAsUnread = false))

        val exactDuplicateTasks = listOf(
            outboxTask("must-not-exist-1", original, "owner-device"),
            outboxTask("must-not-exist-2", original, "remote-device"),
        )
        assertFalse(
            database.ingestEventWithOutbox(
                original,
                countAsUnread = true,
                tasks = exactDuplicateTasks,
            ),
        )
        assertFalse(database.hasOutboxTask(GROUP_ID, original.eventId, "owner-device"))
        assertFalse(database.hasOutboxTask(GROUP_ID, original.eventId, "remote-device"))
        assertEquals(0, database.getGroup(GROUP_ID)?.unreadCount)

        val sequenceConflict = event(
            "equivocation",
            epoch = 1,
            sequence = 1,
            payload = "{}".toByteArray(),
        )
        assertFalse(
            database.ingestEventWithOutbox(
                sequenceConflict,
                countAsUnread = false,
                tasks = listOf(
                    outboxTask("must-not-exist-3", sequenceConflict, "remote-device"),
                ),
            ),
        )
        assertFalse(
            database.hasOutboxTask(GROUP_ID, sequenceConflict.eventId, "remote-device"),
        )
        assertEquals(null, database.getEvent(GROUP_ID, sequenceConflict.eventId))
    }

    @Test
    fun pinProjectionUsesDeterministicEventOrderUnderDelayedDelivery() {
        createGroup()
        val newestPin = event("pin-new", 1, 2).copy(
            kind = "PIN",
            targetEventId = "message-new",
            hlcPhysicalMs = 200,
        )
        val delayedOldUnpin = event("unpin-old", 1, 3).copy(
            kind = "UNPIN",
            targetEventId = "message-old",
            hlcPhysicalMs = 100,
        )
        val finalUnpin = event("unpin-final", 1, 4).copy(
            kind = "UNPIN",
            targetEventId = "message-new",
            hlcPhysicalMs = 300,
        )

        assertTrue(database.ingestEvent(newestPin, false))
        assertTrue(database.ingestEvent(delayedOldUnpin, false))
        assertEquals("message-new", database.getGroup(GROUP_ID)?.pinnedEventId)
        assertTrue(database.ingestEvent(finalUnpin, false))
        assertEquals(null, database.getGroup(GROUP_ID)?.pinnedEventId)
    }

    @Test
    fun readReceiptIsMaterializedInsideEventTransaction() {
        createGroup()
        val receipt = event("read-event", 1, 1).copy(
            authorDeviceId = "remote-device",
            kind = "READ_RECEIPT",
            targetEventId = "message-1",
            body = "{}",
            payload = "{}".toByteArray(),
        )

        assertTrue(
            database.ingestEventWithOutbox(
                receipt,
                countAsUnread = false,
                tasks = emptyList(),
            ),
        )
        assertEquals(
            listOf("remote-device"),
            database.listReceipts(GROUP_ID, "message-1").map { it.recipientDeviceId },
        )
    }

    @Test
    fun rosterPageAndNewestInviteAreAppliedAtomically() {
        createGroup()
        val owner = requireNotNull(database.getMember(GROUP_ID, "owner-device"))
        val remote = requireNotNull(database.getMember(GROUP_ID, "remote-device"))
        assertEquals(
            2,
            database.upsertMembers(
                listOf(
                    owner.copy(displayName = "Updated owner"),
                    remote.copy(role = "MODERATOR", permissions = 7),
                ),
            ),
        )
        assertEquals("Updated owner", database.getMember(GROUP_ID, "owner-device")?.displayName)
        assertEquals("MODERATOR", database.getMember(GROUP_ID, "remote-device")?.role)

        val newer = StoredPendingInvite(
            inviteId = "invite-new",
            groupId = "pending-group",
            inviterDeviceId = "pending-owner",
            token = "new".toByteArray(),
            expiresAtMs = 10_000,
            createdAtMs = 200,
        )
        val delayedOlder = newer.copy(
            inviteId = "invite-old",
            token = "old".toByteArray(),
            createdAtMs = 100,
        )
        database.savePendingInvite(newer)
        database.savePendingInvite(delayedOlder)
        val stored = database.listPendingInvites(nowMs = 0).single()
        assertEquals("invite-new", stored.inviteId)
        assertArrayEquals("new".toByteArray(), stored.token)
    }

    @Test
    fun reactionProjectionConvergesWhenMutationsArriveOutOfOrder() {
        createGroup()
        val newestAdd = event("reaction-add", 1, 2).copy(
            kind = "REACTION_ADD",
            body = "👍",
            targetEventId = "message-1",
            hlcPhysicalMs = 200,
        )
        val delayedRemove = event("reaction-remove-old", 1, 3).copy(
            kind = "REACTION_REMOVE",
            body = "👍",
            targetEventId = "message-1",
            hlcPhysicalMs = 100,
        )
        val newestRemove = event("reaction-remove-new", 1, 4).copy(
            kind = "REACTION_REMOVE",
            body = "👍",
            targetEventId = "message-1",
            hlcPhysicalMs = 300,
        )

        assertTrue(database.ingestEvent(newestAdd, false))
        assertTrue(database.ingestEvent(delayedRemove, false))
        assertEquals(
            1,
            database.listActiveReactionKeys(GROUP_ID, setOf("message-1")).size,
        )
        assertTrue(database.ingestEvent(newestRemove, false))
        assertTrue(
            database.listActiveReactionKeys(GROUP_ID, setOf("message-1")).isEmpty(),
        )
    }

    private fun createGroup() {
        database.createGroup(
            StoredGroup(
                groupId = GROUP_ID,
                title = "Control group",
                localDeviceId = "owner-device",
                ownerDeviceId = "owner-device",
                currentEpoch = 1,
            ),
            listOf(
                StoredGroupMember(
                    groupId = GROUP_ID,
                    deviceId = "owner-device",
                    accountId = "owner",
                    displayName = "Owner",
                    role = "OWNER",
                    permissions = 0,
                    joinedEpoch = 1,
                ),
                StoredGroupMember(
                    groupId = GROUP_ID,
                    deviceId = "remote-device",
                    accountId = "remote",
                    displayName = "Remote",
                    role = "MEMBER",
                    permissions = 0,
                    joinedEpoch = 1,
                ),
            ),
        )
    }

    private fun event(
        id: String,
        epoch: Long,
        sequence: Long,
        payload: ByteArray? = null,
    ) = StoredGroupEvent(
        groupId = GROUP_ID,
        eventId = id,
        epoch = epoch,
        authorDeviceId = "owner-device",
        authorSeq = sequence,
        hlcPhysicalMs = sequence,
        hlcLogical = 0,
        kind = StoredGroupEventKind.MESSAGE.name,
        body = id,
        payload = payload,
    )

    private fun outboxTask(
        taskId: String,
        event: StoredGroupEvent,
        recipientDeviceId: String,
    ) = StoredOutboxTask(
        taskId = taskId,
        groupId = event.groupId,
        eventId = event.eventId,
        recipientDeviceId = recipientDeviceId,
        payload = requireNotNull(event.payload) { "test event must retain a wire payload" },
    )

    private companion object {
        const val GROUP_ID = "group-control-test"
    }
}
