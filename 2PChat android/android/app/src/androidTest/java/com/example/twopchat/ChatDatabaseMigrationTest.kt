package com.example.twopchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.PendingControl
import com.example.twopchat.ui.chat.Message
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun createVersion6Database() {
        ChatDatabaseHelper.closeAllConnections()
        val dbFile = context.getDatabasePath("twopchat.db")
        if (dbFile.exists()) {
            dbFile.delete()
        }
        dbFile.parentFile?.mkdirs()
        System.loadLibrary("sqlcipher")
        val passphrase = SecureStorage.getOrGenerateDbPassphrase(context)
        val database = SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath,
            passphrase,
            null,
            null,
            null
        )
        database.execSQL(
            """CREATE TABLE messages(
                id TEXT PRIMARY KEY, peer_name TEXT, message_text TEXT, is_me INTEGER,
                timestamp TEXT, attachment_type TEXT, attachment_uri TEXT, attachment_name TEXT,
                reply_to_id TEXT, reply_to_text TEXT, reply_to_name TEXT, status TEXT, reactions TEXT
            )""".trimIndent()
        )
        database.version = 6
        database.close()
    }

    @After
    fun cleanUp() {
        ChatDatabaseHelper.closeAllConnections()
        context.deleteDatabase("twopchat.db")
    }

    @Test
    fun version6DatabaseMigratesAndPersistsUnixTimestamp() {
        val expectedTimestamp = 1_784_000_000_000L
        val helper = ChatDatabaseHelper.getInstance(context)
        helper.saveMessage(
            "Alice",
            Message(
                id = "migration-test",
                text = "hello",
                isMe = true,
                timestamp = "12:00",
                sentAtEpochMs = expectedTimestamp,
                isPinned = true,
                albumMediaUris = listOf("/tmp/one.jpg", "/tmp/two.mp4"),
                albumMediaTypes = listOf("IMAGE", "VIDEO"),
            ),
        )

        val restored = helper.getMessagesForPeer("Alice").single()
        assertEquals(expectedTimestamp, restored.sentAtEpochMs)
        assertEquals(true, restored.isPinned)
        assertEquals(listOf("/tmp/one.jpg", "/tmp/two.mp4"), restored.albumMediaUris)
        assertEquals(listOf("IMAGE", "VIDEO"), restored.albumMediaTypes)
    }

    @Test
    fun version6DatabaseMigratesPendingControlQueue() {
        val helper = ChatDatabaseHelper.getInstance(context)
        val control = PendingControl(
            id = "edit:message-1",
            peerName = "Alice",
            type = "edit_message",
            payload = "{\"type\":\"edit_message\",\"text\":\"secret\"}",
            createdAtEpochMs = 1234L,
        )

        helper.enqueuePendingControl(control)
        assertEquals(listOf(control), helper.getPendingControlsForPeer("Alice"))

        helper.deletePendingControl(control.id)
        assertFalse(helper.getPendingControlsForPeer("Alice").isNotEmpty())
    }
}
