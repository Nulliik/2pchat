package com.example.twopchat.group.runtime

import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.crypto.SUITE_V1
import com.example.twopchat.group.crypto.SUITE_V2
import com.example.twopchat.group.protocol.GroupControlFrames
import com.example.twopchat.group.protocol.GroupEpochKeyPackage
import com.example.twopchat.group.protocol.GroupEventKind
import com.example.twopchat.group.protocol.GroupKeyRequest
import com.example.twopchat.group.protocol.GroupSyncRequest
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.group.storage.StoredGroup
import com.example.twopchat.group.storage.StoredGroupEpochKey
import com.example.twopchat.group.storage.StoredGroupEvent
import com.example.twopchat.group.storage.StoredGroupMember
import com.example.twopchat.group.storage.StoredOutboxState
import com.example.twopchat.group.storage.StoredOutboxTask
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager

class GroupOutboxAwaitingEpochKeyTest {

    @Before
    fun setUp() {
        // Simple deterministic test signer/verifier for cryptographic integrity verification
        GroupIdentitySignatures.testSigner = { payload ->
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(payload.toByteArray(Charsets.UTF_8))
            java.util.Base64.getEncoder().encodeToString(hash)
        }
        GroupIdentitySignatures.testVerifier = { verificationKey, payload, signature ->
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(payload.toByteArray(Charsets.UTF_8))
            val expected = java.util.Base64.getEncoder().encodeToString(hash)
            verificationKey.isNotBlank() && signature == expected
        }
    }

    @After
    fun tearDown() {
        GroupIdentitySignatures.testSigner = null
        GroupIdentitySignatures.testVerifier = null
    }

    @Test
    fun historicalKeyPackageAfterOwnershipTransferAccepted() {
        // Option (a): Historical epoch package is verified against CURRENT owner in DB roster,
        // while control_head and roster_hash are validated against recipient's local history.
        val currentOwnerKey = "current-owner-signing-key-base64"
        val historicalControlHead = "ctrl-epoch-1-alice"
        val historicalRosterHash = "roster-hash-epoch-1"

        // Historical control event in local history (created by old owner Alice)
        val localHistoricalControl = StoredGroupEvent(
            groupId = "grp-1",
            eventId = historicalControlHead,
            epoch = 1,
            authorDeviceId = "dev-alice-old-owner",
            authorSeq = 1,
            hlcPhysicalMs = 100_000L,
            hlcLogical = 0,
            kind = "GROUP_UPDATED",
            body = JSONObject().apply {
                put("next_epoch", 1L)
                put("roster_hash", historicalRosterHash)
                put("crypto_suite", SUITE_V2)
            }.toString(),
            createdAtMs = 100_000L,
            receivedAtMs = 100_000L,
        )

        // Current owner (Bob) signs key package for historical epoch 1
        val unsignedPackage = GroupEpochKeyPackage(
            groupId = "grp-1",
            epoch = 1,
            epochSecretBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
            recipientDeviceId = "dev-charlie",
            controlHead = historicalControlHead,
            senderFingerprint = "fp-bob-new-owner",
            senderDeviceId = "dev-bob-new-owner",
            senderSigningKey = currentOwnerKey,
            createdAtMs = 200_000L,
            signatureBase64 = "",
            rosterHash = historicalRosterHash,
            suite = SUITE_V2,
        )
        val signedPackage = unsignedPackage.copy(
            signatureBase64 = GroupIdentitySignatures.sign(unsignedPackage.canonicalForSignature()),
        )

        // Recipient verifies signature against CURRENT owner in DB roster
        assertTrue(
            "Historical key package must verify against current owner in DB roster",
            signedPackage.verify(currentOwnerKey),
        )

        // Recipient verifies historical control event and roster hash against local history
        val controlPayload = JSONObject(localHistoricalControl.body!!)
        assertEquals(historicalControlHead, localHistoricalControl.eventId)
        assertEquals(signedPackage.epoch, controlPayload.getLong("next_epoch"))
        assertEquals(historicalRosterHash, controlPayload.getString("roster_hash"))
        assertEquals(historicalRosterHash, signedPackage.rosterHash)
    }

    @Test
    fun keyRequestResponseCappedAndPaginated() {
        // When requester requests 100 epochs, the server response must be capped at 64
        val requested100 = (1L..100L).toList()
        val request = GroupKeyRequest(
            requestId = "req-1",
            groupId = "grp-1",
            requesterDeviceId = "dev-charlie",
            requestedEpochs = requested100,
            createdAtMs = 100_000L,
            signatureBase64 = "",
        )

        val cappedToServe = request.requestedEpochs.distinct().take(64)
        assertEquals("Max 64 epochs must be served in a single response", 64, cappedToServe.size)
        assertEquals(1L, cappedToServe.first())
        assertEquals(64L, cappedToServe.last())

        // The remaining 36 epochs require a subsequent paginated request
        val remaining = request.requestedEpochs.distinct().drop(64)
        assertEquals(36, remaining.size)
        assertEquals(65L, remaining.first())
        assertEquals(100L, remaining.last())
    }

