package com.example.twopchat.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import com.example.twopchat.ui.chat.Message

class ChatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "twopchat.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_MESSAGES = "messages"
        
        private const val KEY_ID = "id"
        private const val KEY_PEER_NAME = "peer_name"
        private const val KEY_MESSAGE_TEXT = "message_text"
        private const val KEY_IS_ME = "is_me"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_ATTACHMENT_TYPE = "attachment_type"
        private const val KEY_ATTACHMENT_URI = "attachment_uri"
        private const val KEY_ATTACHMENT_NAME = "attachment_name"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_MESSAGES + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_PEER_NAME + " TEXT,"
                + KEY_MESSAGE_TEXT + " TEXT,"
                + KEY_IS_ME + " INTEGER,"
                + KEY_TIMESTAMP + " TEXT,"
                + KEY_ATTACHMENT_TYPE + " TEXT,"
                + KEY_ATTACHMENT_URI + " TEXT,"
                + KEY_ATTACHMENT_NAME + " TEXT" + ")")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES)
        onCreate(db)
    }

    fun saveMessage(peerName: String, msg: Message) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(KEY_PEER_NAME, peerName)
            put(KEY_MESSAGE_TEXT, msg.text)
            put(KEY_IS_ME, if (msg.isMe) 1 else 0)
            put(KEY_TIMESTAMP, msg.timestamp)
            put(KEY_ATTACHMENT_TYPE, msg.attachmentType)
            put(KEY_ATTACHMENT_URI, msg.attachmentUri)
            put(KEY_ATTACHMENT_NAME, msg.attachmentName)
        }
        db.insert(TABLE_MESSAGES, null, values)
    }

    fun getMessagesForPeer(peerName: String): List<Message> {
        val messages = mutableListOf<Message>()
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$KEY_PEER_NAME = ?",
            arrayOf(peerName),
            null,
            null,
            "$KEY_ID ASC"
        )
        
        cursor.use {
            if (it.moveToFirst()) {
                val indexText = it.getColumnIndex(KEY_MESSAGE_TEXT)
                val indexIsMe = it.getColumnIndex(KEY_IS_ME)
                val indexTimestamp = it.getColumnIndex(KEY_TIMESTAMP)
                val indexAttachType = it.getColumnIndex(KEY_ATTACHMENT_TYPE)
                val indexAttachUri = it.getColumnIndex(KEY_ATTACHMENT_URI)
                val indexAttachName = it.getColumnIndex(KEY_ATTACHMENT_NAME)
                val indexId = it.getColumnIndex(KEY_ID)
                
                do {
                    val text = if (indexText != -1) it.getString(indexText) else ""
                    val isMe = if (indexIsMe != -1) it.getInt(indexIsMe) == 1 else false
                    val timestamp = if (indexTimestamp != -1) it.getString(indexTimestamp) else ""
                    val attachType = if (indexAttachType != -1) it.getString(indexAttachType) else null
                    val attachUri = if (indexAttachUri != -1) it.getString(indexAttachUri) else null
                    val attachName = if (indexAttachName != -1) it.getString(indexAttachName) else null
                    val id = if (indexId != -1) it.getLong(indexId).toString() else System.currentTimeMillis().toString()
                    
                    messages.add(
                        Message(
                            id = id,
                            text = text,
                            isMe = isMe,
                            timestamp = timestamp,
                            attachmentType = attachType,
                            attachmentUri = attachUri,
                            attachmentName = attachName
                        )
                    )
                } while (it.moveToNext())
            }
        }
        return messages
    }

    fun clearMessagesForPeer(peerName: String) {
        val db = this.writableDatabase
        db.delete(TABLE_MESSAGES, "$KEY_PEER_NAME = ?", arrayOf(peerName))
    }

    fun clearAllMessages() {
        val db = this.writableDatabase
        db.delete(TABLE_MESSAGES, null, null)
    }
}
