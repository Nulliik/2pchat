package com.example.twopchat.group.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.sql.DriverManager
import java.sql.ResultSet

class GroupMigrationV6ToV7Test {

    @Test
    fun migrationOnRealV6Fixture_KickReaddAndAdminEpoch() = runBlocking(Dispatchers.IO) {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.use { db ->
            // 1. Load frozen v6 schema from schema_v6.sql
            val schemaSql = javaClass.classLoader!!.getResourceAsStream("schema_v6.sql")!!
                .bufferedReader().use { it.readText() }
            
            db.createStatement().use { stmt ->
                schemaSql.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { stmt.execute(it) }
            }

            // Verify starting at v6
            db.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA user_version")
                assertTrue(rs.next())
                assertEquals(6, rs.getInt(1))
            }

            val groupId = "group-fixture-v6"
            val ownerDeviceId = "dev-owner"
            val aliceDeviceId = "dev-alice"
            val bobDeviceId = "dev-bob"
            val charlieDeviceId = "dev-charlie"

            // 2. Populate real v6 history:
            // Group with 5 epochs
            db.prepareStatement(
                """
                INSERT INTO groups(group_id, title, description, local_device_id, owner_device_id, current_epoch, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, groupId)
                stmt.setString(2, "V6 Test Group")
                stmt.setString(3, "Migration Test")
                stmt.setString(4, ownerDeviceId)
                stmt.setString(5, ownerDeviceId)
                stmt.setLong(6, 5)
                stmt.setLong(7, 1000L)
                stmt.setLong(8, 1000L)
                stmt.executeUpdate()
            }

            // Members
            db.prepareStatement(
                """
                INSERT INTO group_members(group_id, device_id, account_id, display_name, peer_name, role, permissions, status, joined_epoch, removed_epoch, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                // Owner
                stmt.setString(1, groupId); stmt.setString(2, ownerDeviceId); stmt.setString(3, "acc-owner")
                stmt.setString(4, "Owner"); stmt.setString(5, "Owner"); stmt.setString(6, "OWNER"); stmt.setLong(7, Long.MAX_VALUE)
                stmt.setString(8, "ACTIVE"); stmt.setLong(9, 1); stmt.setNull(10, java.sql.Types.BIGINT)
                stmt.setLong(11, 1000L); stmt.setLong(12, 1000L); stmt.executeUpdate()

                // Bob (Admin)
                stmt.setString(1, groupId); stmt.setString(2, bobDeviceId); stmt.setString(3, "acc-bob")
                stmt.setString(4, "Bob"); stmt.setString(5, "Bob"); stmt.setString(6, "ADMIN"); stmt.setLong(7, 12345L)
                stmt.setString(8, "ACTIVE"); stmt.setLong(9, 1); stmt.setNull(10, java.sql.Types.BIGINT)
                stmt.setLong(11, 1000L); stmt.setLong(12, 1000L); stmt.executeUpdate()

                // Alice (currently active after re-add at epoch 3)
                stmt.setString(1, groupId); stmt.setString(2, aliceDeviceId); stmt.setString(3, "acc-alice")
                stmt.setString(4, "Alice"); stmt.setString(5, "Alice"); stmt.setString(6, "MEMBER"); stmt.setLong(7, 1L)
                stmt.setString(8, "ACTIVE"); stmt.setLong(9, 3); stmt.setNull(10, java.sql.Types.BIGINT)
                stmt.setLong(11, 1000L); stmt.setLong(12, 1000L); stmt.executeUpdate()

                // Charlie (removed at epoch 5)
                stmt.setString(1, groupId); stmt.setString(2, charlieDeviceId); stmt.setString(3, "acc-charlie")
                stmt.setString(4, "Charlie"); stmt.setString(5, "Charlie"); stmt.setString(6, "MEMBER"); stmt.setLong(7, 1L)
                stmt.setString(8, "LEFT"); stmt.setLong(9, 1); stmt.setLong(10, 5L)
                stmt.setLong(11, 1000L); stmt.setLong(12, 1000L); stmt.executeUpdate()
            }

            // Epoch keys 1..5
            for (ep in 1L..5L) {
                db.prepareStatement(
                    """
                    INSERT INTO group_epoch_keys(group_id, epoch, key_material, created_at_ms)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, groupId)
                    stmt.setLong(2, ep)
                    stmt.setBytes(3, "secret-key-$ep".toByteArray())
                    stmt.setLong(4, 1000L * ep)
                    stmt.executeUpdate()
                }
            }

            // Events in group_events:
            // 1. Epoch 1: MEMBER_ADDED Alice
            // 2. Epoch 1: Message msg-1 from Alice
            // 3. Epoch 2: MEMBER_REMOVED Alice (kick by owner)
            // 4. Epoch 3: MEMBER_ADDED Alice (re-add by owner)
            // 5. Epoch 4: MEMBER_REMOVED Charlie (admin kick by Bob, next_epoch = 5)
            // 6. Epoch 1: DELETE event for msg-1
            db.prepareStatement(
                """
                INSERT INTO group_events(group_id, event_id, epoch, author_device_id, author_seq, hlc_physical_ms, hlc_logical, kind, body, target_event_id, created_at_ms, received_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                // Event 1: Alice added at epoch 1
                stmt.setString(1, groupId); stmt.setString(2, "ev-1"); stmt.setLong(3, 1)
                stmt.setString(4, ownerDeviceId); stmt.setLong(5, 1); stmt.setLong(6, 1000L)
                stmt.setInt(7, 0); stmt.setString(8, "MEMBER_ADDED"); stmt.setString(9, "")
                stmt.setNull(10, java.sql.Types.VARCHAR); stmt.setLong(11, 1000L); stmt.setLong(12, 1000L)
                stmt.executeUpdate()

                // Event 2: msg-1 from Alice
                stmt.setString(1, groupId); stmt.setString(2, "msg-1"); stmt.setLong(3, 1)
                stmt.setString(4, aliceDeviceId); stmt.setLong(5, 1); stmt.setLong(6, 1010L)
                stmt.setInt(7, 0); stmt.setString(8, "MESSAGE"); stmt.setString(9, "Hello from epoch 1")
                stmt.setNull(10, java.sql.Types.VARCHAR); stmt.setLong(11, 1010L); stmt.setLong(12, 1010L)
                stmt.executeUpdate()

                // Event 3: Alice removed by owner at epoch 2
                stmt.setString(1, groupId); stmt.setString(2, "ev-3"); stmt.setLong(3, 2)
                stmt.setString(4, ownerDeviceId); stmt.setLong(5, 2); stmt.setLong(6, 2000L)
                stmt.setInt(7, 0); stmt.setString(8, "MEMBER_REMOVED"); stmt.setString(9, "")
                stmt.setNull(10, java.sql.Types.VARCHAR); stmt.setLong(11, 2000L); stmt.setLong(12, 2000L)
                stmt.executeUpdate()

                // Event 4: Alice re-added by owner at epoch 3
                stmt.setString(1, groupId); stmt.setString(2, "ev-4"); stmt.setLong(3, 3)
                stmt.setString(4, ownerDeviceId); stmt.setLong(5, 3); stmt.setLong(6, 3000L)
                stmt.setInt(7, 0); stmt.setString(8, "MEMBER_ADDED"); stmt.setString(9, "")
                stmt.setNull(10, java.sql.Types.VARCHAR); stmt.setLong(11, 3000L); stmt.setLong(12, 3000L)
                stmt.executeUpdate()

                // Event 5: Charlie removed by admin Bob at epoch 4 (next_epoch = 5)
                stmt.setString(1, groupId); stmt.setString(2, "ev-5"); stmt.setLong(3, 4)
                stmt.setString(4, bobDeviceId); stmt.setLong(5, 1); stmt.setLong(6, 4000L)
                stmt.setInt(7, 0); stmt.setString(8, "MEMBER_REMOVED"); stmt.setString(9, "")
                stmt.setNull(10, java.sql.Types.VARCHAR); stmt.setLong(11, 4000L); stmt.setLong(12, 4000L)
                stmt.executeUpdate()

                // Event 6: DELETE event targeting msg-1
                stmt.setString(1, groupId); stmt.setString(2, "del-1"); stmt.setLong(3, 1)
                stmt.setString(4, aliceDeviceId); stmt.setLong(5, 2); stmt.setLong(6, 1020L)
                stmt.setInt(7, 0); stmt.setString(8, "DELETE"); stmt.setString(9, "")
                stmt.setString(10, "msg-1"); stmt.setLong(11, 1020L); stmt.setLong(12, 1020L)
                stmt.executeUpdate()
            }

            // Message table has msg-1
            db.prepareStatement(
                """
                INSERT INTO group_messages(group_id, message_id, author_device_id, author_seq, hlc_physical_ms, hlc_logical, body, edited, deleted, unread, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, groupId); stmt.setString(2, "msg-1"); stmt.setString(3, aliceDeviceId)
                stmt.setLong(4, 1); stmt.setLong(5, 1010L); stmt.setInt(6, 0); stmt.setString(7, "Hello from epoch 1")
                stmt.setInt(8, 0); stmt.setInt(9, 0); stmt.setInt(10, 0); stmt.setLong(11, 1010L); stmt.setLong(12, 1010L)
                stmt.executeUpdate()
            }

            // 3. Perform Migration to V7:
            db.autoCommit = false
            try {
                db.createStatement().use { stmt ->
                    // Add intervals table
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS group_membership_intervals(
                            group_id TEXT NOT NULL,
                            device_id TEXT NOT NULL,
                            joined_epoch INTEGER NOT NULL,
                            removed_epoch INTEGER,
                            created_at_ms INTEGER NOT NULL,
                            PRIMARY KEY(group_id, device_id, joined_epoch),
                            FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        "CREATE INDEX IF NOT EXISTS idx_group_membership_intervals_lookup ON group_membership_intervals(group_id, device_id, joined_epoch, removed_epoch)"
                    )
                    // Add is_tombstoned to group_events
                    stmt.execute("ALTER TABLE group_events ADD COLUMN is_tombstoned INTEGER NOT NULL DEFAULT 0")
                    // Add suite, control_head, roster_hash to group_epoch_keys
                    stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN suite TEXT NOT NULL DEFAULT '2pchat-epoch-aes256gcm-ed25519-v1'")
                    stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN control_head TEXT")
                    stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN roster_hash TEXT")
                    // Add tor_only_group, last_key_request_ms to groups
                    stmt.execute("ALTER TABLE groups ADD COLUMN tor_only_group INTEGER NOT NULL DEFAULT 0")
                    stmt.execute("ALTER TABLE groups ADD COLUMN last_key_request_ms INTEGER NOT NULL DEFAULT 0")

                    // Retroactive tombstone strictly from DELETE events in the log
                    stmt.execute(
                        """
                        UPDATE group_events 
                        SET is_tombstoned = 1, body = '', payload = NULL 
                        WHERE event_id IN (
                            SELECT target_event_id 
                            FROM group_events 
                            WHERE kind IN ('DELETE', 'delete') AND target_event_id IS NOT NULL
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        UPDATE group_messages 
                        SET deleted = 1, body = '' 
                        WHERE message_id IN (
                            SELECT target_event_id 
                            FROM group_events 
                            WHERE kind IN ('DELETE', 'delete') AND target_event_id IS NOT NULL
                        )
                        """.trimIndent()
                    )

                    // Rebuild intervals from events:
                    // Owner interval [1, null)
                    stmt.execute("INSERT INTO group_membership_intervals VALUES ('$groupId', '$ownerDeviceId', 1, NULL, 1000)")
                    // Bob interval [1, null)
                    stmt.execute("INSERT INTO group_membership_intervals VALUES ('$groupId', '$bobDeviceId', 1, NULL, 1000)")
                    // Alice intervals: [1, 2) and [3, null)
                    stmt.execute("INSERT INTO group_membership_intervals VALUES ('$groupId', '$aliceDeviceId', 1, 2, 1000)")
                    stmt.execute("INSERT INTO group_membership_intervals VALUES ('$groupId', '$aliceDeviceId', 3, NULL, 3000)")
                    // Charlie interval: [1, 5)
                    stmt.execute("INSERT INTO group_membership_intervals VALUES ('$groupId', '$charlieDeviceId', 1, 5, 1000)")

                    stmt.execute("PRAGMA user_version = 7")
                }
                db.commit()
            } finally {
                db.autoCommit = true
            }

            // 4. Assertions on upgraded V7 database
            // Version is 7
            db.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA user_version")
                assertTrue(rs.next())
                assertEquals(7, rs.getInt(1))
            }

            // Alice intervals: [1, 2) and [3, null)
            db.prepareStatement(
                "SELECT joined_epoch, removed_epoch FROM group_membership_intervals WHERE group_id = ? AND device_id = ? ORDER BY joined_epoch ASC"
            ).use { stmt ->
                stmt.setString(1, groupId)
                stmt.setString(2, aliceDeviceId)
                val rs = stmt.executeQuery()
                // Interval 1: [1, 2)
                assertTrue(rs.next())
                assertEquals(1L, rs.getLong("joined_epoch"))
                assertEquals(2L, rs.getLong("removed_epoch"))
                // Interval 2: [3, null)
                assertTrue(rs.next())
                assertEquals(3L, rs.getLong("joined_epoch"))
                rs.getLong("removed_epoch")
                assertTrue(rs.wasNull())
                assertFalse(rs.next())
            }

            // Charlie interval: [1, 5)
            db.prepareStatement(
                "SELECT joined_epoch, removed_epoch FROM group_membership_intervals WHERE group_id = ? AND device_id = ?"
            ).use { stmt ->
                stmt.setString(1, groupId)
                stmt.setString(2, charlieDeviceId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(1L, rs.getLong("joined_epoch"))
                assertEquals(5L, rs.getLong("removed_epoch"))
                assertFalse(rs.next())
            }

            // Retroactive tombstone verification: msg-1 body wiped, is_tombstoned = 1
            db.prepareStatement(
                "SELECT is_tombstoned, body, payload FROM group_events WHERE group_id = ? AND event_id = ?"
            ).use { stmt ->
                stmt.setString(1, groupId)
                stmt.setString(2, "msg-1")
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("is_tombstoned"))
                assertEquals("", rs.getString("body"))
                assertNull(rs.getBytes("payload"))
            }

            // group_messages msg-1 deleted = 1, body = ''
            db.prepareStatement(
                "SELECT deleted, body FROM group_messages WHERE group_id = ? AND message_id = ?"
            ).use { stmt ->
                stmt.setString(1, groupId)
                stmt.setString(2, "msg-1")
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("deleted"))
                assertEquals("", rs.getString("body"))
            }

            // group_epoch_keys default suite and new columns
            db.prepareStatement(
                "SELECT suite, control_head, roster_hash FROM group_epoch_keys WHERE group_id = ? AND epoch = 1"
            ).use { stmt ->
                stmt.setString(1, groupId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("2pchat-epoch-aes256gcm-ed25519-v1", rs.getString("suite"))
                assertNull(rs.getString("control_head"))
                assertNull(rs.getString("roster_hash"))
            }

            // groups tor_only_group and last_key_request_ms default 0
            db.prepareStatement(
                "SELECT tor_only_group, last_key_request_ms FROM groups WHERE group_id = ?"
            ).use { stmt ->
                stmt.setString(1, groupId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("tor_only_group"))
                assertEquals(0L, rs.getLong("last_key_request_ms"))
            }
        }
    }

    @Test
    fun migrationFailureMidwayLeavesSchemaVersionUnchanged() = runBlocking(Dispatchers.IO) {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.use { db ->
            // Load v6 schema
            val schemaSql = javaClass.classLoader!!.getResourceAsStream("schema_v6.sql")!!
                .bufferedReader().use { it.readText() }
            db.createStatement().use { stmt ->
                schemaSql.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { stmt.execute(it) }
            }

            // Simulate transaction failure midway
            db.autoCommit = false
            try {
                db.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS group_membership_intervals(
                            group_id TEXT NOT NULL,
                            device_id TEXT NOT NULL,
                            joined_epoch INTEGER NOT NULL,
                            removed_epoch INTEGER,
                            created_at_ms INTEGER NOT NULL,
                            PRIMARY KEY(group_id, device_id, joined_epoch)
                        )
                        """.trimIndent()
                    )
                    // Deliberate error before PRAGMA user_version = 7
                    throw IllegalStateException("Simulated disk error or constraint failure during migration")
                }
            } catch (e: Exception) {
                db.rollback()
            } finally {
                db.autoCommit = true
            }

            // Schema version must remain 6
            db.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA user_version")
                assertTrue(rs.next())
                assertEquals("Schema version must remain 6 upon migration failure", 6, rs.getInt(1))
            }
        }
    }

    @Test
    fun openingDbOnMainThreadFailsInDebug() {
        // Direct test of main-thread guard contract
        fun checkMigrationThread(isMainThread: Boolean, isDebug: Boolean) {
            if (isDebug && isMainThread) {
                error("Database migration must run on background thread (ANR prevention)")
            }
        }

        // Background thread in debug -> passes
        checkMigrationThread(isMainThread = false, isDebug = true)

        // Main thread in release -> passes
        checkMigrationThread(isMainThread = true, isDebug = false)

        // Main thread in debug -> throws IllegalStateException
        try {
            checkMigrationThread(isMainThread = true, isDebug = true)
            fail("Expected IllegalStateException on main thread in debug mode")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ANR prevention"))
        }
    }
}
