package com.example.twopchat.group.model

enum class GroupAction(val requiredPermission: GroupPermission?) {
    POST_MESSAGE(GroupPermission.POST_MESSAGES),
    POST_MEDIA(GroupPermission.POST_MEDIA),
    POST_LINK(GroupPermission.POST_LINKS),
    ADD_REACTION(GroupPermission.ADD_REACTIONS),
    EDIT_OWN_MESSAGE(GroupPermission.EDIT_OWN_MESSAGES),
    DELETE_OWN_MESSAGE(GroupPermission.DELETE_OWN_MESSAGES),
    EDIT_ANY_MESSAGE(GroupPermission.EDIT_ANY_MESSAGES),
    DELETE_ANY_MESSAGE(GroupPermission.DELETE_ANY_MESSAGES),
    PIN_MESSAGE(GroupPermission.PIN_MESSAGES),
    MANAGE_GROUP_INFO(GroupPermission.MANAGE_GROUP_INFO),
    INVITE_MEMBER(GroupPermission.INVITE_MEMBERS),
    REMOVE_MEMBER(GroupPermission.REMOVE_MEMBERS),
    RESTRICT_MEMBER(GroupPermission.RESTRICT_MEMBERS),
    BAN_MEMBER(GroupPermission.BAN_MEMBERS),
    ASSIGN_ROLE(GroupPermission.ASSIGN_ROLES),
    MANAGE_INVITE_LINKS(GroupPermission.MANAGE_INVITE_LINKS),
    VIEW_ADMIN_LOG(GroupPermission.VIEW_ADMIN_LOG),
    TRANSFER_OWNERSHIP(null),
}

enum class PolicyDenialReason {
    ACTOR_NOT_ACTIVE,
    PERMISSION_MISSING,
    TARGET_NOT_FOUND,
    TARGET_NOT_ACTIVE,
    CANNOT_ACT_ON_SELF,
    OWNER_IS_IMMUTABLE,
    ROLE_CEILING,
    OWNER_TRANSFER_REQUIRED,
    ACTOR_IS_NOT_OWNER,
    INVALID_OWNER_COUNT,
    INVALID_TARGET_ROLE,
    DUPLICATE_MEMBER,
    GROUP_MISMATCH,
}

data class PolicyDecision(
    val allowed: Boolean,
    val denialReason: PolicyDenialReason? = null,
) {
    init {
        require(allowed == (denialReason == null)) {
            "allowed decisions must not have a denial reason and denied decisions must have one"
        }
    }

    companion object {
        val ALLOW = PolicyDecision(allowed = true)

        fun deny(reason: PolicyDenialReason): PolicyDecision =
            PolicyDecision(allowed = false, denialReason = reason)
    }
}

data class OwnershipTransferResult(
    val decision: PolicyDecision,
    val members: List<GroupMember>,
)

object GroupRolePolicy {
    private val memberPermissions = GroupPermissionSet.of(
        GroupPermission.POST_MESSAGES,
        GroupPermission.POST_MEDIA,
        GroupPermission.POST_LINKS,
        GroupPermission.ADD_REACTIONS,
        GroupPermission.EDIT_OWN_MESSAGES,
        GroupPermission.DELETE_OWN_MESSAGES,
    )

    private val moderatorPermissions = GroupPermissionSet.of(
        *memberPermissions.asSet().toTypedArray(),
        GroupPermission.DELETE_ANY_MESSAGES,
        GroupPermission.PIN_MESSAGES,
        GroupPermission.RESTRICT_MEMBERS,
        GroupPermission.BAN_MEMBERS,
        GroupPermission.VIEW_ADMIN_LOG,
    )

    private val administratorPermissions = GroupPermissionSet.of(
        *GroupPermission.entries
            .filterNot { it == GroupPermission.EDIT_ANY_MESSAGES }
            .toTypedArray(),
        GroupPermission.EDIT_ANY_MESSAGES,
    )

    fun defaultPermissions(role: GroupRole): GroupPermissionSet = when (role) {
        GroupRole.OWNER -> GroupPermissionSet.ALL
        GroupRole.ADMINISTRATOR -> administratorPermissions
        GroupRole.MODERATOR -> moderatorPermissions
        GroupRole.MEMBER -> memberPermissions
        GroupRole.RESTRICTED,
        GroupRole.BANNED,
        GroupRole.LEFT,
        -> GroupPermissionSet.NONE
    }