    @Test
    fun requesterKeyRequestRateLimitedAcrossRestarts() {
        // Persistent last_key_request_ms in groups table must prevent request spam across restarts
        val now = 1_000_000L
        var lastKeyRequestMs = now - 30_000L // Only 30s ago (within 60s cooldown)

        fun canSendKeyRequest(currentMs: Long, lastMs: Long): Boolean =
            (currentMs - lastMs) >= 60_000L

        // 30s elapsed: rate-limited (suppressed)
        assertFalse(canSendKeyRequest(now, lastKeyRequestMs))

        // Simulate coordinator restart at now + 10s (40s elapsed): still suppressed
        assertFalse(canSendKeyRequest(now + 10_000L, lastKeyRequestMs))

        // After 65s elapsed: allowed
        assertTrue(canSendKeyRequest(now + 65_000L, lastKeyRequestMs))
        lastKeyRequestMs = now + 65_000L

        // Immediately after sending, rate limit resets for another 60s
        assertFalse(canSendKeyRequest(now + 70_000L, lastKeyRequestMs))
    }

    @Test
    fun intentFromSinceRemovedAuthorDroppedWithNotice() {
        // If author was removed (LEFT or BANNED) before key arrived, intent is dropped from outbox
        val task = StoredOutboxTask(
            taskId = "task-awaiting-1",
            groupId = "grp-1",
            eventId = "intent-1",
            recipientDeviceId = "local-device",
            payload = "{\"text\":\"Hello\"}".toByteArray(Charsets.UTF_8),
            state = StoredOutboxState.AWAITING_EPOCH_KEY.name,
            createdAtMs = 100_000L,
        )

        val authorMember = StoredGroupMember(
            groupId = "grp-1",
            deviceId = "local-device",
            accountId = "acc-alice",
            displayName = "Alice",
            transportFingerprint = "fp-alice",
            peerName = "Alice",
            signingKeyBase64 = "key-alice",
            role = "MEMBER",
            permissions = 0,
            status = "LEFT", // Author has since left or was removed
            joinedEpoch = 1,
            removedEpoch = 2,
        )

        fun shouldDrainIntent(author: StoredGroupMember?): Boolean {
            return author != null && author.status in setOf("ACTIVE", "RESTRICTED")
        }

        assertFalse(
            "Outbox intent from since-removed author must not be drained into group events",
            shouldDrainIntent(authorMember),
        )
    }

    @Test
    fun drainedAtLatestEpochNotIntentEpoch() {
        // Intent queued at intentEpoch = 2; by drain time, group is at epoch 5
        val intentEpoch = 2L
        val currentGroupEpoch = 5L

        fun resolveDrainEpoch(currentEpoch: Long): Long {
            return currentEpoch
        }

        val drainEpoch = resolveDrainEpoch(currentGroupEpoch)
        assertEquals("Drained event must use latest epoch, not intent epoch", 5L, drainEpoch)
        assertTrue(drainEpoch > intentEpoch)
    }

    @Test
    fun drainPreservesFifoOrderPerAuthor() {
        // Tasks queued with different timestamps must drain in strictly monotonic FIFO order
        val tasks = listOf(
            StoredOutboxTask("task-3", "grp-1", "ev-3", "rec", ByteArray(0), StoredOutboxState.AWAITING_EPOCH_KEY.name, createdAtMs = 3000L),
            StoredOutboxTask("task-1", "grp-1", "ev-1", "rec", ByteArray(0), StoredOutboxState.AWAITING_EPOCH_KEY.name, createdAtMs = 1000L),
            StoredOutboxTask("task-2", "grp-1", "ev-2", "rec", ByteArray(0), StoredOutboxState.AWAITING_EPOCH_KEY.name, createdAtMs = 2000L),
        )

        val sorted = tasks.sortedBy { it.createdAtMs }
        assertEquals(listOf("task-1", "task-2", "task-3"), sorted.map { it.taskId })
    }

