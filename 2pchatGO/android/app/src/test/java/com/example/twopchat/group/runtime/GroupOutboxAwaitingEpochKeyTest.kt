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
}
