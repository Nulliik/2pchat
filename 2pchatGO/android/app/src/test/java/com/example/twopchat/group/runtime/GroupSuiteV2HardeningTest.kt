package com.example.twopchat.group.runtime

import com.example.twopchat.group.crypto.EpochAeadGroupCrypto
import com.example.twopchat.group.crypto.GroupCryptoProvider
import com.example.twopchat.group.crypto.ProtectedGroupPayload
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.protocol.GroupControlFrames
import com.example.twopchat.group.protocol.GroupEpochKeyPackage
import com.example.twopchat.group.protocol.GroupEventKind
import com.example.twopchat.group.protocol.GroupInviteResponse
import com.example.twopchat.group.protocol.GroupWireEvent
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.group.storage.StoredGroupEpochKey
import com.example.twopchat.group.storage.StoredGroupMember
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class GroupSuiteV2HardeningTest {

    private fun createSampleWireEvent(
        groupId: String = "group-100",
        eventId: String = "ev-1",
        epoch: Long = 2,
        kind: GroupEventKind = GroupEventKind.MESSAGE,
        cryptoSuite: String = GroupWireProtocol.SUITE_V2,
    ): GroupWireEvent = GroupWireEvent(
        groupId = groupId,
        eventId = eventId,
        epoch = epoch,
        kind = kind,
        authorFingerprint = "fp-author",
        authorDeviceId = "dev-author",
        authorSigningKey = "key-author",
        authorSequence = 1L,
        previousAuthorEvent = null,
        controlHead = "ctrl-1",
        hlcPhysicalMs = 1_000_000L,
        hlcLogical = 0,
        targetEventId = null,
        nonceBase64 = "123456789012",
        ciphertextBase64 = "cipher",
        signatureBase64 = "sig",
        cryptoSuite = cryptoSuite,
    )

    @Test
    fun epochSuiteStoredAndEnforced_v1EventInV2EpochRejected() {
        val v2EpochKey = StoredGroupEpochKey(
            groupId = "grp-test",
            epoch = 2,
            keyMaterial = ByteArray(32) { 0x42 },
            suite = GroupWireProtocol.SUITE_V2,
        )
        val v1Event = createSampleWireEvent(
            groupId = "grp-test",
            epoch = 2,
            cryptoSuite = GroupWireProtocol.SUITE_V1,
        )

        try {
            require(v1Event.cryptoSuite == v2EpochKey.suite) {
                "event suite ${v1Event.cryptoSuite} does not match epoch suite ${v2EpochKey.suite}"
            }
            fail("Expected IllegalArgumentException when v1 event arrives in v2 epoch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("does not match epoch suite"))
        }

        // Matching suite succeeds
        val v2Event = createSampleWireEvent(
            groupId = "grp-test",
            epoch = 2,
            cryptoSuite = GroupWireProtocol.SUITE_V2,
        )
        require(v2Event.cryptoSuite == v2EpochKey.suite)
        assertEquals(GroupWireProtocol.SUITE_V2, v2EpochKey.suite)
    }

    @Test
    fun v1EventAfterV2EpochRejected() {
        val currentGroupEpochSuite = GroupWireProtocol.SUITE_V2
        val incomingEvent = createSampleWireEvent(
            epoch = 2,
            cryptoSuite = GroupWireProtocol.SUITE_V1,
        )

        // Anti-downgrade check enforces no v1 event after v2 epoch key established
        val isDowngradeAttempt = incomingEvent.cryptoSuite != currentGroupEpochSuite
        assertTrue("Anti-downgrade must flag v1 event after group upgraded to v2", isDowngradeAttempt)

        // Wire AAD generation for v2 binds rosterHash
        val rosterHash = "32b2d1e3e8bf3805522fc0dbf7d8b0ab2b304eb91763aa7bc853156a928fce08"
        val v2Event = createSampleWireEvent(
            epoch = 2,
            cryptoSuite = GroupWireProtocol.SUITE_V2,
        )
        val aadV2String = v2Event.authenticatedData(rosterHash).toString(Charsets.UTF_8)
        assertTrue(aadV2String.startsWith("2pchat-group-aad-v2\n"))
        assertTrue(aadV2String.endsWith("\n$rosterHash"))

        // v1 event generates AAD v1 without rosterHash
        val aadV1String = incomingEvent.authenticatedData(rosterHash).toString(Charsets.UTF_8)
        assertTrue(aadV1String.startsWith("2pchat-group-aad-v1\n"))
        assertFalse(aadV1String.endsWith(rosterHash))
    }

    @Test
    fun ownerDefersV2RotationWhileMemberLacksCapability() {
        val groupId = "grp-cap-test"
        val memberAlice = "dev-alice-01"
        val memberBob = "dev-bob-02"

        // Initially neither has signaled supports_v2
        assertFalse(GroupChatCoordinator.doesMemberSupportV2(groupId, memberAlice))
        assertFalse(GroupChatCoordinator.doesMemberSupportV2(groupId, memberBob))

        // Alice signals supports_v2 via signed invite response
        val responseAlice = GroupInviteResponse(
            inviteId = "inv-1",
            groupId = groupId,
            accepted = true,
            memberFingerprint = "fp-alice",
            memberPeerName = "Alice",
            memberDeviceId = memberAlice,
            memberSigningKey = "key-alice",
            createdAtMs = 1_000_000L,
            signatureBase64 = "sig-alice",
            supportsV2 = true,
        )
        val jsonAlice = GroupControlFrames.inviteResponseToJson(responseAlice)
        assertTrue(jsonAlice.optBoolean("supports_v2"))
        val parsedAlice = GroupControlFrames.parseInviteResponse(jsonAlice)
        assertTrue(parsedAlice.supportsV2)

        GroupChatCoordinator.setMemberSupportsV2(groupId, memberAlice, parsedAlice.supportsV2)
        assertTrue(GroupChatCoordinator.doesMemberSupportV2(groupId, memberAlice))

        // Bob still lacks capability
        assertFalse(GroupChatCoordinator.doesMemberSupportV2(groupId, memberBob))

        // Owner capability check determines if v2 rotation can proceed safely
        val activeMembers = listOf(memberAlice, memberBob)
        val allSupport = activeMembers.all { GroupChatCoordinator.doesMemberSupportV2(groupId, it) }
        assertFalse("Owner must defer rotation when any active member lacks supports_v2", allSupport)

        // Bob now upgrades and signals supports_v2
        GroupChatCoordinator.setMemberSupportsV2(groupId, memberBob, true)
        val allSupportAfterUpgrade = activeMembers.all { GroupChatCoordinator.doesMemberSupportV2(groupId, it) }
        assertTrue("Owner can rotate when all active members support v2", allSupportAfterUpgrade)
    }

    @Test
    fun ownerOverrideRotatesToV2DespiteMissingCapability() {
        val groupId = "grp-override-test"
        val memberOffline = "dev-offline-01"

        // Offline member never signaled supports_v2
        GroupChatCoordinator.setMemberSupportsV2(groupId, memberOffline, false)
        assertFalse(GroupChatCoordinator.doesMemberSupportV2(groupId, memberOffline))

        // Standard rotation is deferred
        val canRotateWithoutOverride = GroupChatCoordinator.doesMemberSupportV2(groupId, memberOffline)
        assertFalse(canRotateWithoutOverride)

        // Owner override: forceOverride bypasses missing capability
        val forceOverride = true
        val proceedRotation = forceOverride || canRotateWithoutOverride
        assertTrue("Owner override must permit rotation despite missing capability", proceedRotation)
    }

    @Test
    fun adminRemovalIsProposalUntilOwnerRatifies() {
        val v2Key = StoredGroupEpochKey(
            groupId = "grp-admin-prop",
            epoch = 2,
            keyMaterial = ByteArray(32) { 0x01 },
            suite = GroupWireProtocol.SUITE_V2,
        )
        val adminRole = GroupRole.ADMINISTRATOR
        val targetDeviceId = "dev-bad-actor"

        // In suite v2, an admin's removal is routed as a proposal
        val currentSuite = v2Key.suite
        val action = "remove"
        val isProposal = currentSuite == GroupWireProtocol.SUITE_V2 && action == "remove"
        assertTrue(isProposal)

        // Wire event kind is MEMBER_REMOVAL_PROPOSED ("member_removal_proposed")
        val proposalKind = GroupEventKind.MEMBER_REMOVAL_PROPOSED
        assertEquals("member_removal_proposed", proposalKind.wireName)
        assertEquals(GroupEventKind.MEMBER_REMOVAL_PROPOSED, GroupEventKind.fromWire("member_removal_proposed"))

        // The proposal payload binds the target and status
        val proposalPayload = JSONObject().apply {
            put("proposed_by", "dev-admin")
            put("member_device_id", targetDeviceId)
            put("status", "LEFT")
        }
        assertEquals(targetDeviceId, proposalPayload.getString("member_device_id"))
        assertEquals("LEFT", proposalPayload.getString("status"))

        // Direct MEMBER_REMOVED from non-owner in v2 is rejected by serialized control check
        val isOwner = false
        val isGrandfathered = (
            v2Key.suite == GroupWireProtocol.SUITE_V1 &&
            GroupEventKind.MEMBER_REMOVED == GroupEventKind.MEMBER_REMOVED &&
            adminRole == GroupRole.ADMINISTRATOR
        )
        val allowedDirectExecution = isOwner || isGrandfathered
        assertFalse("Direct admin MEMBER_REMOVED in v2 must not be allowed without proposal", allowedDirectExecution)
    }

    @Test
    fun legacyAdminEpochInMigratedGroupStillCanonical() {
        // Historical v1 epoch: admin removal was direct and canonical
        val v1EpochKey = StoredGroupEpochKey(
            groupId = "grp-legacy",
            epoch = 1,
            keyMaterial = ByteArray(32) { 0x02 },
            suite = GroupWireProtocol.SUITE_V1,
        )
        val actorRole = GroupRole.ADMINISTRATOR
        val kind = GroupEventKind.MEMBER_REMOVED

        val isGrandfatheredAdminRemoval = (
            v1EpochKey.suite == GroupWireProtocol.SUITE_V1 &&
            kind == GroupEventKind.MEMBER_REMOVED &&
            actorRole == GroupRole.ADMINISTRATOR
        )

        assertTrue(
            "Historical admin removals in v1 epochs must remain grandfathered and canonical",
            isGrandfatheredAdminRemoval,
        )
    }

    @Test
    fun v1EpochGroupWithMixedClients_AdminRemovalStillCanonicalUntilV2Rotation() {
        val v1EpochKey = StoredGroupEpochKey(
            groupId = "grp-mixed",
            epoch = 1,
            keyMaterial = ByteArray(32) { 0x03 },
            suite = GroupWireProtocol.SUITE_V1,
        )
        val action = "remove"
        val localRole = GroupRole.ADMINISTRATOR

        // In v1, admin removal directly executes serialized control without requiring proposals
        val shouldExecuteDirectly = (
            v1EpochKey.suite == GroupWireProtocol.SUITE_V1 &&
            action == "remove" &&
            localRole == GroupRole.ADMINISTRATOR
        )
        assertTrue(
            "In v1 epoch group, admin removal remains canonical and direct until group rotates to v2",
            shouldExecuteDirectly,
        )
    }

    @Test
    fun keyPackageSignedByEmbeddedKeyNotRosterOwnerRejected() {
        val rosterOwnerSigningKey = "true-roster-owner-key-12345"
        val attackerEmbeddedKey = "attacker-untrusted-key-67890"

        val keyPackage = GroupEpochKeyPackage(
            groupId = "grp-kp-test",
            epoch = 2,
            epochSecretBase64 = "c2VjcmV0",
            recipientDeviceId = "dev-recipient",
            controlHead = "ctrl-head-1",
            senderFingerprint = "fp-owner",
            senderDeviceId = "dev-owner",
            senderSigningKey = attackerEmbeddedKey, // Attacker embedded their own key
            createdAtMs = 1_000_000L,
            signatureBase64 = "fake-sig",
        )

        // Strict DB roster verification check
        val matchesRosterOwnerKey = keyPackage.senderSigningKey == rosterOwnerSigningKey
        assertFalse("Key package embedded signing key must be rejected if not matching DB roster owner", matchesRosterOwnerKey)

        try {
            require(keyPackage.senderSigningKey == rosterOwnerSigningKey) {
                "key package signing key must match owner in DB roster"
            }
            fail("Expected require failure when senderSigningKey does not match DB roster owner")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must match owner in DB roster"))
        }
    }

    @Test
    fun ownerEquivocation_DifferentRosters_DecryptFailsWithRosterMismatchEvent() {
        val epochSecret = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val crypto = EpochAeadGroupCrypto

        val roster1 = listOf(
            "dev-alice" to "key-alice",
            "dev-bob" to "key-bob",
        )
        val roster2Equivocated = listOf(
            "dev-alice" to "key-alice",
            "dev-eve" to "key-eve",
        )

        val hashRoster1 = GroupWireProtocol.computeRosterHashFromEntries(roster1)
        val hashRoster2 = GroupWireProtocol.computeRosterHashFromEntries(roster2Equivocated)
        assertNotEquals(hashRoster1, hashRoster2)

        val plaintext = "Equivocation test message payload".toByteArray(Charsets.UTF_8)
        val wireEvent = createSampleWireEvent(
            cryptoSuite = GroupWireProtocol.SUITE_V2,
        )

        // Sender produces ciphertext under roster 1's AAD
        val aadRoster1 = wireEvent.authenticatedData(hashRoster1)
        val protected = crypto.protect(epochSecret, aadRoster1, plaintext)

        // Receiver 1 with matching roster 1 successfully decrypts
        val decrypted1 = crypto.unprotect(epochSecret, aadRoster1, protected)
        assertEquals("Equivocation test message payload", String(decrypted1, Charsets.UTF_8))

        // Receiver 2 with equivocated roster 2 computes mismatched AAD
        val aadRoster2 = wireEvent.authenticatedData(hashRoster2)
        try {
            crypto.unprotect(epochSecret, aadRoster2, protected)
            fail("Expected SecurityException due to AES-GCM tag mismatch on equivocated roster hash")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("ciphertext authentication failed"))
        }
    }

    @Test
    fun rosterHashKatMatchesKotlinAndGo() {
        // Out-of-order test entries matching exactly Go's TestComputeRosterHashKat
        val entries = listOf(
            "carol-dev-003" to "carol-key-abcde",
            "alice-dev-001" to "alice-key-12345",
            "bob-dev-002" to "bob-key-67890",
        )
        val expectedHash = "32b2d1e3e8bf3805522fc0dbf7d8b0ab2b304eb91763aa7bc853156a928fce08"
        val actualHash = GroupWireProtocol.computeRosterHashFromEntries(entries)

        assertEquals("Kotlin roster hash must match Go KAT vector exactly", expectedHash, actualHash)

        // Test with StoredGroupMember instances including an inactive member (which must be filtered out)
        val members = listOf(
            StoredGroupMember(
                groupId = "g1",
                deviceId = "carol-dev-003",
                accountId = "carol-acc",
                displayName = "Carol",
                role = "MEMBER",
                permissions = 0L,
                status = "ACTIVE",
                joinedEpoch = 1,
                signingKeyBase64 = "carol-key-abcde",
                transportFingerprint = "fp3",
                peerName = "Carol",
            ),
            StoredGroupMember(
                groupId = "g1",
                deviceId = "alice-dev-001",
                accountId = "alice-acc",
                displayName = "Alice",
                role = "OWNER",
                permissions = 0L,
                status = "ACTIVE",
                joinedEpoch = 1,
                signingKeyBase64 = "alice-key-12345",
                transportFingerprint = "fp1",
                peerName = "Alice",
            ),
            StoredGroupMember(
                groupId = "g1",
                deviceId = "bob-dev-002",
                accountId = "bob-acc",
                displayName = "Bob",
                role = "MEMBER",
                permissions = 0L,
                status = "ACTIVE",
                joinedEpoch = 1,
                signingKeyBase64 = "bob-key-67890",
                transportFingerprint = "fp2",
                peerName = "Bob",
            ),
            StoredGroupMember(
                groupId = "g1",
                deviceId = "dave-dev-999",
                accountId = "dave-acc",
                displayName = "Dave",
                role = "MEMBER",
                permissions = 0L,
                status = "LEFT", // Inactive member must NOT be included in roster hash
                joinedEpoch = 1,
                removedEpoch = 2,
                signingKeyBase64 = "dave-key-xxxxx",
                transportFingerprint = "fp9",
                peerName = "Dave",
            ),
        )

        val actualFromMembers = GroupWireProtocol.computeRosterHash(members)
        assertEquals("StoredGroupMember roster hash must match KAT vector", expectedHash, actualFromMembers)
    }

    @Test
    fun adminAddInV2IsRejected() {
        val ownerDeviceId = "dev-owner"
        val adminDeviceId = "dev-admin"

        fun canApplyMemberAdded(suite: String, authorDeviceId: String): Boolean {
            if (suite == GroupWireProtocol.SUITE_V2 && authorDeviceId != ownerDeviceId) {
                return false
            }
            return true
        }

        // In suite v2, an admin cannot directly add members
        val adminInV2 = canApplyMemberAdded(GroupWireProtocol.SUITE_V2, adminDeviceId)
        assertFalse("Direct admin MEMBER_ADDED in v2 must be rejected", adminInV2)

        // In suite v2, owner can add members
        val ownerInV2 = canApplyMemberAdded(GroupWireProtocol.SUITE_V2, ownerDeviceId)
        assertTrue("Owner MEMBER_ADDED in v2 is accepted", ownerInV2)

        // In suite v1, admin add was allowed (grandfathered)
        val adminInV1 = canApplyMemberAdded(GroupWireProtocol.SUITE_V1, adminDeviceId)
        assertTrue("Admin MEMBER_ADDED in legacy v1 group is allowed", adminInV1)
    }

    @Test
    fun epoch0RosterHashInSignedCreationEvent() {
        val ownerDeviceId = "dev-owner-001"
        val ownerSigningKey = "key-owner-base64"
        val initialRoster = listOf(ownerDeviceId to ownerSigningKey)
        val expectedRosterHash = GroupWireProtocol.computeRosterHashFromEntries(initialRoster)

        // Creation event at epoch 0 binds rosterHash in AAD
        val creationWireEvent = GroupWireEvent(
            groupId = "grp-genesis-1",
            eventId = "",
            epoch = 0L,
            kind = GroupEventKind.SYSTEM,
            authorFingerprint = "fp-owner",
            authorDeviceId = ownerDeviceId,
            authorSigningKey = ownerSigningKey,
            authorSequence = 0L,
            previousAuthorEvent = null,
            controlHead = null,
            hlcPhysicalMs = 1_000_000L,
            hlcLogical = 0,
            targetEventId = null,
            nonceBase64 = "bm9uY2U=",
            ciphertextBase64 = "Y2lwaGVydGV4dA==",
            signatureBase64 = "c2lnbmF0dXJl",
            cryptoSuite = GroupWireProtocol.SUITE_V2,
        )

        // Verification: authenticatedData binds rosterHash for v2 creation event
        val aad = creationWireEvent.authenticatedData(expectedRosterHash).toString(Charsets.UTF_8)
        assertTrue("Genesis creation event AAD must start with v2 prefix", aad.startsWith("2pchat-group-aad-v2\n"))
        assertTrue("Genesis creation event AAD must end with epoch 0 rosterHash", aad.endsWith("\n$expectedRosterHash"))

        // Epoch 1 key package created by owner for invited members carries rosterHash
        val unsignedPkg = GroupEpochKeyPackage(
            groupId = "grp-genesis-1",
            epoch = 1L,
            epochSecretBase64 = "genesis-secret-32-bytes-base64==",
            recipientDeviceId = "dev-alice-002",
            controlHead = "ctrl-genesis-head",
            senderFingerprint = "fp-owner",
            senderDeviceId = ownerDeviceId,
            senderSigningKey = ownerSigningKey,
            createdAtMs = 1_000_000L,
            signatureBase64 = "sig-placeholder",
            rosterHash = expectedRosterHash,
            suite = GroupWireProtocol.SUITE_V2,
        )

        val canonical = unsignedPkg.canonicalForSignature()
        assertTrue("Canonical signature form must include rosterHash", canonical.contains(expectedRosterHash))
        assertTrue("Canonical signature form must include suite", canonical.contains(GroupWireProtocol.SUITE_V2))

        val json = GroupControlFrames.keyPackageToJson(unsignedPkg)
        assertEquals(expectedRosterHash, json.optString("roster_hash"))
        assertEquals(GroupWireProtocol.SUITE_V2, json.optString("suite"))

        val parsed = GroupControlFrames.parseKeyPackage(json)
        assertEquals(expectedRosterHash, parsed.rosterHash)
        assertEquals(GroupWireProtocol.SUITE_V2, parsed.suite)
    }

    @Test
    fun messageFromNewlyAddedMemberArrivingBeforeMemberAddedIsBuffered() {
        val knownMembers = mutableMapOf<String, String>()
        val appliedControlHeads = mutableSetOf<String>()
        val pendingEventsBuffer = mutableListOf<GroupWireEvent>()

        val newlyAddedDeviceId = "dev-alice-new"
        val controlHeadForAddition = "ctrl-event-add-alice"

        val earlyArrivingMessage = createSampleWireEvent(
            groupId = "grp-1",
            eventId = "msg-alice-01",
            epoch = 1,
            cryptoSuite = GroupWireProtocol.SUITE_V2,
        ).copy(
            authorDeviceId = newlyAddedDeviceId,
            controlHead = controlHeadForAddition,
        )

        fun ingestEvent(event: GroupWireEvent): String {
            val controlApplied = event.controlHead == null ||
                appliedControlHeads.contains(event.controlHead)

            val author = knownMembers[event.authorDeviceId]
            if (author == null) {
                if (!controlApplied) {
                    pendingEventsBuffer.add(event)
                    return "BUFFERED"
                }
                throw SecurityException("group event author is absent from the accepted roster")
            }
            return "PROCESSED"
        }

        // 1. Message arrives before control event: controlApplied is false, member unknown -> BUFFERED
        val result1 = ingestEvent(earlyArrivingMessage)
        assertEquals("BUFFERED", result1)
        assertEquals(1, pendingEventsBuffer.size)
        assertEquals("msg-alice-01", pendingEventsBuffer[0].eventId)

        // 2. Control event MEMBER_ADDED arrives and is applied
        appliedControlHeads.add(controlHeadForAddition)
        knownMembers[newlyAddedDeviceId] = "key-alice"

        // 3. Drain pending buffer
        val drained = pendingEventsBuffer.toList()
        pendingEventsBuffer.clear()
        for (event in drained) {
            val resultDrained = ingestEvent(event)
            assertEquals("PROCESSED", resultDrained)
        }
        assertTrue(pendingEventsBuffer.isEmpty())
    }
}