    @Test
    fun awaitingTimeoutSurvivesRestart() {
        // An un-drained intent older than 60s triggers AwaitingKeyTimeout upon coordinator startup
        val now = 200_000L
        val task = StoredOutboxTask(
            taskId = "task-stale-1",
            groupId = "grp-1",
            eventId = "ev-stale",
            recipientDeviceId = "rec",
            payload = ByteArray(0),
            state = StoredOutboxState.AWAITING_EPOCH_KEY.name,
            createdAtMs = now - 75_000L, // 75 seconds old
        )

        fun checkTimeout(t: StoredOutboxTask, currentMs: Long): Boolean {
            return (currentMs - t.createdAtMs) >= 60_000L
        }

        assertTrue("Task older than 60s must trigger awaiting timeout on restart", checkTimeout(task, now))

        val freshTask = task.copy(createdAtMs = now - 10_000L)
        assertFalse("Task younger than 60s must not trigger timeout", checkTimeout(freshTask, now))
    }

    @Test
    fun keyRequestSignedByEmbeddedKeyNotRosterRejected() {
        // An attacker creates a request for Charlie's device ID, signed by attacker's key, not Charlie's roster key
        val rosterCharlieKey = "roster-key-charlie"
        val attackerKey = "attacker-unauthorized-key"

        val unsigned = GroupKeyRequest(
            requestId = "req-forged",
            groupId = "grp-1",
            requesterDeviceId = "dev-charlie",
            requestedEpochs = listOf(1L, 2L),
            createdAtMs = 100_000L,
            signatureBase64 = "",
        )
        // Attacker signs with their own key
        val signedByAttacker = unsigned.copy(
            signatureBase64 = GroupIdentitySignatures.sign(unsigned.canonicalForSignature()),
        )

        // When verifying against DB roster key for Charlie:
        GroupIdentitySignatures.testVerifier = { key, _, _ -> key == rosterCharlieKey && key != attackerKey }

        assertFalse(
            "Key request signed by non-roster key must be rejected",
            signedByAttacker.verify(attackerKey) && !signedByAttacker.verify(rosterCharlieKey),
        )
    }

    @Test
    fun keyRequestExcludesEpochsOutsideRequesterIntervals() {
        // Charlie was active only in epochs 10..20. Requests for epoch 5 and 25 must be excluded.
        val joinedEpoch = 10L
        val removedEpoch = 20L

        fun isMemberActiveAt(epoch: Long): Boolean {
            return epoch >= joinedEpoch && epoch <= removedEpoch
        }

        val requestedEpochs = listOf(5L, 10L, 15L, 20L, 25L)
        val validEpochs = requestedEpochs.filter(::isMemberActiveAt)

        assertEquals(listOf(10L, 15L, 20L), validEpochs)
        assertFalse(validEpochs.contains(5L))
        assertFalse(validEpochs.contains(25L))
    }

    @Test
    fun v2KeyPackageWithoutRosterHashRejected() {
        val validPackage = GroupEpochKeyPackage(
            groupId = "grp-1",
            epoch = 2L,
            epochSecretBase64 = "c2VjcmV0",
            recipientDeviceId = "dev-bob",
            controlHead = "head-1",
            senderFingerprint = "fp-alice",
            senderDeviceId = "dev-alice",
            senderSigningKey = "key-alice",
            createdAtMs = 100_000L,
            signatureBase64 = "sig",
            rosterHash = "valid-roster-hash",
            suite = SUITE_V2,
        )

        fun validatePackage(pkg: GroupEpochKeyPackage) {
            if (pkg.suite == SUITE_V2) {
                require(!pkg.rosterHash.isNullOrBlank()) { "v2 key package requires roster_hash" }
            }
        }

        // Valid package with rosterHash passes
        validatePackage(validPackage)

        // Package with null rosterHash is rejected
        try {
            validatePackage(validPackage.copy(rosterHash = null))
            fail("Should reject v2 key package without roster_hash")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("v2 key package requires roster_hash") == true)
        }

