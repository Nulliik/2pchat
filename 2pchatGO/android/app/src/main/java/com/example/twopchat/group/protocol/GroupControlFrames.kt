package com.example.twopchat.group.protocol

import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.model.GroupPermission
import com.example.twopchat.group.model.GroupRole
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Small signed control envelopes which travel inside an already authenticated
 * pairwise Double Ratchet session. Event ciphertext remains independently
 * signed, so a replicated event can be validated after it leaves that session.
 */
data class GroupInviteResponse(
    val inviteId: String,
    val groupId: String,
    val accepted: Boolean,
    val memberFingerprint: String,
    val memberPeerName: String,
    val memberDeviceId: String,
    val memberSigningKey: String,
    val createdAtMs: Long,
    val signatureBase64: String,
) {
    fun canonicalForSignature(): String = listOf(
        "2pchat-group-invite-response-v1",
        GroupWireProtocol.VERSION.toString(),
        inviteId,
        groupId,
        accepted.toString(),
        memberFingerprint,
        memberPeerName,
        memberDeviceId,
        memberSigningKey,
        createdAtMs.toString(),
    ).joinToString("\n")

    fun verify(): Boolean = GroupIdentitySignatures.verify(
        memberSigningKey,
        canonicalForSignature(),
        signatureBase64,
    )
}

data class GroupStoreAck(
    val groupId: String,
    val eventId: String,
    val recipientDeviceId: String,
    val receivedAtMs: Long,
)

data class GroupEpochKeyPackage(
    val groupId: String,
    val epoch: Long,
    val epochSecretBase64: String,
    val recipientDeviceId: String,
    val controlHead: String,
    val senderFingerprint: String,
    val senderDeviceId: String,
    val senderSigningKey: String,
    val createdAtMs: Long,
    val signatureBase64: String,
) {
    fun canonicalForSignature(): String = listOf(
        "2pchat-group-key-package-v1",
        GroupWireProtocol.VERSION.toString(),
        groupId,
        epoch.toString(),
        epochSecretBase64,
        recipientDeviceId,
        controlHead,
        senderFingerprint,
        senderDeviceId,
        senderSigningKey,
        createdAtMs.toString(),
    ).joinToString("\n")

    fun verify(): Boolean = GroupIdentitySignatures.verify(
        senderSigningKey,
        canonicalForSignature(),
        signatureBase64,
    )
}

data class GroupSyncRequest(
    val requestId: String,
    val groupId: String,
    val requesterDeviceId: String,
    val cursors: Map<String, Long>,
)

data class GroupSyncBatch(
    val requestId: String,
    val groupId: String,
    val events: List<JSONObject>,
    val hasMore: Boolean,
)

enum class GroupRosterMemberStatus {
    INVITED,
    ACTIVE,
    RESTRICTED,
    BANNED,
    LEFT,
}

