package com.example.twopchat.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatDatabaseFailClosedInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseFile: File get() = context.getDatabasePath("twopchat.db")

    @Before
    fun setUp() {
        ChatDatabaseHelper.closeAllConnections()
        context.deleteDatabase("twopchat.db")
        System.loadLibrary("sqlcipher")
    }

    @After
    fun tearDown() {
        ChatDatabaseHelper.closeAllConnections()
        context.deleteDatabase("twopchat.db")
    }

    @Test
    fun readReceiptsRequireCommittedStatusChanges() {
        val helper = ChatDatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO messages(id, peer_name, is_me, status) VALUES ('incoming', 'Alice', 0, 'DELIVERED_edited')")
        db.execSQL("INSERT INTO messages(id, peer_name, is_me, status) VALUES ('outgoing', 'Alice', 1, 'SENT')")
        db.execSQL("CREATE TRIGGER reject_read BEFORE UPDATE ON messages BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")

        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            helper.markMessagesAsRead("Alice")
        }
        db.rawQuery("SELECT status FROM messages WHERE id = 'incoming'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("DELIVERED_edited", it.getString(0))
        }
        db.execSQL("DROP TRIGGER reject_read")
        assertEquals(listOf("incoming"), helper.markMessagesAsRead("Alice"))
        db.rawQuery("SELECT status FROM messages WHERE id = 'incoming'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("READ_edited", it.getString(0))
        }
        assertTrue(helper.markMessagesAsRead("Alice").isEmpty())
    }

    @Test
    fun missingPermissionRowsAreDistinctFromDatabaseErrors() {
        val helper = ChatDatabaseHelper.getInstance(context)
        assertEquals(0, helper.getPeerTransportPolicy("missing"))
        assertEquals("DIRECT_INVITE", helper.getPeerSource("missing"))
        assertEquals(false, helper.isPolicyConfirmed("missing"))
        assertEquals(false, helper.isSuccessionCertificateRevoked("missing"))
        val db = helper.writableDatabase
        db.execSQL("ALTER TABLE peers RENAME TO unavailable_peers")
        db.execSQL("DROP TABLE revoked_succession_certificates")
        listOf<() -> Any>(
            { helper.getPeerTransportPolicy("Alice") },
            { helper.getPeerSource("Alice") },
            { helper.isPolicyConfirmed("Alice") },
            { helper.isSuccessionCertificateRevoked("cert") },
            { helper.setPeerTransportPolicy("Alice", 2) },
            { helper.setPeerSource("Alice", "GROUP_INFERRED") },
            { helper.setPolicyConfirmed("Alice", true) },
            { helper.confirmPeerTransportPolicy("Alice", 2) },
        ).forEach { operation ->
            assertThrows(android.database.sqlite.SQLiteException::class.java) { operation() }
        }
    }

    @Test
    fun policyConfirmationIsAtomicAndPreservesTorFloor() {
        val helper = ChatDatabaseHelper.getInstance(context)
        helper.setPeerTransportPolicy("Alice", 1)
        val db = helper.writableDatabase
        db.execSQL("CREATE TRIGGER reject_confirmation BEFORE UPDATE OF policy_confirmed ON peers BEGIN SELECT RAISE(ABORT, 'injected confirmation failure'); END")
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            helper.confirmPeerTransportPolicy("Alice", 0, 2)
        }
        assertEquals(1, helper.getPeerTransportPolicy("Alice"))
        assertEquals(false, helper.isPolicyConfirmed("Alice"))
        db.execSQL("DROP TRIGGER reject_confirmation")
        assertEquals(2, helper.confirmPeerTransportPolicy("Alice", 0, 2))
        assertEquals(2, helper.getPeerTransportPolicy("Alice"))
        assertTrue(helper.isPolicyConfirmed("Alice"))
        db.execSQL("CREATE TRIGGER reject_peer BEFORE INSERT ON peers BEGIN SELECT RAISE(ABORT, 'injected insert failure'); END")
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            helper.confirmPeerTransportPolicy("Bob", 2)
        }
        assertEquals(false, helper.isPolicyConfirmed("Bob"))
    }

    @Test
    fun metadataMutationsPropagateWriteErrorsWithoutChangingRows() {
        val helper = ChatDatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO messages(id, peer_name, is_me, status, message_text) VALUES ('incoming', 'Alice', 0, 'DELIVERED', 'original')")
        db.execSQL("CREATE TRIGGER reject_update BEFORE UPDATE ON messages BEGIN SELECT RAISE(ABORT, 'injected update failure'); END")
        db.execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT, 'injected delete failure'); END")
        listOf<() -> Any>(
            { helper.deleteMessage("incoming") },
            { helper.deleteMessageForPeer("incoming", "Alice") },
            { helper.updateMessageStatus("incoming", "READ") },
            { helper.batchUpdateMessageStatuses(mapOf("incoming" to "READ")) },
            { helper.updateMessageReactions("incoming", emptyMap()) },
            { helper.updateMessageText("incoming", "changed") },
            { helper.updateMessageTextDirect("incoming", "changed") },
            { helper.updateMessageTextForPeer("incoming", "Alice", "changed") },
        ).forEach { operation ->
            assertThrows(android.database.sqlite.SQLiteException::class.java) { operation() }
        }
        assertEquals(false, helper.deleteMessageForPeer("incoming", "Bob"))
        assertEquals(false, helper.updateMessageTextForPeer("incoming", "Bob", "changed"))
        db.rawQuery("SELECT status, message_text FROM messages WHERE id = 'incoming'", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("DELIVERED", it.getString(0))
            assertEquals("original", it.getString(1))
        }
    }

    @Test
    fun notificationReadFailureEscapesBeforeClearingUnreadState() {
        val helper = ChatDatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO messages(id, peer_name, is_me, status) VALUES ('incoming', 'Alice', 0, 'DELIVERED')")
        db.execSQL("CREATE TRIGGER reject_read BEFORE UPDATE ON messages BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")
        val prefs = com.example.twopchat.config.P2PPreferences.prefs(context)
        val key = com.example.twopchat.config.P2PPreferences.unreadCount("Alice")
        val previous = prefs.getInt(key, 0)
        prefs.edit().putInt(key, 1).commit()
        try {
            val method = com.example.twopchat.service.NotificationActionReceiver::class.java.getDeclaredMethod(
                "markPeerAsRead", Context::class.java, String::class.java, List::class.java,
            ).apply { isAccessible = true }
            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                method.invoke(com.example.twopchat.service.NotificationActionReceiver(), context, "Alice", listOf("incoming"))
            }
            assertTrue(error.cause is android.database.sqlite.SQLiteException)
            assertEquals(1, prefs.getInt(key, 0))
            assertTrue(helper.getPendingControlsForPeer("Alice").isEmpty())
        } finally {
            prefs.edit().putInt(key, previous).commit()
        }
    }

    @Test
    fun laterMigrationTableCreationErrorsAbortUpgrade() {
        val helper = ChatDatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        for ((table, version) in listOf("discovery_sequences" to 17, "group_succession_state" to 18)) {
            db.execSQL("DROP TABLE $table")
            db.execSQL("PRAGMA query_only = ON")
            try {
                assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    helper.onUpgrade(db, version, ChatDatabaseHelper.DATABASE_VERSION)
                }
            } finally {
                db.execSQL("PRAGMA query_only = OFF")
            }
            helper.onUpgrade(db, version, ChatDatabaseHelper.DATABASE_VERSION)
        }
    }

    private fun createLegacyPlaintextDatabase(): ByteArray {
        databaseFile.parentFile?.mkdirs()
        val source = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        source.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY)")
        source.execSQL("INSERT INTO messages(id) VALUES ('precious')")
        source.version = 6
        source.close()
        return databaseFile.readBytes()
    }

    @Test
    fun migrationFailurePreservesPlaintextSourceDatabase() {
        val originalBytes = createLegacyPlaintextDatabase()

        val error = assertThrows(Throwable::class.java) {
            ChatDatabaseHelper.getInstance(context).checkAndMigrateDatabase(
                context,
                databaseFile,
                ByteArray(32) { 1 },
                replace = { _, _ -> false },
            )
        }
        assertTrue(error.message!!.contains("source preserved"))
        assertTrue(
            "plaintext source must survive failed migration",
            databaseFile.readBytes().contentEquals(originalBytes),
        )
        assertNoMigrationTemps()
    }

    @Test
    fun migrationPreserves44ByteBase64Passphrase() {
        val pass = android.util.Base64.encode(ByteArray(32) { it.toByte() }, android.util.Base64.NO_WRAP)
        assertEquals(44, pass.size)
        assertMigrationRoundTrip(pass)
    }

    @Test
    fun migrationPreservesLegacyTextPassphrase() {
        assertMigrationRoundTrip("legacy ' passphrase / пароль".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun migrationPreservesBinaryPassphraseIncludingNullBytes() {
        assertMigrationRoundTrip(ByteArray(32) { (it * 17).toByte() })
    }

    private fun assertMigrationRoundTrip(pass: ByteArray) {
        createLegacyPlaintextDatabase()
        val helper = ChatDatabaseHelper.getInstance(context)
        try {
            helper.checkAndMigrateDatabase(context, databaseFile, pass)
            val installed = databaseFile.readBytes()
            helper.checkAndMigrateDatabase(context, databaseFile, pass, replace = { _, _ ->
                throw AssertionError("Encrypted database must not migrate twice")
            })
            org.junit.Assert.assertArrayEquals(installed, databaseFile.readBytes())
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath, pass, null, SQLiteDatabase.OPEN_READONLY, null, null,
            ).use { migrated ->
                assertEquals(6, migrated.version)
                migrated.rawQuery("SELECT id FROM messages", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("precious", cursor.getString(0))
                    org.junit.Assert.assertFalse(cursor.moveToNext())
                }
            }
            assertNoMigrationTemps()
        } finally {
            pass.fill(0)
        }
    }

    private fun assertNoMigrationTemps() {
        val artifacts = checkNotNull(databaseFile.parentFile?.listFiles())
            .filter { it.name.startsWith("twopchat.db.encrypted.") }
        assertTrue("Migration artifacts remain: ${artifacts.map { it.name }}", artifacts.isEmpty())
    }

    @Test
    fun exportFailureCleansTemporaryDatabaseAndPreservesSource() {
        assertMigrationStageFailure(exportFailure = true)
    }

    @Test
    fun verificationFailureCleansTemporaryDatabaseAndPreservesSource() {
        assertMigrationStageFailure(exportFailure = false)
    }

    private fun assertMigrationStageFailure(exportFailure: Boolean) {
        val original = createLegacyPlaintextDatabase()
        val failure = java.io.IOException("injected migration failure")
        val pass = ByteArray(44) { 65 }
        var reachedStage = false
        var replaced = false
        try {
            val helper = ChatDatabaseHelper.getInstance(context)
            val thrown = assertThrows(java.io.IOException::class.java) {
                if (exportFailure) {
                    helper.checkAndMigrateDatabase(
                        context, databaseFile, pass,
                        replace = { _, _ -> replaced = true; false },
                        export = { source ->
                            source.execSQL("CREATE TABLE encrypted.partial_export(id TEXT)")
                            reachedStage = true
                            throw failure
                        },
                    )
                } else {
                    helper.checkAndMigrateDatabase(
                        context, databaseFile, pass,
                        replace = { _, _ -> replaced = true; false },
                        verify = { migrated ->
                            assertEquals(6, migrated.version)
                            reachedStage = true
                            throw failure
                        },
                    )
                }
            }
            org.junit.Assert.assertSame(failure, thrown)
            assertTrue(reachedStage)
            org.junit.Assert.assertFalse(replaced)
            org.junit.Assert.assertArrayEquals(original, databaseFile.readBytes())
            assertNoMigrationTemps()
        } finally {
            pass.fill(0)
        }
    }

    @Test
    fun version11WithoutPeersUpgradesTransactionallyAndPreservesMessages() {
        databaseFile.parentFile?.mkdirs()
        val pass = com.example.twopchat.security.SecureStorage.getOrGenerateDbPassphrase(context)
        try {
            SQLiteDatabase.openOrCreateDatabase(databaseFile, pass, null, null).use { fixture ->
                fixture.execSQL("""CREATE TABLE messages(
                    id TEXT PRIMARY KEY, peer_name TEXT, message_text TEXT, is_me INTEGER,
                    timestamp TEXT, attachment_type TEXT, attachment_uri TEXT, attachment_name TEXT,
                    reply_to_id TEXT, reply_to_text TEXT, reply_to_name TEXT, status TEXT, reactions TEXT,
                    sent_at_ms INTEGER NOT NULL DEFAULT 0, is_pinned INTEGER NOT NULL DEFAULT 0,
                    album_uris TEXT, album_types TEXT
                )""".trimIndent())
                fixture.execSQL("""CREATE TABLE pending_controls(
                    control_id TEXT PRIMARY KEY, peer_name TEXT NOT NULL, control_type TEXT NOT NULL,
                    control_payload TEXT NOT NULL, created_at_ms INTEGER NOT NULL
                )""".trimIndent())
                fixture.execSQL("INSERT INTO messages(id, peer_name, message_text) VALUES ('v11-message', 'Alice', 'preserved')")
                fixture.version = 11
                fixture.rawQuery("SELECT name FROM sqlite_master WHERE name = 'peers'", null).use {
                    org.junit.Assert.assertFalse(it.moveToFirst())
                }
            }
            val db = ChatDatabaseHelper.getInstance(context).writableDatabase
            assertEquals(ChatDatabaseHelper.DATABASE_VERSION, db.version)
            db.rawQuery("SELECT message_text FROM messages WHERE id = 'v11-message'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("preserved", it.getString(0))
            }
            db.rawQuery("PRAGMA table_info(peers)", null).use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("about_me", "transport_policy", "peer_source", "policy_confirmed")))
            }
            ChatDatabaseHelper.closeAllConnections()
            assertEquals(ChatDatabaseHelper.DATABASE_VERSION, ChatDatabaseHelper.getInstance(context).writableDatabase.version)
        } finally {
            pass.fill(0)
        }
    }

    @Test
    fun wrongKeyOpenThrowsInsteadOfDeletingDatabase() {
        val pass = "correct-passphrase-b64-format-44ch==".toByteArray(Charsets.UTF_8)
        val creator = object : net.zetetic.database.sqlcipher.SQLiteOpenHelper(
            context, "twopchat.db", pass, null, 1, 0, null, null, false,
        ) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE t(x TEXT)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        creator.writableDatabase.close()
        creator.close()
        val before = databaseFile.readBytes()

        val wrongHelper = object : net.zetetic.database.sqlcipher.SQLiteOpenHelper(
            context, "twopchat.db", "definitely-wrong-passphrase-b64-44ch==".toByteArray(Charsets.UTF_8),
            null, 1, 0, null, null, false,
        ) {
            override fun onCreate(db: SQLiteDatabase) = throw AssertionError("must not recreate")
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        assertThrows(Throwable::class.java) { wrongHelper.readableDatabase }
        assertTrue(
            "wrong-key open must not delete the database",
            databaseFile.readBytes().contentEquals(before),
        )
        wrongHelper.close()
    }
}
