package com.example.twopchat.group.model

@JvmInline
value class ConversationId(val value: String) {
    init {
        require(value.isNotBlank()) { "conversation id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "user id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class DeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "device id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class EventId(val value: String) {
    init {
        require(value.isNotBlank()) { "event id must not be blank" }
    }

    override fun toString(): String = value
}

enum class ConversationType {
    DIRECT,
    GROUP,
    SAVED,
}

data class Conversation(
    val id: ConversationId,
    val type: ConversationType,
    val title: String,
    val avatarRef: String? = null,
    val currentEpoch: Long = 0L,
    val controlHeadCid: String? = null,
    val createdAt: HybridLogicalClock,
) {
    init {
        require(currentEpoch >= 0L) { "current epoch must not be negative" }
        require(type == ConversationType.SAVED || title.isNotBlank()) {
            "non-saved conversations must have a title"
        }
    }
}

data class UserIdentity(
    val id: UserId,
    val identityFingerprint: String,
    val displayName: String,
) {
    init {
        require(identityFingerprint.isNotBlank()) { "identity fingerprint must not be blank" }
        require(displayName.isNotBlank()) { "display name must not be blank" }
    }
}

data class DeviceIdentity(
    val id: DeviceId,
    val userId: UserId,
    val credential: String,
    val feedPublicKey: String,
    val revokedAtEpoch: Long? = null,
) {
    init {
        require(credential.isNotBlank()) { "device credential must not be blank" }
        require(feedPublicKey.isNotBlank()) { "feed public key must not be blank" }
        require(revokedAtEpoch == null || revokedAtEpoch >= 0L) {
            "revocation epoch must not be negative"
        }
    }

    fun isActiveAt(epoch: Long): Boolean =
        epoch >= 0L && (revokedAtEpoch == null || epoch < revokedAtEpoch)
}

/**
 * OWNER, ADMINISTRATOR, MODERATOR and MEMBER are active membership roles.
 * The remaining values are explicit membership states kept in the same signed
 * role field so a stale device cannot silently regain its former privileges.
 */
enum class GroupRole {
    OWNER,
    ADMINISTRATOR,
    MODERATOR,
    MEMBER,
    RESTRICTED,
    BANNED,
    LEFT,
}

enum class GroupPermission(val bit: Long) {
    POST_MESSAGES(1L shl 0),
    POST_MEDIA(1L shl 1),
    ADD_REACTIONS(1L shl 2),
    EDIT_OWN_MESSAGES(1L shl 3),
    DELETE_OWN_MESSAGES(1L shl 4),
    EDIT_ANY_MESSAGES(1L shl 5),
    DELETE_ANY_MESSAGES(1L shl 6),
    PIN_MESSAGES(1L shl 7),
    MANAGE_GROUP_INFO(1L shl 8),
    INVITE_MEMBERS(1L shl 9),
    REMOVE_MEMBERS(1L shl 10),
    RESTRICT_MEMBERS(1L shl 11),
    BAN_MEMBERS(1L shl 12),
    ASSIGN_ROLES(1L shl 13),
    MANAGE_INVITE_LINKS(1L shl 14),
    VIEW_ADMIN_LOG(1L shl 15),
    POST_LINKS(1L shl 16),
    ;

    companion object {
        val knownBits: Long = entries.fold(0L) { mask, permission -> mask or permission.bit }
    }
}

@JvmInline
value class GroupPermissionSet(val bits: Long) {
    init {
        require(bits and GroupPermission.knownBits.inv() == 0L) {
            "permission set contains unknown bits"
        }
    }

    operator fun contains(permission: GroupPermission): Boolean =
        bits and permission.bit != 0L

    operator fun plus(permission: GroupPermission): GroupPermissionSet =
        GroupPermissionSet(bits or permission.bit)

    operator fun minus(permission: GroupPermission): GroupPermissionSet =
        GroupPermissionSet(bits and permission.bit.inv())

    fun containsAll(other: GroupPermissionSet): Boolean =
        bits and other.bits == other.bits

    fun intersect(other: GroupPermissionSet): GroupPermissionSet =
        GroupPermissionSet(bits and other.bits)

    fun asSet(): Set<GroupPermission> =
        GroupPermission.entries.filterTo(linkedSetOf()) { it in this }

    companion object {
        val NONE = GroupPermissionSet(0L)
        val ALL = GroupPermissionSet(GroupPermission.knownBits)

        fun of(vararg permissions: GroupPermission): GroupPermissionSet =
            GroupPermissionSet(permissions.fold(0L) { mask, permission -> mask or permission.bit })
    }
}

data class GroupMember(
    val groupId: ConversationId,
    val userId: UserId,
    val role: GroupRole,
    val permissions: GroupPermissionSet,
    val joinedEpoch: Long,
    val removedEpoch: Long? = null,
) {
    init {
        require(joinedEpoch >= 0L) { "joined epoch must not be negative" }
        require(removedEpoch == null || removedEpoch >= joinedEpoch) {
            "removed epoch must be at or after joined epoch"
        }
        require(
            (role == GroupRole.BANNED || role == GroupRole.LEFT) == (removedEpoch != null)
        ) {
            "only banned or left members have a removed epoch"
        }
    }

    val isActive: Boolean
        get() = role != GroupRole.BANNED && role != GroupRole.LEFT && removedEpoch == null
}

enum class GroupEventKind {
    TEXT_MESSAGE,
    MEDIA_MESSAGE,
    REACTION,
    MESSAGE_EDIT,
    MESSAGE_DELETE,
    MESSAGE_PIN,
    MEMBER_ADDED,
    MEMBER_REMOVED,
    MEMBER_RESTRICTED,
    MEMBER_BANNED,
    ROLE_CHANGED,
    OWNER_TRANSFERRED,
    GROUP_INFO_CHANGED,
    INVITE_LINK_CHANGED,
    MLS_COMMIT,
    SYSTEM,
}

enum class GroupEventState {
    QUEUED,
    ENCRYPTED,
    REPLICATING,
    REPLICATED,
    DELIVERED,
    READ,
    FAILED,
    REJECTED,
}

/**
 * Hybrid logical time. [nodeId] is only a deterministic final tie-breaker;
 * causal comparison is carried by [physicalTimeMs] and [logicalCounter].
 */
data class HybridLogicalClock(
    val physicalTimeMs: Long,
    val logicalCounter: Int,
    val nodeId: String,
) : Comparable<HybridLogicalClock> {
    init {
        require(physicalTimeMs >= 0L) { "physical time must not be negative" }
        require(logicalCounter in 0..MAX_LOGICAL_COUNTER) {
            "logical counter is outside the wire range"
        }
        require(nodeId.isNotBlank()) { "node id must not be blank" }
    }

    override fun compareTo(other: HybridLogicalClock): Int =
        compareValuesBy(
            this,
            other,
            HybridLogicalClock::physicalTimeMs,
            HybridLogicalClock::logicalCounter,
            HybridLogicalClock::nodeId,
        )

    fun tick(nowMs: Long): HybridLogicalClock {
        require(nowMs >= 0L) { "physical time must not be negative" }
        return if (nowMs > physicalTimeMs) {
            HybridLogicalClock(nowMs, 0, nodeId)
        } else {
            incremented(physicalTimeMs, logicalCounter, nodeId)
        }
    }

    fun observe(
        remote: HybridLogicalClock,
        nowMs: Long,
        localNodeId: String = nodeId,
    ): HybridLogicalClock {
        require(nowMs >= 0L) { "physical time must not be negative" }
        require(localNodeId.isNotBlank()) { "node id must not be blank" }
        val nextPhysical = maxOf(physicalTimeMs, remote.physicalTimeMs, nowMs)
        val baseLogical = when {
            nextPhysical == physicalTimeMs && nextPhysical == remote.physicalTimeMs ->
                maxOf(logicalCounter, remote.logicalCounter)
            nextPhysical == physicalTimeMs -> logicalCounter
            nextPhysical == remote.physicalTimeMs -> remote.logicalCounter
            else -> null
        }
        return if (baseLogical == null) {
            HybridLogicalClock(nextPhysical, 0, localNodeId)
        } else {
            incremented(nextPhysical, baseLogical, localNodeId)
        }
    }

    private fun incremented(
        physical: Long,
        logical: Int,
        outputNodeId: String,
    ): HybridLogicalClock {
        if (logical < MAX_LOGICAL_COUNTER) {
            return HybridLogicalClock(physical, logical + 1, outputNodeId)
        }
        require(physical < Long.MAX_VALUE) { "hybrid logical clock exhausted" }
        return HybridLogicalClock(physical + 1L, 0, outputNodeId)
    }

    companion object {
        const val MAX_LOGICAL_COUNTER = 1_000_000
    }
}

data class GroupEventOrder(
    val timestamp: HybridLogicalClock,
    val authorDeviceId: DeviceId,
    val senderSequence: Long,
    val eventId: EventId,
) : Comparable<GroupEventOrder> {
    init {
        require(senderSequence >= 0L) { "sender sequence must not be negative" }
    }

    override fun compareTo(other: GroupEventOrder): Int {
        val byTimestamp = timestamp.compareTo(other.timestamp)
        if (byTimestamp != 0) return byTimestamp
        val byDevice = authorDeviceId.value.compareTo(other.authorDeviceId.value)
        if (byDevice != 0) return byDevice
        val bySequence = senderSequence.compareTo(other.senderSequence)
        if (bySequence != 0) return bySequence
        return eventId.value.compareTo(other.eventId.value)
    }
}

data class GroupEvent(
    val groupId: ConversationId,
    val id: EventId,
    val authorUserId: UserId,
    val authorDeviceId: DeviceId,
    val senderSequence: Long,
    val epoch: Long,
    val timestamp: HybridLogicalClock,
    val kind: GroupEventKind,
    val state: GroupEventState,
    val replyToEventId: EventId? = null,
    val payloadDigest: String,
) {
    init {
        require(senderSequence >= 0L) { "sender sequence must not be negative" }
        require(epoch >= 0L) { "epoch must not be negative" }
        require(payloadDigest.isNotBlank()) { "payload digest must not be blank" }
    }

    val order: GroupEventOrder
        get() = GroupEventOrder(timestamp, authorDeviceId, senderSequence, id)
}