data class GroupRosterSnapshotMember(
    val deviceId: String,
    val fingerprint: String,
    val displayName: String,
    val peerName: String,
    val signingKey: String,
    val role: GroupRole,
    val permissions: Long,
    val status: GroupRosterMemberStatus,
    val joinedEpoch: Long,
    val removedEpoch: Long?,
    val lastAuthorSequence: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class GroupRosterSnapshot(
    val recipientDeviceId: String,
    val groupId: String,
    val controlHead: String,
    val epoch: Long,
    val pageIndex: Int,
    val totalPages: Int,
    val ownerFingerprint: String,
    val ownerDeviceId: String,
    val ownerSigningKey: String,
    val createdAtMs: Long,
    val members: List<GroupRosterSnapshotMember>,
    val signatureBase64: String,
) {
    fun canonicalForSignature(): String = buildString {
        append("2pchat-group-roster-snapshot-signature-v1\n")
        append(GroupWireProtocol.VERSION).append('\n')
        appendCanonical(recipientDeviceId)
        appendCanonical(groupId)
        appendCanonical(controlHead)
        append(epoch).append('\n')
        append(pageIndex).append('\n')
        append(totalPages).append('\n')
        appendCanonical(ownerFingerprint)
        appendCanonical(ownerDeviceId)
        appendCanonical(ownerSigningKey)
        append(createdAtMs).append('\n')
        append(members.size).append('\n')
        members.sortedBy(GroupRosterSnapshotMember::deviceId).forEach { member ->
            appendCanonical(member.deviceId)
            appendCanonical(member.fingerprint)
            appendCanonical(member.displayName)
            appendCanonical(member.peerName)
            appendCanonical(member.signingKey)
            append(member.role.name).append('\n')
            append(member.permissions).append('\n')
            append(member.status.name).append('\n')
            append(member.joinedEpoch).append('\n')
            append(member.removedEpoch ?: -1L).append('\n')
            append(member.lastAuthorSequence).append('\n')
            append(member.createdAtMs).append('\n')
            append(member.updatedAtMs).append('\n')
        }
    }

    fun canonicalBytesForSignature(): ByteArray =
        canonicalForSignature().toByteArray(Charsets.UTF_8)

    fun verify(): Boolean = GroupIdentitySignatures.verify(
        ownerSigningKey,
        canonicalForSignature(),
        signatureBase64,
    )
}

object GroupControlFrames {
    const val TYPE_ROSTER_SNAPSHOT = GroupWireProtocol.TYPE_ROSTER_SNAPSHOT
    const val MAX_ROSTER_MEMBERS_PER_PAGE = 256
    const val MAX_ROSTER_PAGES =
        (GroupWireProtocol.MAX_GROUP_MEMBERS_IN_INVITE + MAX_ROSTER_MEMBERS_PER_PAGE - 1) /
            MAX_ROSTER_MEMBERS_PER_PAGE

    fun inviteResponseToJson(response: GroupInviteResponse): JSONObject = JSONObject().apply {
        put("type", GroupWireProtocol.TYPE_INVITE_RESPONSE)
        put("version", GroupWireProtocol.VERSION)
        put("invite_id", response.inviteId)
        put("group_id", response.groupId)
        put("accepted", response.accepted)
        put("member_fingerprint", response.memberFingerprint)
        put("member_peer_name", response.memberPeerName)
        put("member_device_id", response.memberDeviceId)
        put("member_signing_key", response.memberSigningKey)
        put("created_at_ms", response.createdAtMs)
        put("signature", response.signatureBase64)
    }

    fun parseInviteResponse(json: JSONObject): GroupInviteResponse {
        requireFrame(json, GroupWireProtocol.TYPE_INVITE_RESPONSE)
        return GroupInviteResponse(
            inviteId = json.requiredControlToken("invite_id", 128),
            groupId = json.requiredControlToken("group_id", 128),
            accepted = json.getBoolean("accepted"),
            memberFingerprint = json.requiredControlText("member_fingerprint", 256),
            memberPeerName = json.requiredControlText("member_peer_name", 160),
            memberDeviceId = json.requiredControlToken("member_device_id", 128),
            memberSigningKey = json.requiredControlText("member_signing_key", 256),
            createdAtMs = json.requiredControlLong("created_at_ms"),
            signatureBase64 = json.requiredControlText("signature", 256),
        )
    }

    fun storeAckToJson(ack: GroupStoreAck): JSONObject = JSONObject().apply {
        put("type", GroupWireProtocol.TYPE_STORE_ACK)
        put("version", GroupWireProtocol.VERSION)
        put("group_id", ack.groupId)
        put("event_id", ack.eventId)
        put("recipient_device_id", ack.recipientDeviceId)
        put("received_at_ms", ack.receivedAtMs)
    }

    fun keyPackageToJson(keyPackage: GroupEpochKeyPackage): JSONObject = JSONObject().apply {
        put("type", GroupWireProtocol.TYPE_KEY_PACKAGE)
        put("version", GroupWireProtocol.VERSION)
        put("group_id", keyPackage.groupId)
        put("epoch", keyPackage.epoch)
        put("epoch_secret", keyPackage.epochSecretBase64)
        put("recipient_device_id", keyPackage.recipientDeviceId)
        put("control_head", keyPackage.controlHead)
        put("sender_fingerprint", keyPackage.senderFingerprint)
        put("sender_device_id", keyPackage.senderDeviceId)
        put("sender_signing_key", keyPackage.senderSigningKey)
        put("created_at_ms", keyPackage.createdAtMs)
        put("signature", keyPackage.signatureBase64)
    }

    fun parseKeyPackage(json: JSONObject): GroupEpochKeyPackage {
        requireFrame(json, GroupWireProtocol.TYPE_KEY_PACKAGE)
        return GroupEpochKeyPackage(
            groupId = json.requiredControlToken("group_id", 128),
            epoch = json.requiredControlLong("epoch").also { require(it > 0L) },
            epochSecretBase64 = json.requiredControlText("epoch_secret", 128),
            recipientDeviceId = json.requiredControlToken("recipient_device_id", 128),
            controlHead = json.requiredControlToken("control_head", 128),
            senderFingerprint = json.requiredControlText("sender_fingerprint", 256),
            senderDeviceId = json.requiredControlToken("sender_device_id", 128),
            senderSigningKey = json.requiredControlText("sender_signing_key", 256),
            createdAtMs = json.requiredControlLong("created_at_ms"),
            signatureBase64 = json.requiredControlText("signature", 256),
        )
    }

    fun parseStoreAck(json: JSONObject): GroupStoreAck {
        requireFrame(json, GroupWireProtocol.TYPE_STORE_ACK)
        return GroupStoreAck(
            groupId = json.requiredControlToken("group_id", 128),
            eventId = json.requiredControlToken("event_id", 128),
            recipientDeviceId = json.requiredControlToken("recipient_device_id", 128),
            receivedAtMs = json.requiredControlLong("received_at_ms"),
        )
    }

    fun syncRequestToJson(request: GroupSyncRequest): JSONObject = JSONObject().apply {
        put("type", GroupWireProtocol.TYPE_SYNC_REQUEST)
        put("version", GroupWireProtocol.VERSION)
        put("request_id", request.requestId)
        put("group_id", request.groupId)
        put("requester_device_id", request.requesterDeviceId)
        put("cursors", JSONObject().apply {
            request.cursors.toSortedMap().forEach { (deviceId, sequence) ->
                put(deviceId, sequence)
            }
        })
    }

    fun parseSyncRequest(json: JSONObject): GroupSyncRequest {
        requireFrame(json, GroupWireProtocol.TYPE_SYNC_REQUEST)
        val cursorsJson = json.optJSONObject("cursors") ?: JSONObject()
        require(cursorsJson.length() <= GroupWireProtocol.MAX_SYNC_CURSORS)
        val cursors = linkedMapOf<String, Long>()
        val keys = cursorsJson.keys()
        while (keys.hasNext()) {
            val deviceId = keys.next().validateControlToken(128)
            val sequence = cursorsJson.optLong(deviceId, -1)
            require(sequence >= 0) { "invalid group sync cursor" }
            cursors[deviceId] = sequence
        }
        return GroupSyncRequest(
            requestId = json.requiredControlToken("request_id", 128),
            groupId = json.requiredControlToken("group_id", 128),
            requesterDeviceId = json.requiredControlToken("requester_device_id", 128),
            cursors = cursors,
        )
    }

    fun syncBatchToJson(batch: GroupSyncBatch): JSONObject = JSONObject().apply {
        require(batch.events.size <= GroupWireProtocol.MAX_SYNC_EVENTS)
        put("type", GroupWireProtocol.TYPE_SYNC_BATCH)
        put("version", GroupWireProtocol.VERSION)
        put("request_id", batch.requestId)
        put("group_id", batch.groupId)
        put("has_more", batch.hasMore)
        put("events", JSONArray().apply { batch.events.forEach(::put) })
    }

    fun parseSyncBatch(json: JSONObject): GroupSyncBatch {
        requireFrame(json, GroupWireProtocol.TYPE_SYNC_BATCH)
        val array = json.optJSONArray("events") ?: JSONArray()
        require(array.length() <= GroupWireProtocol.MAX_SYNC_EVENTS) {
            "group sync batch is too large"
        }
        val events = buildList {
            for (index in 0 until array.length()) {
                add(
                    array.optJSONObject(index)
                        ?: throw IllegalArgumentException("invalid group sync event"),
                )
            }
        }
        return GroupSyncBatch(
            requestId = json.requiredControlToken("request_id", 128),
            groupId = json.requiredControlToken("group_id", 128),
            events = events,
            hasMore = json.optBoolean("has_more", false),
        )
    }

    fun rosterSnapshotToJson(snapshot: GroupRosterSnapshot): JSONObject {
        validateRosterSnapshot(snapshot)
        return JSONObject().apply {
            put("type", TYPE_ROSTER_SNAPSHOT)
            put("version", GroupWireProtocol.VERSION)
            put("recipient_device_id", snapshot.recipientDeviceId)
            put("group_id", snapshot.groupId)
            put("control_head", snapshot.controlHead)
            put("epoch", snapshot.epoch)
            put("page_index", snapshot.pageIndex)
            put("total_pages", snapshot.totalPages)
            put("owner_fingerprint", snapshot.ownerFingerprint)
            put("owner_device_id", snapshot.ownerDeviceId)
            put("owner_signing_key", snapshot.ownerSigningKey)
            put("created_at_ms", snapshot.createdAtMs)
            put("members", JSONArray().apply {
                snapshot.members
                    .sortedBy(GroupRosterSnapshotMember::deviceId)
                    .forEach { member ->
                        put(JSONObject().apply {
                            put("device_id", member.deviceId)
                            put("fingerprint", member.fingerprint)
                            put("display_name", member.displayName)
                            put("peer_name", member.peerName)
                            put("signing_key", member.signingKey)
                            put("role", member.role.name)
                            put("permissions", member.permissions)
                            put("status", member.status.name)
                            put("joined_epoch", member.joinedEpoch)
                            if (member.removedEpoch == null) {
                                put("removed_epoch", JSONObject.NULL)
                            } else {
                                put("removed_epoch", member.removedEpoch)
                            }
                            put("last_author_sequence", member.lastAuthorSequence)
                            put("created_at_ms", member.createdAtMs)
                            put("updated_at_ms", member.updatedAtMs)
                        })
                    }
            })
            put("signature", snapshot.signatureBase64)
        }.also { GroupWireProtocol.requireBoundedWire(it.toString()) }
    }

    fun parseRosterSnapshot(json: JSONObject): GroupRosterSnapshot {
        GroupWireProtocol.requireBoundedWire(json.toString())
        requireFrame(json, TYPE_ROSTER_SNAPSHOT)
        val membersJson = json.optJSONArray("members")
            ?: throw IllegalArgumentException("missing roster snapshot members")
        require(membersJson.length() in 1..MAX_ROSTER_MEMBERS_PER_PAGE) {
            "invalid roster snapshot page size"
        }
        val members = buildList {
            for (index in 0 until membersJson.length()) {
                val item = membersJson.optJSONObject(index)
                    ?: throw IllegalArgumentException("invalid roster snapshot member")
                add(
                    GroupRosterSnapshotMember(
                        deviceId = item.requiredControlToken("device_id", 128),
                        fingerprint = item.requiredControlText("fingerprint", 256),
                        displayName = item.requiredControlText("display_name", 160),
                        peerName = item.requiredControlText("peer_name", 160),
                        signingKey = item.requiredControlText("signing_key", 256),
                        role = item.requiredRosterRole("role"),
                        permissions = item.requiredControlLong("permissions"),
                        status = item.requiredRosterStatus("status"),
                        joinedEpoch = item.requiredControlLong("joined_epoch"),
                        removedEpoch = item.optionalControlLong("removed_epoch"),
                        lastAuthorSequence = item.requiredControlLong("last_author_sequence"),
                        createdAtMs = item.requiredControlLong("created_at_ms"),
                        updatedAtMs = item.requiredControlLong("updated_at_ms"),
                    ),
                )
            }
        }
        return GroupRosterSnapshot(
            recipientDeviceId = json.requiredControlToken("recipient_device_id", 128),
            groupId = json.requiredControlToken("group_id", 128),
            controlHead = json.requiredControlToken("control_head", 128),
            epoch = json.requiredControlLong("epoch"),
            pageIndex = json.requiredControlInt("page_index"),
            totalPages = json.requiredControlInt("total_pages"),
            ownerFingerprint = json.requiredControlText("owner_fingerprint", 256),
            ownerDeviceId = json.requiredControlToken("owner_device_id", 128),
            ownerSigningKey = json.requiredControlText("owner_signing_key", 256),
            createdAtMs = json.requiredControlLong("created_at_ms"),
            members = members,
            signatureBase64 = json.requiredControlText("signature", 256),
        ).also(::validateRosterSnapshot)
    }

    private fun validateRosterSnapshot(snapshot: GroupRosterSnapshot) {
        snapshot.recipientDeviceId.validateControlToken(128)
        snapshot.groupId.validateControlToken(128)
        snapshot.controlHead.validateControlToken(128)
        require(snapshot.epoch > 0L) { "invalid roster snapshot epoch" }
        require(snapshot.totalPages in 1..MAX_ROSTER_PAGES) {
            "invalid roster snapshot total pages"
        }
        require(snapshot.pageIndex in 0 until snapshot.totalPages) {
            "invalid roster snapshot page index"
        }
        snapshot.ownerFingerprint.validateControlText(256, "owner fingerprint")
        snapshot.ownerDeviceId.validateControlToken(128)
        snapshot.ownerSigningKey.validateControlText(256, "owner signing key")
        require(snapshot.ownerDeviceId == stableGroupDeviceId(snapshot.ownerFingerprint)) {
            "owner device id does not match fingerprint"
        }
        require(snapshot.createdAtMs > 0L) { "invalid roster snapshot creation time" }
        require(snapshot.members.size in 1..MAX_ROSTER_MEMBERS_PER_PAGE) {
            "invalid roster snapshot page size"
        }
        require(snapshot.members.map { it.deviceId }.toSet().size == snapshot.members.size) {
            "duplicate roster snapshot device id"
        }
        require(snapshot.members.map { it.fingerprint }.toSet().size == snapshot.members.size) {
            "duplicate roster snapshot fingerprint"
        }
        snapshot.members.forEach { member ->
            validateRosterMember(member, snapshot.epoch)
        }
        snapshot.members.firstOrNull { it.deviceId == snapshot.ownerDeviceId }?.let { owner ->
            require(
                owner.fingerprint == snapshot.ownerFingerprint &&
                    owner.signingKey == snapshot.ownerSigningKey &&
                    owner.role == GroupRole.OWNER &&
                    owner.status == GroupRosterMemberStatus.ACTIVE,
            ) { "invalid owner roster record" }
        }
        snapshot.signatureBase64.validateControlText(256, "roster snapshot signature")
    }

    private fun validateRosterMember(member: GroupRosterSnapshotMember, epoch: Long) {
        member.deviceId.validateControlToken(128)
        member.fingerprint.validateControlText(256, "member fingerprint")
        member.displayName.validateControlText(160, "member display name")
        member.peerName.validateControlText(160, "member peer name")
        member.signingKey.validateControlText(256, "member signing key")
        require(member.deviceId == stableGroupDeviceId(member.fingerprint)) {
            "member device id does not match fingerprint"
        }
        require(member.permissions and GroupPermission.knownBits.inv() == 0L) {
            "roster member has unknown permission bits"
        }
        require(member.joinedEpoch in 1..epoch) { "invalid roster member joined epoch" }
        require(
            member.removedEpoch == null ||
                member.removedEpoch in member.joinedEpoch..epoch,
        ) { "invalid roster member removed epoch" }
        require(member.lastAuthorSequence >= 0L) {
            "invalid roster member author sequence"
        }
        require(member.createdAtMs > 0L && member.updatedAtMs >= member.createdAtMs) {
            "invalid roster member timestamps"
        }
        when (member.status) {
            GroupRosterMemberStatus.INVITED -> require(
                member.role == GroupRole.MEMBER && member.removedEpoch == null,
            )
            GroupRosterMemberStatus.ACTIVE -> require(
                member.role in ACTIVE_ROSTER_ROLES && member.removedEpoch == null,
            )
            GroupRosterMemberStatus.RESTRICTED -> require(
                member.role in setOf(GroupRole.MEMBER, GroupRole.RESTRICTED) &&
                    member.removedEpoch == null,
            )
            GroupRosterMemberStatus.BANNED -> require(
                member.role == GroupRole.BANNED &&
                    member.removedEpoch != null &&
                    member.permissions == 0L,
            )
            GroupRosterMemberStatus.LEFT -> require(
                member.role == GroupRole.LEFT &&
                    member.removedEpoch != null &&
                    member.permissions == 0L,
            )
        }
    }

    private fun requireFrame(json: JSONObject, expectedType: String) {
        require(json.optString("type") == expectedType)
        require(json.optInt("version", -1) == GroupWireProtocol.VERSION)
    }

    private val ACTIVE_ROSTER_ROLES = setOf(
        GroupRole.OWNER,
        GroupRole.ADMINISTRATOR,
        GroupRole.MODERATOR,
        GroupRole.MEMBER,
    )
}

private fun JSONObject.requiredControlText(key: String, maxLength: Int): String =
    optString(key).also {
        require(it.isNotBlank() && it.length <= maxLength && '\u0000' !in it) {
            "invalid $key"
        }
    }

private fun JSONObject.requiredControlToken(key: String, maxLength: Int): String =
    requiredControlText(key, maxLength).validateControlToken(maxLength)

private fun JSONObject.requiredControlLong(key: String): Long =
    optLong(key, -1L).also { require(it >= 0L) { "invalid $key" } }

private fun JSONObject.requiredControlInt(key: String): Int =
    optInt(key, -1).also { require(it >= 0) { "invalid $key" } }

private fun JSONObject.optionalControlLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else requiredControlLong(key)

private fun JSONObject.requiredRosterRole(key: String): GroupRole =
    runCatching { GroupRole.valueOf(requiredControlToken(key, 32)) }
        .getOrElse { throw IllegalArgumentException("invalid $key", it) }

private fun JSONObject.requiredRosterStatus(key: String): GroupRosterMemberStatus =
    runCatching { GroupRosterMemberStatus.valueOf(requiredControlToken(key, 32)) }
        .getOrElse { throw IllegalArgumentException("invalid $key", it) }

private fun String.validateControlToken(maxLength: Int): String = also {
    require(it.isNotBlank() && it.length <= maxLength)
    require(it.all { char -> char.isLetterOrDigit() || char in "-_.:=" })
}

private fun String.validateControlText(maxLength: Int, field: String): String = also {
    require(it.isNotBlank() && it.length <= maxLength && '\u0000' !in it) {
        "invalid $field"
    }
}

private fun stableGroupDeviceId(fingerprint: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("2pchat-group-device-v1\u0000$fingerprint".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun StringBuilder.appendCanonical(value: String) {
    append(JSONObject.quote(value)).append('\n')
}
