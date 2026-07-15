package com.example.twopchat.data

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
import android.content.ContentValues
import com.example.twopchat.ui.chat.Message
import com.example.twopchat.SecureStorage
import android.util.Log
import com.example.twopchat.ui.chat.MessageDeliveryStatus

class ChatDatabaseHelper private constructor(private val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "twopchat.db"
        private const val DATABASE_VERSION = 8
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
        private const val KEY_CONTROL_ID = "control_id"
        private const val KEY_CONTROL_TYPE = "control_type"
        private const val KEY_CONTROL_PAYLOAD = "control_payload"
        private const val KEY_CREATED_AT_MS = "created_at_ms"
        private const val TAG = "ChatDatabaseHelper"
        private val activeHelpers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ChatDatabaseHelper, Boolean>())
        @Volatile private var instance: ChatDatabaseHelper? = null

        fun getInstance(context: Context): ChatDatabaseHelper =
            instance ?: synchronized(this) {
                instance ?: ChatDatabaseHelper(context.applicationContext).also { instance = it }
            }

        fun closeAllConnections() {
            synchronized(activeHelpers) {
                for (helper in activeHelpers) {
                    try {
                        helper.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to close database connection", e)
                    }
                }
                activeHelpers.clear()
                instance = null
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
            val pass = SecureStorage.getOrGenerateDbPassphrase(context)
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            checkAndMigrateDatabase(context, dbFile, pass)
            return getWritableDatabase(pass)
        }

    private val safeReadableDatabase: SQLiteDatabase
        get() {
            val pass = SecureStorage.getOrGenerateDbPassphrase(context)
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            checkAndMigrateDatabase(context, dbFile, pass)
            return getReadableDatabase(pass)
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
                + KEY_SENT_AT_MS + " INTEGER NOT NULL DEFAULT 0" + ")")
        db.execSQL(createTable)
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

    fun saveMessage(peerName: String, msg: Message) {
        val db = this.safeWritableDatabase
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
            put(KEY_STATUS, msg.status)
            put(KEY_REACTIONS, serializeReactions(msg.reactions))
            put(KEY_SENT_AT_MS, msg.sentAtEpochMs)
        }
        db.insert(TABLE_MESSAGES, null, values)
    }

    private fun readMessageFromCursor(cursor: android.database.Cursor): Message {
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

        val text = if (indexText != -1) dec(cursor.getString(indexText)) else ""
        val isMe = if (indexIsMe != -1) cursor.getInt(indexIsMe) == 1 else false
        val timestamp = if (indexTimestamp != -1) cursor.getString(indexTimestamp) else ""
        val attachType = if (indexAttachType != -1) cursor.getString(indexAttachType) else null
        val attachUri = if (indexAttachUri != -1) decNullable(cursor.getString(indexAttachUri)) else null
        val attachName = if (indexAttachName != -1) decNullable(cursor.getString(indexAttachName)) else null
        val replyToId = if (indexReplyToId != -1) cursor.getString(indexReplyToId) else null
        val replyToText = if (indexReplyToText != -1) decNullable(cursor.getString(indexReplyToText)) else null
        val replyToName = if (indexReplyToName != -1) decNullable(cursor.getString(indexReplyToName)) else null
        val status = if (indexStatus != -1) cursor.getString(indexStatus) else null
        val reactions = if (indexReactions != -1) deserializeReactions(cursor.getString(indexReactions)) else emptyMap()
        val id = if (indexId != -1) cursor.getString(indexId) else java.util.UUID.randomUUID().toString()
        val sentAtEpochMs = if (indexSentAtMs != -1) cursor.getLong(indexSentAtMs) else 0L

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
            if (it.moveToFirst()) {
                do {
                    messages.add(readMessageFromCursor(it))
                } while (it.moveToNext())
            }
        }
        return messages
    }

    fun getMessagesForPeerPaged(peerName: String, limit: Int, offset: Int): List<Message> {
        val messages = mutableListOf<Message>()
        val db = this.safeReadableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_PEER_NAME = ?",
            arrayOf(peerName),
            null,
            null,
            "rowid DESC",
            "$limit OFFSET $offset"
        )
        cursor.use {
            if (it.moveToFirst()) {
                do {
                    messages.add(readMessageFromCursor(it))
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
                return readMessageFromCursor(it)
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
                return readMessageFromCursor(it)
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
                    return readMessageFromCursor(it)
                }
            }
        }
        return null
    }

    fun markMessagesAsRead(peerName: String) {
        val db = this.safeWritableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(KEY_STATUS, "READ")
            }
            db.update(TABLE_MESSAGES, values, "$KEY_PEER_NAME = ? AND $KEY_IS_ME = 0 AND $KEY_STATUS != ?", arrayOf(peerName, "READ"))
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db.endTransaction()
        }
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
                put(KEY_REACTIONS, serializeReactions(reactions))
            }
            db.update(TABLE_MESSAGES, values, "$KEY_ID = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update reactions for $id", e)
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
        
        cursor.use {
            if (it.moveToFirst()) {
                val indexText = it.getColumnIndex(KEY_MESSAGE_TEXT)
                val indexIsMe = it.getColumnIndex(KEY_IS_ME)
                val indexTimestamp = it.getColumnIndex(KEY_TIMESTAMP)
                val indexAttachType = it.getColumnIndex(KEY_ATTACHMENT_TYPE)
                val indexAttachUri = it.getColumnIndex(KEY_ATTACHMENT_URI)
                val indexAttachName = it.getColumnIndex(KEY_ATTACHMENT_NAME)
                val indexReplyToId = it.getColumnIndex(KEY_REPLY_TO_ID)
                val indexReplyToText = it.getColumnIndex(KEY_REPLY_TO_TEXT)
                val indexReplyToName = it.getColumnIndex(KEY_REPLY_TO_NAME)
                val indexStatus = it.getColumnIndex(KEY_STATUS)
                val indexId = it.getColumnIndex(KEY_ID)
                val indexSentAtMs = it.getColumnIndex(KEY_SENT_AT_MS)
                
                do {
                    val text = if (indexText != -1) dec(it.getString(indexText)) else ""
                    val isMe = if (indexIsMe != -1) it.getInt(indexIsMe) == 1 else false
                    val timestamp = if (indexTimestamp != -1) it.getString(indexTimestamp) else ""
                    val attachType = if (indexAttachType != -1) it.getString(indexAttachType) else null
                    val attachUri = if (indexAttachUri != -1) decNullable(it.getString(indexAttachUri)) else null
                    val attachName = if (indexAttachName != -1) decNullable(it.getString(indexAttachName)) else null
                    val replyToId = if (indexReplyToId != -1) it.getString(indexReplyToId) else null
                    val replyToText = if (indexReplyToText != -1) decNullable(it.getString(indexReplyToText)) else null
                    val replyToName = if (indexReplyToName != -1) decNullable(it.getString(indexReplyToName)) else null
                    val status = if (indexStatus != -1) it.getString(indexStatus) else null
                    val id = if (indexId != -1) it.getString(indexId) else java.util.UUID.randomUUID().toString()
                    val sentAtEpochMs = if (indexSentAtMs != -1) it.getLong(indexSentAtMs) else 0L
                    
                    messages.add(
                        Message(
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
                            sentAtEpochMs = sentAtEpochMs
                        )
                    )
                } while (it.moveToNext())
            }
        }
        return messages
    }

    fun enqueuePendingControl(control: PendingControl) {
        val values = ContentValues().apply {
            put(KEY_CONTROL_ID, control.id)
            put(KEY_PEER_NAME, control.peerName)
            put(KEY_CONTROL_TYPE, control.type)
            put(KEY_CONTROL_PAYLOAD, enc(control.payload))
            put(KEY_CREATED_AT_MS, control.createdAtEpochMs)
        }
        safeWritableDatabase.insertWithOnConflict(
            TABLE_PENDING_CONTROLS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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

    private fun checkAndMigrateDatabase(context: Context, dbFile: java.io.File, pass: String) {
        if (!dbFile.exists()) return
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, pass, null, SQLiteDatabase.OPEN_READWRITE)
            db.close()
            return
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (!msg.contains("file is not a database") && !msg.contains("not a database")) {
                return
            }
        }
        try {
            val unencryptedDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, "", null, SQLiteDatabase.OPEN_READWRITE)
            unencryptedDb.close()
        } catch (e: Exception) {
            return
        }
        // The temporary database is already SQLCipher-encrypted. Keep it beside the
        // destination so the final rename is on the same filesystem and always clean it.
        val tempFile = java.io.File(dbFile.parentFile, "$DATABASE_NAME.encrypted.tmp")
        if (tempFile.exists()) tempFile.delete()
        var source: SQLiteDatabase? = null
        try {
            val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, "", null, SQLiteDatabase.OPEN_READWRITE)
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
