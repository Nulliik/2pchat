package com.example.twopchat.group.runtime

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.crypto.EpochAeadGroupCrypto
import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.model.GroupPermission
import com.example.twopchat.group.model.GroupRole
import com.example.twopchat.group.model.GroupRolePolicy
import com.example.twopchat.group.protocol.GroupControlFrames
import com.example.twopchat.group.protocol.GroupEpochKeyPackage
import com.example.twopchat.group.protocol.GroupEventKind
import com.example.twopchat.group.protocol.GroupInvite
import com.example.twopchat.group.protocol.GroupInviteMember
import com.example.twopchat.group.protocol.GroupRosterMemberStatus
import com.example.twopchat.group.protocol.GroupRosterSnapshot
import com.example.twopchat.group.protocol.GroupRosterSnapshotMember
import com.example.twopchat.group.protocol.GroupWireEvent
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.group.storage.GroupDatabaseHelper
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupChatCoordinatorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ownerPeerName = "runtime-owner-${UUID.randomUUID()}"
    private lateinit var ownerKeyPair: KeyPair
    private lateinit var ownerSigningKey: String
    private lateinit var ownerFingerprint: String
    private lateinit var ownerDeviceId: String
    private lateinit var localFingerprint: String
    private lateinit var localSigningKey: String
    private lateinit var localDeviceId: String

    @Before
    fun setUp() {
        GroupChatCoordinator.deleteAll(context)
        NativeBridge.initialize()
        check(NativeBridge.isLoaded) { "Native Go bridge was not initialized" }

        val kpg = KeyPairGenerator.getInstance("Ed25519")
        ownerKeyPair = kpg.generateKeyPair()
        val rawOwnerPublicKey = ownerKeyPair.public.encoded.takeLast(32).toByteArray()

        ownerSigningKey = Base64.encodeToString(rawOwnerPublicKey, Base64.NO_WRAP)
        ownerFingerprint = sha256Hex(rawOwnerPublicKey)
        ownerDeviceId = stableDeviceId(ownerFingerprint)
        localFingerprint = NativeBridge.getLocalIdentity()?.fingerprint ?: ""
        localSigningKey = GroupIdentitySignatures.localVerificationKey()
        localDeviceId = stableDeviceId(localFingerprint)
        P2PPreferences.prefs(context)
            .edit()
            .putString(P2PPreferences.peerFingerprint(ownerPeerName), ownerFingerprint)
            .commit()
        GroupChatCoordinator.initialize(context)
    }

    @After
    fun tearDown() {
        GroupChatCoordinator.deleteAll(context)
        P2PPreferences.prefs(context)
            .edit()
            .remove(P2PPreferences.peerFingerprint(ownerPeerName))
            .commit()
    }

    @Test
    fun soloOwnerCanDeleteGroupFromChatList() {
        val createdGroupId = AtomicReference<String>()
        GroupChatCoordinator.createGroup(
            title = "Solo group",
            description = "",
            contactIds = emptySet(),
            onCreated = createdGroupId::set,
        )
        awaitCondition { createdGroupId.get() != null }
        val groupId = checkNotNull(createdGroupId.get())
        awaitCondition {
            GroupChatCoordinator.summaries.value.any { it.groupId == groupId }
        }

        GroupChatCoordinator.leaveGroup(groupId)

        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getGroup(groupId) == null
            } && GroupChatCoordinator.summaries.value.none { it.groupId == groupId }
        }
    }

    @Test
    fun joiningRefreshAndCanonicalActivationWorkWhileTransportIsOffline() {
        val groupId = "runtime-group-${UUID.randomUUID()}"
        val firstSecret = ByteArray(32) { 0x11 }
        val firstInvite = signedInvite(
            inviteId = "invite-${UUID.randomUUID()}",
            groupId = groupId,
            epoch = 1,
            secret = firstSecret,
        )
        assertTrue(
            "synthetic owner signature must verify",
            firstInvite.verifySignature(),
        )
        val firstInviteJson = GroupWireProtocol.inviteToJson(firstInvite)
        val parsedFirstInvite = GroupWireProtocol.parseInvite(firstInviteJson)
        assertEquals(
            firstInvite.canonicalForSignature(),
            parsedFirstInvite.canonicalForSignature(),
        )
        assertTrue("serialized owner signature must verify", parsedFirstInvite.verifySignature())
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                firstInviteJson,
            ),
        )
        awaitCondition { GroupChatCoordinator.pendingInvites.value.invites.size == 1 }

        GroupChatCoordinator.acceptInvite(firstInvite.inviteId)
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "JOINING"
            }
        }
        val chatState = GroupChatCoordinator.chatState(groupId)
        awaitCondition {
            !chatState.value.composerEnabled &&
                chatState.value.readOnlyReason.isWaitingForOwner()
        }
        val joiningState = chatState.value
        assertFalse(joiningState.composerEnabled)
        assertTrue(
            chatState.value.readOnlyReason.isWaitingForOwner(),
        )

        GroupChatCoordinator.sendMessage(groupId, "must not be emitted while joining")
        GroupDatabaseHelper(context).use { database ->
            assertTrue(database.listEvents(groupId).isEmpty())
            assertEquals(
                1,
                database.listOutboxForEvent(
                    groupId,
                    "invite-response:${firstInvite.inviteId}",
                ).size,
            )
        }

        val secondSecret = ByteArray(32) { 0x22 }
        val refreshedInvite = signedInvite(
            inviteId = "invite-${UUID.randomUUID()}",
            groupId = groupId,
            epoch = 2,
            secret = secondSecret,
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.inviteToJson(refreshedInvite),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getGroup(groupId)?.currentEpoch == 2L &&
                    database.listOutboxForEvent(
                        groupId,
                        "invite-response:${refreshedInvite.inviteId}",
                    ).size == 1
            }
        }
        GroupDatabaseHelper(context).use { database ->
            assertArrayEquals(
                secondSecret,
                database.getEpochKey(groupId, 2L)?.keyMaterial,
            )
        }

        val acceptance = signedOwnerAcceptance(groupId, secondSecret)
        val activeSecret = ByteArray(32) { 0x33 }
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.eventToJson(acceptance),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "JOINING" &&
                    database.getGroup(groupId)?.currentEpoch == 3L &&
                    database.getGroup(groupId)?.controlHead == acceptance.eventId
            }
        }
        awaitCondition {
            !chatState.value.composerEnabled &&
                chatState.value.readOnlyReason.isWaitingForOwner()
        }
        val keyPackage = signedOwnerKeyPackage(
            groupId = groupId,
            controlHead = acceptance.eventId,
            epoch = 3L,
            secret = activeSecret,
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupControlFrames.keyPackageToJson(keyPackage),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getEpochKey(groupId, 3L) != null &&
                    database.getMember(groupId, localDeviceId)?.status == "JOINING" &&
                    !chatState.value.composerEnabled
            }
        }
        GroupChatCoordinator.sendMessage(groupId, "must not be emitted before roster snapshot")
        GroupDatabaseHelper(context).use { database ->
            assertFalse(
                database.loadTimeline(groupId, 10)
                    .any { it.body == "must not be emitted before roster snapshot" },
            )
        }
        val activationSnapshot = signedOwnerRosterSnapshot(
            groupId = groupId,
            controlHead = acceptance.eventId,
            epoch = 3L,
            ownerAuthorSequence = acceptance.authorSequence,
        )
        assertTrue(activationSnapshot.verify())
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupControlFrames.rosterSnapshotToJson(activationSnapshot),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "ACTIVE"
            }
        }
        awaitCondition { chatState.value.composerEnabled }
        assertTrue(chatState.value.composerEnabled)

        GroupChatCoordinator.sendMessage(groupId, "now active")
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.loadTimeline(groupId, 10).any { it.body == "now active" }
            }
        }
        GroupDatabaseHelper(context).use { database ->
            assertFalse(
                database.loadTimeline(groupId, 10)
                    .any { it.body == "must not be emitted while joining" },
            )
            assertFalse(
                database.loadTimeline(groupId, 10)
                    .any { it.body == "must not be emitted before roster snapshot" },
            )
        }

        P2PPreferences.prefs(context)
            .edit()
            .putBoolean("hidden_group_$groupId", true)
            .commit()
        val removal = signedOwnerRemoval(
            groupId = groupId,
            epochSecret = activeSecret,
            controlHead = acceptance.eventId,
            previousOwnerEvent = acceptance.eventId,
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.eventToJson(removal),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "LEFT" &&
                    database.getGroup(groupId)?.currentEpoch == 4L &&
                    database.getGroup(groupId)?.controlHead == removal.eventId
            }
        }
        assertTrue(P2PPreferences.prefs(context).getBoolean("hidden_group_$groupId", false))

        val rejoinSecret = ByteArray(32) { 0x44 }
        val rejoinInvite = signedInvite(
            inviteId = "invite-${UUID.randomUUID()}",
            groupId = groupId,
            epoch = 4L,
            secret = rejoinSecret,
            controlHead = removal.eventId,
            historyCursors = linkedMapOf(ownerDeviceId to 2L, localDeviceId to 1L),
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.inviteToJson(rejoinInvite),
            ),
        )
        awaitCondition {
            GroupChatCoordinator.pendingInvites.value.invites.any {
                it.inviteId == rejoinInvite.inviteId
            }
        }
        GroupChatCoordinator.acceptInvite(rejoinInvite.inviteId)
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "JOINING" &&
                    database.getGroup(groupId)?.currentEpoch == 4L
            }
        }
        assertTrue(P2PPreferences.prefs(context).getBoolean("hidden_group_$groupId", false))

        val rejoinAcceptance = signedOwnerAcceptance(
            groupId = groupId,
            epochSecret = rejoinSecret,
            epoch = 4L,
            nextEpoch = 5L,
            authorSequence = 3L,
            previousOwnerEvent = removal.eventId,
            controlHead = removal.eventId,
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.eventToJson(rejoinAcceptance),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "JOINING" &&
                    database.getGroup(groupId)?.currentEpoch == 5L &&
                    database.getGroup(groupId)?.controlHead == rejoinAcceptance.eventId
            }
        }
        assertFalse(chatState.value.composerEnabled)
        assertTrue(P2PPreferences.prefs(context).getBoolean("hidden_group_$groupId", false))
        val rejoinActiveSecret = ByteArray(32) { 0x55 }
        val rejoinKeyPackage = signedOwnerKeyPackage(
            groupId = groupId,
            controlHead = rejoinAcceptance.eventId,
            epoch = 5L,
            secret = rejoinActiveSecret,
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupControlFrames.keyPackageToJson(rejoinKeyPackage),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getEpochKey(groupId, 5L) != null &&
                    database.getMember(groupId, localDeviceId)?.status == "JOINING" &&
                    !chatState.value.composerEnabled
            }
        }
        GroupChatCoordinator.sendMessage(groupId, "must not be emitted before rejoin snapshot")
        GroupDatabaseHelper(context).use { database ->
            assertFalse(
                database.loadTimeline(groupId, 10)
                    .any { it.body == "must not be emitted before rejoin snapshot" },
            )
        }
        val rejoinSnapshot = signedOwnerRosterSnapshot(
            groupId = groupId,
            controlHead = rejoinAcceptance.eventId,
            epoch = 5L,
            ownerAuthorSequence = rejoinAcceptance.authorSequence,
            localAuthorSequence = 1L,
        )
        assertTrue(rejoinSnapshot.verify())
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupControlFrames.rosterSnapshotToJson(rejoinSnapshot),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "ACTIVE"
            }
        }
        awaitCondition { chatState.value.composerEnabled }
    }

    @Test
    fun restrictedMemberCanPostPlainTextButLinksRequireExplicitPermission() {
        val groupId = "runtime-restrictions-${UUID.randomUUID()}"
        val inviteSecret = ByteArray(32) { 0x61 }
        val invite = signedInvite(
            inviteId = "invite-${UUID.randomUUID()}",
            groupId = groupId,
            epoch = 2L,
            secret = inviteSecret,
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.inviteToJson(invite),
            ),
        )
        awaitCondition {
            GroupChatCoordinator.pendingInvites.value.invites.any {
                it.inviteId == invite.inviteId
            }
        }
        GroupChatCoordinator.acceptInvite(invite.inviteId)
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "JOINING"
            }
        }

        val acceptance = signedOwnerAcceptance(groupId, inviteSecret)
        val activeSecret = ByteArray(32) { 0x62 }
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.eventToJson(acceptance),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getGroup(groupId)?.controlHead == acceptance.eventId
            }
        }
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupControlFrames.keyPackageToJson(
                    signedOwnerKeyPackage(
                        groupId,
                        acceptance.eventId,
                        3L,
                        activeSecret,
                    ),
                ),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getEpochKey(groupId, 3L) != null
            }
        }
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupControlFrames.rosterSnapshotToJson(
                    signedOwnerRosterSnapshot(
                        groupId,
                        acceptance.eventId,
                        3L,
                        acceptance.authorSequence,
                    ),
                ),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.status == "ACTIVE"
            }
        }

        val memberDefaults = GroupRolePolicy.defaultPermissions(GroupRole.MEMBER)
        val withoutLinks = memberDefaults - GroupPermission.POST_LINKS
        val restricted = signedOwnerRestriction(
            groupId = groupId,
            epochSecret = activeSecret,
            permissions = withoutLinks.bits,
            authorSequence = 2L,
            previousOwnerEvent = acceptance.eventId,
            controlHead = acceptance.eventId,
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.eventToJson(restricted),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.let { member ->
                    member.role == GroupRole.MEMBER.name &&
                        member.status == "RESTRICTED" &&
                        member.permissions == withoutLinks.bits
                } == true
            }
        }
        awaitCondition { GroupChatCoordinator.chatState(groupId).value.textComposerEnabled }

        GroupChatCoordinator.sendMessage(groupId, "plain text remains allowed")
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.loadTimeline(groupId, 20)
                    .any { it.body == "plain text remains allowed" }
            }
        }
        val plainMessageId = GroupDatabaseHelper(context).use { database ->
            database.loadTimeline(groupId, 20)
                .single { it.body == "plain text remains allowed" }
                .messageId
        }
        GroupChatCoordinator.sendMessage(groupId, "blocked https://example.com")
        GroupChatCoordinator.sendMessage(
            groupId,
            "blocked reply example.org",
            replyToMessageId = plainMessageId,
        )
        Thread.sleep(300)
        GroupDatabaseHelper(context).use { database ->
            val bodies = database.loadTimeline(groupId, 20).map { it.body }
            assertFalse("a restricted link message was stored", "blocked https://example.com" in bodies)
            assertFalse("a restricted link reply was stored", "blocked reply example.org" in bodies)
        }

        val restored = signedOwnerRestriction(
            groupId = groupId,
            epochSecret = activeSecret,
            permissions = memberDefaults.bits,
            authorSequence = 3L,
            previousOwnerEvent = restricted.eventId,
            controlHead = restricted.eventId,
        )
        assertTrue(
            GroupChatCoordinator.handleIncoming(
                context,
                ownerPeerName,
                GroupWireProtocol.eventToJson(restored),
            ),
        )
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.getMember(groupId, localDeviceId)?.let { member ->
                    member.role == GroupRole.MEMBER.name &&
                        member.status == "ACTIVE" &&
                        member.permissions == memberDefaults.bits
                } == true
            }
        }

        GroupChatCoordinator.sendMessage(groupId, "restored https://example.com")
        awaitCondition {
            GroupDatabaseHelper(context).use { database ->
                database.loadTimeline(groupId, 20)
                    .any { it.body == "restored https://example.com" }
            }
        }

        val forgedRestriction = signedOwnerRestriction(
            groupId = groupId,
            epochSecret = activeSecret,
            permissions = withoutLinks.bits,
            authorSequence = 4L,
            previousOwnerEvent = restored.eventId,
            controlHead = restored.eventId,
        )
        val forgedJson = GroupWireProtocol.eventToJson(forgedRestriction).apply {
            put("signature", Base64.encodeToString(ByteArray(64) { 0x7f }, Base64.NO_WRAP))
        }
        assertTrue(GroupChatCoordinator.handleIncoming(context, ownerPeerName, forgedJson))
        Thread.sleep(300)
        GroupDatabaseHelper(context).use { database ->
            assertEquals(null, database.getEvent(groupId, forgedRestriction.eventId))
            assertEquals(
                memberDefaults.bits,
                database.getMember(groupId, localDeviceId)?.permissions,
            )
        }
    }

    private fun signedInvite(
        inviteId: String,
        groupId: String,
        epoch: Long,
        secret: ByteArray,
        controlHead: String? = null,
        historyCursors: Map<String, Long> =
            linkedMapOf(ownerDeviceId to 0L, localDeviceId to 0L),
    ): GroupInvite {
        val now = System.currentTimeMillis()
        val unsigned = GroupInvite(
            inviteId = inviteId,
            groupId = groupId,
            title = "Runtime group",
            description = "Coordinator integration",
            epoch = epoch,
            epochSecretBase64 = Base64.encodeToString(secret, Base64.NO_WRAP),
            ownerFingerprint = ownerFingerprint,
            senderFingerprint = ownerFingerprint,
            senderSigningKey = ownerSigningKey,
            coordinatorFingerprint = ownerFingerprint,
            controlHead = controlHead,
            historyCursors = historyCursors,
            createdAtMs = now,
            rosterSize = 2,
            members = listOf(
                GroupInviteMember(
                    fingerprint = ownerFingerprint,
                    peerName = ownerPeerName,
                    deviceId = ownerDeviceId,
                    signingKey = ownerSigningKey,
                    role = GroupRole.OWNER.name,
                    status = "ACTIVE",
                ),
                GroupInviteMember(
                    fingerprint = localFingerprint,
                    peerName = "Local",
                    deviceId = localDeviceId,
                    signingKey = localSigningKey,
                    role = GroupRole.MEMBER.name,
                    status = "INVITED",
                ),
            ),
            cryptoSuite = EpochAeadGroupCrypto.suiteId,
            signatureBase64 = "",
        )
        return unsigned.copy(
            signatureBase64 = signAsOwner(unsigned.canonicalForSignature()),
        )
    }

    private fun signedOwnerAcceptance(
        groupId: String,
        epochSecret: ByteArray,
        epoch: Long = 2L,
        nextEpoch: Long = 3L,
        authorSequence: Long = 1L,
        previousOwnerEvent: String? = null,
        controlHead: String? = null,
    ): GroupWireEvent {
        val payload = JSONObject().apply {
            put("member_device_id", localDeviceId)
            put("fingerprint", localFingerprint)
            put("peer_name", "Local")
            put("signing_key", localSigningKey)
            put("role", GroupRole.MEMBER.name)
            put("permissions", GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits)
            put("status", "ACTIVE")
            put("joined_epoch", nextEpoch)
            put("next_epoch", nextEpoch)
        }
        val template = GroupWireEvent(
            groupId = groupId,
            eventId = "",
            epoch = epoch,
            kind = GroupEventKind.MEMBER_ADDED,
            authorFingerprint = ownerFingerprint,
            authorDeviceId = ownerDeviceId,
            authorSigningKey = ownerSigningKey,
            authorSequence = authorSequence,
            previousAuthorEvent = previousOwnerEvent,
            controlHead = controlHead,
            hlcPhysicalMs = System.currentTimeMillis(),
            hlcLogical = 0,
            targetEventId = localDeviceId,
            nonceBase64 = "",
            ciphertextBase64 = "",
            signatureBase64 = "",
            cryptoSuite = EpochAeadGroupCrypto.suiteId,
        )
        val protected = EpochAeadGroupCrypto.protect(
            epochSecret,
            template.authenticatedData(),
            payload.toString().toByteArray(Charsets.UTF_8),
        )
        val unsigned = template.copy(
            nonceBase64 = protected.nonceBase64,
            ciphertextBase64 = protected.ciphertextBase64,
        )
        val withId = unsigned.copy(eventId = unsigned.computedEventId())
        return withId.copy(signatureBase64 = signAsOwner(withId.canonicalForSignature()))
    }

    private fun signedOwnerRemoval(
        groupId: String,
        epochSecret: ByteArray,
        controlHead: String,
        previousOwnerEvent: String,
    ): GroupWireEvent {
        val payload = JSONObject().apply {
            put("member_device_id", localDeviceId)
            put("status", "LEFT")
            put("next_epoch", 4L)
        }
        val template = GroupWireEvent(
            groupId = groupId,
            eventId = "",
            epoch = 3L,
            kind = GroupEventKind.MEMBER_REMOVED,
            authorFingerprint = ownerFingerprint,
            authorDeviceId = ownerDeviceId,
            authorSigningKey = ownerSigningKey,
            authorSequence = 2L,
            previousAuthorEvent = previousOwnerEvent,
            controlHead = controlHead,
            hlcPhysicalMs = System.currentTimeMillis(),
            hlcLogical = 0,
            targetEventId = localDeviceId,
            nonceBase64 = "",
            ciphertextBase64 = "",
            signatureBase64 = "",
            cryptoSuite = EpochAeadGroupCrypto.suiteId,
        )
        val protected = EpochAeadGroupCrypto.protect(
            epochSecret,
            template.authenticatedData(),
            payload.toString().toByteArray(Charsets.UTF_8),
        )
        val unsigned = template.copy(
            nonceBase64 = protected.nonceBase64,
            ciphertextBase64 = protected.ciphertextBase64,
        )
        val withId = unsigned.copy(eventId = unsigned.computedEventId())
        return withId.copy(signatureBase64 = signAsOwner(withId.canonicalForSignature()))
    }

    private fun signedOwnerRestriction(
        groupId: String,
        epochSecret: ByteArray,
        permissions: Long,
        authorSequence: Long,
        previousOwnerEvent: String,
        controlHead: String,
    ): GroupWireEvent {
        val payload = JSONObject().apply {
            put("member_device_id", localDeviceId)
            put("permissions", permissions)
        }
        val template = GroupWireEvent(
            groupId = groupId,
            eventId = "",
            epoch = 3L,
            kind = GroupEventKind.MEMBER_RESTRICTED,
            authorFingerprint = ownerFingerprint,
            authorDeviceId = ownerDeviceId,
            authorSigningKey = ownerSigningKey,
            authorSequence = authorSequence,
            previousAuthorEvent = previousOwnerEvent,
            controlHead = controlHead,
            hlcPhysicalMs = System.currentTimeMillis(),
            hlcLogical = 0,
            targetEventId = localDeviceId,
            nonceBase64 = "",
            ciphertextBase64 = "",
            signatureBase64 = "",
            cryptoSuite = EpochAeadGroupCrypto.suiteId,
        )
        val protected = EpochAeadGroupCrypto.protect(
            epochSecret,
            template.authenticatedData(),
            payload.toString().toByteArray(Charsets.UTF_8),
        )
        val unsigned = template.copy(
            nonceBase64 = protected.nonceBase64,
            ciphertextBase64 = protected.ciphertextBase64,
        )
        val withId = unsigned.copy(eventId = unsigned.computedEventId())
        return withId.copy(signatureBase64 = signAsOwner(withId.canonicalForSignature()))
    }

    private fun signedOwnerKeyPackage(
        groupId: String,
        controlHead: String,
        epoch: Long,
        secret: ByteArray,
    ): GroupEpochKeyPackage {
        val unsigned = GroupEpochKeyPackage(
            groupId = groupId,
            epoch = epoch,
            epochSecretBase64 = Base64.encodeToString(secret, Base64.NO_WRAP),
            recipientDeviceId = localDeviceId,
            controlHead = controlHead,
            senderFingerprint = ownerFingerprint,
            senderDeviceId = ownerDeviceId,
            senderSigningKey = ownerSigningKey,
            createdAtMs = System.currentTimeMillis(),
            signatureBase64 = "",
        )
        return unsigned.copy(
            signatureBase64 = signAsOwner(unsigned.canonicalForSignature()),
        )
    }

    private fun signedOwnerRosterSnapshot(
        groupId: String,
        controlHead: String,
        epoch: Long,
        ownerAuthorSequence: Long,
        localAuthorSequence: Long = 0L,
    ): GroupRosterSnapshot {
        val now = System.currentTimeMillis()
        val unsigned = GroupRosterSnapshot(
            recipientDeviceId = localDeviceId,
            groupId = groupId,
            controlHead = controlHead,
            epoch = epoch,
            pageIndex = 0,
            totalPages = 1,
            ownerFingerprint = ownerFingerprint,
            ownerDeviceId = ownerDeviceId,
            ownerSigningKey = ownerSigningKey,
            createdAtMs = now,
            members = listOf(
                GroupRosterSnapshotMember(
                    deviceId = ownerDeviceId,
                    fingerprint = ownerFingerprint,
                    displayName = ownerPeerName,
                    peerName = ownerPeerName,
                    signingKey = ownerSigningKey,
                    role = GroupRole.OWNER,
                    permissions = GroupRolePolicy.defaultPermissions(GroupRole.OWNER).bits,
                    status = GroupRosterMemberStatus.ACTIVE,
                    joinedEpoch = 1L,
                    removedEpoch = null,
                    lastAuthorSequence = ownerAuthorSequence,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
                GroupRosterSnapshotMember(
                    deviceId = localDeviceId,
                    fingerprint = localFingerprint,
                    displayName = "Local",
                    peerName = "Local",
                    signingKey = localSigningKey,
                    role = GroupRole.MEMBER,
                    permissions = GroupRolePolicy.defaultPermissions(GroupRole.MEMBER).bits,
                    status = GroupRosterMemberStatus.ACTIVE,
                    joinedEpoch = epoch,
                    removedEpoch = null,
                    lastAuthorSequence = localAuthorSequence,
                    createdAtMs = now,
                    updatedAtMs = now,
                ),
            ),
            signatureBase64 = "",
        )
        return unsigned.copy(
            signatureBase64 = signAsOwner(unsigned.canonicalForSignature()),
        )
    }

    private fun signAsOwner(canonical: String): String {
        val payload =
            "2pchat-group-signature-api-v1\u0000".toByteArray(Charsets.UTF_8) +
                canonical.toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(ownerKeyPair.private)
        signer.update(payload)
        val signature = signer.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun awaitCondition(
        timeoutMs: Long = 10_000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return
            Thread.sleep(50)
        }
        assertTrue("condition was not met within ${timeoutMs}ms", predicate())
    }

    private fun String.isWaitingForOwner(): Boolean =
        contains("owner", ignoreCase = true) || contains("владел", ignoreCase = true)

    private fun stableDeviceId(fingerprint: String): String =
        sha256Hex("2pchat-group-device-v1\u0000$fingerprint".toByteArray())

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
