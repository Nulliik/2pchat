package com.example.twopchat.group.storage

import android.content.ContentValues
import android.content.Context
import com.example.twopchat.SecureStorage
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

enum class StoredGroupEventKind {
    MESSAGE,
    EDIT,
    DELETE,
    SYSTEM,
    CONTROL,
}

enum class StoredOutboxState {
    PENDING,
    RETRY,
    ACKED,
    FAILED,
}

data class StoredGroup(
    val groupId: String,
    val title: String,
    val description: String = "",
    val avatarUri: String? = null,
    val localDeviceId: String,
    val ownerDeviceId: String,
    val currentEpoch: Long = 0,
    val controlHead: String? = null,
    val pinnedEventId: String? = null,
    val metadataVersion: Long = 0,
    val unreadCount: Int = 0,
    val adminOnlyPosting: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
)

data class StoredGroupMember(
    val groupId: String,
    val deviceId: String,
    val accountId: String,
    val displayName: String,
    val role: String,
    val permissions: Long,
    val status: String = "ACTIVE",
    val joinedEpoch: Long,
    val removedEpoch: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
    val transportFingerprint: String = "",
    val peerName: String = displayName,
    val signingKeyBase64: String = "",
)

data class StoredGroupEpochKey(
    val groupId: String,
    val epoch: Long,
    val keyMaterial: ByteArray,
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long? = null,
)

data class StoredGroupEvent(
    val groupId: String,
    val eventId: String,
    val epoch: Long,
    val authorDeviceId: String,
    val authorSeq: Long,
    val hlcPhysicalMs: Long,
    val hlcLogical: Int,
    val kind: String,
    val body: String? = null,
    val targetEventId: String? = null,
    val controlHead: String? = null,
    val payload: ByteArray? = null,
    val createdAtMs: Long = hlcPhysicalMs,
    val receivedAtMs: Long = System.currentTimeMillis(),
)

