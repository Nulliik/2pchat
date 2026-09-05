package com.example.twopchat.group.protocol

import com.example.twopchat.group.crypto.GroupIdentitySignatures
import com.example.twopchat.group.crypto.GroupCryptoProvider
import com.example.twopchat.group.crypto.ProtectedGroupPayload
import com.example.twopchat.group.model.HybridLogicalClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object GroupWireProtocol {
    const val VERSION = 1
    const val TYPE_EVENT = "group_event_v1"
    const val TYPE_INVITE = "group_invite_v1"
    const val TYPE_INVITE_RESPONSE = "group_invite_response_v1"
    const val TYPE_KEY_PACKAGE = "group_key_package_v1"
    const val TYPE_STORE_ACK = "group_store_ack_v1"
    const val TYPE_SYNC_REQUEST = "group_sync_request_v1"
    const val TYPE_SYNC_BATCH = "group_sync_batch_v1"
    const val TYPE_ROSTER_SNAPSHOT = "group_roster_snapshot_v1"
    const val TYPE_ATTACHMENT_REQUEST = "group_attachment_request_v1"
    const val TYPE_ATTACHMENT_BLOCK = "group_attachment_block_v1"
    const val TYPE_JOIN_REQUEST = "group_join_request_v1"
    const val TYPE_TYPING = "group_typing_v1"
    const val TYPE_KEY_REQUEST = "group_key_request_v1"

    const val MAX_WIRE_BYTES = 1536 * 1024
    const val MAX_EVENT_CIPHERTEXT_CHARS = 1024 * 1024
    const val MAX_SYNC_EVENTS = 100
    const val MAX_SYNC_CURSORS = 256
    const val MAX_GROUP_MEMBERS_IN_INVITE = 10_000
    const val MAX_GROUP_AVATAR_BYTES = 500_000
    const val MAX_GROUP_AVATAR_BASE64_CHARS = 666_668
    const val MAX_GROUP_WALLPAPER_BYTES = 500_000
    const val MAX_GROUP_WALLPAPER_BASE64_CHARS = 666_668
    const val MAX_HLC_LOGICAL = HybridLogicalClock.MAX_LOGICAL_COUNTER

    const val SUITE_V1 = com.example.twopchat.group.crypto.SUITE_V1
    const val SUITE_V2 = com.example.twopchat.group.crypto.SUITE_V2

    fun computeRosterHash(members: Collection<com.example.twopchat.group.storage.StoredGroupMember>): String {
        val entries = members
            .filter { it.status in setOf("ACTIVE", "RESTRICTED") }
            .map { "${it.deviceId}:${it.signingKeyBase64}" }
            .sorted()
        val rosterString = entries.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(rosterString.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun computeRosterHashFromEntries(entries: List<Pair<String, String>>): String {
        val sortedEntries = entries
            .map { "${it.first}:${it.second}" }
            .sorted()
        val rosterString = sortedEntries.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(rosterString.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isGroupFrame(json: JSONObject): Boolean =
        json.optString("type") in setOf(
            TYPE_EVENT,
            TYPE_INVITE,
            TYPE_INVITE_RESPONSE,
            TYPE_KEY_PACKAGE,
            TYPE_STORE_ACK,
            TYPE_SYNC_REQUEST,
            TYPE_SYNC_BATCH,
            TYPE_ROSTER_SNAPSHOT,
            TYPE_ATTACHMENT_REQUEST,
            TYPE_ATTACHMENT_BLOCK,
            TYPE_JOIN_REQUEST,
            TYPE_TYPING,
            TYPE_KEY_REQUEST,
        )

    fun parseEvent(json: JSONObject): GroupWireEvent {
        requireTypeAndVersion(json, TYPE_EVENT)
        val isTombstoned = json.optBoolean("is_tombstoned", false)
        val event = GroupWireEvent(
            groupId = json.requiredToken("group_id", 128),
            eventId = json.requiredToken("event_id", 128),
            epoch = json.requiredPositiveLong("epoch"),
            kind = GroupEventKind.fromWire(json.requiredToken("kind", 64)),
            authorFingerprint = if (isTombstoned) json.optString("author_fingerprint", "").take(256) else json.requiredText("author_fingerprint", 256),
            authorDeviceId = json.requiredToken("author_device_id", 128),
            authorSigningKey = if (isTombstoned) json.optString("author_signing_key", "").take(256) else json.requiredText("author_signing_key", 256),
            authorSequence = json.requiredPositiveLong("author_sequence"),
            previousAuthorEvent = json.optNullableToken("previous_author_event", 128),
            controlHead = json.optNullableToken("control_head", 128),
            hlcPhysicalMs = json.requiredPositiveLong("hlc_physical_ms"),
            hlcLogical = json.optInt("hlc_logical", 0).also {
                require(it in 0..MAX_HLC_LOGICAL) { "invalid HLC logical component" }
            },
            targetEventId = json.optNullableToken("target_event_id", 128),
            nonceBase64 = if (isTombstoned) json.optString("nonce", "") else json.requiredText("nonce", 128),
            ciphertextBase64 = if (isTombstoned) json.optString("ciphertext", "") else json.requiredText(
                "ciphertext",
                MAX_EVENT_CIPHERTEXT_CHARS,
            ),
            signatureBase64 = if (isTombstoned) json.optString("signature", "") else json.requiredText("signature", 256),
            cryptoSuite = json.optString("crypto_suite", "").takeIf { it.isNotBlank() } ?: "2pchat-epoch-aes256gcm-ed25519-v1",
            expiresAtMs = json.optLong("expires_at_ms", 0L).coerceAtLeast(0L),
            isTombstoned = isTombstoned,
        )
        if (!isTombstoned) {
            require(event.eventId == event.computedEventId()) { "group event id mismatch" }
        }
        return event
    }

    fun eventToJson(event: GroupWireEvent): JSONObject = JSONObject().apply {
        put("type", TYPE_EVENT)
        put("version", VERSION)
        put("group_id", event.groupId)
        put("event_id", event.eventId)
        put("epoch", event.epoch)
        put("kind", event.kind.wireName)
        put("author_fingerprint", event.authorFingerprint)
        put("author_device_id", event.authorDeviceId)
        put("author_signing_key", event.authorSigningKey)
        put("author_sequence", event.authorSequence)
        putNullable("previous_author_event", event.previousAuthorEvent)
        putNullable("control_head", event.controlHead)
        put("hlc_physical_ms", event.hlcPhysicalMs)
        put("hlc_logical", event.hlcLogical)
        putNullable("target_event_id", event.targetEventId)
        put("nonce", event.nonceBase64)
        put("ciphertext", event.ciphertextBase64)
        put("signature", event.signatureBase64)
        put("crypto_suite", event.cryptoSuite)
        if (event.expiresAtMs > 0L) put("expires_at_ms", event.expiresAtMs)
        if (event.isTombstoned) put("is_tombstoned", true)
    }

    fun parseInvite(json: JSONObject): GroupInvite {
        requireTypeAndVersion(json, TYPE_INVITE)
        val membersJson = json.optJSONArray("members") ?: JSONArray()
        require(membersJson.length() in 1..MAX_GROUP_MEMBERS_IN_INVITE) {
            "invalid group invite member count"
        }
        val members = buildList {
            for (index in 0 until membersJson.length()) {
                val item = membersJson.optJSONObject(index)
                    ?: throw IllegalArgumentException("invalid group invite member")
                add(
                    GroupInviteMember(
                        fingerprint = item.requiredText("fingerprint", 256),
                        peerName = item.requiredText("peer_name", 128),
                        deviceId = item.requiredToken("device_id", 128),
                        signingKey = item.optString("signing_key").take(256),
                        role = item.requiredToken("role", 32),
                        status = item.optString("status", "ACTIVE").take(32),
                    )
                )
            }
        }
        val cursorJson = json.optJSONObject("history_cursors") ?: JSONObject()
        require(cursorJson.length() <= MAX_GROUP_MEMBERS_IN_INVITE)
        val historyCursors = linkedMapOf<String, Long>()
        val cursorKeys = cursorJson.keys()
        while (cursorKeys.hasNext()) {
            val deviceId = cursorKeys.next()
            require(deviceId.isNotBlank() && deviceId.length <= 128)
            require(deviceId.all { it.isLetterOrDigit() || it in "-_.:=" })
            val sequence = cursorJson.optLong(deviceId, -1)
            require(sequence >= 0)
            historyCursors[deviceId] = sequence
        }
        val transitionsJson = json.optJSONArray("owner_transitions") ?: JSONArray()
        require(transitionsJson.length() <= GroupOwnerLineage.MAX_TRANSITIONS) {
            "too many owner transitions in group invite"
        }
        val ownerTransitions = buildList {
            for (index in 0 until transitionsJson.length()) {
                add(
                    GroupOwnerLineage.parse(
                        transitionsJson.optJSONObject(index)
                            ?: throw IllegalArgumentException("invalid owner transition"),
                    ),
                )
            }
        }
        return GroupInvite(
            inviteId = json.requiredToken("invite_id", 128),
            groupId = json.requiredToken("group_id", 128),
            title = json.requiredText("title", 160),
            description = json.optString("description").take(2_000),
            adminOnlyPosting = json.optBoolean("admin_only_posting", false),
            torOnlyGroup = json.optBoolean("tor_only_group", false),
            epoch = json.requiredPositiveLong("epoch"),
            epochSecretBase64 = json.requiredText("epoch_secret", 128),
            ownerFingerprint = json.requiredText("owner_fingerprint", 256),
            senderFingerprint = json.requiredText("sender_fingerprint", 256),
            senderSigningKey = json.requiredText("sender_signing_key", 256),
            coordinatorFingerprint = json.requiredText("coordinator_fingerprint", 256),
            controlHead = json.optNullableToken("control_head", 128),
            historyCursors = historyCursors,
            ownerTransitions = ownerTransitions,
            createdAtMs = json.requiredPositiveLong("created_at_ms"),
            rosterSize = json.optInt("roster_size", -1).also {
                require(it in members.size..MAX_GROUP_MEMBERS_IN_INVITE)
            },
            members = members,
            cryptoSuite = json.requiredToken("crypto_suite", 128),
            signatureBase64 = json.requiredText("signature", 256),
            groupAvatarDataB64 = json.optString("group_avatar_data", "")
                .ifBlank { null }
                ?.also {
                    require(it.length <= MAX_GROUP_AVATAR_BASE64_CHARS) {
                        "group avatar payload is too large"
                    }
                },
            groupAvatarSigned = json.optBoolean("group_avatar_signed", false),
            groupWallpaperDataB64 = json.optString("group_wallpaper_data", "")
                .ifBlank { null }
                ?.also {
                    require(it.length <= MAX_GROUP_WALLPAPER_BASE64_CHARS) {
                        "group wallpaper payload is too large"
                    }
                },
            groupWallpaperSigned = json.optBoolean("group_wallpaper_signed", false),
        ).also {
            require(!it.groupAvatarSigned || it.groupAvatarDataB64 != null) {
                "signed group avatar payload is missing"
            }
            require(!it.groupWallpaperSigned || it.groupWallpaperDataB64 != null) {
                "signed group wallpaper payload is missing"
            }
        }
    }

    fun inviteToJson(invite: GroupInvite): JSONObject = JSONObject().apply {
        put("type", TYPE_INVITE)
        put("version", VERSION)
        put("invite_id", invite.inviteId)
        put("group_id", invite.groupId)
        put("title", invite.title)
        put("description", invite.description)
        put("admin_only_posting", invite.adminOnlyPosting)
        if (invite.torOnlyGroup) put("tor_only_group", true)
        invite.groupAvatarDataB64?.let { put("group_avatar_data", it) }
        if (invite.groupAvatarSigned) put("group_avatar_signed", true)
        invite.groupWallpaperDataB64?.let { put("group_wallpaper_data", it) }
        if (invite.groupWallpaperSigned) put("group_wallpaper_signed", true)
        put("epoch", invite.epoch)
        put("epoch_secret", invite.epochSecretBase64)
        put("owner_fingerprint", invite.ownerFingerprint)
        put("sender_fingerprint", invite.senderFingerprint)
        put("sender_signing_key", invite.senderSigningKey)
        put("coordinator_fingerprint", invite.coordinatorFingerprint)
        putNullable("control_head", invite.controlHead)
        put("history_cursors", JSONObject().apply {
            invite.historyCursors.toSortedMap().forEach { (deviceId, sequence) ->
                put(deviceId, sequence)
            }
        })
        put("owner_transitions", JSONArray().apply {
            invite.ownerTransitions.forEach { put(GroupOwnerLineage.toJson(it)) }
        })
        put("created_at_ms", invite.createdAtMs)
        put("roster_size", invite.rosterSize)
        put("crypto_suite", invite.cryptoSuite)
        put("members", JSONArray().apply {
            invite.members.forEach { member ->
                put(JSONObject().apply {
                    put("fingerprint", member.fingerprint)
                    put("peer_name", member.peerName)
                    put("device_id", member.deviceId)
                    put("signing_key", member.signingKey)
                    put("role", member.role)
                    put("status", member.status)
                })
            }
        })
        put("signature", invite.signatureBase64)
    }

    fun newInviteId(): String = UUID.randomUUID().toString()

    fun requireBoundedWire(frame: String) {
        require(frame.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_BYTES) {
            "group frame exceeds maximum size"
        }
    }

    private fun requireTypeAndVersion(json: JSONObject, expectedType: String) {
        require(json.optString("type") == expectedType) { "unexpected group frame type" }
        require(json.optInt("version", -1) == VERSION) { "unsupported group frame version" }
    }
}

class GroupEventFactory(
    private val crypto: GroupCryptoProvider,
) {
    fun create(
        groupId: String,
        epoch: Long,
        epochSecret: ByteArray,
        kind: GroupEventKind,
        authorFingerprint: String,
        authorDeviceId: String,
        authorSequence: Long,
        previousAuthorEvent: String?,
        controlHead: String?,
        hlcPhysicalMs: Long,
        hlcLogical: Int,
        plaintextPayload: JSONObject,
        targetEventId: String? = null,
        expiresAtMs: Long = 0L,
        cryptoSuite: String = GroupWireProtocol.SUITE_V1,
        rosterHash: String? = null,
    ): GroupWireEvent {
        require(groupId.isNotBlank() && epoch > 0 && authorSequence > 0)
        if (cryptoSuite == GroupWireProtocol.SUITE_V2) {
            require(!rosterHash.isNullOrBlank()) { "rosterHash is required for v2 crypto suite" }
        }
        val signingKey = GroupIdentitySignatures.localVerificationKey()
        require(signingKey.isNotBlank()) { "local group signing identity is unavailable" }
        val aadTemplate = GroupWireEvent(
            groupId = groupId,
            eventId = "",
            epoch = epoch,
            kind = kind,
            authorFingerprint = authorFingerprint,
            authorDeviceId = authorDeviceId,
            authorSigningKey = signingKey,
            authorSequence = authorSequence,
            previousAuthorEvent = previousAuthorEvent,
            controlHead = controlHead,
            hlcPhysicalMs = hlcPhysicalMs,
            hlcLogical = hlcLogical,
            targetEventId = targetEventId,
            nonceBase64 = "",
            ciphertextBase64 = "",
            signatureBase64 = "",
            cryptoSuite = cryptoSuite,
            expiresAtMs = expiresAtMs,
        )
        val plaintext = plaintextPayload.toString().toByteArray(Charsets.UTF_8)
        require(plaintext.size <= 256 * 1024) { "group event plaintext is too large" }
        val protected = crypto.protect(epochSecret, aadTemplate.authenticatedData(rosterHash), plaintext)
        val unsigned = aadTemplate.copy(
            nonceBase64 = protected.nonceBase64,
            ciphertextBase64 = protected.ciphertextBase64,
        )
        val eventId = unsigned.computedEventId()
        val withId = unsigned.copy(eventId = eventId)
        val signature = GroupIdentitySignatures.sign(withId.canonicalForSignature())
        require(signature.isNotBlank()) { "failed to sign group event" }
        return withId.copy(signatureBase64 = signature)
    }

    fun decrypt(
        event: GroupWireEvent,
        epochSecret: ByteArray,
        rosterHash: String? = null,
    ): JSONObject {
        require(event.cryptoSuite in crypto.supportedSuites) { "unsupported group crypto suite: ${event.cryptoSuite}" }
        if (event.cryptoSuite == GroupWireProtocol.SUITE_V2) {
            require(!rosterHash.isNullOrBlank()) { "rosterHash is required for v2 crypto suite" }
        }
        val plaintext = crypto.unprotect(
            epochSecret,
            event.authenticatedData(rosterHash),
            ProtectedGroupPayload(
                nonceBase64 = event.nonceBase64,
                ciphertextBase64 = event.ciphertextBase64,
            ),
        )
        require(plaintext.size <= 256 * 1024) { "decrypted group payload is too large" }
        return JSONObject(plaintext.toString(Charsets.UTF_8))
    }
}

enum class GroupEventKind(val wireName: String) {
    MESSAGE("message"),
    POLL("poll"),
    POLL_VOTE("poll_vote"),
    MEDIA("media"),
    REPLY("reply"),
    EDIT("edit"),
    DELETE("delete"),
    REACTION_ADD("reaction_add"),
    REACTION_REMOVE("reaction_remove"),
    PIN("pin"),
    UNPIN("unpin"),
    READ_RECEIPT("read_receipt"),
    TYPING("typing"),
    GROUP_UPDATED("group_updated"),
    MEMBER_ADDED("member_added"),
    MEMBER_REMOVED("member_removed"),
    MEMBER_REMOVAL_PROPOSED("member_removal_proposed"),
    ROLE_CHANGED("role_changed"),
    MEMBER_RESTRICTED("member_restricted"),
    OWNERSHIP_TRANSFERRED("ownership_transferred"),
    SYSTEM("system");

    companion object {
        fun fromWire(value: String): GroupEventKind =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("unsupported group event kind: $value")
    }
}

data class GroupWireEvent(
    val groupId: String,
    val eventId: String,
    val epoch: Long,
    val kind: GroupEventKind,
    val authorFingerprint: String,
    val authorDeviceId: String,
    val authorSigningKey: String,
    val authorSequence: Long,
    val previousAuthorEvent: String?,
    val controlHead: String?,
    val hlcPhysicalMs: Long,
    val hlcLogical: Int,
    val targetEventId: String?,
    val nonceBase64: String,
    val ciphertextBase64: String,
    val signatureBase64: String,
    val cryptoSuite: String,
    val expiresAtMs: Long = 0L,
    val isTombstoned: Boolean = false,
) {
    fun authenticatedData(rosterHash: String? = null): ByteArray = if (cryptoSuite == GroupWireProtocol.SUITE_V2) {
        buildString {
            append("2pchat-group-aad-v2\n")
            append(groupId).append('\n')
            append(epoch).append('\n')
            append(kind.wireName).append('\n')
            append(authorDeviceId).append('\n')
            append(authorSequence).append('\n')
            append(controlHead.orEmpty()).append('\n')
            append(rosterHash.orEmpty())
        }.toByteArray(Charsets.UTF_8)
    } else {
        buildString {
            append("2pchat-group-aad-v1\n")
            append(groupId).append('\n')
            append(epoch).append('\n')
            append(kind.wireName).append('\n')
            append(authorDeviceId).append('\n')
            append(authorSequence).append('\n')
            append(controlHead.orEmpty())
        }.toByteArray(Charsets.UTF_8)
    }

    fun canonicalForSignature(): String = buildString {
        append(GroupIdentitySignatures.DOMAIN).append('\n')
        append(GroupWireProtocol.VERSION).append('\n')
        append(groupId).append('\n')
        append(epoch).append('\n')
        append(kind.wireName).append('\n')
        append(authorFingerprint).append('\n')
        append(authorDeviceId).append('\n')
        append(authorSigningKey).append('\n')
        append(authorSequence).append('\n')
        append(previousAuthorEvent.orEmpty()).append('\n')
        append(controlHead.orEmpty()).append('\n')
        append(hlcPhysicalMs).append('\n')
        append(hlcLogical).append('\n')
        append(targetEventId.orEmpty()).append('\n')
        append(nonceBase64).append('\n')
        append(ciphertextBase64).append('\n')
        append(cryptoSuite).append('\n')
        append(expiresAtMs)
    }

    fun computedEventId(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalForSignature().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifySignature(expectedSigningKey: String): Boolean =
        expectedSigningKey == authorSigningKey &&
            GroupIdentitySignatures.verify(
                expectedSigningKey,
                canonicalForSignature(),
                signatureBase64,
            )
}

data class GroupInviteMember(
    val fingerprint: String,
    val peerName: String,
    val deviceId: String,
    val signingKey: String,
    val role: String,
    val status: String,
)

data class GroupInvite(
    val inviteId: String,
    val groupId: String,
    val title: String,
    val description: String,
    val epoch: Long,
    val epochSecretBase64: String,
    val ownerFingerprint: String,
    val senderFingerprint: String,
    val senderSigningKey: String,
    val coordinatorFingerprint: String,
    val controlHead: String?,
    val historyCursors: Map<String, Long>,
    val ownerTransitions: List<GroupOwnerTransitionCertificate> = emptyList(),
    val createdAtMs: Long,
    val rosterSize: Int,
    val members: List<GroupInviteMember>,
    val cryptoSuite: String,
    val signatureBase64: String,
    val adminOnlyPosting: Boolean = false,
    val torOnlyGroup: Boolean = false,
    val groupAvatarDataB64: String? = null,
    val groupAvatarSigned: Boolean = false,
    val groupWallpaperDataB64: String? = null,
    val groupWallpaperSigned: Boolean = false,
) {
    fun canonicalForSignature(): String = buildString {
        append("2pchat-group-invite-signature-v1\n")
        append(GroupWireProtocol.VERSION).append('\n')
        append(inviteId).append('\n')
        append(groupId).append('\n')
        append(title).append('\n')
        append(description).append('\n')
        append(epoch).append('\n')
        append(epochSecretBase64).append('\n')
        append(ownerFingerprint).append('\n')
        append(senderFingerprint).append('\n')
        append(senderSigningKey).append('\n')
        append(coordinatorFingerprint).append('\n')
        append(controlHead.orEmpty()).append('\n')
        historyCursors.toSortedMap().forEach { (deviceId, sequence) ->
            append(deviceId).append('=').append(sequence).append('\n')
        }
        append(ownerTransitions.size).append('\n')
        ownerTransitions.forEach { transition ->
            append(transition.canonicalForSignature())
            append(transition.signatureBase64).append('\n')
        }
        append(createdAtMs).append('\n')
        append(rosterSize).append('\n')
        append(cryptoSuite).append('\n')
        members.sortedBy { it.fingerprint }.forEach { member ->
            append(member.fingerprint).append('|')
            append(member.peerName.replace("|", "")).append('|')
            append(member.deviceId).append('|')
            append(member.signingKey).append('|')
            append(member.role).append('|')
            append(member.status).append('\n')
        }
        // Legacy v1 invites did not bind avatar data. They remain verifiable, but
        // receivers ignore their untrusted avatar unless this signed marker exists.
        if (groupAvatarSigned) {
            requireNotNull(groupAvatarDataB64)
            append("group_avatar_signed=v1\n")
            append("group_avatar_data=").append(groupAvatarDataB64).append('\n')
        }
        if (groupWallpaperSigned) {
            requireNotNull(groupWallpaperDataB64)
            append("group_wallpaper_signed=v1\n")
            append("group_wallpaper_data=").append(groupWallpaperDataB64).append('\n')
        }
    }

    fun verifySignature(): Boolean = GroupIdentitySignatures.verify(
        senderSigningKey,
        canonicalForSignature(),
        signatureBase64,
    )
}

private fun JSONObject.requiredText(key: String, maxLength: Int): String =
    optString(key).also {
        require(it.isNotBlank() && it.length <= maxLength) { "invalid $key" }
        require(!it.contains('\u0000')) { "invalid $key" }
    }

private fun JSONObject.requiredToken(key: String, maxLength: Int): String =
    requiredText(key, maxLength).also {
        require(it.all { char -> char.isLetterOrDigit() || char in "-_.:=" }) {
            "invalid $key token"
        }
    }

private fun JSONObject.optNullableToken(key: String, maxLength: Int): String? =
    if (!has(key) || isNull(key)) {
        null
    } else {
        optString(key).takeIf { it.isNotBlank() }?.also {
            require(it.length <= maxLength) { "invalid $key" }
            require(it.all { char -> char.isLetterOrDigit() || char in "-_.:=" }) {
                "invalid $key token"
            }
        }
    }

private fun JSONObject.requiredPositiveLong(key: String): Long =
    optLong(key, -1L).also { require(it > 0L) { "invalid $key" } }

private fun JSONObject.putNullable(key: String, value: String?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}
