package com.example.twopchat.data

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import android.content.ContentValues
import com.example.twopchat.ui.chat.Message
import com.example.twopchat.SecureStorage
import android.util.Log
import com.example.twopchat.ui.chat.MessageDeliveryStatus

data class StoredAttachmentRecord(
    val messageId: String,
    val attachmentType: String?,
    val uri: String,
    val attachmentName: String?,
    val status: String?,
    val isMine: Boolean,
)

class ChatDatabaseHelper private constructor(private val context: Context) : 
    SQLiteOpenHelper(
        context, 
        DATABASE_NAME, 
        SecureStorage.getOrGenerateDbPassphrase(context), 
        null, 
        DATABASE_VERSION, 
        0, 
        null, 
        null, 
        false
    ) {

    companion object {
        private const val DATABASE_NAME = "twopchat.db"
        private const val DATABASE_VERSION = 10
        private const val TABLE_MESSAGES = "messages"
        private const val TABLE_PENDING_CONTROLS = "pending_controls"
        
        private const val KEY_ID = "id"
        private const val KEY_PEER_NAME = "peer_name"
        private const val KEY_MESSAGE_TEXT = "message_text"
        private const val KEY_IS_ME = "is_me"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_ATTACHMENT_TYPE = "attachment_type"
        private const val KEY_ATTACHMENT_URI = "attachment_uri"
        private const val KEY_ATTACHMENT_NAME = "attachment_name"
        private const val KEY_REPLY_TO_ID = "reply_to_id"
        private const val KEY_REPLY_TO_TEXT = "reply_to_text"
        private const val KEY_REPLY_TO_NAME = "reply_to_name"
        private const val KEY_STATUS = "status"
        private const val KEY_REACTIONS = "reactions"
        private const val KEY_SENT_AT_MS = "sent_at_ms"
        private const val KEY_IS_PINNED = "is_pinned"
        private const val KEY_ALBUM_URIS = "album_uris"
        private const val KEY_ALBUM_TYPES = "album_types"
        private const val KEY_CONTROL_ID = "control_id"
        private const val KEY_CONTROL_TYPE = "control_type"
        private const val KEY_CONTROL_PAYLOAD = "control_payload"
        private const val KEY_CREATED_AT_MS = "created_at_ms"
        private const val TAG = "ChatDatabaseHelper"
        private val dbLock = Any()
        private val activeHelpers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ChatDatabaseHelper, Boolean>())
        @Volatile private var instance: ChatDatabaseHelper? = null
        @Volatile private var isMigrationChecked = false

        fun getInstance(context: Context): ChatDatabaseHelper =
            instance ?: synchronized(dbLock) {
                instance ?: ChatDatabaseHelper(context.applicationContext).also { instance = it }
            }

        fun closeAllConnections() {
            synchronized(dbLock) {
                for (helper in activeHelpers) {
                    try {
                        helper.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to close database connection", e)
                    }
                }
                activeHelpers.clear()
                instance = null
                isMigrationChecked = false
            }
        }
    }

    init {
        synchronized(activeHelpers) {
            activeHelpers.add(this)
        }
    }

    private val safeWritableDatabase: SQLiteDatabase
        get() {
            if (!isMigrationChecked) {
                synchronized(dbLock) {
                    if (!isMigrationChecked) {
                        val pass = SecureStorage.getOrGenerateDbPassphrase(context)
                        val dbFile = context.getDatabasePath(DATABASE_NAME)
                        checkAndMigrateDatabase(context, dbFile, pass)
                        isMigrationChecked = true
                    }
                }
            }
            return writableDatabase
        }

    private val safeReadableDatabase: SQLiteDatabase
        get() {
            if (!isMigrationChecked) {
                synchronized(dbLock) {
                    if (!isMigrationChecked) {
                        val pass = SecureStorage.getOrGenerateDbPassphrase(context)
                        val dbFile = context.getDatabasePath(DATABASE_NAME)
                        checkAndMigrateDatabase(context, dbFile, pass)
                        isMigrationChecked = true
                    }
                }
            }
            return readableDatabase
        }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try {
            db.enableWriteAheadLogging()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enable Write-Ahead Logging (WAL)", e)
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_MESSAGES + "("
                + KEY_ID + " TEXT PRIMARY KEY,"
                + KEY_PEER_NAME + " TEXT,"
                + KEY_MESSAGE_TEXT + " TEXT,"
                + KEY_IS_ME + " INTEGER,"
                + KEY_TIMESTAMP + " TEXT,"
                + KEY_ATTACHMENT_TYPE + " TEXT,"
                + KEY_ATTACHMENT_URI + " TEXT,"
                + KEY_ATTACHMENT_NAME + " TEXT,"
                + KEY_REPLY_TO_ID + " TEXT,"
                + KEY_REPLY_TO_TEXT + " TEXT,"
                + KEY_REPLY_TO_NAME + " TEXT,"
                + KEY_STATUS + " TEXT,"
                + KEY_REACTIONS + " TEXT,"
                + KEY_SENT_AT_MS + " INTEGER NOT NULL DEFAULT 0,"
                + KEY_IS_PINNED + " INTEGER NOT NULL DEFAULT 0,"
                + KEY_ALBUM_URIS + " TEXT,"
                + KEY_ALBUM_TYPES + " TEXT" + ")")
        db.execSQL(createTable)
        createMessagePeerIndex(db)
        createPendingControlsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $KEY_REPLY_TO_ID TEXT")
                db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $KEY_REPLY_TO_TEXT TEXT")
                db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $KEY_REPLY_TO_NAME TEXT")
            } catch (e: Exception) {
                Log.e(TAG, "Legacy reply-column migration failed", e)
            }
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $KEY_STATUS TEXT")
            } catch (e: Exception) {
                Log.e(TAG, "Legacy status-column migration failed", e)
            }
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES RENAME TO messages_old")
                val createTable = ("CREATE TABLE " + TABLE_MESSAGES + "("
                        + KEY_ID + " TEXT PRIMARY KEY,"
                        + KEY_PEER_NAME + " TEXT,"
                        + KEY_MESSAGE_TEXT + " TEXT,"
                        + KEY_IS_ME + " INTEGER,"
                        + KEY_TIMESTAMP + " TEXT,"
                        + KEY_ATTACHMENT_TYPE + " TEXT,"
                        + KEY_ATTACHMENT_URI + " TEXT,"
                        + KEY_ATTACHMENT_NAME + " TEXT,"
                        + KEY_REPLY_TO_ID + " TEXT,"
                        + KEY_REPLY_TO_TEXT + " TEXT,"
                        + KEY_REPLY_TO_NAME + " TEXT,"
                        + KEY_STATUS + " TEXT" + ")")
                db.execSQL(createTable)
                db.execSQL("INSERT INTO $TABLE_MESSAGES ($KEY_ID, $KEY_PEER_NAME, $KEY_MESSAGE_TEXT, $KEY_IS_ME, $KEY_TIMESTAMP, " +
                        "$KEY_ATTACHMENT_TYPE, $KEY_ATTACHMENT_URI, $KEY_ATTACHMENT_NAME, $KEY_REPLY_TO_ID, $KEY_REPLY_TO_TEXT, $KEY_REPLY_TO_NAME, $KEY_STATUS) " +
                        "SELECT CAST($KEY_ID AS TEXT), $KEY_PEER_NAME, $KEY_MESSAGE_TEXT, $KEY_IS_ME, $KEY_TIMESTAMP, " +
                        "$KEY_ATTACHMENT_TYPE, $KEY_ATTACHMENT_URI, $KEY_ATTACHMENT_NAME, $KEY_REPLY_TO_ID, $KEY_REPLY_TO_TEXT, $KEY_REPLY_TO_NAME, $KEY_STATUS " +
                        "FROM messages_old")
                db.execSQL("DROP TABLE messages_old")
            } catch (e: Exception) {
                // Never destroy user history on migration failure. Abort the upgrade so
                // SQLite can roll the transaction back and report the actionable error.
                throw android.database.sqlite.SQLiteException("Message database migration failed", e)
            }
        }
        if (oldVersion < 5) {
            // Encrypt legacy sensitive columns in-place. The transaction managed by
            // SQLiteOpenHelper guarantees an all-or-nothing migration.
            val sensitive = arrayOf(
                KEY_MESSAGE_TEXT, KEY_ATTACHMENT_URI, KEY_ATTACHMENT_NAME,
                KEY_REPLY_TO_TEXT, KEY_REPLY_TO_NAME
            )
            db.query(TABLE_MESSAGES, arrayOf(KEY_ID, *sensitive), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val values = ContentValues()
                    sensitive.forEach { column ->
                        val index = cursor.getColumnIndexOrThrow(column)
                        if (!cursor.isNull(index)) {
                            val current = cursor.getString(index)
                            if (!SecureStorage.isEncrypted(current)) values.put(column, enc(current))
                        }
                    }
                    if (values.size() > 0) {
                        db.update(TABLE_MESSAGES, values, "$KEY_ID = ?", arrayOf(cursor.getString(0)))
                    }
                }
            }
        }
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $KEY_REACTIONS TEXT")
            } catch (e: Exception) {
                Log.e(TAG, "Legacy reactions-column migration failed", e)
            }
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $KEY_SENT_AT_MS INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8) {
            createPendingControlsTable(db)
        }
        if (oldVersion < 9) {
            createMessagePeerIndex(db)
        }
        if (oldVersion < 10) {
            db.execSQL(
                "ALTER TABLE $TABLE_MESSAGES " +
                    "ADD COLUMN $KEY_IS_PINNED INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $KEY_ALBUM_URIS TEXT")
            db.execSQL("ALTER TABLE $TABLE_MESSAGES ADD COLUMN $KEY_ALBUM_TYPES TEXT")
        }
    }

    private fun serializeReactions(reactions: Map<String, List<String>>): String {
        val json = org.json.JSONObject()
        reactions.forEach { (emoji, senders) ->
            val arr = org.json.JSONArray()
            senders.forEach { arr.put(it) }
            json.put(emoji, arr)
        }
        return json.toString()
    }

    private fun deserializeReactions(jsonStr: String?): Map<String, List<String>> {
        if (jsonStr.isNullOrEmpty()) return emptyMap()
        return try {
            val json = org.json.JSONObject(jsonStr)
            val map = mutableMapOf<String, List<String>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val emoji = keys.next()
                val arr = json.optJSONArray(emoji)
                val list = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        list.add(arr.optString(i))
                    }
                }
                if (list.isNotEmpty()) {
                    map[emoji] = list
                }
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun updateMessagePinned(id: String, isPinned: Boolean) {
        val db = this.safeWritableDatabase
        val values = ContentValues().apply {
            put(KEY_IS_PINNED, if (isPinned) 1 else 0)
        }
        db.update(TABLE_MESSAGES, values, "$KEY_ID = ?", arrayOf(id))
    }

    fun getPinnedMessagesForPeer(peerName: String): List<Message> {
        val messages = mutableListOf<Message>()
        val db = this.safeReadableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_PEER_NAME = ? AND $KEY_IS_PINNED = 1",
            arrayOf(peerName),
            null,
            null,
            "rowid ASC"
        )
        cursor.use {
            val stringCipher = SecureStorage.newStringCipher()
            while (it.moveToNext()) {
                messages.add(readMessageFromCursor(it, stringCipher))
            }
        }
        return messages
    }

    fun unpinAllMessagesForPeer(peerName: String) {
        val db = this.safeWritableDatabase
        val values = ContentValues().apply {
            put(KEY_IS_PINNED, 0)
        }
        db.update(TABLE_MESSAGES, values, "$KEY_PEER_NAME = ?", arrayOf(peerName))
    }

    private fun getMessageStatusById(db: SQLiteDatabase, id: String): String? {
        return try {
            db.query(TABLE_MESSAGES, arrayOf(KEY_STATUS), "$KEY_ID = ?", arrayOf(id), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(KEY_STATUS)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun isHigherPriorityStatus(existing: String, incoming: String): Boolean {
        val priority = mapOf("read" to 4, "delivered" to 3, "sent" to 2, "sending" to 1)
        val pExisting = priority[existing.lowercase()] ?: 0
        val pIncoming = priority[incoming.lowercase()] ?: 0
        return pExisting > pIncoming
    }

    fun saveMessage(peerName: String, msg: Message) {
        val db = this.safeWritableDatabase
        val existingStatus = getMessageStatusById(db, msg.id)
        val finalStatus = if (existingStatus != null && isHigherPriorityStatus(existingStatus, msg.status)) {
            existingStatus
        } else {
            msg.status
        }
        val values = ContentValues().apply {
            put(KEY_ID, msg.id)
            put(KEY_PEER_NAME, peerName)
            put(KEY_MESSAGE_TEXT, enc(msg.text))
            put(KEY_IS_ME, if (msg.isMe) 1 else 0)
            put(KEY_TIMESTAMP, msg.timestamp)
            put(KEY_ATTACHMENT_TYPE, msg.attachmentType)
            put(KEY_ATTACHMENT_URI, encNullable(msg.attachmentUri))
            put(KEY_ATTACHMENT_NAME, encNullable(msg.attachmentName))
            put(KEY_REPLY_TO_ID, msg.replyToId)
            put(KEY_REPLY_TO_TEXT, encNullable(msg.replyToText))
            put(KEY_REPLY_TO_NAME, encNullable(msg.replyToName))
            put(KEY_STATUS, finalStatus)
            put(KEY_REACTIONS, encNullable(serializeReactions(msg.reactions)))
            put(KEY_SENT_AT_MS, msg.sentAtEpochMs)
            put(KEY_IS_PINNED, if (msg.isPinned) 1 else 0)
            put(KEY_ALBUM_URIS, encNullable(if (msg.albumMediaUris.isNotEmpty()) msg.albumMediaUris.joinToString("|||") else null))
            put(KEY_ALBUM_TYPES, if (msg.albumMediaTypes.isNotEmpty()) msg.albumMediaTypes.joinToString("|||") else null)
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun readMessageFromCursor(
        cursor: android.database.Cursor,
        stringCipher: SecureStorage.StringCipher,
    ): Message {
        val indexText = cursor.getColumnIndex(KEY_MESSAGE_TEXT)
        val indexIsMe = cursor.getColumnIndex(KEY_IS_ME)
        val indexTimestamp = cursor.getColumnIndex(KEY_TIMESTAMP)
        val indexAttachType = cursor.getColumnIndex(KEY_ATTACHMENT_TYPE)
        val indexAttachUri = cursor.getColumnIndex(KEY_ATTACHMENT_URI)
        val indexAttachName = cursor.getColumnIndex(KEY_ATTACHMENT_NAME)
        val indexReplyToId = cursor.getColumnIndex(KEY_REPLY_TO_ID)
        val indexReplyToText = cursor.getColumnIndex(KEY_REPLY_TO_TEXT)
        val indexReplyToName = cursor.getColumnIndex(KEY_REPLY_TO_NAME)
        val indexStatus = cursor.getColumnIndex(KEY_STATUS)
        val indexReactions = cursor.getColumnIndex(KEY_REACTIONS)
        val indexId = cursor.getColumnIndex(KEY_ID)
        val indexSentAtMs = cursor.getColumnIndex(KEY_SENT_AT_MS)
        val indexIsPinned = cursor.getColumnIndex(KEY_IS_PINNED)
        val indexAlbumUris = cursor.getColumnIndex(KEY_ALBUM_URIS)
        val indexAlbumTypes = cursor.getColumnIndex(KEY_ALBUM_TYPES)

        val text = if (indexText != -1) {
            stringCipher.decrypt(cursor.getString(indexText)).orEmpty()
        } else {
            ""
        }
        val isMe = if (indexIsMe != -1) cursor.getInt(indexIsMe) == 1 else false
        val timestamp = if (indexTimestamp != -1) cursor.getString(indexTimestamp) else ""
        val attachType = if (indexAttachType != -1) cursor.getString(indexAttachType) else null
        val attachUri = if (indexAttachUri != -1) stringCipher.decrypt(cursor.getString(indexAttachUri)) else null
        val attachName = if (indexAttachName != -1) stringCipher.decrypt(cursor.getString(indexAttachName)) else null
        val replyToId = if (indexReplyToId != -1) cursor.getString(indexReplyToId) else null
        val replyToText = if (indexReplyToText != -1) stringCipher.decrypt(cursor.getString(indexReplyToText)) else null
        val replyToName = if (indexReplyToName != -1) stringCipher.decrypt(cursor.getString(indexReplyToName)) else null
        val status = if (indexStatus != -1) cursor.getString(indexStatus) else null
        val reactions = if (indexReactions != -1) deserializeReactions(stringCipher.decrypt(cursor.getString(indexReactions))) else emptyMap()
        val id = if (indexId != -1) cursor.getString(indexId) else java.util.UUID.randomUUID().toString()
        val sentAtEpochMs = if (indexSentAtMs != -1) cursor.getLong(indexSentAtMs) else 0L
        val isPinned = if (indexIsPinned != -1) cursor.getInt(indexIsPinned) == 1 else false
        val rawAlbumUris = if (indexAlbumUris != -1) stringCipher.decrypt(cursor.getString(indexAlbumUris)) else null
        val albumMediaUris = rawAlbumUris?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()
        val rawAlbumTypes = if (indexAlbumTypes != -1) cursor.getString(indexAlbumTypes) else null
        val albumMediaTypes = rawAlbumTypes?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()

        return Message(
            id = id,
            text = text,
            isMe = isMe,
            timestamp = timestamp,
            attachmentType = attachType,
            attachmentUri = attachUri,
            attachmentName = attachName,
            replyToId = replyToId,
            replyToText = replyToText,
            replyToName = replyToName,
            status = status,
            reactions = reactions,
            sentAtEpochMs = sentAtEpochMs,
            isPinned = isPinned,
            albumMediaUris = albumMediaUris,
            albumMediaTypes = albumMediaTypes,
        )
    }

    fun getMessagesForPeer(peerName: String): List<Message> {
        val messages = mutableListOf<Message>()
        val db = this.safeReadableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_PEER_NAME = ?",
            arrayOf(peerName),
            null,
            null,
            "rowid ASC"
        )
        cursor.use {
            val stringCipher = SecureStorage.newStringCipher()
            if (it.moveToFirst()) {
                do {
                    messages.add(readMessageFromCursor(it, stringCipher))
                } while (it.moveToNext())
            }
        }
        return messages
    }

    fun getStoredAttachments(): List<StoredAttachmentRecord> {
        val records = mutableListOf<StoredAttachmentRecord>()
        safeReadableDatabase.query(
            TABLE_MESSAGES,
            arrayOf(
                KEY_ID,
                KEY_ATTACHMENT_TYPE,
                KEY_ATTACHMENT_URI,
                KEY_ATTACHMENT_NAME,
                KEY_STATUS,
                KEY_IS_ME,
            ),
            "$KEY_ATTACHMENT_URI IS NOT NULL",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uri = decNullable(cursor.getString(2)).orEmpty()
                if (uri.isNotBlank()) {
                    records += StoredAttachmentRecord(
                        messageId = cursor.getString(0),
                        attachmentType = cursor.getString(1),
                        uri = uri,
                        attachmentName = decNullable(cursor.getString(3)),
                        status = cursor.getString(4),
                        isMine = cursor.getInt(5) == 1,
                    )
                }
            }
        }
        return records
    }

    fun clearAttachmentUris(messageIds: Collection<String>): Int {
        if (messageIds.isEmpty()) return 0
        val db = safeWritableDatabase
        var updated = 0
        db.beginTransaction()
        try {
            messageIds.distinct().chunked(400).forEach { ids ->
                val placeholders = ids.joinToString(",") { "?" }
                updated += db.update(
                    TABLE_MESSAGES,
                    ContentValues().apply { putNull(KEY_ATTACHMENT_URI) },
                    "$KEY_ID IN ($placeholders)",
                    ids.toTypedArray(),
                )
                db.update(
                    TABLE_MESSAGES,
                    ContentValues().apply { put(KEY_STATUS, "CANCELLED") },
                    "$KEY_ID IN ($placeholders) AND $KEY_STATUS = ?",
                    (ids + "PENDING").toTypedArray(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    fun getMessagesForPeerPaged(peerName: String, limit: Int, offset: Int): List<Message> {
        val messages = mutableListOf<Message>()
        val db = this.safeReadableDatabase
        val limitClause = if (offset > 0) "$offset, $limit" else "$limit"
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_PEER_NAME = ?",
            arrayOf(peerName),
            null,
            null,
            "rowid DESC",
            limitClause
        )
        cursor.use {
            val stringCipher = SecureStorage.newStringCipher()
            if (it.moveToFirst()) {
                do {
                    messages.add(readMessageFromCursor(it, stringCipher))
                } while (it.moveToNext())
            }
        }
        messages.reverse()
        return messages
    }

    fun getLastMessageForPeer(peerName: String): Message? {
        val db = this.safeReadableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_PEER_NAME = ?",
            arrayOf(peerName),
            null,
            null,
            "rowid DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return readMessageFromCursor(it, SecureStorage.newStringCipher())
            }
        }
        return null
    }

    fun findMessageForReaction(peerName: String, msgId: String, messageText: String): Message? {
        val db = this.safeReadableDatabase
        var cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_ID = ?",
            arrayOf(msgId),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return readMessageFromCursor(it, SecureStorage.newStringCipher())
            }
        }
        if (messageText.isNotEmpty()) {
            cursor = db.query(
                TABLE_MESSAGES,
                null,
                "$KEY_PEER_NAME = ? AND $KEY_IS_ME = 1 AND $KEY_MESSAGE_TEXT = ?",
                arrayOf(peerName, enc(messageText)),
                null,
                null,
                "rowid DESC",
                "1"
            )
            cursor.use {
                if (it.moveToFirst()) {
                    return readMessageFromCursor(it, SecureStorage.newStringCipher())
                }
            }
        }
        return null
    }

    fun markMessagesAsRead(peerName: String): List<String> {
        val messageIds = mutableListOf<String>()
        try {
            val db = this.safeWritableDatabase
            db.beginTransaction()
            try {
                val where = "$KEY_PEER_NAME = ? AND $KEY_IS_ME = 0 " +
                    "AND ($KEY_STATUS IS NULL OR $KEY_STATUS NOT LIKE 'READ%')"
                db.query(
                    TABLE_MESSAGES,
                    arrayOf(KEY_ID),
                    where,
                    arrayOf(peerName),
                    null,
                    null,
                    "rowid ASC",
                ).use { cursor ->
                    while (cursor.moveToNext()) messageIds += cursor.getString(0)
                }
                db.execSQL(
                    """
                    UPDATE $TABLE_MESSAGES
                    SET $KEY_STATUS = CASE
                        WHEN $KEY_STATUS LIKE '%edited%' THEN 'READ_edited'
                        ELSE 'READ'
                    END
                    WHERE $where
                    """.trimIndent(),
                    arrayOf(peerName),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark messages as read for $peerName", e)
        }
        return messageIds
    }

    fun clearMessagesForPeer(peerName: String) {
        val db = this.safeWritableDatabase
        db.delete(TABLE_MESSAGES, "$KEY_PEER_NAME = ?", arrayOf(peerName))
    }

    fun deleteMessage(id: String) {
        try {
            val db = this.safeWritableDatabase
            db.delete(TABLE_MESSAGES, "$KEY_ID = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete message $id", e)
        }
    }

    fun updateMessageStatus(id: String, status: String) {
        try {
            val db = this.safeWritableDatabase
            var mergedStatus = status
            db.rawQuery(
                "SELECT $KEY_STATUS FROM $TABLE_MESSAGES WHERE $KEY_ID = ?",
                arrayOf(id),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    mergedStatus = MessageDeliveryStatus.merge(cursor.getString(0), status)
                }
            }
            val values = ContentValues().apply {
                put(KEY_STATUS, mergedStatus)
            }
            db.update(TABLE_MESSAGES, values, "$KEY_ID = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update status for $id", e)
        }
    }

    fun updateMessageReactions(id: String, reactions: Map<String, List<String>>) {
        try {
            val db = this.safeWritableDatabase
            val values = ContentValues().apply {
                put(KEY_REACTIONS, encNullable(serializeReactions(reactions)))
            }
            db.update(TABLE_MESSAGES, values, "$KEY_ID = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update reactions for $id", e)
        }
    }

    fun getMessageById(id: String): Message? {
        val db = this.safeReadableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_ID = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return readMessageFromCursor(it, SecureStorage.newStringCipher())
            }
        }
        return null
    }

    fun updateMessageTextForPeer(id: String, peerName: String, newText: String): Boolean {
        try {
            val db = this.safeWritableDatabase
            var isMe = 1
            var oldStatus = ""
            val selectQuery = "SELECT $KEY_STATUS, $KEY_IS_ME FROM $TABLE_MESSAGES WHERE $KEY_ID = ? AND $KEY_PEER_NAME = ?"
            db.rawQuery(selectQuery, arrayOf(id, peerName)).use { cursor ->
                if (cursor.moveToFirst()) {
                    oldStatus = cursor.getString(0) ?: ""
                    isMe = cursor.getInt(1)
                } else {
                    return false
                }
            }
            if (isMe != 0) return false // CRIT-01: Peer can only edit incoming messages sent by the peer

            val values = ContentValues().apply {
                put(KEY_MESSAGE_TEXT, enc(newText))
                if (!oldStatus.contains("edited")) {
                    val newStatus = if (oldStatus.isEmpty()) "edited" else "${oldStatus}_edited"
                    put(KEY_STATUS, newStatus)
                }
            }
            val rows = db.update(TABLE_MESSAGES, values, "$KEY_ID = ? AND $KEY_PEER_NAME = ? AND $KEY_IS_ME = 0", arrayOf(id, peerName))
            return rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update text for $id for peer $peerName", e)
            return false
        }
    }

    fun deleteMessageForPeer(id: String, peerName: String): Boolean {
        try {
            val db = this.safeWritableDatabase
            var isMe = 1
            val selectQuery = "SELECT $KEY_IS_ME FROM $TABLE_MESSAGES WHERE $KEY_ID = ? AND $KEY_PEER_NAME = ?"
            db.rawQuery(selectQuery, arrayOf(id, peerName)).use { cursor ->
                if (cursor.moveToFirst()) {
                    isMe = cursor.getInt(0)
                } else {
                    return false
                }
            }
            if (isMe != 0) return false // CRIT-01: Peer can only delete incoming messages sent by the peer

            val rows = db.delete(TABLE_MESSAGES, "$KEY_ID = ? AND $KEY_PEER_NAME = ? AND $KEY_IS_ME = 0", arrayOf(id, peerName))
            return rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete message $id for peer $peerName", e)
            return false
        }
    }

    fun updateMessageText(id: String, newText: String) {
        try {
            val db = this.safeWritableDatabase
            val values = ContentValues().apply {
                put(KEY_MESSAGE_TEXT, enc(newText))
            }
            val selectQuery = "SELECT $KEY_STATUS FROM $TABLE_MESSAGES WHERE $KEY_ID = ?"
            db.rawQuery(selectQuery, arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val oldStatus = cursor.getString(0) ?: ""
                    if (!oldStatus.contains("edited")) {
                        val newStatus = if (oldStatus.isEmpty()) "edited" else "${oldStatus}_edited"
                        values.put(KEY_STATUS, newStatus)
                    }
                }
            }
            db.update(TABLE_MESSAGES, values, "$KEY_ID = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update text for $id", e)
        }
    }

    fun getPendingMessagesForPeer(peerName: String): List<Message> {
        val messages = mutableListOf<Message>()
        val db = this.safeReadableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_PEER_NAME = ? AND $KEY_STATUS = ?",
            arrayOf(peerName, "PENDING"),
            null,
            null,
            "rowid ASC"
        )
        // Delegate to the shared readMessageFromCursor() helper so any new
        // columns (reactions, sentAtEpochMs, etc.) are handled automatically
        // without requiring updates in multiple places (WARN-06).
        cursor.use {
            val stringCipher = SecureStorage.newStringCipher()
            while (it.moveToNext()) {
                messages.add(readMessageFromCursor(it, stringCipher))
            }
        }
        return messages
    }

    fun enqueuePendingControl(control: PendingControl) {
        val db = safeWritableDatabase
        val values = ContentValues().apply {
            put(KEY_CONTROL_ID, control.id)
            put(KEY_PEER_NAME, control.peerName)
            put(KEY_CONTROL_TYPE, control.type)
            put(KEY_CONTROL_PAYLOAD, enc(control.payload))
            put(KEY_CREATED_AT_MS, control.createdAtEpochMs)
        }
        db.insertWithOnConflict(
            TABLE_PENDING_CONTROLS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        try {
            db.execSQL(
                "DELETE FROM $TABLE_PENDING_CONTROLS WHERE $KEY_PEER_NAME = ? AND $KEY_CONTROL_ID NOT IN (" +
                    "SELECT $KEY_CONTROL_ID FROM $TABLE_PENDING_CONTROLS WHERE $KEY_PEER_NAME = ? ORDER BY $KEY_CREATED_AT_MS DESC LIMIT 1000)",
                arrayOf(control.peerName, control.peerName)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trim pending_controls for ${control.peerName}", e)
        }
    }

    fun getPendingControlsForPeer(peerName: String): List<PendingControl> {
        val controls = mutableListOf<PendingControl>()
        safeReadableDatabase.query(
            TABLE_PENDING_CONTROLS,
            null,
            "$KEY_PEER_NAME = ?",
            arrayOf(peerName),
            null,
            null,
            "$KEY_CREATED_AT_MS ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                controls += PendingControl(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_CONTROL_ID)),
                    peerName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PEER_NAME)),
                    type = cursor.getString(cursor.getColumnIndexOrThrow(KEY_CONTROL_TYPE)),
                    payload = dec(cursor.getString(cursor.getColumnIndexOrThrow(KEY_CONTROL_PAYLOAD))),
                    createdAtEpochMs = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CREATED_AT_MS)),
                )
            }
        }
        return controls
    }

    fun deletePendingControl(controlId: String) {
        safeWritableDatabase.delete(
            TABLE_PENDING_CONTROLS,
            "$KEY_CONTROL_ID = ?",
            arrayOf(controlId),
        )
    }

    fun deletePendingControlsForPeer(peerName: String) {
        safeWritableDatabase.delete(
            TABLE_PENDING_CONTROLS,
            "$KEY_PEER_NAME = ?",
            arrayOf(peerName),
        )
    }

    fun deletePendingControlsForPeerByTypes(peerName: String, types: Set<String>) {
        if (types.isEmpty()) return
        val placeholders = types.joinToString(",") { "?" }
        safeWritableDatabase.delete(
            TABLE_PENDING_CONTROLS,
            "$KEY_PEER_NAME = ? AND $KEY_CONTROL_TYPE IN ($placeholders)",
            arrayOf(peerName, *types.toTypedArray()),
        )
    }

    fun renamePeer(oldPeerName: String, newPeerName: String) {
        if (oldPeerName == newPeerName) return
        val db = this.safeWritableDatabase
        val values = ContentValues().apply {
            put(KEY_PEER_NAME, newPeerName)
        }
        db.update(
            TABLE_MESSAGES,
            values,
            "$KEY_PEER_NAME = ?",
            arrayOf(oldPeerName)
        )
    }

    fun clearAllMessages() {
        val db = this.safeWritableDatabase
        db.delete(TABLE_MESSAGES, null, null)
    }

    private fun enc(value: String) = SecureStorage.encrypt(value)
    private fun encNullable(value: String?) = value?.let(SecureStorage::encrypt)
    private fun dec(value: String?) = SecureStorage.decrypt(value).orEmpty()
    private fun decNullable(value: String?) = SecureStorage.decrypt(value)

    private fun createPendingControlsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE_PENDING_CONTROLS(" +
                "$KEY_CONTROL_ID TEXT PRIMARY KEY," +
                "$KEY_PEER_NAME TEXT NOT NULL," +
                "$KEY_CONTROL_TYPE TEXT NOT NULL," +
                "$KEY_CONTROL_PAYLOAD TEXT NOT NULL," +
                "$KEY_CREATED_AT_MS INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS pending_controls_peer_created " +
                "ON $TABLE_PENDING_CONTROLS($KEY_PEER_NAME, $KEY_CREATED_AT_MS)"
        )
    }

    private fun createMessagePeerIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS messages_peer_sent " +
                "ON $TABLE_MESSAGES($KEY_PEER_NAME, $KEY_SENT_AT_MS)"
        )
    }

    private fun checkAndMigrateDatabase(context: Context, dbFile: java.io.File, pass: String) {
        if (!dbFile.exists()) return
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, pass, null, SQLiteDatabase.OPEN_READWRITE, null)
            db.close()
            return
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("file is not a database") && !msg.contains("not a database")) {
                return
            }
        }
        try {
            val unencryptedDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null as String?, null, SQLiteDatabase.OPEN_READWRITE, null)
            unencryptedDb.close()
        } catch (e: Exception) {
            return
        }
        // The temporary database is already SQLCipher-encrypted. Keep it beside the
        // destination so the final rename is on the same filesystem and always clean it.
        val tempFile = java.io.File(dbFile.parentFile, "$DATABASE_NAME.encrypted.tmp")
        if (tempFile.exists()) tempFile.delete()
        require(pass.matches(Regex("^[A-Za-z0-9+/=]+$"))) { "Invalid passphrase format" }
        var source: SQLiteDatabase? = null
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null as String?, null, SQLiteDatabase.OPEN_READWRITE, null)
            source = db
            val escPath = tempFile.absolutePath.replace("'", "''")
            val escPass = pass.replace("'", "''")
            db.execSQL("ATTACH DATABASE '$escPath' AS encrypted KEY '$escPass'")
            db.execSQL("SELECT sqlcipher_export('encrypted')")
            db.execSQL("DETACH DATABASE encrypted")
            db.close()
            source = null
            check(dbFile.delete()) { "Could not replace legacy plaintext database" }
            check(tempFile.renameTo(dbFile)) { "Could not install encrypted database" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate plaintext database", e)
        } finally {
            try {
                source?.close()
            } catch (_: Exception) {
            }
            if (tempFile.exists()) tempFile.delete()
        }
    }
}

data class PendingControl(
    val id: String,
    val peerName: String,
    val type: String,
    val payload: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)