    fun canPostUnderGroupPolicy(adminOnlyPosting: Boolean, role: GroupRole): Boolean =
        !adminOnlyPosting || role == GroupRole.OWNER || role == GroupRole.ADMINISTRATOR

    fun canPerform(actor: GroupMember, action: GroupAction): PolicyDecision {
        if (!actor.isActive || actor.role == GroupRole.RESTRICTED) {
            return PolicyDecision.deny(PolicyDenialReason.ACTOR_NOT_ACTIVE)
        }
        if (action == GroupAction.TRANSFER_OWNERSHIP) {
            return if (actor.role == GroupRole.OWNER) {
                PolicyDecision.ALLOW
            } else {
                PolicyDecision.deny(PolicyDenialReason.ACTOR_IS_NOT_OWNER)
            }
        }
        val required = checkNotNull(action.requiredPermission)
        return if (required in effectivePermissions(actor)) {
            PolicyDecision.ALLOW
        } else {
            PolicyDecision.deny(PolicyDenialReason.PERMISSION_MISSING)
        }
    }

    fun canEditMessage(actor: GroupMember, authorUserId: UserId): PolicyDecision =
        canPerform(
            actor,
            if (actor.userId == authorUserId) {
                GroupAction.EDIT_OWN_MESSAGE
            } else {
                GroupAction.EDIT_ANY_MESSAGE
            },
        )

    fun canDeleteMessage(actor: GroupMember, authorUserId: UserId): PolicyDecision =
        canPerform(
            actor,
            if (actor.userId == authorUserId) {
                GroupAction.DELETE_OWN_MESSAGE
            } else {
                GroupAction.DELETE_ANY_MESSAGE
            },
        )

    fun canPinMessage(actor: GroupMember): PolicyDecision =
        canPerform(actor, GroupAction.PIN_MESSAGE)

    fun canRemoveMember(actor: GroupMember, target: GroupMember): PolicyDecision =
        canModerate(actor, target, GroupAction.REMOVE_MEMBER)

    fun canRestrictMember(actor: GroupMember, target: GroupMember): PolicyDecision =
        canModerate(actor, target, GroupAction.RESTRICT_MEMBER)

    fun canBanMember(actor: GroupMember, target: GroupMember): PolicyDecision =
        canModerate(actor, target, GroupAction.BAN_MEMBER)

    fun canAssignRole(
        actor: GroupMember,
        target: GroupMember,
        desiredRole: GroupRole,
    ): PolicyDecision {
        validateSameGroup(actor, target)?.let { return it }
        canPerform(actor, GroupAction.ASSIGN_ROLE).takeUnless { it.allowed }?.let { return it }
        if (!target.isActive) return PolicyDecision.deny(PolicyDenialReason.TARGET_NOT_ACTIVE)
        if (actor.userId == target.userId) {
            return PolicyDecision.deny(PolicyDenialReason.CANNOT_ACT_ON_SELF)
        }
        if (target.role == GroupRole.OWNER) {
            return PolicyDecision.deny(PolicyDenialReason.OWNER_IS_IMMUTABLE)
        }
        if (desiredRole == GroupRole.OWNER) {
            return PolicyDecision.deny(PolicyDenialReason.OWNER_TRANSFER_REQUIRED)
        }
        if (desiredRole !in assignableRoles) {
            return PolicyDecision.deny(PolicyDenialReason.INVALID_TARGET_ROLE)
        }
        if (roleRank(actor.role) <= roleRank(target.role) ||
            roleRank(actor.role) <= roleRank(desiredRole)
        ) {
            return PolicyDecision.deny(PolicyDenialReason.ROLE_CEILING)
        }
        return PolicyDecision.ALLOW
    }

    fun validateRoster(members: List<GroupMember>): PolicyDecision {
        if (members.map(GroupMember::userId).distinct().size != members.size) {
            return PolicyDecision.deny(PolicyDenialReason.DUPLICATE_MEMBER)
        }
        if (members.map(GroupMember::groupId).distinct().size > 1) {
            return PolicyDecision.deny(PolicyDenialReason.GROUP_MISMATCH)
        }
        val ownerCount = members.count { it.role == GroupRole.OWNER && it.isActive }
        return if (ownerCount == 1) {
            PolicyDecision.ALLOW
        } else {
            PolicyDecision.deny(PolicyDenialReason.INVALID_OWNER_COUNT)
        }
    }