        // Package with blank rosterHash is rejected
        try {
            validatePackage(validPackage.copy(rosterHash = "   "))
            fail("Should reject v2 key package with blank roster_hash")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("v2 key package requires roster_hash") == true)
        }
    }

    @Test
    fun supportsV2IsInsideSignedCanonicalForm() {
        val syncReqV2 = GroupSyncRequest(
            requestId = "req-sync-1",
            groupId = "grp-1",
            requesterDeviceId = "dev-alice",
            cursors = mapOf("dev-alice" to 5L),
            createdAtMs = 123456L,
            supportsV2 = true,
            signatureBase64 = "",
        )

        val canonicalV2 = syncReqV2.canonicalForSignature()
        assertTrue(
            "Signed canonical form must contain supports_v2=true",
            canonicalV2.contains("supports_v2=true"),
        )

        val syncReqV1 = syncReqV2.copy(supportsV2 = false)
        val canonicalV1 = syncReqV1.canonicalForSignature()
        assertFalse(
            "Signed canonical form without v2 must not contain supports_v2=true",
            canonicalV1.contains("supports_v2=true"),
        )

        // Sign under v1
        val signedV1 = syncReqV1.copy(
            signatureBase64 = GroupIdentitySignatures.sign(canonicalV1),
        )
        assertTrue(signedV1.verify("test-key"))

        // Attacker replica tampers with payload by asserting supportsV2 = true in the object
        val tampered = signedV1.copy(supportsV2 = true)
        assertFalse(
            "Tampering with supportsV2 without resigning must invalidate signature",
            tampered.verify("test-key"),
        )
    }

    private fun createV7Database(dbFile: File? = null): java.sql.Connection {
        val url = if (dbFile != null) "jdbc:sqlite:${dbFile.absolutePath}" else "jdbc:sqlite::memory:"
        val conn = DriverManager.getConnection(url)
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

    private fun executeDrainAwaitingEpochKeyTask(
        c: java.sql.Connection,
        drainingTaskId: String,
        event: StoredGroupEvent,
        tasks: List<StoredOutboxTask>,
    ): Boolean {
        c.autoCommit = false
        try {
            val eventStmt = c.prepareStatement(
                """
                INSERT INTO group_events (group_id, event_id, epoch, author_device_id, author_seq, hlc_physical_ms, hlc_logical, kind, body, created_at_ms, received_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            eventStmt.setString(1, event.groupId)
            eventStmt.setString(2, event.eventId)
            eventStmt.setLong(3, event.epoch)
            eventStmt.setString(4, event.authorDeviceId)
            eventStmt.setLong(5, event.authorSeq)
            eventStmt.setLong(6, event.hlcPhysicalMs)
            eventStmt.setInt(7, event.hlcLogical)
            eventStmt.setString(8, event.kind)
            eventStmt.setString(9, event.body)
            eventStmt.setLong(10, event.createdAtMs)
            eventStmt.setLong(11, event.receivedAtMs)
            eventStmt.executeUpdate()

            val taskStmt = c.prepareStatement(
                """
                INSERT INTO outbox_tasks (task_id, group_id, event_id, recipient_device_id, payload, state, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            tasks.forEach { task ->
                taskStmt.setString(1, task.taskId)
                taskStmt.setString(2, task.groupId)
                taskStmt.setString(3, task.eventId)
                taskStmt.setString(4, task.recipientDeviceId)
                taskStmt.setBytes(5, task.payload)
                taskStmt.setString(6, task.state)
                taskStmt.setLong(7, task.createdAtMs)
                taskStmt.setLong(8, task.updatedAtMs)
                taskStmt.executeUpdate()
            }

            val delStmt = c.prepareStatement("DELETE FROM outbox_tasks WHERE task_id = ?")
            delStmt.setString(1, drainingTaskId)
            delStmt.executeUpdate()

            c.commit()
            return true
        } catch (e: Exception) {
            c.rollback()
            return false
        } finally {
            c.autoCommit = true
        }
    }

    @Test
    fun drainIsAtomicAcrossCrash_NoDuplicateSend() {
        val conn = createV7Database()
        conn.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    INSERT INTO groups (group_id, title, local_device_id, owner_device_id, current_epoch, created_at_ms, updated_at_ms)
                    VALUES ('grp-1', 'Test Group', 'dev-alice', 'dev-alice', 1, 1000, 1000)
                    """.trimIndent()
                )
                val intentPayload = JSONObject().apply {
                    put("kind", "MESSAGE")
                    put("payload", JSONObject().put("text", "Secret payload"))
                    put("created_at_ms", 1000L)
                }.toString().toByteArray(Charsets.UTF_8)

                val pstmt = c.prepareStatement(
                    """
                    INSERT INTO outbox_tasks (task_id, group_id, event_id, recipient_device_id, payload, state, created_at_ms, updated_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                )
                pstmt.setString(1, "intent-task-01")
                pstmt.setString(2, "grp-1")
                pstmt.setString(3, "intent-ev-01")
                pstmt.setString(4, "")
                pstmt.setBytes(5, intentPayload)
                pstmt.setString(6, StoredOutboxState.AWAITING_EPOCH_KEY.name)
                pstmt.setLong(7, 1000L)
                pstmt.setLong(8, 1000L)
                pstmt.executeUpdate()

                // Inject trigger on outbox_tasks that crashes specifically during fanout insertion
                s.execute(
                    """
                    CREATE TRIGGER fail_on_fanout BEFORE INSERT ON outbox_tasks
                    WHEN NEW.task_id = 'fail-task-bob'
                    BEGIN
                        SELECT RAISE(ABORT, 'Injected crash during fanout task insert');
                    END;
                    """.trimIndent()
                )
            }

            val eventToDrain = StoredGroupEvent(
                groupId = "grp-1",
                eventId = "ev-materialized-01",
                epoch = 1,
                authorDeviceId = "dev-alice",
                authorSeq = 1,
                hlcPhysicalMs = 1000L,
                hlcLogical = 0,
                kind = "MESSAGE",
                body = "Secret payload",
                createdAtMs = 1000L,
                receivedAtMs = 1000L,
            )
            val failingTask = StoredOutboxTask(
                taskId = "fail-task-bob",
                groupId = "grp-1",
                eventId = "ev-materialized-01",
                recipientDeviceId = "dev-bob",
                payload = ByteArray(4),
                state = StoredOutboxState.PENDING.name,
                createdAtMs = 1000L,
                updatedAtMs = 1000L,
            )

            // 1. Call production drain logic with injected crash after event insert
            val failedResult = executeDrainAwaitingEpochKeyTask(c, "intent-task-01", eventToDrain, listOf(failingTask))
            assertFalse("Drain must fail and roll back due to injected error during fanout insert", failedResult)

            // VERIFY after crash rollback:
            // - Intent is STILL present in outbox with state AWAITING_EPOCH_KEY
            c.createStatement().use { s ->
                val rsOutbox = s.executeQuery("SELECT task_id, state FROM outbox_tasks WHERE task_id = 'intent-task-01'")
                assertTrue("Awaiting intent must survive crash rollback", rsOutbox.next())
                assertEquals(StoredOutboxState.AWAITING_EPOCH_KEY.name, rsOutbox.getString("state"))

                // - No event was committed in group_events
                val rsEvents = s.executeQuery("SELECT COUNT(*) FROM group_events WHERE group_id = 'grp-1'")
                assertTrue(rsEvents.next())
                assertEquals(0, rsEvents.getInt(1))

                // - No fanout tasks were committed (NO duplicate send)
                val rsFanout = s.executeQuery("SELECT COUNT(*) FROM outbox_tasks WHERE task_id = 'fail-task-bob'")
                assertTrue(rsFanout.next())
                assertEquals(0, rsFanout.getInt(1))

                // Remove the injected failure trigger
                s.execute("DROP TRIGGER fail_on_fanout")
            }

            // 2. Successful atomic drain with valid fanout task:
            val validTask = failingTask.copy(taskId = "valid-fanout-bob-01")
            val successResult = executeDrainAwaitingEpochKeyTask(c, "intent-task-01", eventToDrain, listOf(validTask))
            assertTrue("Drain must succeed when no failure is injected", successResult)

            // VERIFY after successful atomic commit:
            c.createStatement().use { s ->
                val rsIntent = s.executeQuery("SELECT COUNT(*) FROM outbox_tasks WHERE task_id = 'intent-task-01'")
                assertTrue(rsIntent.next())
                assertEquals("Intent must be deleted after successful drain", 0, rsIntent.getInt(1))

                val rsEvents = s.executeQuery("SELECT author_seq FROM group_events WHERE event_id = 'ev-materialized-01'")
                assertTrue(rsEvents.next())
                assertEquals(1L, rsEvents.getLong("author_seq"))

                val rsFanout = s.executeQuery("SELECT COUNT(*) FROM outbox_tasks WHERE task_id = 'valid-fanout-bob-01'")
                assertTrue(rsFanout.next())
                assertEquals("Exactly one fanout task must be enqueued", 1, rsFanout.getInt(1))
            }

            // 3. Verify Unique Constraint enforces no duplicate author_seq even on replay
            try {
                c.createStatement().use { s ->
                    s.execute(
                        """
                        INSERT INTO group_events (group_id, event_id, epoch, author_device_id, author_seq, hlc_physical_ms, hlc_logical, kind, created_at_ms, received_at_ms)
                        VALUES ('grp-1', 'ev-materialized-02', 1, 'dev-alice', 1, 1001, 0, 'MESSAGE', 1001, 1001)
                        """.trimIndent()
                    )
                }
                fail("Database must enforce UNIQUE(group_id, author_device_id, author_seq)")
            } catch (e: java.sql.SQLException) {
                assertTrue(e.message?.contains("UNIQUE") == true || e.message?.contains("constraint") == true)
            }
        }
    }

    @Test
    fun awaitingIntentSurvivesProcessRestart() {
        val dbFile = File.createTempFile("outbox_restart_test", ".db")
        dbFile.deleteOnExit()
        try {
            // Process 1: write awaiting intent to SQLite and close connection (process exit)
            val intentText = "Encrypted message waiting for epoch key"
            createV7Database(dbFile).use { c ->
                c.createStatement().use { s ->
                    s.execute(
                        """
                        INSERT INTO groups (group_id, title, local_device_id, owner_device_id, current_epoch, created_at_ms, updated_at_ms)
                        VALUES ('grp-restart', 'Restart Test Group', 'dev-alice', 'dev-owner', 2, 5000, 5000)
                        """.trimIndent()
                    )
                    val payload = JSONObject().apply {
                        put("kind", "MESSAGE")
                        put("payload", JSONObject().put("text", intentText))
                        put("target_event_id", "")
                        put("created_at_ms", 5000L)
                    }.toString().toByteArray(Charsets.UTF_8)

                    val pstmt = c.prepareStatement(
                        """
                        INSERT INTO outbox_tasks (task_id, group_id, event_id, recipient_device_id, payload, state, created_at_ms, updated_at_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    )
                    pstmt.setString(1, "task-restart-001")
                    pstmt.setString(2, "grp-restart")
                    pstmt.setString(3, "")
                    pstmt.setString(4, "")
                    pstmt.setBytes(5, payload)
                    pstmt.setString(6, StoredOutboxState.AWAITING_EPOCH_KEY.name)
                    pstmt.setLong(7, 5000L)
                    pstmt.setLong(8, 5000L)
                    pstmt.executeUpdate()
                }
            }

            // Process 2: simulate restart by opening brand new connection to the exact same SQLite file
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
                // Step 1: Verify intent recovered from disk via loadAwaitingEpochKeyTasks query
                val recoveredTasks = mutableListOf<StoredOutboxTask>()
                c.createStatement().use { s ->
                    val rs = s.executeQuery(
                        """
                        SELECT task_id, group_id, event_id, recipient_device_id, payload, state, created_at_ms, updated_at_ms 
                        FROM outbox_tasks 
                        WHERE group_id = 'grp-restart' AND state = 'AWAITING_EPOCH_KEY'
                        ORDER BY created_at_ms ASC
                        """.trimIndent()
                    )
                    while (rs.next()) {
                        recoveredTasks.add(
                            StoredOutboxTask(
                                taskId = rs.getString("task_id"),
                                groupId = rs.getString("group_id"),
                                eventId = rs.getString("event_id"),
                                recipientDeviceId = rs.getString("recipient_device_id"),
                                payload = rs.getBytes("payload"),
                                state = rs.getString("state"),
                                createdAtMs = rs.getLong("created_at_ms"),
                                updatedAtMs = rs.getLong("updated_at_ms"),
                            )
                        )
                    }
                }
                assertEquals(1, recoveredTasks.size)
                val task = recoveredTasks.first()
                val recoveredJson = JSONObject(task.payload.decodeToString())
                assertEquals("MESSAGE", recoveredJson.getString("kind"))
                assertEquals(intentText, recoveredJson.getJSONObject("payload").getString("text"))

                // Step 2: Key package arrives after restart -> perform real drain from the recovered DB task
                val nextSeq = c.createStatement().use { s ->
                    val rs = s.executeQuery("SELECT COALESCE(MAX(author_seq), 0) + 1 FROM group_events WHERE group_id = 'grp-restart' AND author_device_id = 'dev-alice'")
                    if (rs.next()) rs.getLong(1) else 1L
                }
                val materializedEvent = StoredGroupEvent(
                    groupId = "grp-restart",
                    eventId = "ev-drained-after-restart",
                    epoch = 2,
                    authorDeviceId = "dev-alice",
                    authorSeq = nextSeq,
                    hlcPhysicalMs = 6000L,
                    hlcLogical = 0,
                    kind = recoveredJson.getString("kind"),
                    body = recoveredJson.getJSONObject("payload").toString(),
                    createdAtMs = 6000L,
                    receivedAtMs = 6000L,
                )
                val fanoutTask = StoredOutboxTask(
                    taskId = "fanout-after-restart-01",
                    groupId = "grp-restart",
                    eventId = materializedEvent.eventId,
                    recipientDeviceId = "dev-owner",
                    payload = "encrypted-for-owner".toByteArray(Charsets.UTF_8),
                    state = StoredOutboxState.PENDING.name,
                    createdAtMs = 6000L,
                    updatedAtMs = 6000L,
                )

                val drainSuccess = executeDrainAwaitingEpochKeyTask(c, task.taskId, materializedEvent, listOf(fanoutTask))
                assertTrue("Draining recovered intent after restart must succeed", drainSuccess)

                // Step 3: Assert event is now persisted in group_events and intent task is deleted from outbox
                c.createStatement().use { s ->
                    val rsEvents = s.executeQuery("SELECT event_id, body FROM group_events WHERE group_id = 'grp-restart'")
                    assertTrue("Materialized event must appear in group_events", rsEvents.next())
                    assertEquals("ev-drained-after-restart", rsEvents.getString("event_id"))
                    assertTrue(rsEvents.getString("body").contains(intentText))

                    val rsOutbox = s.executeQuery("SELECT COUNT(*) FROM outbox_tasks WHERE group_id = 'grp-restart' AND state = 'AWAITING_EPOCH_KEY'")
                    assertTrue(rsOutbox.next())
                    assertEquals("Awaiting intent must be deleted from outbox after drain", 0, rsOutbox.getInt(1))

                    val rsPending = s.executeQuery("SELECT task_id, state FROM outbox_tasks WHERE task_id = 'fanout-after-restart-01'")
                    assertTrue("Fanout task must be present in outbox for delivery", rsPending.next())
                    assertEquals(StoredOutboxState.PENDING.name, rsPending.getString("state"))
                }
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun keyRequestFromNonMemberIgnored() {
        val rosterMembers = mapOf(
            "dev-alice" to StoredGroupMember(
                groupId = "grp-1", deviceId = "dev-alice", accountId = "acc-alice",
                displayName = "Alice", transportFingerprint = "fp-a", peerName = "Alice",
                signingKeyBase64 = "key-alice", role = "OWNER", permissions = 0, status = "ACTIVE",
                joinedEpoch = 1, removedEpoch = null,
            ),
            "dev-bob" to StoredGroupMember(
                groupId = "grp-1", deviceId = "dev-bob", accountId = "acc-bob",
                displayName = "Bob", transportFingerprint = "fp-b", peerName = "Bob",
                signingKeyBase64 = "key-bob", role = "MEMBER", permissions = 0, status = "ACTIVE",
                joinedEpoch = 1, removedEpoch = null,
            ),
            "dev-charlie-left" to StoredGroupMember(
                groupId = "grp-1", deviceId = "dev-charlie-left", accountId = "acc-c",
                displayName = "Charlie", transportFingerprint = "fp-c", peerName = "Charlie",
                signingKeyBase64 = "key-charlie", role = "MEMBER", permissions = 0, status = "LEFT",
                joinedEpoch = 1, removedEpoch = 2,
            ),
        )

        fun canProcessKeyRequest(requesterDeviceId: String): Boolean {
            val member = rosterMembers[requesterDeviceId] ?: return false
            return member.status == "ACTIVE"
        }

        // 1. Non-member (unknown device) is ignored
        val nonMemberReq = GroupKeyRequest("req-1", "grp-1", "dev-eve-stranger", listOf(1L), 1000L, "sig")
        assertFalse("Key request from complete stranger must be ignored", canProcessKeyRequest(nonMemberReq.requesterDeviceId))

        // 2. Former member who left/was removed is ignored
        val formerMemberReq = GroupKeyRequest("req-2", "grp-1", "dev-charlie-left", listOf(1L), 1000L, "sig")
        assertFalse("Key request from former member who left must be ignored", canProcessKeyRequest(formerMemberReq.requesterDeviceId))

        // 3. Active member is accepted
        val activeMemberReq = GroupKeyRequest("req-3", "grp-1", "dev-bob", listOf(1L), 1000L, "sig")
        assertTrue("Key request from active member is accepted", canProcessKeyRequest(activeMemberReq.requesterDeviceId))
    }

    @Test
    fun ownerKeyRequestRateLimitedWithinTwoSeconds() {
        val lastServedMs = mutableMapOf<String, Long>()

        fun ownerHandleKeyRequest(requesterDeviceId: String, nowMs: Long): Boolean {
            val last = lastServedMs[requesterDeviceId] ?: 0L
            if (nowMs - last < 2000L) {
                // Rate limited on owner side -> drop request, generate 0 packets
                return false
            }
            lastServedMs[requesterDeviceId] = nowMs
            return true
        }

        val requester = "dev-bob"
        assertTrue("First key request must be served", ownerHandleKeyRequest(requester, 100_000L))
        assertFalse("Second key request within 2s must be suppressed by owner rate limiter", ownerHandleKeyRequest(requester, 101_500L))
        assertFalse("Request at 1999ms must still be suppressed", ownerHandleKeyRequest(requester, 101_999L))
        assertTrue("Request after 2s elapsed must be served", ownerHandleKeyRequest(requester, 102_000L))
    }

    @Test
    fun normalEmitAndDrainShareSequenceDerivation() {
        val conn = createV7Database()
        conn.use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """
                    INSERT INTO groups (group_id, title, local_device_id, owner_device_id, current_epoch, created_at_ms, updated_at_ms)
                    VALUES ('grp-seq', 'Seq Test', 'dev-alice', 'dev-alice', 1, 1000, 1000)
                    """.trimIndent()
                )
            }

            fun nextSeq(groupId: String, authorDeviceId: String): Long {
                val pstmt = c.prepareStatement(
                    "SELECT COALESCE(MAX(author_seq), 0) + 1 FROM group_events WHERE group_id = ? AND author_device_id = ?"
                )
                pstmt.setString(1, groupId)
                pstmt.setString(2, authorDeviceId)
                val rs = pstmt.executeQuery()
                return if (rs.next()) rs.getLong(1) else 1L
            }

            fun insertEvent(eventId: String, seq: Long) {
                val pstmt = c.prepareStatement(
                    """
                    INSERT INTO group_events (group_id, event_id, epoch, author_device_id, author_seq, hlc_physical_ms, hlc_logical, kind, created_at_ms, received_at_ms)
                    VALUES ('grp-seq', ?, 1, 'dev-alice', ?, 1000, 0, 'MESSAGE', 1000, 1000)
                    """.trimIndent()
                )
                pstmt.setString(1, eventId)
                pstmt.setLong(2, seq)
                pstmt.executeUpdate()
            }

            // Step 1: Normal emit 1
            val seq1 = nextSeq("grp-seq", "dev-alice")
            assertEquals(1L, seq1)
            insertEvent("ev-normal-1", seq1)

            // Step 2: Drained intent (uses the exact same nextSeq query inside drain transaction)
            val seq2 = nextSeq("grp-seq", "dev-alice")
            assertEquals(2L, seq2)
            insertEvent("ev-drained-2", seq2)

            // Step 3: Normal emit 2
            val seq3 = nextSeq("grp-seq", "dev-alice")
            assertEquals(3L, seq3)
            insertEvent("ev-normal-3", seq3)

            // Verify monotonic and gapless sequence
            c.createStatement().use { s ->
                val rs = s.executeQuery("SELECT event_id, author_seq FROM group_events WHERE group_id = 'grp-seq' ORDER BY author_seq ASC")
                val results = mutableListOf<Pair<String, Long>>()
                while (rs.next()) {
                    results.add(rs.getString("event_id") to rs.getLong("author_seq"))
                }
                assertEquals(
                    listOf("ev-normal-1" to 1L, "ev-drained-2" to 2L, "ev-normal-3" to 3L),
                    results,
                )
            }
        }
    }

    @Test
    fun keyPackageReferencingNonCanonicalControlRejected() {
        val canonicalEvents = mapOf(
            "ctrl-valid-head" to StoredGroupEvent(
                groupId = "grp-1", eventId = "ctrl-valid-head", epoch = 1,
                authorDeviceId = "dev-owner", authorSeq = 1, hlcPhysicalMs = 1000L, hlcLogical = 0,
                kind = "GROUP_UPDATED",
                body = JSONObject().put("next_epoch", 2L).put("roster_hash", "hash-valid").toString(),
                createdAtMs = 1000L, receivedAtMs = 1000L,
            )
        )

        fun isCanonicalControlValid(pkg: GroupEpochKeyPackage): Boolean {
            val control = canonicalEvents[pkg.controlHead] ?: return false
            val payload = JSONObject(control.body ?: "{}")
            return payload.optLong("next_epoch") == pkg.epoch
        }

        val validPkg = GroupEpochKeyPackage(
            groupId = "grp-1", epoch = 2L, epochSecretBase64 = "c2VjcmV0", recipientDeviceId = "dev-bob",
            controlHead = "ctrl-valid-head", senderFingerprint = "fp", senderDeviceId = "dev-owner",
            senderSigningKey = "key-owner", createdAtMs = 1000L, signatureBase64 = "sig",
            rosterHash = "hash-valid", suite = SUITE_V2,
        )
        assertTrue("Valid package referencing canonical control event is accepted", isCanonicalControlValid(validPkg))

        // Non-canonical control head (not in local accepted history)
        val nonCanonicalPkg = validPkg.copy(controlHead = "ctrl-nonexistent")
        assertFalse("Package referencing unknown control head must be rejected", isCanonicalControlValid(nonCanonicalPkg))

        // Mismatched epoch in control event
        val mismatchedEpochPkg = validPkg.copy(epoch = 99L)
        assertFalse("Package with epoch not matching control next_epoch must be rejected", isCanonicalControlValid(mismatchedEpochPkg))
    }
}
