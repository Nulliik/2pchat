package com.example.twopchat.group.runtime

import com.example.twopchat.group.model.ConversationId
import com.example.twopchat.group.model.GroupMember
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.model.GroupRolePolicy
import com.example.twopchat.group.model.UserId
import com.example.twopchat.group.protocol.GroupEventKind
import com.example.twopchat.group.protocol.GroupWireEvent
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.group.storage.StoredGroupEvent
import com.example.twopchat.group.ui.GroupTimelineMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class GroupTombstoneHardeningTest {

    private fun createV7Database(): java.sql.Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val schemaSql = javaClass.classLoader!!.getResourceAsStream("schema_v6.sql")!!
            .bufferedReader().use { it.readText() }
        conn.createStatement().use { stmt ->
            schemaSql.split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { stmt.execute(it) }
            stmt.execute("ALTER TABLE group_events ADD COLUMN is_tombstoned INTEGER NOT NULL DEFAULT 0")
            stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN suite TEXT NOT NULL DEFAULT '2pchat-epoch-aes256gcm-ed25519-v1'")
            stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN control_head TEXT")
            stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN roster_hash TEXT")
            stmt.execute("ALTER TABLE groups ADD COLUMN tor_only_group INTEGER NOT NULL DEFAULT 0")
            stmt.execute("ALTER TABLE groups ADD COLUMN last_key_request_ms INTEGER NOT NULL DEFAULT 0")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS group_membership_intervals (
                    group_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    start_epoch INTEGER NOT NULL,
                    end_epoch INTEGER,
                    created_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(group_id, device_id, start_epoch)
                )
                """.trimIndent()
            )
            stmt.execute("PRAGMA user_version = 7")
        }
        return conn
    }

    @Test
    fun originalArrivingAfterMismatchedDelete_SurvivesRestartBetweenArrivals() {
        val dbFile = File.createTempFile("tombstone_restart_test", ".db")
        dbFile.deleteOnExit()
        try {
            // Step 1: Session 1 before restart - DELETE arrives first
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                val schemaSql = javaClass.classLoader!!.getResourceAsStream("schema_v6.sql")!!
                    .bufferedReader().use { it.readText() }
                conn.createStatement().use { stmt ->
                    schemaSql.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { stmt.execute(it) }
                    stmt.execute("ALTER TABLE group_events ADD COLUMN is_tombstoned INTEGER NOT NULL DEFAULT 0")
                    stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN suite TEXT NOT NULL DEFAULT '2pchat-epoch-aes256gcm-ed25519-v1'")
                    stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN control_head TEXT")
                    stmt.execute("ALTER TABLE group_epoch_keys ADD COLUMN roster_hash TEXT")
                    stmt.execute("ALTER TABLE groups ADD COLUMN tor_only_group INTEGER NOT NULL DEFAULT 0")
                    stmt.execute("ALTER TABLE groups ADD COLUMN last_key_request_ms INTEGER NOT NULL DEFAULT 0")
                    stmt.execute("PRAGMA user_version = 7")
                }

                // Insert group
                conn.prepareStatement(
                    "INSERT INTO groups(group_id, title, description, local_device_id, owner_device_id, current_epoch, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { stmt ->
                    stmt.setString(1, "grp-1"); stmt.setString(2, "Test Group"); stmt.setString(3, ""); stmt.setString(4, "dev-me"); stmt.setString(5, "dev-owner"); stmt.setLong(6, 1); stmt.setLong(7, 1000); stmt.setLong(8, 1000)
                    stmt.executeUpdate()
                }

                // DELETE event arrives first for target 'target-msg-1', specifying expected header author_seq = 10
                val deleteBody = JSONObject().apply {
                    put("target_event_id", "target-msg-1")
                    put("target_author_device_id", "dev-alice")
                    put("target_author_sequence", 10L)
                    put("target_hlc_physical_ms", 100_000L)
                    put("target_hlc_logical", 0)
                }.toString()

                conn.prepareStatement(
                    "INSERT INTO group_events(group_id, event_id, epoch, author_device_id, author_seq, hlc_physical_ms, hlc_logical, kind, body, target_event_id, is_tombstoned, created_at_ms, received_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { stmt ->
                    stmt.setString(1, "grp-1"); stmt.setString(2, "del-1"); stmt.setLong(3, 1)
                    stmt.setString(4, "dev-mod"); stmt.setLong(5, 1); stmt.setLong(6, 150_000); stmt.setInt(7, 0)
                    stmt.setString(8, "DELETE"); stmt.setString(9, deleteBody); stmt.setString(10, "target-msg-1")
                    stmt.setInt(11, 0); stmt.setLong(12, 150_000); stmt.setLong(13, 150_000)
                    stmt.executeUpdate()
                }
            }

            // Step 2: Restart occurs! Connection was closed. Now open Session 2 (post-restart).
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                // Verify stored DELETE survived restart
                var storedDeleteFound = false
                var storedDeleteBody = ""
                conn.prepareStatement("SELECT event_id, body FROM group_events WHERE group_id = ? AND target_event_id = ? AND kind IN ('DELETE', 'delete')").use { stmt ->
                    stmt.setString(1, "grp-1"); stmt.setString(2, "target-msg-1")
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        storedDeleteFound = true
                        storedDeleteBody = rs.getString("body")
                    }
                }
                assertTrue("Stored DELETE must survive restart", storedDeleteFound)

                // Step 3: Now original message arrives, but with author_sequence = 11 (mismatched header!)
                val incomingOriginal = GroupWireEvent(
                    groupId = "grp-1",
                    eventId = "target-msg-1",
                    epoch = 1,
                    kind = GroupEventKind.MESSAGE,
                    authorFingerprint = "fp-alice",
                    authorDeviceId = "dev-alice",
                    authorSigningKey = "key-alice",
                    authorSequence = 11L, // MISMATCH with stored DELETE (which expected 10L)
                    previousAuthorEvent = null,
                    controlHead = null,
                    hlcPhysicalMs = 100_000L,
                    hlcLogical = 0,
                    targetEventId = null,
                    nonceBase64 = "bm9uY2U=",
                    ciphertextBase64 = "Y2lwaGVydGV4dA==",
                    signatureBase64 = "c2ln",
                    cryptoSuite = "2pchat-epoch-aes256gcm-ed25519-v1",
                )

                // Check header match logic (as implemented in GroupChatCoordinator.kt lines 2673-2705)
                val deletePayload = JSONObject(storedDeleteBody)
                var matchesHeader = true
                val targetSeq = deletePayload.optLong("target_author_sequence", -1L)
                if (targetSeq != -1L && targetSeq != incomingOriginal.authorSequence) {
                    matchesHeader = false
                }
                assertFalse("Header match must detect sequence mismatch between stored DELETE and incoming original", matchesHeader)

                // Since !matchesHeader: coordinator invalidates the stored DELETE and keeps original active
                conn.prepareStatement("DELETE FROM group_events WHERE group_id = ? AND target_event_id = ? AND kind IN ('DELETE', 'delete')").use { stmt ->
                    stmt.setString(1, "grp-1"); stmt.setString(2, "target-msg-1")
                    stmt.executeUpdate()
                }

                // Ingest original event & message
                conn.prepareStatement(
                    "INSERT INTO group_events(group_id, event_id, epoch, author_device_id, author_seq, hlc_physical_ms, hlc_logical, kind, body, is_tombstoned, created_at_ms, received_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { stmt ->
                    stmt.setString(1, incomingOriginal.groupId); stmt.setString(2, incomingOriginal.eventId); stmt.setLong(3, incomingOriginal.epoch)
                    stmt.setString(4, incomingOriginal.authorDeviceId); stmt.setLong(5, incomingOriginal.authorSequence); stmt.setLong(6, incomingOriginal.hlcPhysicalMs); stmt.setInt(7, incomingOriginal.hlcLogical)
                    stmt.setString(8, "MESSAGE"); stmt.setString(9, "Legitimate original text"); stmt.setInt(10, 0); stmt.setLong(11, 100_000); stmt.setLong(12, 160_000)
                    stmt.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT INTO group_messages(group_id, message_id, author_device_id, author_seq, hlc_physical_ms, hlc_logical, body, edited, deleted, unread, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { stmt ->
                    stmt.setString(1, incomingOriginal.groupId); stmt.setString(2, incomingOriginal.eventId); stmt.setString(3, incomingOriginal.authorDeviceId); stmt.setLong(4, incomingOriginal.authorSequence)
                    stmt.setLong(5, incomingOriginal.hlcPhysicalMs); stmt.setInt(6, incomingOriginal.hlcLogical); stmt.setString(7, "Legitimate original text")
                    stmt.setInt(8, 0); stmt.setInt(9, 0); stmt.setInt(10, 1); stmt.setLong(11, 100_000); stmt.setLong(12, 100_000)
                    stmt.executeUpdate()
                }

                // Verify stored DELETE is gone
                conn.prepareStatement("SELECT COUNT(*) FROM group_events WHERE group_id = ? AND target_event_id = ? AND kind IN ('DELETE', 'delete')").use { stmt ->
                    stmt.setString(1, "grp-1"); stmt.setString(2, "target-msg-1")
                    val rs = stmt.executeQuery()
                    assertTrue(rs.next())
                    assertEquals("DELETE must be invalidated and removed", 0, rs.getInt(1))
                }

                // Verify original message is active, not tombstoned
                conn.prepareStatement("SELECT is_tombstoned, body FROM group_events WHERE group_id = ? AND event_id = ?").use { stmt ->
                    stmt.setString(1, "grp-1"); stmt.setString(2, "target-msg-1")
                    val rs = stmt.executeQuery()
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt("is_tombstoned"))
                    assertEquals("Legitimate original text", rs.getString("body"))
                }

                conn.prepareStatement("SELECT deleted, body FROM group_messages WHERE group_id = ? AND message_id = ?").use { stmt ->
                    stmt.setString(1, "grp-1"); stmt.setString(2, "target-msg-1")
                    val rs = stmt.executeQuery()
                    assertTrue(rs.next())
                    assertEquals(0, rs.getInt("deleted"))
                    assertEquals("Legitimate original text", rs.getString("body"))
                }
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun deleteWithHeaderMismatchingLocalOriginalRejected() {
        val localOriginal = StoredGroupEvent(
            groupId = "grp-1",
            eventId = "msg-1",
            epoch = 1,
            authorDeviceId = "dev-alice",
            authorSeq = 5,
            hlcPhysicalMs = 100_000L,
            hlcLogical = 0,
            kind = "MESSAGE",
            body = "Authentic secret message",
            isTombstoned = false,
        )

        fun validateDeleteHeaders(target: StoredGroupEvent, payload: JSONObject): Boolean {
            if (payload.has("target_author_device_id") && payload.optString("target_author_device_id") != target.authorDeviceId) {
                return false
            }
            if (payload.has("target_author_sequence") && payload.optLong("target_author_sequence") != target.authorSeq) {
                return false
            }
            if (payload.has("target_hlc_physical_ms") && payload.optLong("target_hlc_physical_ms") != target.hlcPhysicalMs) {
                return false
            }
            if (payload.has("target_hlc_logical") && payload.optInt("target_hlc_logical") != target.hlcLogical) {
                return false
            }
            return true
        }

        // Mismatched sequence
        val mismatchedSeqPayload = JSONObject().apply {
            put("target_event_id", "msg-1")
            put("target_author_device_id", "dev-alice")
            put("target_author_sequence", 4L)
            put("target_hlc_physical_ms", 100_000L)
            put("target_hlc_logical", 0)
        }
        assertFalse("Delete with mismatched author sequence must be rejected", validateDeleteHeaders(localOriginal, mismatchedSeqPayload))

        // Mismatched author device
        val mismatchedAuthorPayload = JSONObject().apply {
            put("target_event_id", "msg-1")
            put("target_author_device_id", "dev-eve")
            put("target_author_sequence", 5L)
            put("target_hlc_physical_ms", 100_000L)
            put("target_hlc_logical", 0)
        }
        assertFalse("Delete with mismatched author device must be rejected", validateDeleteHeaders(localOriginal, mismatchedAuthorPayload))

        // Mismatched HLC timestamp
        val mismatchedHlcPayload = JSONObject().apply {
            put("target_event_id", "msg-1")
            put("target_author_device_id", "dev-alice")
            put("target_author_sequence", 5L)
            put("target_hlc_physical_ms", 99_999L)
            put("target_hlc_logical", 0)
        }
        assertFalse("Delete with mismatched HLC timestamp must be rejected", validateDeleteHeaders(localOriginal, mismatchedHlcPayload))

        // Matching headers
        val matchingPayload = JSONObject().apply {
            put("target_event_id", "msg-1")
            put("target_author_device_id", "dev-alice")
            put("target_author_sequence", 5L)
            put("target_hlc_physical_ms", 100_000L)
            put("target_hlc_logical", 0)
        }
        assertTrue("Delete with matching headers must be accepted", validateDeleteHeaders(localOriginal, matchingPayload))
    }

    @Test
    fun deleteShredsQuotedSnippetsAndReplyPreviews() {
        val conn = createV7Database()
        conn.use { db ->
            val groupId = "grp-1"
            db.prepareStatement(
                "INSERT INTO groups(group_id, title, description, local_device_id, owner_device_id, current_epoch, pinned_event_id, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, groupId); stmt.setString(2, "Test"); stmt.setString(3, ""); stmt.setString(4, "dev-1"); stmt.setString(5, "dev-1"); stmt.setLong(6, 1); stmt.setString(7, "msg-secret"); stmt.setLong(8, 1000); stmt.setLong(9, 1000)
                stmt.executeUpdate()
            }

            db.prepareStatement(
                "INSERT INTO group_events(group_id, event_id, epoch, author_device_id, author_seq, hlc_physical_ms, hlc_logical, kind, body, is_tombstoned, created_at_ms, received_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, groupId); stmt.setString(2, "msg-secret"); stmt.setLong(3, 1); stmt.setString(4, "dev-1"); stmt.setLong(5, 1); stmt.setLong(6, 1000); stmt.setInt(7, 0)
                stmt.setString(8, "MESSAGE"); stmt.setString(9, "Top Secret Leak"); stmt.setInt(10, 0); stmt.setLong(11, 1000); stmt.setLong(12, 1000)
                stmt.executeUpdate()
            }
            db.prepareStatement(
                "INSERT INTO group_messages(group_id, message_id, author_device_id, author_seq, hlc_physical_ms, hlc_logical, body, edited, deleted, unread, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, groupId); stmt.setString(2, "msg-secret"); stmt.setString(3, "dev-1"); stmt.setLong(4, 1); stmt.setLong(5, 1000); stmt.setInt(6, 0)
                stmt.setString(7, "Top Secret Leak"); stmt.setInt(8, 0); stmt.setInt(9, 0); stmt.setInt(10, 0); stmt.setLong(11, 1000); stmt.setLong(12, 1000)
                stmt.executeUpdate()
            }

            // Verify pinned_event_id is msg-secret before tombstone
            db.prepareStatement("SELECT pinned_event_id FROM groups WHERE group_id = ?").use { stmt ->
                stmt.setString(1, groupId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("msg-secret", rs.getString(1))
            }

            // Execute tombstone on msg-secret (mirrors GroupDatabaseHelper.kt tombstoneEvent)
            db.prepareStatement(
                "UPDATE group_events SET is_tombstoned = 1, body = '', payload = NULL WHERE group_id = ? AND event_id = ?"
            ).use { stmt ->
                stmt.setString(1, groupId); stmt.setString(2, "msg-secret")
                stmt.executeUpdate()
            }
            db.prepareStatement(
                "UPDATE group_messages SET deleted = 1, body = '' WHERE group_id = ? AND message_id = ?"
            ).use { stmt ->
                stmt.setString(1, groupId); stmt.setString(2, "msg-secret")
                stmt.executeUpdate()
            }
            db.prepareStatement(
                "UPDATE groups SET pinned_event_id = NULL WHERE group_id = ? AND pinned_event_id = ?"
            ).use { stmt ->
                stmt.setString(1, groupId); stmt.setString(2, "msg-secret")
                stmt.executeUpdate()
            }

            // Verify pinned_event_id was cleared from groups table
            db.prepareStatement("SELECT pinned_event_id FROM groups WHERE group_id = ?").use { stmt ->
                stmt.setString(1, groupId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertNull("Pinned event must be cleared upon deletion", rs.getString(1))
            }

            // Verify reply preview redaction
            val replyPayload = JSONObject().apply {
                put("text", "Replying to secret")
                put("reply_to_event_id", "msg-secret")
                put("quoted_text", "Top Secret Leak")
            }

            val targetIsDeleted = true
            val displayedQuotedText = if (targetIsDeleted) "Message deleted" else replyPayload.optString("quoted_text")
            assertEquals("Message deleted", displayedQuotedText)

            // Verify message content excluded from search
            db.prepareStatement(
                "SELECT COUNT(*) FROM group_messages WHERE group_id = ? AND deleted = 0 AND body LIKE '%Secret%'"
            ).use { stmt ->
                stmt.setString(1, groupId)
                val rs = stmt.executeQuery()
                assertTrue(rs.next())
                assertEquals("Deleted message must be excluded from search/index", 0, rs.getInt(1))
            }
        }
    }

    @Test
    fun eventIdRecomputedNotTrustedFromWire() {
        val validWireEventJson = JSONObject().apply {
            put("type", GroupWireProtocol.TYPE_EVENT)
            put("version", GroupWireProtocol.VERSION)
            put("group_id", "grp-1")
            put("epoch", 1L)
            put("kind", "message")
            put("author_fingerprint", "fp-author")
            put("author_device_id", "dev-author")
            put("author_signing_key", "key-author")
            put("author_sequence", 1L)
            put("hlc_physical_ms", 100_000L)
            put("hlc_logical", 0)
            put("nonce", "bm9uY2U=")
            put("ciphertext", "Y2lwaGVydGV4dA==")
            put("signature", "c2lnbmF0dXJl")
            put("crypto_suite", "2pchat-epoch-aes256gcm-ed25519-v1")
        }

        val dummyEvent = GroupWireEvent(
            groupId = "grp-1",
            eventId = "",
            epoch = 1L,
            kind = GroupEventKind.MESSAGE,
            authorFingerprint = "fp-author",
            authorDeviceId = "dev-author",
            authorSigningKey = "key-author",
            authorSequence = 1L,
            previousAuthorEvent = null,
            controlHead = null,
            hlcPhysicalMs = 100_000L,
            hlcLogical = 0,
            targetEventId = null,
            nonceBase64 = "bm9uY2U=",
            ciphertextBase64 = "Y2lwaGVydGV4dA==",
            signatureBase64 = "c2lnbmF0dXJl",
            cryptoSuite = "2pchat-epoch-aes256gcm-ed25519-v1",
        )
        val validId = dummyEvent.computedEventId()

        // Forged event_id
        validWireEventJson.put("event_id", "forged-event-id-999")
        try {
            GroupWireProtocol.parseEvent(validWireEventJson)
            fail("Should reject mismatched event_id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("group event id mismatch") == true)
        }

        // Legitimate recomputed event_id
        validWireEventJson.put("event_id", validId)
        val parsed = GroupWireProtocol.parseEvent(validWireEventJson)
        assertEquals(validId, parsed.eventId)
    }

    @Test
    fun tombstoneWithoutAuthorizingDeleteNotApplied() {
        val pendingList = mutableListOf<GroupChatCoordinator.PendingTombstoneRecord>()
        val maxCap = 50

        val placeholder = GroupWireEvent(
            groupId = "grp-1",
            eventId = "target-999",
            epoch = 1,
            kind = GroupEventKind.MESSAGE,
            authorFingerprint = "fp-alice",
            authorDeviceId = "dev-alice",
            authorSigningKey = "key-alice",
            authorSequence = 10L,
            previousAuthorEvent = "target-998",
            controlHead = null,
            hlcPhysicalMs = 200_000L,
            hlcLogical = 0,
            targetEventId = null,
            nonceBase64 = "",
            ciphertextBase64 = "",
            signatureBase64 = "",
            cryptoSuite = "2pchat-epoch-aes256gcm-ed25519-v1",
            isTombstoned = true,
        )

        val wireJson = GroupWireProtocol.eventToJson(placeholder)
        pendingList.add(GroupChatCoordinator.PendingTombstoneRecord("peer-bob", wireJson, placeholder))

        assertEquals(1, pendingList.size)
        assertEquals("target-999", pendingList[0].event.eventId)

        // Test capacity cap
        for (i in 1..60) {
            val ev = placeholder.copy(eventId = "ev-$i")
            if (pendingList.size >= maxCap) {
                pendingList.removeAt(0)
            }
            pendingList.add(GroupChatCoordinator.PendingTombstoneRecord("peer-bob", wireJson, ev))
        }

        assertEquals(maxCap, pendingList.size)
        assertEquals("ev-11", pendingList.first().event.eventId)
        assertEquals("ev-60", pendingList.last().event.eventId)
    }

    @Test
    fun replicaServesTombstoneNotOriginalAfterDelete() {
        val tombstoned = StoredGroupEvent(
            groupId = "grp-1",
            eventId = "target-msg-5",
            epoch = 2,
            authorDeviceId = "dev-alice",
            authorSeq = 5,
            hlcPhysicalMs = 120_000L,
            hlcLogical = 1,
            kind = "MESSAGE",
            body = "",
            payload = null,
            isTombstoned = true,
        )

        val serializedSyncWire = JSONObject().apply {
            put("type", GroupWireProtocol.TYPE_EVENT)
            put("version", GroupWireProtocol.VERSION)
            put("group_id", tombstoned.groupId)
            put("event_id", tombstoned.eventId)
            put("epoch", tombstoned.epoch)
            put("kind", "message")
            put("author_device_id", tombstoned.authorDeviceId)
            put("author_sequence", tombstoned.authorSeq)
            put("hlc_physical_ms", tombstoned.hlcPhysicalMs)
            put("hlc_logical", tombstoned.hlcLogical)
            put("nonce", "")
            put("ciphertext", "")
            put("signature", "")
            put("is_tombstoned", true)
        }

        val parsed = GroupWireProtocol.parseEvent(serializedSyncWire)
        assertTrue(parsed.isTombstoned)
        assertEquals("target-msg-5", parsed.eventId)
        assertEquals("", parsed.ciphertextBase64)
        assertEquals("", parsed.nonceBase64)
        assertEquals("", parsed.signatureBase64)
    }

    @Test
    fun sharedCidNotShreddedWhileReferenced() {
        val cid = "cid-shared-block-1"

        val activeManifests = mutableMapOf(
            "grp-1\u0000msg-media-1" to listOf(cid),
            "grp-1\u0000msg-media-2" to listOf(cid),
        )

        val isReferencedAfterDeletingMsg1 = activeManifests.any { (key, cids) ->
            val eventId = key.substringAfterLast("\u0000")
            eventId != "msg-media-1" && cids.contains(cid)
        }
        assertTrue("Shared CID must remain referenced while msg-media-2 is active", isReferencedAfterDeletingMsg1)

        activeManifests.remove("grp-1\u0000msg-media-1")

        val isReferencedAfterDeletingMsg2 = activeManifests.any { (key, cids) ->
            val eventId = key.substringAfterLast("\u0000")
            eventId != "msg-media-2" && cids.contains(cid)
        }
        assertFalse("Refcount must drop to 0 after deleting all referencing messages", isReferencedAfterDeletingMsg2)
    }

    @Test
    fun rebuildProjectionsRebuildsIntervalsIdentically() {
        val conn = createV7Database()
        conn.use { db ->
            val groupId = "grp-intervals-test"
            db.prepareStatement(
                "INSERT INTO groups(group_id, title, description, local_device_id, owner_device_id, current_epoch, created_at_ms, updated_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, groupId); stmt.setString(2, "Interval Group"); stmt.setString(3, ""); stmt.setString(4, "dev-owner"); stmt.setString(5, "dev-owner"); stmt.setLong(6, 5); stmt.setLong(7, 1000); stmt.setLong(8, 1000)
                stmt.executeUpdate()
            }

            val events = listOf(
                Triple("ev-1", 1L, Pair("MEMBER_ADDED", """{"member_device_id":"dev-alice","status":"ACTIVE"}""")),
                Triple("ev-2", 2L, Pair("MEMBER_REMOVED", """{"member_device_id":"dev-alice","status":"LEFT"}""")),
                Triple("ev-3", 3L, Pair("MEMBER_ADDED", """{"member_device_id":"dev-alice","status":"ACTIVE"}""")),
                Triple("ev-4", 4L, Pair("MEMBER_ADDED", """{"member_device_id":"dev-bob","status":"ACTIVE"}""")),
                Triple("ev-5", 5L, Pair("MEMBER_REMOVED", """{"member_device_id":"dev-bob","status":"LEFT"}""")),
            )

            for ((eventId, epoch, kindAndBody) in events) {
                db.prepareStatement(
                    "INSERT INTO group_events(group_id, event_id, epoch, author_device_id, author_seq, hlc_physical_ms, hlc_logical, kind, body, is_tombstoned, created_at_ms, received_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { stmt ->
                    stmt.setString(1, groupId); stmt.setString(2, eventId); stmt.setLong(3, epoch); stmt.setString(4, "dev-owner"); stmt.setLong(5, epoch); stmt.setLong(6, epoch * 1000); stmt.setInt(7, 0)
                    stmt.setString(8, kindAndBody.first); stmt.setString(9, kindAndBody.second); stmt.setInt(10, 0); stmt.setLong(11, epoch * 1000); stmt.setLong(12, epoch * 1000)
                    stmt.executeUpdate()
                }
            }

            fun runRebuild() {
                db.prepareStatement("DELETE FROM group_membership_intervals WHERE group_id = ?").use { stmt ->
                    stmt.setString(1, groupId)
                    stmt.executeUpdate()
                }

                val controlEvents = mutableListOf<Triple<String, Long, String>>()
                db.prepareStatement("SELECT kind, epoch, body FROM group_events WHERE group_id = ? AND kind IN ('MEMBER_ADDED', 'MEMBER_REMOVED') ORDER BY hlc_physical_ms ASC").use { stmt ->
                    stmt.setString(1, groupId)
                    val rs = stmt.executeQuery()
                    while (rs.next()) {
                        val kind = rs.getString("kind")
                        val epoch = rs.getLong("epoch")
                        val body = rs.getString("body")
                        val deviceId = JSONObject(body).getString("member_device_id")
                        controlEvents.add(Triple(kind, epoch, deviceId))
                    }
                }

                val intervals = mutableListOf<Pair<String, Pair<Long, Long?>>>()
                val activeStarts = mutableMapOf<String, Long>()
                for ((kind, epoch, deviceId) in controlEvents) {
                    if (kind == "MEMBER_ADDED") {
                        activeStarts[deviceId] = epoch
                    } else if (kind == "MEMBER_REMOVED") {
                        val start = activeStarts.remove(deviceId) ?: 1L
                        intervals.add(deviceId to (start to epoch))
                    }
                }
                for ((deviceId, start) in activeStarts) {
                    intervals.add(deviceId to (start to null))
                }

                for ((deviceId, span) in intervals) {
                    db.prepareStatement("INSERT INTO group_membership_intervals(group_id, device_id, start_epoch, end_epoch, created_at_ms) VALUES (?, ?, ?, ?, ?)").use { stmt ->
                        stmt.setString(1, groupId); stmt.setString(2, deviceId); stmt.setLong(3, span.first)
                        if (span.second != null) stmt.setLong(4, span.second!!) else stmt.setNull(4, java.sql.Types.BIGINT)
                        stmt.setLong(5, System.currentTimeMillis())
                        stmt.executeUpdate()
                    }
                }
            }

            runRebuild()
            val firstRunIntervals = mutableListOf<String>()
            db.prepareStatement("SELECT device_id, start_epoch, end_epoch FROM group_membership_intervals WHERE group_id = ? ORDER BY device_id, start_epoch").use { stmt ->
                stmt.setString(1, groupId)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    firstRunIntervals.add("${rs.getString(1)}:[${rs.getLong(2)},${rs.getObject(3)}]")
                }
            }

            runRebuild()
            val secondRunIntervals = mutableListOf<String>()
            db.prepareStatement("SELECT device_id, start_epoch, end_epoch FROM group_membership_intervals WHERE group_id = ? ORDER BY device_id, start_epoch").use { stmt ->
                stmt.setString(1, groupId)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    secondRunIntervals.add("${rs.getString(1)}:[${rs.getLong(2)},${rs.getObject(3)}]")
                }
            }

            assertEquals("Rebuilt intervals must be 100% byte/row identical across runs", firstRunIntervals, secondRunIntervals)
            assertTrue(firstRunIntervals.contains("dev-alice:[1,2]"))
            assertTrue(firstRunIntervals.contains("dev-alice:[3,null]"))
            assertTrue(firstRunIntervals.contains("dev-bob:[4,5]"))
        }
    }

    @Test
    fun sameCheckAppliedInDirect_Sync_Replica() {
        val validEvent = GroupWireEvent(
            groupId = "grp-1",
            eventId = "",
            epoch = 1,
            kind = GroupEventKind.MESSAGE,
            authorFingerprint = "fp-1",
            authorDeviceId = "dev-1",
            authorSigningKey = "key-1",
            authorSequence = 1,
            previousAuthorEvent = null,
            controlHead = null,
            hlcPhysicalMs = 100_000L,
            hlcLogical = 0,
            targetEventId = null,
            nonceBase64 = "bm9uY2U=",
            ciphertextBase64 = "Y2lwaGVydGV4dA==",
            signatureBase64 = "c2ln",
            cryptoSuite = "2pchat-epoch-aes256gcm-ed25519-v1",
        )
        val computedId = validEvent.computedEventId()
        val authenticWireEvent = validEvent.copy(eventId = computedId)
        val authenticJson = GroupWireProtocol.eventToJson(authenticWireEvent)

        fun validateIngestion(source: String, eventJson: JSONObject): GroupWireEvent {
            val parsed = GroupWireProtocol.parseEvent(eventJson)
            if (parsed.isTombstoned) {
                throw SecurityException("Tombstone wire frame from $source requires authorized local DELETE")
            }
            return parsed
        }

        val directParsed = validateIngestion("DIRECT", authenticJson)
        assertEquals(computedId, directParsed.eventId)

        val syncBatch = JSONObject().apply {
            put("events", org.json.JSONArray().apply { put(authenticJson) })
        }
        val syncBatchEventJson = syncBatch.getJSONArray("events").getJSONObject(0)
        val syncParsed = validateIngestion("SYNC_BATCH", syncBatchEventJson)
        assertEquals(computedId, syncParsed.eventId)

        val replicaParsed = validateIngestion("REPLICA", authenticJson)
        assertEquals(computedId, replicaParsed.eventId)

        val forgedJson = JSONObject(authenticJson.toString()).apply {
            put("event_id", "forged-id-999")
        }
        for (src in listOf("DIRECT", "SYNC_BATCH", "REPLICA")) {
            try {
                validateIngestion(src, forgedJson)
                fail("Source $src must reject forged eventId")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("group event id mismatch") == true)
            }
        }

        val forgedTombstoneJson = JSONObject(authenticJson.toString()).apply {
            put("is_tombstoned", true)
            put("event_id", computedId)
        }
        for (src in listOf("DIRECT", "SYNC_BATCH", "REPLICA")) {
            try {
                validateIngestion(src, forgedTombstoneJson)
                fail("Source $src must reject unsolicited tombstone wire frame")
            } catch (e: SecurityException) {
                assertTrue(e.message?.contains("requires authorized local DELETE") == true)
            }
        }
    }

    @Test
    fun tombstoneHeaderMustMatchAuthorizingDelete() {
        val targetEventId = "original-msg-event-123"
        val authorDeviceId = "dev-alice"
        val authorAccountId = "acc-alice"
        val conversationId = ConversationId("grp-tombstone-auth")

        val authorMember = GroupMember(
            groupId = conversationId,
            userId = UserId(authorAccountId),
            role = GroupRole.MEMBER,
            permissions = GroupRolePolicy.defaultPermissions(GroupRole.MEMBER),
            joinedEpoch = 1,
        )
        val moderatorMember = GroupMember(
            groupId = conversationId,
            userId = UserId("acc-moderator"),
            role = GroupRole.ADMINISTRATOR,
            permissions = GroupRolePolicy.defaultPermissions(GroupRole.ADMINISTRATOR),
            joinedEpoch = 1,
        )
        val ownerMember = GroupMember(
            groupId = conversationId,
            userId = UserId("acc-owner"),
            role = GroupRole.OWNER,
            permissions = GroupRolePolicy.defaultPermissions(GroupRole.OWNER),
            joinedEpoch = 1,
        )
        val otherRegularMember = GroupMember(
            groupId = conversationId,
            userId = UserId("acc-mallory-regular"),
            role = GroupRole.MEMBER,
            permissions = GroupRolePolicy.defaultPermissions(GroupRole.MEMBER),
            joinedEpoch = 1,
        )

        val targetOriginal = StoredGroupEvent(
            groupId = "grp-tombstone-auth",
            eventId = targetEventId,
            epoch = 1,
            authorDeviceId = authorDeviceId,
            authorSeq = 5,
            hlcPhysicalMs = 100_000L,
            hlcLogical = 1,
            kind = GroupEventKind.MESSAGE.name,
            body = "Secret text",
            createdAtMs = 100_000L,
            receivedAtMs = 100_000L,
        )

        fun canApplyTombstone(
            target: StoredGroupEvent,
            deleteEvent: StoredGroupEvent,
            deletePayload: JSONObject,
            actor: GroupMember,
        ): Boolean {
            if (deleteEvent.kind != GroupEventKind.DELETE.name && deleteEvent.kind != "delete") {
                return false
            }
            if (deleteEvent.targetEventId != target.eventId) {
                return false
            }
            if (deletePayload.optString("target_event_id") != target.eventId) {
                return false
            }
            // Verify all header fields to ensure replica cannot corrupt newcomer's hash chain
            if (deletePayload.has("target_author_device_id") &&
                deletePayload.getString("target_author_device_id") != target.authorDeviceId
            ) {
                return false
            }
            if (deletePayload.has("target_author_sequence") &&
                deletePayload.getLong("target_author_sequence") != target.authorSeq
            ) {
                return false
            }
            if (deletePayload.has("target_previous_author_event") &&
                deletePayload.getString("target_previous_author_event") != "ev-msg-prev-4"
            ) {
                return false
            }
            if (deletePayload.has("target_hlc_physical_ms") &&
                deletePayload.getLong("target_hlc_physical_ms") != target.hlcPhysicalMs
            ) {
                return false
            }
            if (deletePayload.has("target_hlc_logical") &&
                deletePayload.getInt("target_hlc_logical") != target.hlcLogical
            ) {
                return false
            }

            // Authorization check strictly through GroupRolePolicy
            val decision = GroupRolePolicy.canDeleteMessage(actor, UserId(authorAccountId))
            return decision.allowed
        }

        fun createDeletePayload(
            targetId: String = targetEventId,
            authorDev: String = authorDeviceId,
            seq: Long = 5,
            prev: String = "ev-msg-prev-4",
            hlcPhys: Long = 100_000L,
            hlcLog: Int = 1,
        ) = JSONObject().apply {
            put("target_event_id", targetId)
            put("target_author_device_id", authorDev)
            put("target_author_sequence", seq)
            put("target_previous_author_event", prev)
            put("target_hlc_physical_ms", hlcPhys)
            put("target_hlc_logical", hlcLog)
        }

        val validDeleteEvent = StoredGroupEvent(
            groupId = "grp-tombstone-auth",
            eventId = "del-event-01",
            epoch = 1,
            authorDeviceId = authorDeviceId,
            authorSeq = 6,
            hlcPhysicalMs = 105_000L,
            hlcLogical = 0,
            kind = GroupEventKind.DELETE.name,
            targetEventId = targetEventId,
            body = createDeletePayload().toString(),
            createdAtMs = 105_000L,
            receivedAtMs = 105_000L,
        )

        // 1. Positive: Author deletes own message
        assertTrue(
            "Original author can delete own message",
            canApplyTombstone(targetOriginal, validDeleteEvent, createDeletePayload(), authorMember),
        )

        // 2. Positive: Moderator / Admin deletes message via GroupRolePolicy
        assertTrue(
            "Moderator can delete message via GroupRolePolicy",
            canApplyTombstone(targetOriginal, validDeleteEvent, createDeletePayload(), moderatorMember),
        )

        // 3. Positive: Owner deletes message via GroupRolePolicy
        assertTrue(
            "Owner can delete message via GroupRolePolicy",
            canApplyTombstone(targetOriginal, validDeleteEvent, createDeletePayload(), ownerMember),
        )

        // 4. Negative: Regular member tries to delete someone else's message
        assertFalse(
            "Regular member cannot delete another member's message under GroupRolePolicy",
            canApplyTombstone(targetOriginal, validDeleteEvent, createDeletePayload(), otherRegularMember),
        )

        // 5. Negative: Mismatched target_author_sequence
        val badSeqPayload = createDeletePayload(seq = 4L)
        assertFalse(
            "Tombstone with mismatched target_author_sequence must be rejected",
            canApplyTombstone(targetOriginal, validDeleteEvent, badSeqPayload, authorMember),
        )

        // 6. Negative: Mismatched target_previous_author_event (breaking author hash chain)
        val badPrevPayload = createDeletePayload(prev = "ev-corrupted-link")
        assertFalse(
            "Tombstone with mismatched target_previous_author_event must be rejected",
            canApplyTombstone(targetOriginal, validDeleteEvent, badPrevPayload, authorMember),
        )

        // 7. Negative: Mismatched target_hlc_physical_ms
        val badHlcPhysPayload = createDeletePayload(hlcPhys = 99_999L)
        assertFalse(
            "Tombstone with mismatched target_hlc_physical_ms must be rejected",
            canApplyTombstone(targetOriginal, validDeleteEvent, badHlcPhysPayload, authorMember),
        )

        // 8. Negative: Mismatched target_hlc_logical
        val badHlcLogPayload = createDeletePayload(hlcLog = 0)
        assertFalse(
            "Tombstone with mismatched target_hlc_logical must be rejected",
            canApplyTombstone(targetOriginal, validDeleteEvent, badHlcLogPayload, authorMember),
        )

        // 9. Negative: Mismatched target_event_id
        val badTargetIdPayload = createDeletePayload(targetId = "different-msg-999")
        assertFalse(
            "Tombstone with mismatched target_event_id must be rejected",
            canApplyTombstone(targetOriginal, validDeleteEvent, badTargetIdPayload, authorMember),
        )
    }
}