data class StoredGroupMessage(
    val groupId: String,
    val messageId: String,
    val authorDeviceId: String,
    val authorSeq: Long,
    val hlcPhysicalMs: Long,
    val hlcLogical: Int,
    val body: String,
    val edited: Boolean,
    val deleted: Boolean,
    val unread: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class GroupTimelineCursor(
    val hlcPhysicalMs: Long,
    val hlcLogical: Int,
    val authorDeviceId: String,
    val authorSeq: Long,
    val eventId: String,
) {
    companion object {
        fun from(message: StoredGroupMessage): GroupTimelineCursor = GroupTimelineCursor(
            hlcPhysicalMs = message.hlcPhysicalMs,
            hlcLogical = message.hlcLogical,
            authorDeviceId = message.authorDeviceId,
            authorSeq = message.authorSeq,
            eventId = message.messageId,
        )
    }
}

data class StoredOutboxTask(
    val taskId: String,
    val groupId: String,
    val eventId: String,
    val recipientDeviceId: String,
    val payload: ByteArray,
    val state: String = StoredOutboxState.PENDING.name,
    val attempts: Int = 0,
    val nextAttemptMs: Long = 0,
    val lastError: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
)

data class StoredReceipt(
    val groupId: String,
    val eventId: String,
    val recipientDeviceId: String,
    val type: String,
    val receivedAtMs: Long,
)

data class StoredPendingInvite(
    val inviteId: String,
    val groupId: String,
    val inviterDeviceId: String,
    val token: ByteArray,
    val state: String = "PENDING",
    val expiresAtMs: Long,
    val createdAtMs: Long = System.currentTimeMillis(),
)

data class StoredSyncCursor(
    val groupId: String,
    val deviceId: String,
    val lastAuthorSeq: Long,
    val lastEventId: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class StoredOwnerLineageCertificate(
    val groupId: String,
    val sequence: Int,
    val transitionId: String,
    val payload: ByteArray,
)

data class StoredRosterSnapshotPage(
    val groupId: String,
    val controlHead: String,
    val epoch: Long,
    val pageIndex: Int,
    val totalPages: Int,
    val snapshotCreatedAtMs: Long,
    val payload: ByteArray,
)

data class StoredAdmissionCursor(
    val groupId: String,
    val recipientDeviceId: String,
    val controlHead: String,
    val authorDeviceId: String,
    val lastAuthorSequence: Long,
    val createdAtMs: Long,
)

/**
 * SQLCipher-backed durable storage for group protocol state.
 *
 * Network delivery is at-least-once. [ingestEvent] converts that into exactly-once
 * local persistence using both the event id and the per-author sequence constraint.
 * Timeline pages are returned newest-first in deterministic HLC/device/sequence/id order.
 */
class GroupDatabaseHelper(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(
    context.applicationContext,
    databaseName,
    SecureStorage.getOrGenerateDbPassphrase(context.applicationContext),
    null,
    DATABASE_VERSION,
    0,
    null,
    null,
    false,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE groups(
                group_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                avatar_uri TEXT,
                local_device_id TEXT NOT NULL,
                owner_device_id TEXT NOT NULL,
                current_epoch INTEGER NOT NULL DEFAULT 0,
                control_head TEXT,
                pinned_event_id TEXT,
                metadata_version INTEGER NOT NULL DEFAULT 0,
                unread_count INTEGER NOT NULL DEFAULT 0 CHECK(unread_count >= 0),
                admin_only_posting INTEGER NOT NULL DEFAULT 0 CHECK(admin_only_posting IN (0, 1)),
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE group_members(
                group_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                account_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                transport_fingerprint TEXT NOT NULL DEFAULT '',
                peer_name TEXT NOT NULL,
                signing_key_base64 TEXT NOT NULL DEFAULT '',
                role TEXT NOT NULL,
                permissions INTEGER NOT NULL,
                status TEXT NOT NULL,
                joined_epoch INTEGER NOT NULL,
                removed_epoch INTEGER,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(group_id, device_id),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE group_epoch_keys(
                group_id TEXT NOT NULL,
                epoch INTEGER NOT NULL,
                key_material BLOB NOT NULL,
                created_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER,
                PRIMARY KEY(group_id, epoch),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE group_events(
                group_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                epoch INTEGER NOT NULL,
                author_device_id TEXT NOT NULL,
                author_seq INTEGER NOT NULL CHECK(author_seq >= 0),
                hlc_physical_ms INTEGER NOT NULL,
                hlc_logical INTEGER NOT NULL CHECK(hlc_logical >= 0),
                kind TEXT NOT NULL,
                body TEXT,
                target_event_id TEXT,
                control_head TEXT,
                payload BLOB,
                created_at_ms INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL,
                PRIMARY KEY(group_id, event_id),
                UNIQUE(group_id, author_device_id, author_seq),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE group_messages(
                group_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                author_device_id TEXT NOT NULL,
                author_seq INTEGER NOT NULL,
                hlc_physical_ms INTEGER NOT NULL,
                hlc_logical INTEGER NOT NULL,
                body TEXT NOT NULL,
                edited INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                unread INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(group_id, message_id),
                FOREIGN KEY(group_id, message_id)
                    REFERENCES group_events(group_id, event_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE outbox_tasks(
                task_id TEXT PRIMARY KEY,
                group_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                recipient_device_id TEXT NOT NULL,
                payload BLOB NOT NULL,
                state TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
                next_attempt_ms INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                UNIQUE(group_id, event_id, recipient_device_id),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE receipts(
                group_id TEXT NOT NULL,
                event_id TEXT NOT NULL,
                recipient_device_id TEXT NOT NULL,
                type TEXT NOT NULL,
                received_at_ms INTEGER NOT NULL,
                PRIMARY KEY(group_id, event_id, recipient_device_id, type),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pending_invites(
                invite_id TEXT PRIMARY KEY,
                group_id TEXT NOT NULL,
                inviter_device_id TEXT NOT NULL,
                token BLOB NOT NULL,
                state TEXT NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sync_cursors(
                group_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                last_author_seq INTEGER NOT NULL CHECK(last_author_seq >= 0),
                last_event_id TEXT,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY(group_id, device_id),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        createReactionTable(db)
        createProtocolRecoveryTables(db)

        db.execSQL(
            "CREATE INDEX idx_group_events_timeline ON group_events(" +
                "group_id, hlc_physical_ms, hlc_logical, author_device_id, author_seq, event_id)",
        )
        db.execSQL(
            "CREATE INDEX idx_group_events_author_seq ON group_events(group_id, author_device_id, author_seq)",
        )
        db.execSQL(
            "CREATE INDEX idx_group_events_target ON group_events(group_id, target_event_id)",
        )
        db.execSQL(
            "CREATE INDEX idx_group_messages_timeline ON group_messages(" +
                "group_id, hlc_physical_ms, hlc_logical, author_device_id, author_seq, message_id)",
        )
        db.execSQL(
            "CREATE INDEX idx_outbox_due ON outbox_tasks(state, next_attempt_ms, created_at_ms)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_members_group_status ON group_members(group_id, status)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_events_kind ON group_events(group_id, kind)",
        )
        createReceiptLookupIndex(db)
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_events_lookup ON group_events(group_id, event_id)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createReactionTable(db)
            db.query(
                TABLE_EVENTS,
                null,
                "kind IN (?, ?)",
                arrayOf("REACTION_ADD", "REACTION_REMOVE"),
                null,
                null,
                EVENT_ASC_ORDER,
            ).use { cursor ->
                while (cursor.moveToNext()) materializeReaction(db, cursor.toEvent())
            }
        }
        if (oldVersion < 4) {
            createProtocolRecoveryTables(db)
        }
        if (oldVersion < 5) {
            db.execSQL(
                "ALTER TABLE groups ADD COLUMN admin_only_posting INTEGER NOT NULL " +
                    "DEFAULT 0 CHECK(admin_only_posting IN (0, 1))",
            )
        }
        if (oldVersion < 6) {
            createReceiptLookupIndex(db)
        }
    }

    private fun createReceiptLookupIndex(db: SQLiteDatabase) {
        val receiptsTableExists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(TABLE_RECEIPTS),
        ).use { it.moveToFirst() }
        if (!receiptsTableExists) return
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_receipts_lookup ON receipts(group_id, event_id)",
        )
    }

    fun createGroup(
        group: StoredGroup,
        members: List<StoredGroupMember>,
        initialEpochKey: StoredGroupEpochKey? = null,
        ownerLineage: List<StoredOwnerLineageCertificate> = emptyList(),
        outboxTasks: List<StoredOutboxTask> = emptyList(),
    ) {
        require(group.groupId.isNotBlank())
        require(members.all { it.groupId == group.groupId })
        require(initialEpochKey == null || initialEpochKey.groupId == group.groupId)
        require(ownerLineage.all { it.groupId == group.groupId })
        require(outboxTasks.all { it.groupId == group.groupId })
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow(TABLE_GROUPS, null, groupValues(group))
            members.forEach { db.insertOrThrow(TABLE_MEMBERS, null, memberValues(it)) }
            initialEpochKey?.let {
                db.insertOrThrow(TABLE_EPOCH_KEYS, null, epochKeyValues(it))
            }
            insertOwnerLineage(db, group.groupId, ownerLineage)
            outboxTasks.forEach { task ->
                db.insertOrThrow(TABLE_OUTBOX, null, outboxValues(task))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertGroup(group: StoredGroup) {
        val db = writableDatabase
        val values = groupValues(group)
        val updated = db.update(
            TABLE_GROUPS,
            values,
            "group_id = ?",
            arrayOf(group.groupId),
        )
        if (updated == 0) db.insertOrThrow(TABLE_GROUPS, null, values)
    }

    fun getGroup(groupId: String): StoredGroup? =
        readableDatabase.query(
            TABLE_GROUPS,
            null,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toGroup() else null
        }

    fun listGroups(): List<StoredGroup> {
        val result = mutableListOf<StoredGroup>()
        readableDatabase.query(
            TABLE_GROUPS,
            null,
            null,
            null,
            null,
            null,
            "updated_at_ms DESC, group_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toGroup()
        }
        return result
    }

    fun upsertMember(member: StoredGroupMember) {
        writableDatabase.insertWithOnConflict(
            TABLE_MEMBERS,
            null,
            memberValues(member),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Applies one authenticated roster page without exposing a partially
     * updated page after a process or database failure.
     */
    fun upsertMembers(members: List<StoredGroupMember>): Int {
        if (members.isEmpty()) return 0
        val groupId = members.first().groupId
        require(groupId.isNotBlank() && members.all { it.groupId == groupId })
        require(members.map { it.deviceId }.toSet().size == members.size)
        val db = writableDatabase
        db.beginTransaction()
        try {
            members.forEach { member ->
                check(
                    db.insertWithOnConflict(
                        TABLE_MEMBERS,
                        null,
                        memberValues(member),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) != -1L,
                )
            }
            db.setTransactionSuccessful()
            return members.size
        } finally {
            db.endTransaction()
        }
    }

    fun replaceOwnerLineage(
        groupId: String,
        certificates: List<StoredOwnerLineageCertificate>,
    ) {
        require(groupId.isNotBlank())
        require(certificates.all { it.groupId == groupId })
        require(certificates.map { it.sequence } == (1..certificates.size).toList())
        require(certificates.map { it.transitionId }.toSet().size == certificates.size)
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertOwnerLineage(db, groupId, certificates)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun applyAcceptedInviteState(
        expectedGroup: StoredGroup,
        updatedGroup: StoredGroup,
        epochKey: StoredGroupEpochKey,
        members: List<StoredGroupMember>,
        ownerLineage: List<StoredOwnerLineageCertificate>,
        syncCursors: List<StoredSyncCursor>,
        outboxTasks: List<StoredOutboxTask>,
    ): Boolean {
        require(expectedGroup.groupId == updatedGroup.groupId)
        require(epochKey.groupId == updatedGroup.groupId)
        require(members.isNotEmpty() && members.all { it.groupId == updatedGroup.groupId })
        require(members.map { it.deviceId }.toSet().size == members.size)
        require(ownerLineage.all { it.groupId == updatedGroup.groupId })
        require(syncCursors.all { it.groupId == updatedGroup.groupId })
        require(syncCursors.map { it.deviceId }.toSet().size == syncCursors.size)
        require(outboxTasks.all { it.groupId == updatedGroup.groupId })
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.query(
                TABLE_GROUPS,
                null,
                "group_id = ?",
                arrayOf(expectedGroup.groupId),
                null,
                null,
                null,
                "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toGroup() else null }
                ?: return false
            if (
                current.ownerDeviceId != expectedGroup.ownerDeviceId ||
                current.currentEpoch != expectedGroup.currentEpoch ||
                current.controlHead != expectedGroup.controlHead
            ) {
                return false
            }
            insertOwnerLineage(db, updatedGroup.groupId, ownerLineage)
            val existingKey = db.query(
                TABLE_EPOCH_KEYS,
                arrayOf("key_material"),
                "group_id = ? AND epoch = ?",
                arrayOf(epochKey.groupId, epochKey.epoch.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getBlob(0) else null
            }
            if (existingKey == null) {
                db.insertOrThrow(TABLE_EPOCH_KEYS, null, epochKeyValues(epochKey))
            } else {
                require(existingKey.contentEquals(epochKey.keyMaterial)) {
                    "conflicting secret for an existing group epoch"
                }
            }
            check(
                db.update(
                    TABLE_GROUPS,
                    groupValues(updatedGroup),
                    "group_id = ?",
                    arrayOf(updatedGroup.groupId),
                ) == 1,
            )
            members.forEach { member ->
                check(
                    db.insertWithOnConflict(
                        TABLE_MEMBERS,
                        null,
                        memberValues(member),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) != -1L,
                )
            }
            syncCursors.forEach { cursor ->
                upsertSyncCursor(db, cursor)
            }
            outboxTasks.forEach { task ->
                db.insertWithOnConflict(
                    TABLE_OUTBOX,
                    null,
                    outboxValues(task),
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun listOwnerLineage(groupId: String): List<StoredOwnerLineageCertificate> {
        val result = mutableListOf<StoredOwnerLineageCertificate>()
        readableDatabase.query(
            TABLE_OWNER_LINEAGE,
            null,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            "sequence ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredOwnerLineageCertificate(
                    groupId = cursor.string("group_id"),
                    sequence = cursor.int("sequence"),
                    transitionId = cursor.string("transition_id"),
                    payload = cursor.blob("payload"),
                )
            }
        }
        return result
    }

    fun storeRosterSnapshotPage(page: StoredRosterSnapshotPage): Boolean {
        require(page.groupId.isNotBlank() && page.controlHead.isNotBlank())
        require(page.epoch > 0L)
        require(page.pageIndex in 0 until page.totalPages)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                TABLE_ROSTER_PAGES,
                null,
                "group_id = ? AND control_head = ? AND page_index = ?",
                arrayOf(page.groupId, page.controlHead, page.pageIndex.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    StoredRosterSnapshotPage(
                        groupId = cursor.string("group_id"),
                        controlHead = cursor.string("control_head"),
                        epoch = cursor.long("epoch"),
                        pageIndex = cursor.int("page_index"),
                        totalPages = cursor.int("total_pages"),
                        snapshotCreatedAtMs = cursor.long("snapshot_created_at_ms"),
                        payload = cursor.blob("payload"),
                    )
                }
            }
            val accepted = if (existing == null) {
                db.insertOrThrow(
                    TABLE_ROSTER_PAGES,
                    null,
                    rosterSnapshotPageValues(page),
                )
                true
            } else {
                existing.epoch == page.epoch &&
                    existing.totalPages == page.totalPages &&
                    existing.snapshotCreatedAtMs == page.snapshotCreatedAtMs &&
                    existing.payload.contentEquals(page.payload)
            }
            if (accepted) db.setTransactionSuccessful()
            return accepted
        } finally {
            db.endTransaction()
        }
    }

    fun listRosterSnapshotPages(
        groupId: String,
        controlHead: String,
    ): List<StoredRosterSnapshotPage> {
        val result = mutableListOf<StoredRosterSnapshotPage>()
        readableDatabase.query(
            TABLE_ROSTER_PAGES,
            null,
            "group_id = ? AND control_head = ?",
            arrayOf(groupId, controlHead),
            null,
            null,
            "page_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredRosterSnapshotPage(
                    groupId = cursor.string("group_id"),
                    controlHead = cursor.string("control_head"),
                    epoch = cursor.long("epoch"),
                    pageIndex = cursor.int("page_index"),
                    totalPages = cursor.int("total_pages"),
                    snapshotCreatedAtMs = cursor.long("snapshot_created_at_ms"),
                    payload = cursor.blob("payload"),
                )
            }
        }
        return result
    }

    fun listAdmissionCursors(
        groupId: String,
        recipientDeviceId: String,
        controlHead: String,
    ): List<StoredAdmissionCursor> {
        val result = mutableListOf<StoredAdmissionCursor>()
        readableDatabase.query(
            TABLE_ADMISSION_CURSORS,
            null,
            "group_id = ? AND recipient_device_id = ? AND control_head = ?",
            arrayOf(groupId, recipientDeviceId, controlHead),
            null,
            null,
            "author_device_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredAdmissionCursor(
                    groupId = cursor.string("group_id"),
                    recipientDeviceId = cursor.string("recipient_device_id"),
                    controlHead = cursor.string("control_head"),
                    authorDeviceId = cursor.string("author_device_id"),
                    lastAuthorSequence = cursor.long("last_author_sequence"),
                    createdAtMs = cursor.long("created_at_ms"),
                )
            }
        }
        return result
    }

    fun applyCompleteRosterSnapshot(
        groupId: String,
        expectedControlHead: String,
        expectedEpoch: Long,
        members: List<StoredGroupMember>,
        authorSequences: Map<String, Long>,
    ): Boolean {
        require(members.isNotEmpty() && members.all { it.groupId == groupId })
        require(members.map { it.deviceId }.toSet().size == members.size)
        require(
            authorSequences.keys == members.mapTo(hashSetOf()) { it.deviceId } &&
                authorSequences.values.all { it >= 0L },
        )
        val db = writableDatabase
        db.beginTransaction()
        try {
            val matches = db.query(
                TABLE_GROUPS,
                arrayOf("group_id"),
                "group_id = ? AND control_head = ? AND current_epoch = ?",
                arrayOf(groupId, expectedControlHead, expectedEpoch.toString()),
                null,
                null,
                null,
                "1",
            ).use(android.database.Cursor::moveToFirst)
            if (!matches) return false
            members.forEach { member ->
                check(
                    db.insertWithOnConflict(
                        TABLE_MEMBERS,
                        null,
                        memberValues(member),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    ) != -1L,
                )
            }
            authorSequences.forEach { (deviceId, snapshotSequence) ->
                val existingSequence = db.query(
                    TABLE_SYNC_CURSORS,
                    arrayOf("last_author_seq"),
                    "group_id = ? AND device_id = ?",
                    arrayOf(groupId, deviceId),
                    null,
                    null,
                    null,
                    "1",
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                }
                db.insertWithOnConflict(
                    TABLE_SYNC_CURSORS,
                    null,
                    ContentValues().apply {
                        put("group_id", groupId)
                        put("device_id", deviceId)
                        put("last_author_seq", maxOf(existingSequence, snapshotSequence))
                        put("last_event_id", expectedControlHead)
                        put("updated_at_ms", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.delete(
                TABLE_ROSTER_PAGES,
                "group_id = ? AND control_head = ?",
                arrayOf(groupId, expectedControlHead),
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun getMember(groupId: String, deviceId: String): StoredGroupMember? =
        readableDatabase.query(
            TABLE_MEMBERS,
            null,
            "group_id = ? AND device_id = ?",
            arrayOf(groupId, deviceId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toMember() else null
        }

    fun listMembers(groupId: String): List<StoredGroupMember> {
        val result = mutableListOf<StoredGroupMember>()
        readableDatabase.query(
            TABLE_MEMBERS,
            null,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            "role ASC, display_name COLLATE NOCASE ASC, device_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMember()
        }
        return result
    }

    fun storeEpochKey(key: StoredGroupEpochKey): Boolean {
        require(key.epoch >= 0)
        require(key.keyMaterial.isNotEmpty())
        val inserted = writableDatabase.insertWithOnConflict(
            TABLE_EPOCH_KEYS,
            null,
            epochKeyValues(key),
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        if (inserted) return true
        val existing = getEpochKey(key.groupId, key.epoch)
        return existing != null && existing.keyMaterial.contentEquals(key.keyMaterial)
    }

    fun getEpochKey(groupId: String, epoch: Long): StoredGroupEpochKey? =
        readableDatabase.query(
            TABLE_EPOCH_KEYS,
            null,
            "group_id = ? AND epoch = ?",
            arrayOf(groupId, epoch.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                StoredGroupEpochKey(
                    groupId = cursor.string("group_id"),
                    epoch = cursor.long("epoch"),
                    keyMaterial = cursor.blob("key_material"),
                    createdAtMs = cursor.long("created_at_ms"),
                    expiresAtMs = cursor.nullableLong("expires_at_ms"),
                )
            }
        }

    /**
     * Atomically appends and materializes an event.
     *
     * Returns false for either duplicate constraint. A duplicate never changes the
     * materialized timeline and never increments unread_count.
     */
    fun ingestEvent(event: StoredGroupEvent, countAsUnread: Boolean): Boolean {
        validateEventForIngest(event)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!insertAndMaterializeEvent(db, event, countAsUnread)) return false
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Atomically commits a local event and its per-recipient durable fan-out.
     *
     * Event conflicts roll the transaction back before any task is inserted.
     * Existing task identities are harmless and are ignored independently.
     */
    fun ingestEventWithOutbox(
        event: StoredGroupEvent,
        countAsUnread: Boolean,
        tasks: List<StoredOutboxTask>,
    ): Boolean {
        validateEventForIngest(event)
        require(
            tasks.all {
                it.groupId == event.groupId &&
                    it.eventId == event.eventId &&
                    it.taskId.isNotBlank() &&
                    it.recipientDeviceId.isNotBlank()
            },
        ) { "outbox tasks must address the ingested event" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!insertAndMaterializeEvent(db, event, countAsUnread)) return false
            tasks.forEach { task ->
                db.insertWithOnConflict(
                    TABLE_OUTBOX,
                    null,
                    outboxValues(task),
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Batch ingests multiple events within a single SQLite transaction,
     * reducing disk I/O flushes from N transactions down to 1.
     */
    fun ingestEventsBatch(items: List<Pair<StoredGroupEvent, Boolean>>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            var count = 0
            for ((event, countAsUnread) in items) {
                validateEventForIngest(event)
                if (insertAndMaterializeEvent(db, event, countAsUnread)) {
                    count++
                }
            }
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    fun getEvent(groupId: String, eventId: String): StoredGroupEvent? =
        readableDatabase.query(
            TABLE_EVENTS,
            null,
            "group_id = ? AND event_id = ?",
            arrayOf(groupId, eventId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toEvent() else null
        }

    /**
     * Returns the event currently named by the durable control head in one SQL
     * snapshot. A detached fork event is never returned by this lookup.
     */
    fun getCanonicalControlEvent(groupId: String): StoredGroupEvent? =
        readableDatabase.rawQuery(
            """
            SELECT event.*
            FROM $TABLE_GROUPS AS group_state
            JOIN $TABLE_EVENTS AS event
              ON event.group_id = group_state.group_id
             AND event.event_id = group_state.control_head
            WHERE group_state.group_id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(groupId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toEvent() else null }

    fun getEventByAuthorSequence(
        groupId: String,
        authorDeviceId: String,
        authorSequence: Long,
    ): StoredGroupEvent? = readableDatabase.query(
        TABLE_EVENTS,
        null,
        "group_id = ? AND author_device_id = ? AND author_seq = ?",
        arrayOf(groupId, authorDeviceId, authorSequence.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toEvent() else null }

    fun listEventsSince(
        groupId: String,
        authorDeviceId: String,
        afterAuthorSeq: Long,
        limit: Int,
        minimumEpoch: Long = 0,
    ): List<StoredGroupEvent> {
        require(limit in 1..MAX_PAGE_SIZE)
        require(minimumEpoch >= 0)
        val result = mutableListOf<StoredGroupEvent>()
        readableDatabase.query(
            TABLE_EVENTS,
            null,
            "group_id = ? AND author_device_id = ? AND author_seq > ? AND epoch >= ?",
            arrayOf(
                groupId,
                authorDeviceId,
                afterAuthorSeq.toString(),
                minimumEpoch.toString(),
            ),
            null,
            null,
            "author_seq ASC, event_id ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEvent()
        }
        return result
    }

    /**
     * Pages durable wire events for startup reconciliation. Only rows which
     * retain their original wire payload can be reconstructed into outbox
     * tasks. Continue with the last returned [StoredGroupEvent.authorSeq].
     */
    fun listRecoverableAuthorEvents(
        groupId: String,
        authorDeviceId: String,
        afterAuthorSeq: Long,
        limit: Int,
    ): List<StoredGroupEvent> {
        require(groupId.isNotBlank() && authorDeviceId.isNotBlank())
        require(afterAuthorSeq >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        val result = mutableListOf<StoredGroupEvent>()
        readableDatabase.query(
            TABLE_EVENTS,
            null,
            "group_id = ? AND author_device_id = ? AND author_seq > ? AND payload IS NOT NULL",
            arrayOf(groupId, authorDeviceId, afterAuthorSeq.toString()),
            null,
            null,
            "author_seq ASC, event_id ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEvent()
        }
        return result
    }

    fun nextAuthorSequence(groupId: String, authorDeviceId: String): Long {
        require(groupId.isNotBlank() && authorDeviceId.isNotBlank())
        return readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(author_seq), 0) + 1 FROM $TABLE_EVENTS " +
                "WHERE group_id = ? AND author_device_id = ?",
            arrayOf(groupId, authorDeviceId),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    fun contiguousAuthorSequence(
        groupId: String,
        authorDeviceId: String,
        baselineSequence: Long = 0,
    ): Long {
        require(groupId.isNotBlank() && authorDeviceId.isNotBlank())
        require(baselineSequence >= 0)
        var contiguous = baselineSequence
        readableDatabase.query(
            TABLE_EVENTS,
            arrayOf("author_seq"),
            "group_id = ? AND author_device_id = ? AND author_seq > ?",
            arrayOf(groupId, authorDeviceId, baselineSequence.toString()),
            null,
            null,
            "author_seq ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sequence = cursor.getLong(0)
                if (sequence == contiguous + 1L) {
                    contiguous = sequence
                } else if (sequence > contiguous + 1L) {
                    break
                }
            }
        }
        return contiguous
    }

    fun latestAuthorEvent(groupId: String, authorDeviceId: String): StoredGroupEvent? =
        readableDatabase.query(
            TABLE_EVENTS,
            null,
            "group_id = ? AND author_device_id = ?",
            arrayOf(groupId, authorDeviceId),
            null,
            null,
            "author_seq DESC, event_id DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toEvent() else null }

    fun hasEventTarget(groupId: String, targetEventId: String): Boolean =
        readableDatabase.query(
            TABLE_EVENTS,
            arrayOf("event_id"),
            "group_id = ? AND target_event_id = ?",
            arrayOf(groupId, targetEventId),
            null,
            null,
            null,
            "1",
        ).use(android.database.Cursor::moveToFirst)

    fun hasSerializedControlTarget(groupId: String, targetEventId: String): Boolean {
        val controlKinds = arrayOf(
            "GROUP_UPDATED",
            "MEMBER_ADDED",
            "MEMBER_REMOVED",
            "MEMBER_RESTRICTED",
            "ROLE_CHANGED",
            "OWNERSHIP_TRANSFERRED",
        )
        return readableDatabase.query(
            TABLE_EVENTS,
            arrayOf("event_id"),
            "group_id = ? AND target_event_id = ? AND kind IN (${controlKinds.joinToString { "?" }})",
            arrayOf(groupId, targetEventId, *controlKinds),
            null,
            null,
            null,
            "1",
        ).use(android.database.Cursor::moveToFirst)
    }

    fun hasPendingOutboxEventPrefix(groupId: String, eventPrefix: String): Boolean =
        readableDatabase.query(
            TABLE_OUTBOX,
            arrayOf("task_id"),
            "group_id = ? AND event_id LIKE ? AND state IN (?, ?)",
            arrayOf(
                groupId,
                "$eventPrefix%",
                StoredOutboxState.PENDING.name,
                StoredOutboxState.RETRY.name,
            ),
            null,
            null,
            null,
            "1",
        ).use(android.database.Cursor::moveToFirst)

    fun listControlChildren(
        groupId: String,
        controlHead: String?,
        limit: Int = 10,
    ): List<StoredGroupEvent> {
        require(limit in 1..100)
        val selection: String
        val args: Array<String>
        if (controlHead == null) {
            selection = "group_id = ? AND control_head IS NULL"
            args = arrayOf(groupId)
        } else {
            selection = "group_id = ? AND control_head = ?"
            args = arrayOf(groupId, controlHead)
        }
        val result = mutableListOf<StoredGroupEvent>()
        readableDatabase.query(
            TABLE_EVENTS,
            null,
            selection,
            args,
            null,
            null,
            "author_seq ASC, event_id ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEvent()
        }
        return result
    }

    /**
     * Bounded deterministic event scan used to rebuild reactions, pins and the
     * auditable control log. It intentionally has no OFFSET so callers cannot
     * accidentally request an unbounded history into memory.
     */
    fun listEvents(groupId: String, limit: Int = MAX_PAGE_SIZE): List<StoredGroupEvent> {
        require(limit in 1..MAX_PAGE_SIZE)
        val result = mutableListOf<StoredGroupEvent>()
        readableDatabase.query(
            TABLE_EVENTS,
            null,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            EVENT_ASC_ORDER,
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEvent()
        }
        return result
    }

    fun listRecentEvents(groupId: String, limit: Int = MAX_PAGE_SIZE): List<StoredGroupEvent> {
        require(limit in 1..MAX_PAGE_SIZE)
        val result = mutableListOf<StoredGroupEvent>()
        readableDatabase.query(
            TABLE_EVENTS,
            null,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            "hlc_physical_ms DESC, hlc_logical DESC, author_device_id DESC, " +
                "author_seq DESC, event_id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEvent()
        }
        result.reverse()
        return result
    }

    fun listActiveReactionKeys(
        groupId: String,
        targetEventIds: Collection<String>,
    ): List<String> {
        if (targetEventIds.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        targetEventIds.distinct().chunked(400).forEach { targets ->
            val placeholders = targets.joinToString(",") { "?" }
            readableDatabase.query(
                TABLE_REACTIONS,
                arrayOf("target_event_id", "emoji", "author_device_id"),
                "group_id = ? AND active = 1 AND target_event_id IN ($placeholders)",
                (listOf(groupId) + targets).toTypedArray(),
                null,
                null,
                "target_event_id ASC, emoji ASC, author_device_id ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += "${cursor.string("target_event_id")}\u0000" +
                        "${cursor.string("emoji")}\u0000${cursor.string("author_device_id")}"
                }
            }
        }
        return result
    }

    /**
     * Returns a stable newest-first page. Pass a cursor made from the last row of
     * the previous page to continue without OFFSET races.
     */
    fun loadTimeline(
        groupId: String,
        limit: Int,
        before: GroupTimelineCursor? = null,
    ): List<StoredGroupMessage> {
        require(limit in 1..MAX_PAGE_SIZE)
        val selection: String
        val args: Array<String>
        if (before == null) {
            selection = "group_id = ?"
            args = arrayOf(groupId)
        } else {
            selection =
                """
                group_id = ? AND (
                    hlc_physical_ms < ? OR
                    (hlc_physical_ms = ? AND hlc_logical < ?) OR
                    (hlc_physical_ms = ? AND hlc_logical = ? AND author_device_id < ?) OR
                    (hlc_physical_ms = ? AND hlc_logical = ? AND author_device_id = ? AND author_seq < ?) OR
                    (hlc_physical_ms = ? AND hlc_logical = ? AND author_device_id = ? AND author_seq = ? AND message_id < ?)
                )
                """.trimIndent()
            args = arrayOf(
                groupId,
                before.hlcPhysicalMs.toString(),
                before.hlcPhysicalMs.toString(),
                before.hlcLogical.toString(),
                before.hlcPhysicalMs.toString(),
                before.hlcLogical.toString(),
                before.authorDeviceId,
                before.hlcPhysicalMs.toString(),
                before.hlcLogical.toString(),
                before.authorDeviceId,
                before.authorSeq.toString(),
                before.hlcPhysicalMs.toString(),
                before.hlcLogical.toString(),
                before.authorDeviceId,
                before.authorSeq.toString(),
                before.eventId,
            )
        }
        val result = mutableListOf<StoredGroupMessage>()
        readableDatabase.query(
            TABLE_MESSAGES,
            null,
            selection,
            args,
            null,
            null,
            TIMELINE_ORDER,
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    fun loadMessage(groupId: String, messageId: String): StoredGroupMessage? =
        readableDatabase.query(
            TABLE_MESSAGES,
            null,
            "group_id = ? AND message_id = ?",
            arrayOf(groupId, messageId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toMessage() else null }

    fun enqueueOutbox(task: StoredOutboxTask): Boolean {
        require(task.taskId.isNotBlank() && task.recipientDeviceId.isNotBlank())
        return writableDatabase.insertWithOnConflict(
            TABLE_OUTBOX,
            null,
            outboxValues(task),
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    fun enqueueOutbox(tasks: List<StoredOutboxTask>): Int {
        if (tasks.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            tasks.forEach {
                if (
                    db.insertWithOnConflict(
                        TABLE_OUTBOX,
                        null,
                        outboxValues(it),
                        SQLiteDatabase.CONFLICT_IGNORE,
                    ) != -1L
                ) {
                    inserted++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun loadDueOutbox(nowMs: Long, limit: Int): List<StoredOutboxTask> {
        require(limit in 1..MAX_PAGE_SIZE)
        val result = mutableListOf<StoredOutboxTask>()
        readableDatabase.query(
            TABLE_OUTBOX,
            null,
            "state IN (?, ?) AND next_attempt_ms <= ?",
            arrayOf(
                StoredOutboxState.PENDING.name,
                StoredOutboxState.RETRY.name,
                nowMs.toString(),
            ),
            null,
            null,
            "next_attempt_ms ASC, created_at_ms ASC, task_id ASC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toOutboxTask()
        }
        return result
    }

    fun getOutboxTask(taskId: String): StoredOutboxTask? =
        queryOutbox(readableDatabase, taskId)

    fun listOutboxForEvent(groupId: String, eventId: String): List<StoredOutboxTask> {
        val result = mutableListOf<StoredOutboxTask>()
        readableDatabase.query(
            TABLE_OUTBOX,
            null,
            "group_id = ? AND event_id = ?",
            arrayOf(groupId, eventId),
            null,
            null,
            "recipient_device_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toOutboxTask()
        }
        return result
    }

    fun hasOutboxTask(
        groupId: String,
        eventId: String,
        recipientDeviceId: String,
    ): Boolean {
        require(groupId.isNotBlank() && eventId.isNotBlank() && recipientDeviceId.isNotBlank())
        return readableDatabase.query(
            TABLE_OUTBOX,
            arrayOf("task_id"),
            "group_id = ? AND event_id = ? AND recipient_device_id = ?",
            arrayOf(groupId, eventId, recipientDeviceId),
            null,
            null,
            null,
            "1",
        ).use(android.database.Cursor::moveToFirst)
    }

    fun acknowledgeOutbox(
        taskId: String,
        receiptType: String = "STORED",
        acknowledgedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val task = queryOutbox(db, taskId) ?: return false
            val values = ContentValues().apply {
                put("state", StoredOutboxState.ACKED.name)
                put("updated_at_ms", acknowledgedAtMs)
                putNull("last_error")
            }
            db.update(TABLE_OUTBOX, values, "task_id = ?", arrayOf(taskId))
            db.insertWithOnConflict(
                TABLE_RECEIPTS,
                null,
                ContentValues().apply {
                    put("group_id", task.groupId)
                    put("event_id", task.eventId)
                    put("recipient_device_id", task.recipientDeviceId)
                    put("type", receiptType)
                    put("received_at_ms", acknowledgedAtMs)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun retryOutbox(
        taskId: String,
        nextAttemptMs: Long,
        lastError: String?,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val changed = writableDatabase.execUpdate(
            """
            UPDATE $TABLE_OUTBOX
            SET state = ?, attempts = attempts + 1, next_attempt_ms = ?,
                last_error = ?, updated_at_ms = ?
            WHERE task_id = ? AND state != ?
            """.trimIndent(),
            arrayOf<Any?>(
                StoredOutboxState.RETRY.name,
                nextAttemptMs,
                lastError,
                updatedAtMs,
                taskId,
                StoredOutboxState.ACKED.name,
            ),
        )
        return changed > 0
    }

    fun failOutbox(
        taskId: String,
        lastError: String,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean = writableDatabase.execUpdate(
        """
        UPDATE $TABLE_OUTBOX
        SET state = ?, last_error = ?, updated_at_ms = ?
        WHERE task_id = ? AND state != ?
        """.trimIndent(),
        arrayOf<Any?>(
            StoredOutboxState.FAILED.name,
            lastError,
            updatedAtMs,
            taskId,
            StoredOutboxState.ACKED.name,
        ),
    ) > 0

    fun requeueOutboxForEvent(
        groupId: String,
        eventId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = writableDatabase.execUpdate(
        """
        UPDATE $TABLE_OUTBOX
        SET state = ?, attempts = 0, next_attempt_ms = ?, last_error = NULL,
            updated_at_ms = ?
        WHERE group_id = ? AND event_id = ? AND state != ?
        """.trimIndent(),
        arrayOf<Any?>(
            StoredOutboxState.PENDING.name,
            nowMs,
            nowMs,
            groupId,
            eventId,
            StoredOutboxState.ACKED.name,
        ),
    )

    fun listReceipts(groupId: String, eventId: String): List<StoredReceipt> {
        val result = mutableListOf<StoredReceipt>()
        readableDatabase.query(
            TABLE_RECEIPTS,
            null,
            "group_id = ? AND event_id = ?",
            arrayOf(groupId, eventId),
            null,
            null,
            "received_at_ms ASC, recipient_device_id ASC, type ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredReceipt(
                    groupId = cursor.string("group_id"),
                    eventId = cursor.string("event_id"),
                    recipientDeviceId = cursor.string("recipient_device_id"),
                    type = cursor.string("type"),
                    receivedAtMs = cursor.long("received_at_ms"),
                )
            }
        }
        return result
    }

    fun listAllReceipts(groupId: String): List<StoredReceipt> {
        val result = mutableListOf<StoredReceipt>()
        readableDatabase.query(
            TABLE_RECEIPTS,
            null,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            "received_at_ms ASC, recipient_device_id ASC, event_id ASC, type ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredReceipt(
                    groupId = cursor.string("group_id"),
                    eventId = cursor.string("event_id"),
                    recipientDeviceId = cursor.string("recipient_device_id"),
                    type = cursor.string("type"),
                    receivedAtMs = cursor.long("received_at_ms"),
                )
            }
        }
        return result
    }

    fun recordReceipt(receipt: StoredReceipt) {
        require(receipt.groupId.isNotBlank() && receipt.eventId.isNotBlank())
        require(receipt.recipientDeviceId.isNotBlank() && receipt.type.isNotBlank())
        writableDatabase.insertWithOnConflict(
            TABLE_RECEIPTS,
            null,
            ContentValues().apply {
                put("group_id", receipt.groupId)
                put("event_id", receipt.eventId)
                put("recipient_device_id", receipt.recipientDeviceId)
                put("type", receipt.type)
                put("received_at_ms", receipt.receivedAtMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun savePendingInvite(invite: StoredPendingInvite) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val newerExists = db.rawQuery(
                """
                SELECT 1 FROM $TABLE_INVITES
                WHERE group_id = ? AND inviter_device_id = ? AND created_at_ms > ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    invite.groupId,
                    invite.inviterDeviceId,
                    invite.createdAtMs.toString(),
                ),
            ).use { it.moveToFirst() }
            if (newerExists) {
                db.setTransactionSuccessful()
                return
            }
            db.delete(
                TABLE_INVITES,
                "group_id = ? AND inviter_device_id = ? AND invite_id != ?",
                arrayOf(invite.groupId, invite.inviterDeviceId, invite.inviteId),
            )
            db.insertWithOnConflict(
                TABLE_INVITES,
                null,
                ContentValues().apply {
                    put("invite_id", invite.inviteId)
                    put("group_id", invite.groupId)
                    put("inviter_device_id", invite.inviterDeviceId)
                    put("token", invite.token)
                    put("state", invite.state)
                    put("expires_at_ms", invite.expiresAtMs)
                    put("created_at_ms", invite.createdAtMs)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listPendingInvites(nowMs: Long = System.currentTimeMillis()): List<StoredPendingInvite> {
        val result = mutableListOf<StoredPendingInvite>()
        readableDatabase.query(
            TABLE_INVITES,
            null,
            "state = ? AND expires_at_ms > ?",
            arrayOf("PENDING", nowMs.toString()),
            null,
            null,
            "created_at_ms ASC, invite_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredPendingInvite(
                    inviteId = cursor.string("invite_id"),
                    groupId = cursor.string("group_id"),
                    inviterDeviceId = cursor.string("inviter_device_id"),
                    token = cursor.blob("token"),
                    state = cursor.string("state"),
                    expiresAtMs = cursor.long("expires_at_ms"),
                    createdAtMs = cursor.long("created_at_ms"),
                )
            }
        }
        return result
    }

    fun listDeclinedInvites(): List<StoredPendingInvite> =
        listInvitesByState("DECLINED")

    fun getStoredInvite(inviteId: String): StoredPendingInvite? =
        readableDatabase.query(
            TABLE_INVITES,
            null,
            "invite_id = ?",
            arrayOf(inviteId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toPendingInvite() else null }

    fun markInviteDeclined(inviteId: String): Boolean =
        writableDatabase.execUpdate(
            "UPDATE $TABLE_INVITES SET state = ? WHERE invite_id = ? AND state = ?",
            arrayOf<Any?>("DECLINED", inviteId, "PENDING"),
        ) > 0

    fun deletePendingInvite(inviteId: String): Boolean =
        writableDatabase.delete(TABLE_INVITES, "invite_id = ?", arrayOf(inviteId)) > 0

    private fun listInvitesByState(state: String): List<StoredPendingInvite> {
        val result = mutableListOf<StoredPendingInvite>()
        readableDatabase.query(
            TABLE_INVITES,
            null,
            "state = ?",
            arrayOf(state),
            null,
            null,
            "created_at_ms ASC, invite_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toPendingInvite()
        }
        return result
    }

    fun upsertSyncCursor(cursor: StoredSyncCursor) {
        upsertSyncCursor(writableDatabase, cursor)
    }

    fun getSyncCursor(groupId: String, deviceId: String): StoredSyncCursor? =
        readableDatabase.query(
            TABLE_SYNC_CURSORS,
            null,
            "group_id = ? AND device_id = ?",
            arrayOf(groupId, deviceId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                StoredSyncCursor(
                    groupId = cursor.string("group_id"),
                    deviceId = cursor.string("device_id"),
                    lastAuthorSeq = cursor.long("last_author_seq"),
                    lastEventId = cursor.nullableString("last_event_id"),
                    updatedAtMs = cursor.long("updated_at_ms"),
                )
            }
        }

    fun markRead(
        groupId: String,
        readAtMs: Long = System.currentTimeMillis(),
    ): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val changed = db.execUpdate(
                "UPDATE $TABLE_MESSAGES SET unread = 0, updated_at_ms = MAX(updated_at_ms, ?) " +
                    "WHERE group_id = ? AND unread != 0",
                arrayOf<Any?>(readAtMs, groupId),
            )
            db.execSQL(
                "UPDATE $TABLE_GROUPS SET unread_count = 0, " +
                    "updated_at_ms = MAX(updated_at_ms, ?) WHERE group_id = ?",
                arrayOf<Any?>(readAtMs, groupId),
            )
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Compare-and-swap for the serialized membership/role control chain.
     * Concurrent or stale control events therefore cannot both become current.
     */
    fun advanceControlHead(
        groupId: String,
        expectedHead: String?,
        newHead: String,
        ownerDeviceId: String? = null,
        currentEpoch: Long? = null,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        require(groupId.isNotBlank() && newHead.isNotBlank())
        val assignments = mutableListOf(
            "control_head = ?",
            "metadata_version = metadata_version + 1",
            "updated_at_ms = MAX(updated_at_ms, ?)",
        )
        val args = mutableListOf<Any?>(newHead, updatedAtMs)
        if (ownerDeviceId != null) {
            assignments += "owner_device_id = ?"
            args += ownerDeviceId
        }
        if (currentEpoch != null) {
            assignments += "current_epoch = ?"
            args += currentEpoch
        }
        val headPredicate = if (expectedHead == null) {
            "control_head IS NULL"
        } else {
            args += expectedHead
            "control_head = ?"
        }
        args += groupId
        return writableDatabase.execUpdate(
            "UPDATE $TABLE_GROUPS SET ${assignments.joinToString(", ")} " +
                "WHERE $headPredicate AND group_id = ?",
            args.toTypedArray(),
        ) > 0
    }

    /**
     * Atomically advances the serialized control head and applies its roster or
     * metadata projection. A crash can therefore never expose head B with
     * roster A.
     */
    fun applyControlMutation(
        groupId: String,
        expectedHead: String?,
        newHead: String,
        ownerDeviceId: String? = null,
        currentEpoch: Long? = null,
        title: String? = null,
        description: String? = null,
        avatarUri: String? = null,
        adminOnlyPosting: Boolean? = null,
        members: List<StoredGroupMember> = emptyList(),
        ownerLineageCertificate: StoredOwnerLineageCertificate? = null,
        admissionRecipientDeviceId: String? = null,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        require(groupId.isNotBlank() && newHead.isNotBlank())
        require(members.all { it.groupId == groupId })
        require(ownerLineageCertificate == null || ownerLineageCertificate.groupId == groupId)
        require(admissionRecipientDeviceId == null || admissionRecipientDeviceId.isNotBlank())
        if (title != null) require(title.isNotBlank() && title.length <= 160)
        if (description != null) require(description.length <= 2_000)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val currentHead = db.query(
                TABLE_GROUPS,
                arrayOf("control_head"),
                "group_id = ?",
                arrayOf(groupId),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return false
                if (cursor.isNull(0)) null else cursor.getString(0)
            }
            if (currentHead == newHead) {
                db.setTransactionSuccessful()
                return true
            }
            if (currentHead != expectedHead) return false

            ownerLineageCertificate?.let { certificate ->
                val existing = db.query(
                    TABLE_OWNER_LINEAGE,
                    null,
                    "group_id = ? AND sequence = ?",
                    arrayOf(groupId, certificate.sequence.toString()),
                    null,
                    null,
                    null,
                    "1",
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        null
                    } else {
                        StoredOwnerLineageCertificate(
                            groupId = cursor.string("group_id"),
                            sequence = cursor.int("sequence"),
                            transitionId = cursor.string("transition_id"),
                            payload = cursor.blob("payload"),
                        )
                    }
                }
                if (existing == null) {
                    val previousSequence = db.rawQuery(
                        "SELECT COALESCE(MAX(sequence), 0) FROM $TABLE_OWNER_LINEAGE " +
                            "WHERE group_id = ?",
                        arrayOf(groupId),
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getInt(0)
                    }
                    check(certificate.sequence == previousSequence + 1) {
                        "owner lineage sequence is not contiguous"
                    }
                    db.insertOrThrow(
                        TABLE_OWNER_LINEAGE,
                        null,
                        ownerLineageValues(certificate),
                    )
                } else {
                    check(
                        existing.transitionId == certificate.transitionId &&
                            existing.payload.contentEquals(certificate.payload),
                    ) { "conflicting owner lineage certificate" }
                }
            }
            val groupValues = ContentValues().apply {
                put("control_head", newHead)
                put("updated_at_ms", updatedAtMs)
                ownerDeviceId?.let { put("owner_device_id", it) }
                currentEpoch?.let { put("current_epoch", it) }
                title?.let { put("title", it) }
                description?.let { put("description", it) }
                avatarUri?.let { put("avatar_uri", it) }
                adminOnlyPosting?.let { put("admin_only_posting", if (it) 1 else 0) }
            }
            check(db.update(TABLE_GROUPS, groupValues, "group_id = ?", arrayOf(groupId)) == 1)
            db.execSQL(
                "UPDATE $TABLE_GROUPS SET metadata_version = metadata_version + 1 " +
                    "WHERE group_id = ?",
                arrayOf(groupId),
            )
            members.forEach { member ->
                db.insertWithOnConflict(
                    TABLE_MEMBERS,
                    null,
                    memberValues(member),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            admissionRecipientDeviceId?.let { recipientDeviceId ->
                val admitted = db.query(
                    TABLE_MEMBERS,
                    arrayOf("device_id"),
                    "group_id = ? AND device_id = ? AND status IN (?, ?)",
                    arrayOf(groupId, recipientDeviceId, "ACTIVE", "RESTRICTED"),
                    null,
                    null,
                    null,
                    "1",
                ).use(android.database.Cursor::moveToFirst)
                check(admitted) { "admission cut recipient is not participating" }
                db.delete(
                    TABLE_ADMISSION_CURSORS,
                    "group_id = ? AND recipient_device_id = ?",
                    arrayOf(groupId, recipientDeviceId),
                )
                db.execSQL(
                    """
                    INSERT INTO $TABLE_ADMISSION_CURSORS(
                        group_id, recipient_device_id, control_head,
                        author_device_id, last_author_sequence, created_at_ms
                    )
                    SELECT member.group_id, ?, ?, member.device_id,
                           COALESCE(MAX(event.author_seq), 0), ?
                    FROM $TABLE_MEMBERS AS member
                    LEFT JOIN $TABLE_EVENTS AS event
                      ON event.group_id = member.group_id
                     AND event.author_device_id = member.device_id
                    WHERE member.group_id = ? AND member.status IN (?, ?)
                    GROUP BY member.group_id, member.device_id
                    """.trimIndent(),
                    arrayOf<Any?>(
                        recipientDeviceId,
                        newHead,
                        updatedAtMs,
                        groupId,
                        "ACTIVE",
                        "RESTRICTED",
                    ),
                )
                val captured = db.rawQuery(
                    "SELECT COUNT(*) FROM $TABLE_ADMISSION_CURSORS " +
                        "WHERE group_id = ? AND recipient_device_id = ? AND control_head = ?",
                    arrayOf(groupId, recipientDeviceId, newHead),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
                check(captured > 0) { "admission cut is empty" }
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun setPinnedEvent(
        groupId: String,
        eventId: String?,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean = writableDatabase.execUpdate(
        "UPDATE $TABLE_GROUPS SET pinned_event_id = ?, " +
            "updated_at_ms = MAX(updated_at_ms, ?) WHERE group_id = ?",
        arrayOf<Any?>(eventId, updatedAtMs, groupId),
    ) > 0

    fun updateGroupMetadata(
        groupId: String,
        title: String,
        description: String,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        require(title.isNotBlank() && title.length <= 160)
        require(description.length <= 2_000)
        return writableDatabase.execUpdate(
            "UPDATE $TABLE_GROUPS SET title = ?, description = ?, " +
                "updated_at_ms = MAX(updated_at_ms, ?) WHERE group_id = ?",
            arrayOf<Any?>(title, description, updatedAtMs, groupId),
        ) > 0
    }

    fun deleteGroup(groupId: String): Boolean =
        writableDatabase.delete(TABLE_GROUPS, "group_id = ?", arrayOf(groupId)) > 0

    fun closeAndDelete(context: Context): Boolean {
        close()
        return context.applicationContext.deleteDatabase(databaseName)
    }

    private fun validateEventForIngest(event: StoredGroupEvent) {
        require(event.groupId.isNotBlank() && event.eventId.isNotBlank())
        require(event.authorDeviceId.isNotBlank())
        require(event.authorSeq >= 0 && event.hlcLogical >= 0)
    }

    private fun insertAndMaterializeEvent(
        db: SQLiteDatabase,
        event: StoredGroupEvent,
        countAsUnread: Boolean,
    ): Boolean {
        val inserted = db.insertWithOnConflict(
            TABLE_EVENTS,
            null,
            eventValues(event),
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        if (!inserted) return false

        when (event.kind) {
            StoredGroupEventKind.MESSAGE.name,
            "POLL",
            "MEDIA",
            ->
                rebuildMaterializedMessage(
                    db,
                    event.groupId,
                    event.eventId,
                    newMessageUnread = countAsUnread,
                )
            StoredGroupEventKind.EDIT.name,
            StoredGroupEventKind.DELETE.name,
            -> event.targetEventId?.let {
                rebuildMaterializedMessage(db, event.groupId, it, newMessageUnread = null)
            }
            "PIN",
            "UNPIN",
            -> rebuildPinnedEvent(db, event.groupId)
            "READ_RECEIPT" -> event.targetEventId?.let { target ->
                db.insertWithOnConflict(
                    TABLE_RECEIPTS,
                    null,
                    ContentValues().apply {
                        put("group_id", event.groupId)
                        put("event_id", target)
                        put("recipient_device_id", event.authorDeviceId)
                        put("type", "READ")
                        put("received_at_ms", event.hlcPhysicalMs)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            "REACTION_ADD",
            "REACTION_REMOVE",
            -> materializeReaction(db, event)
        }
        if (event.kind in setOf(StoredGroupEventKind.MESSAGE.name, "POLL", "MEDIA") && countAsUnread) {
            db.execSQL(
                "UPDATE $TABLE_GROUPS SET unread_count = unread_count + 1, " +
                    "updated_at_ms = MAX(updated_at_ms, ?) WHERE group_id = ?",
                arrayOf<Any?>(event.receivedAtMs, event.groupId),
            )
        }
        return true
    }

    private fun materializeReaction(db: SQLiteDatabase, event: StoredGroupEvent) {
        val target = event.targetEventId?.takeIf { it.isNotBlank() } ?: return
        val emoji = event.body?.takeIf { it.isNotBlank() } ?: return
        val existing = db.query(
            TABLE_REACTIONS,
            arrayOf(
                "hlc_physical_ms",
                "hlc_logical",
                "author_seq",
                "event_id",
            ),
            "group_id = ? AND target_event_id = ? AND emoji = ? AND author_device_id = ?",
            arrayOf(event.groupId, target, emoji, event.authorDeviceId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                listOf(
                    cursor.long("hlc_physical_ms"),
                    cursor.long("hlc_logical"),
                    cursor.long("author_seq"),
                ) to cursor.string("event_id")
            }
        }
        if (existing != null) {
            val currentNumbers = existing.first
            val newer = when {
                event.hlcPhysicalMs != currentNumbers[0] ->
                    event.hlcPhysicalMs > currentNumbers[0]
                event.hlcLogical.toLong() != currentNumbers[1] ->
                    event.hlcLogical.toLong() > currentNumbers[1]
                event.authorSeq != currentNumbers[2] ->
                    event.authorSeq > currentNumbers[2]
                else -> event.eventId > existing.second
            }
            if (!newer) return
        }
        db.insertWithOnConflict(
            TABLE_REACTIONS,
            null,
            ContentValues().apply {
                put("group_id", event.groupId)
                put("target_event_id", target)
                put("emoji", emoji)
                put("author_device_id", event.authorDeviceId)
                put("active", if (event.kind == "REACTION_ADD") 1 else 0)
                put("event_id", event.eventId)
                put("hlc_physical_ms", event.hlcPhysicalMs)
                put("hlc_logical", event.hlcLogical)
                put("author_seq", event.authorSeq)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun createReactionTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_reactions(
                group_id TEXT NOT NULL,
                target_event_id TEXT NOT NULL,
                emoji TEXT NOT NULL,
                author_device_id TEXT NOT NULL,
                active INTEGER NOT NULL CHECK(active IN (0, 1)),
                event_id TEXT NOT NULL,
                hlc_physical_ms INTEGER NOT NULL,
                hlc_logical INTEGER NOT NULL,
                author_seq INTEGER NOT NULL,
                PRIMARY KEY(group_id, target_event_id, emoji, author_device_id),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_reactions_active " +
                "ON group_reactions(group_id, active, target_event_id)",
        )
    }

    private fun createProtocolRecoveryTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS owner_lineage_certificates(
                group_id TEXT NOT NULL,
                sequence INTEGER NOT NULL CHECK(sequence > 0),
                transition_id TEXT NOT NULL,
                payload BLOB NOT NULL,
                PRIMARY KEY(group_id, sequence),
                UNIQUE(group_id, transition_id),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS roster_snapshot_pages(
                group_id TEXT NOT NULL,
                control_head TEXT NOT NULL,
                epoch INTEGER NOT NULL CHECK(epoch > 0),
                page_index INTEGER NOT NULL CHECK(page_index >= 0),
                total_pages INTEGER NOT NULL CHECK(total_pages > 0),
                snapshot_created_at_ms INTEGER NOT NULL,
                payload BLOB NOT NULL,
                PRIMARY KEY(group_id, control_head, page_index),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS admission_cursors(
                group_id TEXT NOT NULL,
                recipient_device_id TEXT NOT NULL,
                control_head TEXT NOT NULL,
                author_device_id TEXT NOT NULL,
                last_author_sequence INTEGER NOT NULL CHECK(last_author_sequence >= 0),
                created_at_ms INTEGER NOT NULL,
                PRIMARY KEY(group_id, recipient_device_id, control_head, author_device_id),
                FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun upsertSyncCursor(db: SQLiteDatabase, cursor: StoredSyncCursor) {
        require(cursor.groupId.isNotBlank() && cursor.deviceId.isNotBlank())
        require(cursor.lastAuthorSeq >= 0L)
        val existing = db.query(
            TABLE_SYNC_CURSORS,
            arrayOf("last_author_seq"),
            "group_id = ? AND device_id = ?",
            arrayOf(cursor.groupId, cursor.deviceId),
            null,
            null,
            null,
            "1",
        ).use { row -> if (row.moveToFirst()) row.getLong(0) else null }
        if (existing != null && existing > cursor.lastAuthorSeq) return
        db.insertWithOnConflict(
            TABLE_SYNC_CURSORS,
            null,
            ContentValues().apply {
                put("group_id", cursor.groupId)
                put("device_id", cursor.deviceId)
                put("last_author_seq", cursor.lastAuthorSeq)
                put("last_event_id", cursor.lastEventId)
                put("updated_at_ms", cursor.updatedAtMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun rebuildPinnedEvent(db: SQLiteDatabase, groupId: String) {
        val latest = db.query(
            TABLE_EVENTS,
            arrayOf("kind", "target_event_id", "hlc_physical_ms"),
            "group_id = ? AND kind IN (?, ?)",
            arrayOf(groupId, "PIN", "UNPIN"),
            null,
            null,
            "hlc_physical_ms DESC, hlc_logical DESC, author_device_id DESC, " +
                "author_seq DESC, event_id DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                Triple(
                    cursor.string("kind"),
                    cursor.nullableString("target_event_id"),
                    cursor.long("hlc_physical_ms"),
                )
            }
        }
        val values = ContentValues().apply {
            if (latest?.first == "PIN") {
                put("pinned_event_id", latest.second)
            } else {
                putNull("pinned_event_id")
            }
        }
        db.update(TABLE_GROUPS, values, "group_id = ?", arrayOf(groupId))
        latest?.third?.let { timestamp ->
            db.execSQL(
                "UPDATE $TABLE_GROUPS SET updated_at_ms = MAX(updated_at_ms, ?) " +
                    "WHERE group_id = ?",
                arrayOf<Any?>(timestamp, groupId),
            )
        }
    }

    private fun rebuildMaterializedMessage(
        db: SQLiteDatabase,
        groupId: String,
        messageId: String,
        newMessageUnread: Boolean?,
    ) {
        val base = db.query(
            TABLE_EVENTS,
            null,
            "group_id = ? AND event_id = ? AND kind IN (?, ?, ?)",
            arrayOf(
                groupId,
                messageId,
                StoredGroupEventKind.MESSAGE.name,
                "POLL",
                "MEDIA",
            ),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toEvent() else null } ?: return

        var body = base.body.orEmpty()
        var edited = false
        var deleted = false
        var updatedAt = base.receivedAtMs
        db.query(
            TABLE_EVENTS,
            null,
            "group_id = ? AND target_event_id = ? AND kind IN (?, ?)",
            arrayOf(
                groupId,
                messageId,
                StoredGroupEventKind.EDIT.name,
                StoredGroupEventKind.DELETE.name,
            ),
            null,
            null,
            EVENT_ASC_ORDER,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val mutation = cursor.toEvent()
                updatedAt = maxOf(updatedAt, mutation.receivedAtMs)
                when (mutation.kind) {
                    StoredGroupEventKind.EDIT.name -> {
                        body = mutation.body.orEmpty()
                        edited = true
                    }
                    StoredGroupEventKind.DELETE.name -> deleted = true
                }
            }
        }

        val previousUnread = db.query(
            TABLE_MESSAGES,
            arrayOf("unread"),
            "group_id = ? AND message_id = ?",
            arrayOf(groupId, messageId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) != 0 else false
        }
        db.insertWithOnConflict(
            TABLE_MESSAGES,
            null,
            ContentValues().apply {
                put("group_id", groupId)
                put("message_id", messageId)
                put("author_device_id", base.authorDeviceId)
                put("author_seq", base.authorSeq)
                put("hlc_physical_ms", base.hlcPhysicalMs)
                put("hlc_logical", base.hlcLogical)
                put("body", body)
                put("edited", edited.asInt())
                put("deleted", deleted.asInt())
                put("unread", (newMessageUnread ?: previousUnread).asInt())
                put("created_at_ms", base.createdAtMs)
                put("updated_at_ms", updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun queryOutbox(db: SQLiteDatabase, taskId: String): StoredOutboxTask? =
        db.query(
            TABLE_OUTBOX,
            null,
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toOutboxTask() else null }

    private fun groupValues(group: StoredGroup) = ContentValues().apply {
        put("group_id", group.groupId)
        put("title", group.title)
        put("description", group.description)
        put("avatar_uri", group.avatarUri)
        put("local_device_id", group.localDeviceId)
        put("owner_device_id", group.ownerDeviceId)
        put("current_epoch", group.currentEpoch)
        put("control_head", group.controlHead)
        put("pinned_event_id", group.pinnedEventId)
        put("metadata_version", group.metadataVersion)
        put("unread_count", group.unreadCount)
        put("admin_only_posting", if (group.adminOnlyPosting) 1 else 0)
        put("created_at_ms", group.createdAtMs)
        put("updated_at_ms", group.updatedAtMs)
    }

    private fun memberValues(member: StoredGroupMember) = ContentValues().apply {
        put("group_id", member.groupId)
        put("device_id", member.deviceId)
        put("account_id", member.accountId)
        put("display_name", member.displayName)
        put("transport_fingerprint", member.transportFingerprint)
        put("peer_name", member.peerName)
        put("signing_key_base64", member.signingKeyBase64)
        put("role", member.role)
        put("permissions", member.permissions)
        put("status", member.status)
        put("joined_epoch", member.joinedEpoch)
        putNullableLong("removed_epoch", member.removedEpoch)
        put("created_at_ms", member.createdAtMs)
        put("updated_at_ms", member.updatedAtMs)
    }

    private fun epochKeyValues(key: StoredGroupEpochKey) = ContentValues().apply {
        put("group_id", key.groupId)
        put("epoch", key.epoch)
        put("key_material", key.keyMaterial)
        put("created_at_ms", key.createdAtMs)
        putNullableLong("expires_at_ms", key.expiresAtMs)
    }

    private fun eventValues(event: StoredGroupEvent) = ContentValues().apply {
        put("group_id", event.groupId)
        put("event_id", event.eventId)
        put("epoch", event.epoch)
        put("author_device_id", event.authorDeviceId)
        put("author_seq", event.authorSeq)
        put("hlc_physical_ms", event.hlcPhysicalMs)
        put("hlc_logical", event.hlcLogical)
        put("kind", event.kind)
        put("body", event.body)
        put("target_event_id", event.targetEventId)
        put("control_head", event.controlHead)
        put("payload", event.payload)
        put("created_at_ms", event.createdAtMs)
        put("received_at_ms", event.receivedAtMs)
    }

    private fun outboxValues(task: StoredOutboxTask) = ContentValues().apply {
        put("task_id", task.taskId)
        put("group_id", task.groupId)
        put("event_id", task.eventId)
        put("recipient_device_id", task.recipientDeviceId)
        put("payload", task.payload)
        put("state", task.state)
        put("attempts", task.attempts)
        put("next_attempt_ms", task.nextAttemptMs)
        put("last_error", task.lastError)
        put("created_at_ms", task.createdAtMs)
        put("updated_at_ms", task.updatedAtMs)
    }

    private fun ownerLineageValues(certificate: StoredOwnerLineageCertificate) =
        ContentValues().apply {
            put("group_id", certificate.groupId)
            put("sequence", certificate.sequence)
            put("transition_id", certificate.transitionId)
            put("payload", certificate.payload)
        }

    private fun insertOwnerLineage(
        db: SQLiteDatabase,
        groupId: String,
        certificates: List<StoredOwnerLineageCertificate>,
    ) {
        require(certificates.all { it.groupId == groupId })
        require(certificates.map { it.sequence } == (1..certificates.size).toList())
        require(certificates.map { it.transitionId }.toSet().size == certificates.size)
        db.delete(TABLE_OWNER_LINEAGE, "group_id = ?", arrayOf(groupId))
        certificates.forEach { certificate ->
            db.insertOrThrow(
                TABLE_OWNER_LINEAGE,
                null,
                ownerLineageValues(certificate),
            )
        }
    }

    private fun rosterSnapshotPageValues(page: StoredRosterSnapshotPage) =
        ContentValues().apply {
            put("group_id", page.groupId)
            put("control_head", page.controlHead)
            put("epoch", page.epoch)
            put("page_index", page.pageIndex)
            put("total_pages", page.totalPages)
            put("snapshot_created_at_ms", page.snapshotCreatedAtMs)
            put("payload", page.payload)
        }

    private fun android.database.Cursor.toGroup() = StoredGroup(
        groupId = string("group_id"),
        title = string("title"),
        description = string("description"),
        avatarUri = nullableString("avatar_uri"),
        localDeviceId = string("local_device_id"),
        ownerDeviceId = string("owner_device_id"),
        currentEpoch = long("current_epoch"),
        controlHead = nullableString("control_head"),
        pinnedEventId = nullableString("pinned_event_id"),
        metadataVersion = long("metadata_version"),
        unreadCount = int("unread_count"),
        adminOnlyPosting = int("admin_only_posting") != 0,
        createdAtMs = long("created_at_ms"),
        updatedAtMs = long("updated_at_ms"),
    )

    private fun android.database.Cursor.toMember() = StoredGroupMember(
        groupId = string("group_id"),
        deviceId = string("device_id"),
        accountId = string("account_id"),
        displayName = string("display_name"),
        transportFingerprint = string("transport_fingerprint"),
        peerName = string("peer_name"),
        signingKeyBase64 = string("signing_key_base64"),
        role = string("role"),
        permissions = long("permissions"),
        status = string("status"),
        joinedEpoch = long("joined_epoch"),
        removedEpoch = nullableLong("removed_epoch"),
        createdAtMs = long("created_at_ms"),
        updatedAtMs = long("updated_at_ms"),
    )

    private fun android.database.Cursor.toEvent() = StoredGroupEvent(
        groupId = string("group_id"),
        eventId = string("event_id"),
        epoch = long("epoch"),
        authorDeviceId = string("author_device_id"),
        authorSeq = long("author_seq"),
        hlcPhysicalMs = long("hlc_physical_ms"),
        hlcLogical = int("hlc_logical"),
        kind = string("kind"),
        body = nullableString("body"),
        targetEventId = nullableString("target_event_id"),
        controlHead = nullableString("control_head"),
        payload = nullableBlob("payload"),
        createdAtMs = long("created_at_ms"),
        receivedAtMs = long("received_at_ms"),
    )

    private fun android.database.Cursor.toMessage() = StoredGroupMessage(
        groupId = string("group_id"),
        messageId = string("message_id"),
        authorDeviceId = string("author_device_id"),
        authorSeq = long("author_seq"),
        hlcPhysicalMs = long("hlc_physical_ms"),
        hlcLogical = int("hlc_logical"),
        body = string("body"),
        edited = int("edited") != 0,
        deleted = int("deleted") != 0,
        unread = int("unread") != 0,
        createdAtMs = long("created_at_ms"),
        updatedAtMs = long("updated_at_ms"),
    )

    private fun android.database.Cursor.toOutboxTask() = StoredOutboxTask(
        taskId = string("task_id"),
        groupId = string("group_id"),
        eventId = string("event_id"),
        recipientDeviceId = string("recipient_device_id"),
        payload = blob("payload"),
        state = string("state"),
        attempts = int("attempts"),
        nextAttemptMs = long("next_attempt_ms"),
        lastError = nullableString("last_error"),
        createdAtMs = long("created_at_ms"),
        updatedAtMs = long("updated_at_ms"),
    )

    private fun android.database.Cursor.toPendingInvite() = StoredPendingInvite(
        inviteId = string("invite_id"),
        groupId = string("group_id"),
        inviterDeviceId = string("inviter_device_id"),
        token = blob("token"),
        state = string("state"),
        expiresAtMs = long("expires_at_ms"),
        createdAtMs = long("created_at_ms"),
    )

    private fun android.database.Cursor.string(column: String): String =
        getString(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.int(column: String): Int =
        getInt(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.long(column: String): Long =
        getLong(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.blob(column: String): ByteArray =
        getBlob(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.nullableBlob(column: String): ByteArray? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getBlob(index)
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Boolean.asInt(): Int = if (this) 1 else 0

    private fun SQLiteDatabase.execUpdate(sql: String, bindArgs: Array<out Any?>): Int {
        compileStatement(sql).use { statement ->
            bindArgs.forEachIndexed { index, value ->
                val position = index + 1
                when (value) {
                    null -> statement.bindNull(position)
                    is ByteArray -> statement.bindBlob(position, value)
                    is Float -> statement.bindDouble(position, value.toDouble())
                    is Double -> statement.bindDouble(position, value)
                    is Number -> statement.bindLong(position, value.toLong())
                    else -> statement.bindString(position, value.toString())
                }
            }
            return statement.executeUpdateDelete()
        }
    }

    fun clearHistory(groupId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("group_events", "group_id = ?", arrayOf(groupId))
            db.delete("group_messages", "group_id = ?", arrayOf(groupId))
            db.delete("group_attachments", "group_id = ?", arrayOf(groupId))
            db.execSQL("UPDATE groups SET unread_count = 0, pinned_event_id = NULL WHERE group_id = ?", arrayOf(groupId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DATABASE_NAME = "twopchat-groups.db"
        const val DATABASE_VERSION = 6
        private const val MAX_PAGE_SIZE = 1_000

        private const val TABLE_GROUPS = "groups"
        private const val TABLE_MEMBERS = "group_members"
        private const val TABLE_EPOCH_KEYS = "group_epoch_keys"
        private const val TABLE_EVENTS = "group_events"
        private const val TABLE_MESSAGES = "group_messages"
        private const val TABLE_OUTBOX = "outbox_tasks"
        private const val TABLE_RECEIPTS = "receipts"
        private const val TABLE_INVITES = "pending_invites"
        private const val TABLE_OWNER_LINEAGE = "owner_lineage_certificates"
        private const val TABLE_ROSTER_PAGES = "roster_snapshot_pages"
        private const val TABLE_ADMISSION_CURSORS = "admission_cursors"
        private const val TABLE_SYNC_CURSORS = "sync_cursors"
        private const val TABLE_REACTIONS = "group_reactions"

        private const val TIMELINE_ORDER =
            "hlc_physical_ms DESC, hlc_logical DESC, author_device_id DESC, " +
                "author_seq DESC, message_id DESC"
        private const val EVENT_ASC_ORDER =
            "hlc_physical_ms ASC, hlc_logical ASC, author_device_id ASC, " +
                "author_seq ASC, event_id ASC"

        fun deleteDatabase(context: Context): Boolean =
            context.applicationContext.deleteDatabase(DATABASE_NAME)
    }
}
