package com.example.twopchat.group.protocol

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.twopchat.NativeBridge
import com.example.twopchat.group.crypto.EpochAeadGroupCrypto
import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.model.GroupPermission
import com.example.twopchat.group.model.GroupRole
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupWireProtocolInstrumentedTest {
    @Test
    fun signedEventCanonicalRoundTripDecryptsAndVerifies() {
        val secret = EpochAeadGroupCrypto.generateEpochSecret()
        val factory = GroupEventFactory(EpochAeadGroupCrypto)
        val event = factory.create(
            groupId = "group-1",
            epoch = 7L,
            epochSecret = secret,
            kind = GroupEventKind.REPLY,
            authorFingerprint = "fingerprint-local",
            authorDeviceId = "device-local",
            authorSequence = 42L,
            previousAuthorEvent = "previous-event",
            controlHead = "control-head-7",
            hlcPhysicalMs = 1_784_000_000_000L,
            hlcLogical = 3,
            plaintextPayload = JSONObject()
                .put("text", "hello group")
                .put("reply_to", "message-1"),
            targetEventId = "message-1",
        )

        val json = GroupWireProtocol.eventToJson(event)
        GroupWireProtocol.requireBoundedWire(json.toString())
        val parsed = GroupWireProtocol.parseEvent(JSONObject(json.toString()))
        val plaintext = factory.decrypt(parsed, secret)

        assertEquals(event, parsed)
        assertEquals(event.canonicalForSignature(), parsed.canonicalForSignature())
        assertEquals(event.computedEventId(), event.eventId)
        assertTrue(parsed.verifySignature(parsed.authorSigningKey))
        assertEquals("hello group", plaintext.getString("text"))
        assertEquals("message-1", plaintext.getString("reply_to"))
    }

    @Test
    fun eventTamperBreaksIdSignatureAndAead() {
        val secret = EpochAeadGroupCrypto.generateEpochSecret()
        val factory = GroupEventFactory(EpochAeadGroupCrypto)
        val event = newEvent(factory, secret)

        val mismatchedIdJson = GroupWireProtocol.eventToJson(event)
            .put("author_sequence", event.authorSequence + 1L)
        assertThrows(IllegalArgumentException::class.java) {
            GroupWireProtocol.parseEvent(mismatchedIdJson)
        }

        val tamperedUnsigned = event.copy(
            epoch = event.epoch + 1L,
            eventId = "",
        )
        val tampered = tamperedUnsigned.copy(eventId = tamperedUnsigned.computedEventId())
        assertFalse(tampered.verifySignature(event.authorSigningKey))
        assertThrows(SecurityException::class.java) {
            factory.decrypt(tampered, secret)
        }

        val badSignature = event.copy(signatureBase64 = mutateBase64(event.signatureBase64))
        assertFalse(badSignature.verifySignature(event.authorSigningKey))
        assertFalse(badSignature.verifySignature("unexpected-signing-key"))
    }

    @Test
    fun inviteCanonicalRoundTripAndSignatureTamper() {
        val signingKey = GroupIdentitySignatures.localVerificationKey()
        val members = listOf(
            GroupInviteMember(
                fingerprint = "fingerprint-z",
                peerName = "Zoë",
                deviceId = "device-z",
                signingKey = signingKey,
                role = "MEMBER",
                status = "ACTIVE",
            ),
            GroupInviteMember(
                fingerprint = "fingerprint-a",
                peerName = "Alice",
                deviceId = "device-a",
                signingKey = signingKey,
                role = "OWNER",
                status = "ACTIVE",
            ),
        )
        val unsigned = GroupInvite(
            inviteId = "invite-1",
            groupId = "group-1",
            title = "Canonical group",
            description = "description",
            epoch = 1L,
            epochSecretBase64 = Base64.encodeToString(ByteArray(32) { 7 }, Base64.NO_WRAP),
            ownerFingerprint = "fingerprint-a",
            senderFingerprint = "fingerprint-a",
            senderSigningKey = signingKey,
            coordinatorFingerprint = "fingerprint-a",
            controlHead = "control-head-1",
            historyCursors = linkedMapOf(
                "device-z" to 9L,
                "device-a" to 4L,
            ),
            createdAtMs = 1_784_000_000_000L,
            rosterSize = members.size,
            members = members,
            cryptoSuite = EpochAeadGroupCrypto.suiteId,
            signatureBase64 = "",
            adminOnlyPosting = true,
            groupAvatarDataB64 = Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP),
            groupAvatarSigned = true,
            groupWallpaperDataB64 = Base64.encodeToString(byteArrayOf(4, 5, 6), Base64.NO_WRAP),
            groupWallpaperSigned = true,
        )
        val signed = unsigned.copy(
            signatureBase64 = GroupIdentitySignatures.sign(unsigned.canonicalForSignature()),
        )

        val parsed = GroupWireProtocol.parseInvite(
            JSONObject(GroupWireProtocol.inviteToJson(signed).toString()),
        )

        assertEquals(signed, parsed)
        assertTrue(parsed.verifySignature())
        assertEquals(
            signed.canonicalForSignature(),
            signed.copy(members = signed.members.reversed()).canonicalForSignature(),
        )
        assertEquals(
            signed.canonicalForSignature(),
            signed.copy(historyCursors = signed.historyCursors.toList().reversed().toMap())
                .canonicalForSignature(),
        )
        assertFalse(parsed.copy(title = "tampered title").verifySignature())
        assertFalse(parsed.copy(groupAvatarDataB64 = "dGFtcGVyZWQ=").verifySignature())
        assertFalse(parsed.copy(groupWallpaperDataB64 = "dGFtcGVyZWQ=").verifySignature())
        val oversizedAvatar = JSONObject(GroupWireProtocol.inviteToJson(signed).toString()).apply {
            put("group_avatar_data", "A".repeat(GroupWireProtocol.MAX_GROUP_AVATAR_BASE64_CHARS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupWireProtocol.parseInvite(oversizedAvatar)
        }
        val oversizedWallpaper = JSONObject(GroupWireProtocol.inviteToJson(signed).toString()).apply {
            put("group_wallpaper_data", "A".repeat(GroupWireProtocol.MAX_GROUP_WALLPAPER_BASE64_CHARS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupWireProtocol.parseInvite(oversizedWallpaper)
        }
    }

    @Test
    fun signedControlFramesRoundTripAndRejectSignatureTamper() {
        val signingKey = GroupIdentitySignatures.localVerificationKey()
        val unsignedResponse = GroupInviteResponse(
            inviteId = "invite-1",
            groupId = "group-1",
            accepted = true,
            memberFingerprint = "fingerprint-local",
            memberPeerName = "Local",
            memberDeviceId = "device-local",
            memberSigningKey = signingKey,
            createdAtMs = 1_784_000_000_100L,
            signatureBase64 = "",
        )
        val response = unsignedResponse.copy(
            signatureBase64 = GroupIdentitySignatures.sign(
                unsignedResponse.canonicalForSignature(),
            ),
        )
        val parsedResponse = GroupControlFrames.parseInviteResponse(
            JSONObject(GroupControlFrames.inviteResponseToJson(response).toString()),
        )
        assertEquals(response, parsedResponse)
        assertTrue(parsedResponse.verify())
        assertFalse(parsedResponse.copy(accepted = false).verify())

        val unsignedKeyPackage = GroupEpochKeyPackage(
            groupId = "group-1",
            epoch = 2L,
            epochSecretBase64 = Base64.encodeToString(ByteArray(32) { 11 }, Base64.NO_WRAP),
            recipientDeviceId = "device-remote",
            controlHead = "control-head-2",
            senderFingerprint = "fingerprint-local",
            senderDeviceId = "device-local",
            senderSigningKey = signingKey,
            createdAtMs = 1_784_000_000_200L,
            signatureBase64 = "",
        )
        val keyPackage = unsignedKeyPackage.copy(
            signatureBase64 = GroupIdentitySignatures.sign(
                unsignedKeyPackage.canonicalForSignature(),
            ),
        )
        val parsedKeyPackage = GroupControlFrames.parseKeyPackage(
            JSONObject(GroupControlFrames.keyPackageToJson(keyPackage).toString()),
        )
        assertEquals(keyPackage, parsedKeyPackage)
        assertTrue(parsedKeyPackage.verify())
        assertFalse(parsedKeyPackage.copy(epoch = 3L).verify())
    }

    @Test
    fun unsignedControlFramesRoundTripCanonically() {
        val ack = GroupStoreAck(
            groupId = "group-1",
            eventId = "event-1",
            recipientDeviceId = "device-2",
            receivedAtMs = 1_784_000_000_300L,
        )
        assertEquals(
            ack,
            GroupControlFrames.parseStoreAck(
                JSONObject(GroupControlFrames.storeAckToJson(ack).toString()),
            ),
        )

        val request = GroupSyncRequest(
            requestId = "request-1",
            groupId = "group-1",
            requesterDeviceId = "device-2",
            cursors = linkedMapOf("device-z" to 9L, "device-a" to 4L),
        )
        val requestJson = GroupControlFrames.syncRequestToJson(request)
        assertEquals(listOf("device-a", "device-z"), requestJson.getJSONObject("cursors").keys().asSequence().toList())
        assertEquals(
            request,
            GroupControlFrames.parseSyncRequest(JSONObject(requestJson.toString())),
        )

        val eventJson = GroupWireProtocol.eventToJson(
            newEvent(
                GroupEventFactory(EpochAeadGroupCrypto),
                EpochAeadGroupCrypto.generateEpochSecret(),
            ),
        )
        val batch = GroupSyncBatch(
            requestId = "request-1",
            groupId = "group-1",
            events = listOf(eventJson),
            hasMore = true,
        )
        val parsedBatch = GroupControlFrames.parseSyncBatch(
            JSONObject(GroupControlFrames.syncBatchToJson(batch).toString()),
        )
        assertEquals(batch.requestId, parsedBatch.requestId)
        assertEquals(batch.groupId, parsedBatch.groupId)
        assertEquals(batch.hasMore, parsedBatch.hasMore)
        assertEquals(eventJson.toString(), parsedBatch.events.single().toString())
    }

    @Test
    fun malformedAndOversizedFramesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GroupWireProtocol.parseInvite(
                JSONObject()
                    .put("type", GroupWireProtocol.TYPE_INVITE)
                    .put("version", GroupWireProtocol.VERSION)
                    .put("members", JSONArray()),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupWireProtocol.parseEvent(
                JSONObject()
                    .put("type", GroupWireProtocol.TYPE_EVENT)
                    .put("version", GroupWireProtocol.VERSION),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupWireProtocol.requireBoundedWire(
                "x".repeat(GroupWireProtocol.MAX_WIRE_BYTES + 1),
            )
        }

        val event = newEvent(
            GroupEventFactory(EpochAeadGroupCrypto),
            EpochAeadGroupCrypto.generateEpochSecret(),
        )
        val oversizedCiphertext = GroupWireProtocol.eventToJson(event)
            .put(
                "ciphertext",
                "A".repeat(GroupWireProtocol.MAX_EVENT_CIPHERTEXT_CHARS + 1),
            )
        assertThrows(IllegalArgumentException::class.java) {
            GroupWireProtocol.parseEvent(oversizedCiphertext)
        }

        val oversizedBatch = JSONObject()
            .put("type", GroupWireProtocol.TYPE_SYNC_BATCH)
            .put("version", GroupWireProtocol.VERSION)
            .put("request_id", "request-1")
            .put("group_id", "group-1")
            .put("events", JSONArray().apply {
                repeat(GroupWireProtocol.MAX_SYNC_EVENTS + 1) {
                    put(JSONObject().put("event", it))
                }
            })
        assertThrows(IllegalArgumentException::class.java) {
            GroupControlFrames.parseSyncBatch(oversizedBatch)
        }
    }

    @Test
    fun signedRosterSnapshotRoundTripsWithDeterministicCanonicalBytes() {
        val unsigned = rosterSnapshot(
            members = listOf(
                rosterMember("member-z", GroupRole.MODERATOR),
                rosterMember("owner", GroupRole.OWNER),
                rosterMember("member-a", GroupRole.MEMBER),
            ).sortedBy(GroupRosterSnapshotMember::deviceId),
        )
        val signed = unsigned.copy(
            signatureBase64 = GroupIdentitySignatures.sign(unsigned.canonicalForSignature()),
        )

        val wire = GroupControlFrames.rosterSnapshotToJson(signed)
        GroupWireProtocol.requireBoundedWire(wire.toString())
        val parsed = GroupControlFrames.parseRosterSnapshot(JSONObject(wire.toString()))

        assertEquals(signed, parsed)
        assertTrue(parsed.verify())
        assertEquals(
            signed.canonicalBytesForSignature().toList(),
            signed.copy(members = signed.members.reversed())
                .canonicalBytesForSignature()
                .toList(),
        )
        assertEquals(
            wire.toString(),
            GroupControlFrames.rosterSnapshotToJson(
                signed.copy(members = signed.members.reversed()),
            ).toString(),
        )
        assertEquals(
            listOf("member-a", "member-z", "owner").map(::stableDeviceIdForTest).sorted(),
            parsed.members.map(GroupRosterSnapshotMember::deviceId),
        )
    }

    @Test
    fun rosterSnapshotSignatureCoversRoutingPagingOwnerAndEveryMemberField() {
        val unsigned = rosterSnapshot(
            totalPages = 2,
            members = listOf(
                rosterMember("owner", GroupRole.OWNER),
                rosterMember("member-a", GroupRole.MEMBER),
            ).sortedBy(GroupRosterSnapshotMember::deviceId),
        )
        val signed = unsigned.copy(
            signatureBase64 = GroupIdentitySignatures.sign(unsigned.canonicalForSignature()),
        )
        assertTrue(signed.verify())

        val tampered = listOf(
            signed.copy(recipientDeviceId = stableDeviceIdForTest("another-recipient")),
            signed.copy(groupId = "other-group"),
            signed.copy(controlHead = "other-control-head"),
            signed.copy(epoch = signed.epoch + 1L),
            signed.copy(pageIndex = 1),
            signed.copy(totalPages = 3),
            signed.copy(ownerFingerprint = "different-owner"),
            signed.copy(ownerDeviceId = stableDeviceIdForTest("different-owner")),
            signed.copy(createdAtMs = signed.createdAtMs + 1L),
            signed.copy(
                members = signed.members.mapIndexed { index, member ->
                    if (index == 0) member.copy(displayName = "tampered") else member
                },
            ),
            signed.copy(
                members = signed.members.mapIndexed { index, member ->
                    if (index == 0) {
                        member.copy(permissions = member.permissions xor GroupPermission.POST_MEDIA.bit)
                    } else {
                        member
                    }
                },
            ),
            signed.copy(
                members = signed.members.mapIndexed { index, member ->
                    if (index == 0) {
                        member.copy(lastAuthorSequence = member.lastAuthorSequence + 1L)
                    } else {
                        member
                    }
                },
            ),
            signed.copy(signatureBase64 = mutateBase64(signed.signatureBase64)),
        )
        tampered.forEach { snapshot -> assertFalse(snapshot.verify()) }
    }

    @Test
    fun rosterSnapshotAcceptsExactPageLimitAndRejectsOversizedPage() {
        val members = buildList {
            add(rosterMember("owner", GroupRole.OWNER))
            repeat(GroupControlFrames.MAX_ROSTER_MEMBERS_PER_PAGE - 1) { index ->
                add(rosterMember("member-$index", GroupRole.MEMBER))
            }
        }.sortedBy(GroupRosterSnapshotMember::deviceId)
        val unsigned = rosterSnapshot(members = members)
        val signed = unsigned.copy(
            signatureBase64 = GroupIdentitySignatures.sign(unsigned.canonicalForSignature()),
        )
        val wire = GroupControlFrames.rosterSnapshotToJson(signed)

        assertEquals(
            GroupControlFrames.MAX_ROSTER_MEMBERS_PER_PAGE,
            GroupControlFrames.parseRosterSnapshot(wire).members.size,
        )

        val oversized = JSONObject(wire.toString())
        val array = oversized.getJSONArray("members")
        array.put(JSONObject(array.getJSONObject(0).toString()))
        assertThrows(IllegalArgumentException::class.java) {
            GroupControlFrames.parseRosterSnapshot(oversized)
        }
    }

    @Test
    fun rosterSnapshotRejectsMalformedBoundsEnumsPermissionsAndIdentities() {
        val unsigned = rosterSnapshot(
            members = listOf(
                rosterMember("owner", GroupRole.OWNER),
                rosterMember("member-a", GroupRole.MEMBER),
            ).sortedBy(GroupRosterSnapshotMember::deviceId),
        )
        val signed = unsigned.copy(
            signatureBase64 = GroupIdentitySignatures.sign(unsigned.canonicalForSignature()),
        )
        val valid = GroupControlFrames.rosterSnapshotToJson(signed)

        fun reject(mutate: (JSONObject) -> Unit) {
            val malformed = JSONObject(valid.toString())
            mutate(malformed)
            assertThrows(IllegalArgumentException::class.java) {
                GroupControlFrames.parseRosterSnapshot(malformed)
            }
        }

        reject { it.put("epoch", 0) }
        reject { it.put("page_index", 1) }
        reject { it.put("total_pages", GroupControlFrames.MAX_ROSTER_PAGES + 1) }
        reject { it.put("owner_device_id", "0".repeat(64)) }
        reject {
            it.getJSONArray("members").getJSONObject(0).put("device_id", "0".repeat(64))
        }
        reject {
            it.getJSONArray("members").getJSONObject(0).put("role", "SUPERUSER")
        }
        reject {
            it.getJSONArray("members").getJSONObject(0).put("status", "UNKNOWN")
        }
        reject {
            it.getJSONArray("members").getJSONObject(0).put("permissions", 1L shl 40)
        }
        reject {
            it.getJSONArray("members").getJSONObject(0).put("display_name", "x".repeat(161))
        }
        reject {
            val members = it.getJSONArray("members")
            members.put(JSONObject(members.getJSONObject(0).toString()))
        }
        reject {
            it.put("ignored_padding", "x".repeat(GroupWireProtocol.MAX_WIRE_BYTES))
        }
    }

    private fun newEvent(
        factory: GroupEventFactory,
        secret: ByteArray,
    ): GroupWireEvent = factory.create(
        groupId = "group-1",
        epoch = 1L,
        epochSecret = secret,
        kind = GroupEventKind.MESSAGE,
        authorFingerprint = "fingerprint-local",
        authorDeviceId = "device-local",
        authorSequence = 1L,
        previousAuthorEvent = null,
        controlHead = "control-head-1",
        hlcPhysicalMs = 1_784_000_000_000L,
        hlcLogical = 0,
        plaintextPayload = JSONObject().put("text", "hello"),
    )

    private fun mutateBase64(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun rosterSnapshot(
        totalPages: Int = 1,
        members: List<GroupRosterSnapshotMember>,
    ): GroupRosterSnapshot {
        val ownerFingerprint = "owner"
        return GroupRosterSnapshot(
            recipientDeviceId = stableDeviceIdForTest("recipient"),
            groupId = "group-1",
            controlHead = "control-head-7",
            epoch = 7L,
            pageIndex = 0,
            totalPages = totalPages,
            ownerFingerprint = ownerFingerprint,
            ownerDeviceId = stableDeviceIdForTest(ownerFingerprint),
            ownerSigningKey = GroupIdentitySignatures.localVerificationKey(),
            createdAtMs = 1_784_000_000_000L,
            members = members,
            signatureBase64 = "",
        )
    }

    private fun rosterMember(
        fingerprint: String,
        role: GroupRole,
    ): GroupRosterSnapshotMember {
        val status = when (role) {
            GroupRole.BANNED -> GroupRosterMemberStatus.BANNED
            GroupRole.LEFT -> GroupRosterMemberStatus.LEFT
            GroupRole.RESTRICTED -> GroupRosterMemberStatus.RESTRICTED
            else -> GroupRosterMemberStatus.ACTIVE
        }
        val removedEpoch = if (
            status == GroupRosterMemberStatus.BANNED ||
            status == GroupRosterMemberStatus.LEFT
        ) {
            7L
        } else {
            null
        }
        return GroupRosterSnapshotMember(
            deviceId = stableDeviceIdForTest(fingerprint),
            fingerprint = fingerprint,
            displayName = "Display $fingerprint",
            peerName = "Peer $fingerprint",
            signingKey = GroupIdentitySignatures.localVerificationKey(),
            role = role,
            permissions = if (removedEpoch == null) {
                GroupPermission.POST_MESSAGES.bit or GroupPermission.ADD_REACTIONS.bit
            } else {
                0L
            },
            status = status,
            joinedEpoch = 1L,
            removedEpoch = removedEpoch,
            lastAuthorSequence = 17L,
            createdAtMs = 1_783_000_000_000L,
            updatedAtMs = 1_784_000_000_000L,
        )
    }

    private fun stableDeviceIdForTest(fingerprint: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("2pchat-group-device-v1\u0000$fingerprint".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeSigningIdentity() {
            NativeBridge.initialize()
            assertNotEquals("", GroupIdentitySignatures.localVerificationKey())
        }
    }
}
