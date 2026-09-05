package com.example.twopchat.group.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.security.SecureStorage
import net.zetetic.database.sqlcipher.SQLiteDatabase
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
class GroupDatabaseHelperInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "twopchat-groups-instrumented.db"
    private lateinit var helper: GroupDatabaseHelper

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        helper = GroupDatabaseHelper(context, databaseName)
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun freshDatabaseContainsNormalizedSchema() {
        val expected = setOf(
            "groups",
            "group_members",
            "group_membership_intervals",
            "group_epoch_keys",
            "group_events",
            "group_messages",
            "outbox_tasks",
            "receipts",
            "pending_invites",
            "sync_cursors",
            "owner_lineage_certificates",
            "roster_snapshot_pages",
        )

        val actual = mutableSetOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) actual += cursor.getString(0)
        }

        assertTrue(actual.containsAll(expected))
        assertEquals(GroupDatabaseHelper.DATABASE_VERSION, helper.readableDatabase.version)
        helper.readableDatabase.rawQuery(
            "SELECT tbl_name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf("idx_group_receipts_lookup"),
        ).use { cursor ->
            assertTrue("receipt lookup index was not created", cursor.moveToFirst())
            assertEquals("receipts", cursor.getString(0))
        }
    }

    @Test
    fun postingPolicyPersistsAsControlMetadata() {
        createGroup()

        assertTrue(
            helper.applyControlMutation(
                groupId = GROUP_ID,
                expectedHead = null,
                newHead = "policy-control",
                adminOnlyPosting = true,
            ),
        )

        assertTrue(checkNotNull(helper.getGroup(GROUP_ID)).adminOnlyPosting)
    }

    @Test
    fun duplicateEventDoesNotDuplicateMessageOrUnreadCount() {
        createGroup()
        val event = messageEvent("event-1", "alice-device", 1, 10_000, "hello")

        assertTrue(helper.ingestEvent(event, countAsUnread = true))
        assertFalse(helper.ingestEvent(event, countAsUnread = true))

        assertEquals(1, helper.loadTimeline(GROUP_ID, 20).size)
        assertEquals(1, helper.getGroup(GROUP_ID)?.unreadCount)
        assertEquals(1, helper.markRead(GROUP_ID, readAtMs = 20_000))
        assertEquals(0, helper.getGroup(GROUP_ID)?.unreadCount)
        assertFalse(helper.loadTimeline(GROUP_ID, 20).single().unread)
    }

    @Test
    fun outboxSurvivesCloseAndReopen() {
        createGroup()
        val event = messageEvent("event-outbox", "local-device", 1, 1_000, "queued")
        assertTrue(helper.ingestEvent(event, countAsUnread = false))
        assertTrue(
            helper.enqueueOutbox(
                StoredOutboxTask(
                    taskId = "task-bob",
                    groupId = GROUP_ID,
                    eventId = event.eventId,
                    recipientDeviceId = "bob-device",
                    payload = byteArrayOf(1, 2, 3),
                    nextAttemptMs = 5_000,
                ),
            ),
        )

        helper.close()
        helper = GroupDatabaseHelper(context, databaseName)

        val restored = helper.loadDueOutbox(nowMs = 5_000, limit = 10).single()
        assertEquals("task-bob", restored.taskId)
        assertTrue(restored.payload.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(helper.retryOutbox(restored.taskId, 8_000, "offline", updatedAtMs = 6_000))
        assertTrue(helper.loadDueOutbox(nowMs = 7_999, limit = 10).isEmpty())
        assertEquals(1, helper.loadDueOutbox(nowMs = 8_000, limit = 10).single().attempts)
        assertTrue(helper.acknowledgeOutbox(restored.taskId, acknowledgedAtMs = 9_000))
        assertTrue(helper.loadDueOutbox(nowMs = Long.MAX_VALUE, limit = 10).isEmpty())
        assertEquals(1, helper.listReceipts(GROUP_ID, event.eventId).size)
    }

    @Test
    fun reconnectMakesBackedOffRecipientFramesImmediatelyDue() {
        createGroup()
        val event = messageEvent("event-reconnect", "local-device", 1, 1_000, "queued")
        assertTrue(helper.ingestEvent(event, countAsUnread = false))
        assertTrue(
            helper.enqueueOutbox(
                StoredOutboxTask(
                    taskId = "task-reconnect",
                    groupId = GROUP_ID,
                    eventId = event.eventId,
                    recipientDeviceId = "bob-device",
                    payload = byteArrayOf(4, 5, 6),
                    nextAttemptMs = 5_000,
                ),
            ),
        )
        assertTrue(helper.retryOutbox("task-reconnect", 900_000, "peer offline", updatedAtMs = 6_000))
        assertTrue(helper.loadDueOutbox(nowMs = 10_000, limit = 10).isEmpty())

        assertEquals(1, helper.requeueOutboxForRecipient(GROUP_ID, "bob-device", nowMs = 10_000))
        val due = helper.loadDueOutbox(nowMs = 10_000, limit = 10).single()
        assertEquals("task-reconnect", due.taskId)
        assertEquals(1, due.attempts)
        assertEquals(null, due.lastError)
    }

    @Test
    fun delayedArrivalUsesDeterministicHlcOrderingAndCursorPaging() {
        createGroup()
        val later = messageEvent("event-later", "bob-device", 1, 20_000, "later")
        val earlier = messageEvent("event-earlier", "alice-device", 1, 10_000, "earlier")

        // Arrival order intentionally differs from logical timeline order.
        assertTrue(helper.ingestEvent(later, countAsUnread = true))
        assertTrue(helper.ingestEvent(earlier, countAsUnread = true))

        val firstPage = helper.loadTimeline(GROUP_ID, limit = 1)
        assertEquals(listOf("event-later"), firstPage.map { it.messageId })
        val secondPage = helper.loadTimeline(
            GROUP_ID,
            limit = 1,
            before = GroupTimelineCursor.from(firstPage.single()),
        )
        assertEquals(listOf("event-earlier"), secondPage.map { it.messageId })
    }

    @Test
    fun roleAndPermissionDataRoundTrips() {
        createGroup()
        val moderatorPermissions = 0b101101L
        helper.upsertMember(
            StoredGroupMember(
                groupId = GROUP_ID,
                deviceId = "moderator-device",
                accountId = "moderator-account",
                displayName = "Moderator",
                role = "MODERATOR",
                permissions = moderatorPermissions,
                status = "RESTRICTED",
                joinedEpoch = 3,
                removedEpoch = 9,
                createdAtMs = 100,
                updatedAtMs = 200,
                transportFingerprint = "sha256:moderator-transport",
                peerName = "moderator.onion",
                signingKeyBase64 = "bW9kZXJhdG9yLXNpZ25pbmcta2V5",
            ),
        )

        val restored = helper.getMember(GROUP_ID, "moderator-device")
        assertNotNull(restored)
        assertEquals("MODERATOR", restored?.role)
        assertEquals(moderatorPermissions, restored?.permissions)
        assertEquals("RESTRICTED", restored?.status)
        assertEquals(9L, restored?.removedEpoch)
        assertEquals("sha256:moderator-transport", restored?.transportFingerprint)
        assertEquals("moderator.onion", restored?.peerName)
        assertEquals("bW9kZXJhdG9yLXNpZ25pbmcta2V5", restored?.signingKeyBase64)
        assertEquals(2, helper.listMembers(GROUP_ID).size)
    }

    @Test
    fun ownerLineagePersistsInSequenceAcrossDatabaseReopen() {
        createGroup()
        val lineage = listOf(
            StoredOwnerLineageCertificate(
                groupId = GROUP_ID,
                sequence = 1,
                transitionId = "transition-1",
                payload = "signed-certificate-1".toByteArray(),
            ),
            StoredOwnerLineageCertificate(
                groupId = GROUP_ID,
                sequence = 2,
                transitionId = "transition-2",
                payload = "signed-certificate-2".toByteArray(),
            ),
        )
        helper.replaceOwnerLineage(GROUP_ID, lineage)

        helper.close()
        helper = GroupDatabaseHelper(context, databaseName)

        val restored = helper.listOwnerLineage(GROUP_ID)
        assertEquals(listOf(1, 2), restored.map { it.sequence })
        assertEquals(listOf("transition-1", "transition-2"), restored.map { it.transitionId })
        assertArrayEquals(lineage[0].payload, restored[0].payload)
        assertArrayEquals(lineage[1].payload, restored[1].payload)
    }

    @Test
    fun multiPageRosterSnapshotSurvivesReopenAndAppliesAtomically() {
        createGroup()
        assertTrue(
            helper.advanceControlHead(
                groupId = GROUP_ID,
                expectedHead = null,
                newHead = "snapshot-head",
                currentEpoch = 2L,
                updatedAtMs = 2L,
            ),
        )
        val createdAt = 10_000L
        val page0 = StoredRosterSnapshotPage(
            groupId = GROUP_ID,
            controlHead = "snapshot-head",
            epoch = 2L,
            pageIndex = 0,
            totalPages = 2,
            snapshotCreatedAtMs = createdAt,
            payload = "signed-page-0".toByteArray(),
        )
        val page1 = page0.copy(
            pageIndex = 1,
            payload = "signed-page-1".toByteArray(),
        )
        assertTrue(helper.storeRosterSnapshotPage(page1))
        assertTrue(helper.storeRosterSnapshotPage(page0))
        assertTrue(helper.storeRosterSnapshotPage(page0))
        assertFalse(
            helper.storeRosterSnapshotPage(
                page0.copy(payload = "conflicting-page".toByteArray()),
            ),
        )

        helper.close()
        helper = GroupDatabaseHelper(context, databaseName)

        val restoredPages = helper.listRosterSnapshotPages(GROUP_ID, "snapshot-head")
        assertEquals(listOf(0, 1), restoredPages.map { it.pageIndex })
        assertArrayEquals(page0.payload, restoredPages[0].payload)
        assertArrayEquals(page1.payload, restoredPages[1].payload)

        val snapshotMembers = listOf(
            checkNotNull(helper.getMember(GROUP_ID, "local-device")).copy(
                joinedEpoch = 1L,
                updatedAtMs = createdAt,
            ),
            StoredGroupMember(
                groupId = GROUP_ID,
                deviceId = "joined-device",
                accountId = "joined-account",
                displayName = "Joined member",
                transportFingerprint = "joined-account",
                peerName = "joined.peer",
                signingKeyBase64 = "joined-signing-key",
                role = "MEMBER",
                permissions = 7L,
                status = "ACTIVE",
                joinedEpoch = 2L,
                createdAtMs = createdAt,
                updatedAtMs = createdAt,
            ),
        )

        assertFalse(
            helper.applyCompleteRosterSnapshot(
                groupId = GROUP_ID,
                expectedControlHead = "snapshot-head",
                expectedEpoch = 3L,
                members = snapshotMembers,
                authorSequences = mapOf(
                    "local-device" to 4L,
                    "joined-device" to 7L,
                ),
            ),
        )
        assertEquals(null, helper.getMember(GROUP_ID, "joined-device"))
        assertEquals(null, helper.getSyncCursor(GROUP_ID, "joined-device"))
        assertEquals(
            2,
            helper.listRosterSnapshotPages(GROUP_ID, "snapshot-head").size,
        )

        assertTrue(
            helper.applyCompleteRosterSnapshot(
                groupId = GROUP_ID,
                expectedControlHead = "snapshot-head",
                expectedEpoch = 2L,
                members = snapshotMembers,
                authorSequences = mapOf(
                    "local-device" to 4L,
                    "joined-device" to 7L,
                ),
            ),
        )
        assertEquals("ACTIVE", helper.getMember(GROUP_ID, "joined-device")?.status)
        assertEquals(4L, helper.getSyncCursor(GROUP_ID, "local-device")?.lastAuthorSeq)
        assertEquals(7L, helper.getSyncCursor(GROUP_ID, "joined-device")?.lastAuthorSeq)
        assertTrue(helper.listRosterSnapshotPages(GROUP_ID, "snapshot-head").isEmpty())
    }

    @Test
    fun version2DatabaseMigratesRecoveryTablesWithoutLosingExistingData() {
        helper.close()
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        System.loadLibrary("sqlcipher")
        val legacy = SQLiteDatabase.openOrCreateDatabase(
            file.absolutePath,
            SecureStorage.getOrGenerateDbPassphrase(context),
            null,
            null,
            null,
        )
        legacy.execSQL("CREATE TABLE groups(group_id TEXT PRIMARY KEY)")
        legacy.execSQL("CREATE TABLE migration_marker(value TEXT NOT NULL)")
        legacy.execSQL("INSERT INTO migration_marker(value) VALUES ('preserved')")
        legacy.version = 2
        legacy.close()

        helper = GroupDatabaseHelper(context, databaseName)

        assertEquals(GroupDatabaseHelper.DATABASE_VERSION, helper.readableDatabase.version)
        val tables = mutableSetOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        assertTrue("owner lineage table was not migrated", "owner_lineage_certificates" in tables)
        assertTrue("snapshot page table was not migrated", "roster_snapshot_pages" in tables)
        val groupColumns = mutableSetOf<String>()
        helper.readableDatabase.rawQuery("PRAGMA table_info(groups)", emptyArray()).use { cursor ->
            while (cursor.moveToNext()) groupColumns += cursor.getString(1)
        }
        assertTrue("posting policy column was not migrated", "admin_only_posting" in groupColumns)
        helper.readableDatabase.rawQuery(
            "SELECT value FROM migration_marker",
            emptyArray(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("preserved", cursor.getString(0))
        }
    }

    @Test
    fun testMembershipIntervalsMaintainedAcrossKickAndReadd() {
        createGroup()
        val bob = StoredGroupMember(
            groupId = GROUP_ID,
            deviceId = "bob-device",
            accountId = "bob-account",
            displayName = "Bob",
            role = "MEMBER",
            permissions = 0L,
            status = "ACTIVE",
            joinedEpoch = 1,
            removedEpoch = null,
            createdAtMs = 100,
            updatedAtMs = 100,
        )
        // Add Bob at epoch 1
        helper.applyControlMutation(
            groupId = GROUP_ID,
            expectedHead = null,
            newHead = "c1",
            updatedMembers = listOf(bob),
        )
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 1))
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 2))

        // Kick Bob at epoch 3 (boundary semantics: [1, 3))
        val bobRemoved = bob.copy(status = "LEFT", removedEpoch = 3, updatedAtMs = 200)
        helper.applyControlMutation(
            groupId = GROUP_ID,
            expectedHead = "c1",
            newHead = "c2",
            currentEpoch = 3,
            updatedMembers = listOf(bobRemoved),
        )
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 1))
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 2))
        assertFalse(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 3))
        assertFalse(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 4))

        // Re-add Bob at epoch 5 ([1, 3) and [5, null))
        val bobReadded = bob.copy(status = "ACTIVE", joinedEpoch = 5, removedEpoch = null, updatedAtMs = 300)
        helper.applyControlMutation(
            groupId = GROUP_ID,
            expectedHead = "c2",
            newHead = "c3",
            currentEpoch = 5,
            updatedMembers = listOf(bobReadded),
        )
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 1))
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 2))
        assertFalse(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 3))
        assertFalse(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 4))
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 5))
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 6))

        val intervals = helper.listMembershipIntervals(GROUP_ID, "bob-device")
        assertEquals(2, intervals.size)
        assertEquals(1L, intervals[0].joinedEpoch)
        assertEquals(3L, intervals[0].removedEpoch)
        assertEquals(5L, intervals[1].joinedEpoch)
        assertEquals(null, intervals[1].removedEpoch)
    }

    @Test
    fun testRebuildProjectionsRebuildsIntervalsIdentically() {
        createGroup()
        val bob = StoredGroupMember(
            groupId = GROUP_ID,
            deviceId = "bob-device",
            accountId = "bob-account",
            displayName = "Bob",
            role = "MEMBER",
            permissions = 0L,
            status = "ACTIVE",
            joinedEpoch = 1,
            removedEpoch = null,
            createdAtMs = 100,
            updatedAtMs = 100,
        )
        helper.applyControlMutation(
            groupId = GROUP_ID,
            expectedHead = null,
            newHead = "c1",
            updatedMembers = listOf(bob),
        )
        val bobRemoved = bob.copy(status = "LEFT", removedEpoch = 3, updatedAtMs = 200)
        helper.applyControlMutation(
            groupId = GROUP_ID,
            expectedHead = "c1",
            newHead = "c2",
            currentEpoch = 3,
            updatedMembers = listOf(bobRemoved),
        )
        val bobReadded = bob.copy(status = "ACTIVE", joinedEpoch = 5, removedEpoch = null, updatedAtMs = 300)
        helper.applyControlMutation(
            groupId = GROUP_ID,
            expectedHead = "c2",
            newHead = "c3",
            currentEpoch = 5,
            updatedMembers = listOf(bobReadded),
        )

        // Store control events in group_events so rebuildMembershipIntervals can replay
        val c1Event = StoredGroupEvent(
            groupId = GROUP_ID,
            eventId = "c1",
            epoch = 1,
            authorDeviceId = "local-device",
            authorSeq = 1,
            hlcPhysicalMs = 100,
            hlcLogical = 0,
            kind = StoredGroupEventKind.CONTROL_MUTATION.name,
            body = """{"kind":"MEMBER_ADDED","target_member_device_id":"bob-device","epoch":1}""",
            createdAtMs = 100,
            receivedAtMs = 100,
        )
        val c2Event = StoredGroupEvent(
            groupId = GROUP_ID,
            eventId = "c2",
            epoch = 3,
            authorDeviceId = "local-device",
            authorSeq = 2,
            hlcPhysicalMs = 200,
            hlcLogical = 0,
            kind = StoredGroupEventKind.CONTROL_MUTATION.name,
            body = """{"kind":"MEMBER_REMOVED","target_member_device_id":"bob-device","epoch":3}""",
            createdAtMs = 200,
            receivedAtMs = 200,
        )
        val c3Event = StoredGroupEvent(
            groupId = GROUP_ID,
            eventId = "c3",
            epoch = 5,
            authorDeviceId = "local-device",
            authorSeq = 3,
            hlcPhysicalMs = 300,
            hlcLogical = 0,
            kind = StoredGroupEventKind.CONTROL_MUTATION.name,
            body = """{"kind":"MEMBER_ADDED","target_member_device_id":"bob-device","epoch":5}""",
            createdAtMs = 300,
            receivedAtMs = 300,
        )
        helper.ingestEvent(c1Event)
        helper.ingestEvent(c2Event)
        helper.ingestEvent(c3Event)

        // Rebuild projections
        helper.rebuildProjections(GROUP_ID)

        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 1))
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 2))
        assertFalse(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 3))
        assertFalse(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 4))
        assertTrue(helper.isMemberActiveAtEpoch(GROUP_ID, "bob-device", 5))

        val intervals = helper.listMembershipIntervals(GROUP_ID, "bob-device")
        assertEquals(2, intervals.size)
        assertEquals(1L, intervals[0].joinedEpoch)
        assertEquals(3L, intervals[0].removedEpoch)
        assertEquals(5L, intervals[1].joinedEpoch)
        assertEquals(null, intervals[1].removedEpoch)
    }

    @Test
    fun migrationOnRealV6Fixture_KickReaddAndAdminEpoch() {
        helper.close()
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        System.loadLibrary("sqlcipher")
        val v6Db = SQLiteDatabase.openOrCreateDatabase(
            file.absolutePath,
            SecureStorage.getOrGenerateDbPassphrase(context),
            null,
            null,
            null,
            null,
        )
        v6Db.execSQL("CREATE TABLE groups(group_id TEXT PRIMARY KEY, title TEXT NOT NULL, local_device_id TEXT NOT NULL, owner_device_id TEXT NOT NULL, current_epoch INTEGER NOT NULL, control_head TEXT, control_depth INTEGER NOT NULL DEFAULT 0, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, avatar_uri TEXT, direct_peer_pubkey TEXT, direct_transport_type TEXT, unread_count INTEGER NOT NULL DEFAULT 0, last_read_hlc_physical_ms INTEGER NOT NULL DEFAULT 0, last_read_hlc_logical INTEGER NOT NULL DEFAULT 0, is_archived INTEGER NOT NULL DEFAULT 0, is_muted INTEGER NOT NULL DEFAULT 0, admin_only_posting INTEGER NOT NULL DEFAULT 0)")
        v6Db.execSQL("CREATE TABLE group_members(group_id TEXT NOT NULL, device_id TEXT NOT NULL, account_id TEXT NOT NULL, display_name TEXT NOT NULL, role TEXT NOT NULL, permissions INTEGER NOT NULL, status TEXT NOT NULL, joined_epoch INTEGER NOT NULL, removed_epoch INTEGER, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, transport_fingerprint TEXT, peer_name TEXT, signing_key_base64 TEXT, PRIMARY KEY (group_id, device_id))")
        v6Db.execSQL("CREATE TABLE group_epoch_keys(group_id TEXT NOT NULL, epoch INTEGER NOT NULL, key_material BLOB NOT NULL, created_at_ms INTEGER NOT NULL, PRIMARY KEY (group_id, epoch))")
        v6Db.execSQL("CREATE TABLE group_events(group_id TEXT NOT NULL, event_id TEXT PRIMARY KEY, epoch INTEGER NOT NULL, author_device_id TEXT NOT NULL, author_seq INTEGER NOT NULL, hlc_physical_ms INTEGER NOT NULL, hlc_logical INTEGER NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL, created_at_ms INTEGER NOT NULL, received_at_ms INTEGER NOT NULL)")
        v6Db.execSQL("CREATE TABLE group_messages(group_id TEXT NOT NULL, event_id TEXT PRIMARY KEY, author_device_id TEXT NOT NULL, hlc_physical_ms INTEGER NOT NULL, hlc_logical INTEGER NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL, created_at_ms INTEGER NOT NULL, delivered_at_ms INTEGER, read_at_ms INTEGER)")
        v6Db.execSQL("CREATE TABLE outbox_tasks(task_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, event_id TEXT NOT NULL, recipient_device_id TEXT NOT NULL, payload BLOB NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_ms INTEGER NOT NULL, last_error TEXT, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)")
        v6Db.execSQL("CREATE TABLE receipts(group_id TEXT NOT NULL, event_id TEXT NOT NULL, recipient_device_id TEXT NOT NULL, received_at_ms INTEGER NOT NULL, PRIMARY KEY (group_id, event_id, recipient_device_id))")
        v6Db.execSQL("CREATE TABLE pending_invites(invite_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, invitee_device_id TEXT NOT NULL, created_at_ms INTEGER NOT NULL)")
        v6Db.execSQL("CREATE TABLE sync_cursors(group_id TEXT NOT NULL, peer_device_id TEXT NOT NULL, last_synced_hlc_physical_ms INTEGER NOT NULL, last_synced_hlc_logical INTEGER NOT NULL, PRIMARY KEY (group_id, peer_device_id))")
        v6Db.execSQL("CREATE TABLE owner_lineage_certificates(group_id TEXT NOT NULL, sequence INTEGER NOT NULL, transition_id TEXT NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (group_id, sequence))")
        v6Db.execSQL("CREATE TABLE roster_snapshot_pages(group_id TEXT NOT NULL, root_event_id TEXT NOT NULL, page_index INTEGER NOT NULL, total_pages INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY (group_id, root_event_id, page_index))")

        // Seed initial v6 group and members
        v6Db.execSQL("INSERT INTO groups(group_id, title, local_device_id, owner_device_id, current_epoch, created_at_ms, updated_at_ms) VALUES ('g-v6', 'V6 Group', 'owner-dev', 'owner-dev', 3, 1000, 2000)")
        v6Db.execSQL("INSERT INTO group_members(group_id, device_id, account_id, display_name, role, permissions, status, joined_epoch, created_at_ms, updated_at_ms) VALUES ('g-v6', 'owner-dev', 'acc-owner', 'Owner', 'OWNER', 999, 'ACTIVE', 1, 1000, 1000)")
        v6Db.execSQL("INSERT INTO group_members(group_id, device_id, account_id, display_name, role, permissions, status, joined_epoch, removed_epoch, created_at_ms, updated_at_ms) VALUES ('g-v6', 'kicked-dev', 'acc-kicked', 'Kicked', 'MEMBER', 0, 'LEFT', 1, 2, 1000, 1500)")
        v6Db.execSQL("INSERT INTO group_epoch_keys(group_id, epoch, key_material, created_at_ms) VALUES ('g-v6', 1, x'010203', 1000)")
        v6Db.version = 6
        v6Db.close()

        // Open with updated helper -> auto-triggers upgradeToV7
        helper = GroupDatabaseHelper(context, databaseName)
        assertEquals(7, helper.readableDatabase.version)

        // Check new table group_membership_intervals exists
        assertTrue(helper.isMemberActiveAtEpoch("g-v6", "owner-dev", 1))
        assertTrue(helper.isMemberActiveAtEpoch("g-v6", "owner-dev", 3))
        // kicked-dev joined at 1, removed at 2 -> active at 1, inactive at 2
        assertTrue(helper.isMemberActiveAtEpoch("g-v6", "kicked-dev", 1))
        assertFalse(helper.isMemberActiveAtEpoch("g-v6", "kicked-dev", 2))

        // Check suite column was added to group_epoch_keys
        val key = helper.getEpochKey("g-v6", 1)
        assertNotNull(key)
        assertEquals("2pchat-epoch-aes256gcm-ed25519-v1", key?.suite)
    }

    @Test
    fun testMonotonicTorOnlyGroupFlagCannotBeDowngraded() {
        // Create a tor-only group
        helper.createGroup(
            StoredGroup(
                groupId = "group-tor-monotonic",
                title = "Tor Group",
                localDeviceId = "local-device",
                ownerDeviceId = "local-device",
                currentEpoch = 1,
                torOnlyGroup = true,
                createdAtMs = 1,
                updatedAtMs = 1,
            ),
            members = listOf(
                StoredGroupMember(
                    groupId = "group-tor-monotonic",
                    deviceId = "local-device",
                    accountId = "local-account",
                    displayName = "Owner",
                    role = "OWNER",
                    permissions = Long.MAX_VALUE,
                    joinedEpoch = 1,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                ),
            ),
            initialEpochKey = StoredGroupEpochKey(
                groupId = "group-tor-monotonic",
                epoch = 1,
                keyMaterial = byteArrayOf(1, 2, 3),
                createdAtMs = 1,
            ),
        )

        assertTrue(checkNotNull(helper.getGroup("group-tor-monotonic")).torOnlyGroup)

        // Attempting to downgrade torOnlyGroup from true to false must be rejected by applyControlMutation
        val downgradeResult = helper.applyControlMutation(
            groupId = "group-tor-monotonic",
            expectedHead = null,
            newHead = "mut-downgrade",
            torOnlyGroup = false,
        )
        assertFalse("Monotonic rule: weakening torOnlyGroup from true to false must fail", downgradeResult)
        // Group must still have torOnlyGroup == true
        assertTrue(checkNotNull(helper.getGroup("group-tor-monotonic")).torOnlyGroup)

        // Permitted transition: upgrading false to true
        createGroup() // creates GROUP_ID with torOnlyGroup = false
        assertFalse(checkNotNull(helper.getGroup(GROUP_ID)).torOnlyGroup)
        val upgradeResult = helper.applyControlMutation(
            groupId = GROUP_ID,
            expectedHead = null,
            newHead = "mut-upgrade",
            torOnlyGroup = true,
        )
        assertTrue("Enabling torOnlyGroup (false -> true) must succeed", upgradeResult)
        assertTrue(checkNotNull(helper.getGroup(GROUP_ID)).torOnlyGroup)
    }

    private fun createGroup() {
        helper.createGroup(
            StoredGroup(
                groupId = GROUP_ID,
                title = "Test group",
                localDeviceId = "local-device",
                ownerDeviceId = "local-device",
                currentEpoch = 1,
                createdAtMs = 1,
                updatedAtMs = 1,
            ),
            members = listOf(
                StoredGroupMember(
                    groupId = GROUP_ID,
                    deviceId = "local-device",
                    accountId = "local-account",
                    displayName = "Owner",
                    role = "OWNER",
                    permissions = Long.MAX_VALUE,
                    joinedEpoch = 1,
                    createdAtMs = 1,
                    updatedAtMs = 1,
                ),
            ),
            initialEpochKey = StoredGroupEpochKey(
                groupId = GROUP_ID,
                epoch = 1,
                keyMaterial = byteArrayOf(7, 8, 9),
                createdAtMs = 1,
            ),
        )
    }

    private fun messageEvent(
        eventId: String,
        authorDeviceId: String,
        authorSeq: Long,
        hlcPhysicalMs: Long,
        body: String,
    ) = StoredGroupEvent(
        groupId = GROUP_ID,
        eventId = eventId,
        epoch = 1,
        authorDeviceId = authorDeviceId,
        authorSeq = authorSeq,
        hlcPhysicalMs = hlcPhysicalMs,
        hlcLogical = 0,
        kind = StoredGroupEventKind.MESSAGE.name,
        body = body,
        createdAtMs = hlcPhysicalMs,
        receivedAtMs = hlcPhysicalMs + 50_000,
    )

    companion object {
        private const val GROUP_ID = "group-storage-test"
    }
}
