package com.example.twopchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import net.sqlcipher.database.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun createVersion6Database() {
        ChatDatabaseHelper.closeAllConnections()
        context.deleteDatabase("twopchat.db")
        SQLiteDatabase.loadLibs(context)
        val passphrase = SecureStorage.getOrGenerateDbPassphrase(context)
        val database = SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath("twopchat.db"),
            passphrase,
            null,
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
            Message("migration-test", "hello", true, "12:00", sentAtEpochMs = expectedTimestamp),
        )

        val restored = helper.getMessagesForPeer("Alice").single()
        assertEquals(expectedTimestamp, restored.sentAtEpochMs)
    }
}