    /**
     * Ownership transfer is the only operation allowed to create a new owner.
     * It atomically demotes the old owner to administrator and promotes one
     * active member, preserving the exactly-one-owner invariant.
     */
    fun transferOwnership(
        members: List<GroupMember>,
        actorUserId: UserId,
        targetUserId: UserId,
    ): OwnershipTransferResult {
        val rosterDecision = validateRoster(members)
        if (!rosterDecision.allowed) return OwnershipTransferResult(rosterDecision, members)

        val actor = members.firstOrNull { it.userId == actorUserId }
            ?: return deniedTransfer(PolicyDenialReason.TARGET_NOT_FOUND, members)
        val target = members.firstOrNull { it.userId == targetUserId }
            ?: return deniedTransfer(PolicyDenialReason.TARGET_NOT_FOUND, members)
        if (actor.role != GroupRole.OWNER || !actor.isActive) {
            return deniedTransfer(PolicyDenialReason.ACTOR_IS_NOT_OWNER, members)
        }
        if (actor.userId == target.userId) {
            return deniedTransfer(PolicyDenialReason.CANNOT_ACT_ON_SELF, members)
        }
        if (!target.isActive || target.role == GroupRole.RESTRICTED) {
            return deniedTransfer(PolicyDenialReason.TARGET_NOT_ACTIVE, members)
        }
        if (target.role !in assignableRoles) {
            return deniedTransfer(PolicyDenialReason.INVALID_TARGET_ROLE, members)
        }

        val transferred = members.map { member ->
            when (member.userId) {
                actor.userId -> member.copy(
                    role = GroupRole.ADMINISTRATOR,
                    permissions = defaultPermissions(GroupRole.ADMINISTRATOR),
                )
                target.userId -> member.copy(
                    role = GroupRole.OWNER,
                    permissions = defaultPermissions(GroupRole.OWNER),
                )
                else -> member
            }
        }
        val finalDecision = validateRoster(transferred)
        return OwnershipTransferResult(finalDecision, if (finalDecision.allowed) transferred else members)
    }

    private fun canModerate(
        actor: GroupMember,
        target: GroupMember,
        action: GroupAction,
    ): PolicyDecision {
        validateSameGroup(actor, target)?.let { return it }
        canPerform(actor, action).takeUnless { it.allowed }?.let { return it }
        if (!target.isActive) return PolicyDecision.deny(PolicyDenialReason.TARGET_NOT_ACTIVE)
        if (actor.userId == target.userId) {
            return PolicyDecision.deny(PolicyDenialReason.CANNOT_ACT_ON_SELF)
        }
        if (target.role == GroupRole.OWNER) {
            return PolicyDecision.deny(PolicyDenialReason.OWNER_IS_IMMUTABLE)
        }
        if (roleRank(actor.role) <= roleRank(target.role)) {
            return PolicyDecision.deny(PolicyDenialReason.ROLE_CEILING)
        }
        return PolicyDecision.ALLOW
    }

    private fun effectivePermissions(member: GroupMember): GroupPermissionSet =
        if (member.role == GroupRole.OWNER) GroupPermissionSet.ALL else member.permissions

    private fun validateSameGroup(
        actor: GroupMember,
        target: GroupMember,
    ): PolicyDecision? =
        if (actor.groupId == target.groupId) {
            null
        } else {
            PolicyDecision.deny(PolicyDenialReason.GROUP_MISMATCH)
        }

    private fun roleRank(role: GroupRole): Int = when (role) {
        GroupRole.OWNER -> 4
        GroupRole.ADMINISTRATOR -> 3
        GroupRole.MODERATOR -> 2
        GroupRole.MEMBER -> 1
        GroupRole.RESTRICTED -> 0
        GroupRole.BANNED,
        GroupRole.LEFT,
        -> -1
    }

    private fun deniedTransfer(
        reason: PolicyDenialReason,
        members: List<GroupMember>,
    ): OwnershipTransferResult =
        OwnershipTransferResult(PolicyDecision.deny(reason), members)

    private val assignableRoles = setOf(
        GroupRole.ADMINISTRATOR,
        GroupRole.MODERATOR,
        GroupRole.MEMBER,
    )
}
