package com.example.twopchat.group.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRolePolicyTest {
    @Test
    fun adminOnlyPostingAllowsOnlyOwnerAndAdministrator() {
        GroupRole.entries.forEach { role ->
            assertEquals(
                role.name,
                role == GroupRole.OWNER || role == GroupRole.ADMINISTRATOR,
                GroupRolePolicy.canPostUnderGroupPolicy(true, role),
            )
            assertTrue(
                role.name,
                GroupRolePolicy.canPostUnderGroupPolicy(false, role),
            )
        }
    }

    @Test
    fun defaultPermissionMatrixIsCompleteAndLeastPrivilege() {
        val expected = mapOf(
            GroupRole.OWNER to GroupPermission.entries.toSet(),
            GroupRole.ADMINISTRATOR to GroupPermission.entries.toSet(),
            GroupRole.MODERATOR to setOf(
                GroupPermission.POST_MESSAGES,
                GroupPermission.POST_MEDIA,
                GroupPermission.POST_LINKS,
                GroupPermission.ADD_REACTIONS,
                GroupPermission.EDIT_OWN_MESSAGES,
                GroupPermission.DELETE_OWN_MESSAGES,
                GroupPermission.DELETE_ANY_MESSAGES,
                GroupPermission.PIN_MESSAGES,
                GroupPermission.RESTRICT_MEMBERS,
                GroupPermission.BAN_MEMBERS,
                GroupPermission.VIEW_ADMIN_LOG,
            ),
            GroupRole.MEMBER to setOf(
                GroupPermission.POST_MESSAGES,
                GroupPermission.POST_MEDIA,
                GroupPermission.POST_LINKS,
                GroupPermission.ADD_REACTIONS,
                GroupPermission.EDIT_OWN_MESSAGES,
                GroupPermission.DELETE_OWN_MESSAGES,
            ),
            GroupRole.RESTRICTED to emptySet(),
            GroupRole.BANNED to emptySet(),
            GroupRole.LEFT to emptySet(),
        )

        GroupRole.entries.forEach { role ->
            assertEquals(role.name, expected.getValue(role), GroupRolePolicy.defaultPermissions(role).asSet())
        }
    }

    @Test
    fun everyPermissionBackedActionUsesTheExplicitMemberMask() {
        GroupAction.entries
            .filter { it.requiredPermission != null }
            .forEach { action ->
                val required = checkNotNull(action.requiredPermission)
                val withPermission = member(
                    "actor",
                    GroupRole.MEMBER,
                    GroupPermissionSet.of(required),
                )
                val withoutPermission = withPermission.copy(permissions = GroupPermissionSet.NONE)

                assertTrue(action.name, GroupRolePolicy.canPerform(withPermission, action).allowed)
                assertEquals(
                    action.name,
                    PolicyDenialReason.PERMISSION_MISSING,
                    GroupRolePolicy.canPerform(withoutPermission, action).denialReason,
                )
            }
    }

    @Test
    fun restrictedMemberKeepsMemberRoleAndOnlyLosesRemovedCapabilities() {
        val restrictedMember = member(
            "restricted-member",
            GroupRole.MEMBER,
            GroupRolePolicy.defaultPermissions(GroupRole.MEMBER) -
                GroupPermission.POST_LINKS,
        )

        assertTrue(
            GroupRolePolicy.canPerform(restrictedMember, GroupAction.POST_MESSAGE).allowed,
        )
        assertEquals(
            PolicyDenialReason.PERMISSION_MISSING,
            GroupRolePolicy.canPerform(restrictedMember, GroupAction.POST_LINK).denialReason,
        )
    }

    @Test
    fun restrictedBannedAndLeftActorsCannotUseInjectedPermissions() {
        listOf(GroupRole.RESTRICTED, GroupRole.BANNED, GroupRole.LEFT).forEach { role ->
            val actor = member("actor-$role", role, GroupPermissionSet.ALL)
            GroupAction.entries.forEach { action ->
                assertFalse("$role unexpectedly performed $action", GroupRolePolicy.canPerform(actor, action).allowed)
            }
        }
    }

    @Test
    fun ownershipTransferActionIsOwnerOnly() {
        GroupRole.entries.forEach { role ->
            val decision = GroupRolePolicy.canPerform(
                member("actor-$role", role, GroupRolePolicy.defaultPermissions(role)),
                GroupAction.TRANSFER_OWNERSHIP,
            )
            assertEquals(role == GroupRole.OWNER, decision.allowed)
        }
    }

    @Test
    fun ownAndForeignMessagePermissionsAreSeparated() {
        val local = UserId("local")
        val other = UserId("other")
        val ownOnly = member(
            local.value,
            GroupRole.MEMBER,
            GroupPermissionSet.of(
                GroupPermission.EDIT_OWN_MESSAGES,
                GroupPermission.DELETE_OWN_MESSAGES,
            ),
        )

        assertTrue(GroupRolePolicy.canEditMessage(ownOnly, local).allowed)
        assertFalse(GroupRolePolicy.canEditMessage(ownOnly, other).allowed)
        assertTrue(GroupRolePolicy.canDeleteMessage(ownOnly, local).allowed)
        assertFalse(GroupRolePolicy.canDeleteMessage(ownOnly, other).allowed)

        val moderator = ownOnly.copy(
            role = GroupRole.MODERATOR,
            permissions = ownOnly.permissions +
                GroupPermission.EDIT_ANY_MESSAGES +
                GroupPermission.DELETE_ANY_MESSAGES,
        )
        assertTrue(GroupRolePolicy.canEditMessage(moderator, other).allowed)
        assertTrue(GroupRolePolicy.canDeleteMessage(moderator, other).allowed)
    }

    @Test
    fun roleAssignmentHonoursStrictRoleCeilingForEveryRolePair() {
        val activeRoles = listOf(
            GroupRole.OWNER,
            GroupRole.ADMINISTRATOR,
            GroupRole.MODERATOR,
            GroupRole.MEMBER,
            GroupRole.RESTRICTED,
        )
        val rank = mapOf(
            GroupRole.OWNER to 4,
            GroupRole.ADMINISTRATOR to 3,
            GroupRole.MODERATOR to 2,
            GroupRole.MEMBER to 1,
            GroupRole.RESTRICTED to 0,
        )
        val desiredRoles = listOf(
            GroupRole.ADMINISTRATOR,
            GroupRole.MODERATOR,
            GroupRole.MEMBER,
        )

        activeRoles.forEach { actorRole ->
            activeRoles.forEach { targetRole ->
                desiredRoles.forEach { desiredRole ->
                    val actor = member(
                        "actor-$actorRole",
                        actorRole,
                        GroupPermissionSet.of(GroupPermission.ASSIGN_ROLES),
                    )
                    val target = member(
                        "target-$targetRole",
                        targetRole,
                        GroupRolePolicy.defaultPermissions(targetRole),
                    )
                    val expected = actorRole != GroupRole.RESTRICTED &&
                        targetRole != GroupRole.OWNER &&
                        rank.getValue(actorRole) > rank.getValue(targetRole) &&
                        rank.getValue(actorRole) > rank.getValue(desiredRole)
                    assertEquals(
                        "$actorRole -> $targetRole as $desiredRole",
                        expected,
                        GroupRolePolicy.canAssignRole(actor, target, desiredRole).allowed,
                    )
                }
            }
        }
    }

    @Test
    fun ownerCanOnlyBeCreatedByOwnershipTransfer() {
        val owner = member("owner", GroupRole.OWNER)
        val target = member("target", GroupRole.ADMINISTRATOR)

        val decision = GroupRolePolicy.canAssignRole(owner, target, GroupRole.OWNER)

        assertFalse(decision.allowed)
        assertEquals(PolicyDenialReason.OWNER_TRANSFER_REQUIRED, decision.denialReason)
    }

    @Test
    fun moderationMatrixProtectsSameAndHigherRoles() {
        val roles = listOf(
            GroupRole.OWNER,
            GroupRole.ADMINISTRATOR,
            GroupRole.MODERATOR,
            GroupRole.MEMBER,
            GroupRole.RESTRICTED,
        )
        val rank = mapOf(
            GroupRole.OWNER to 4,
            GroupRole.ADMINISTRATOR to 3,
            GroupRole.MODERATOR to 2,
            GroupRole.MEMBER to 1,
            GroupRole.RESTRICTED to 0,
        )
        roles.forEach { actorRole ->
            roles.forEach { targetRole ->
                val actor = member(
                    "actor-$actorRole",
                    actorRole,
                    GroupPermissionSet.of(
                        GroupPermission.REMOVE_MEMBERS,
                        GroupPermission.RESTRICT_MEMBERS,
                        GroupPermission.BAN_MEMBERS,
                    ),
                )
                val target = member("target-$targetRole", targetRole)
                val expected = actorRole != GroupRole.RESTRICTED &&
                    targetRole != GroupRole.OWNER &&
                    rank.getValue(actorRole) > rank.getValue(targetRole)

                assertEquals(expected, GroupRolePolicy.canRemoveMember(actor, target).allowed)
                assertEquals(expected, GroupRolePolicy.canRestrictMember(actor, target).allowed)
                assertEquals(expected, GroupRolePolicy.canBanMember(actor, target).allowed)
            }
        }
    }

    @Test
    fun actorCannotModerateOrAssignItself() {
        val actor = member(
            "actor",
            GroupRole.OWNER,
            GroupPermissionSet.ALL,
        )

        assertEquals(
            PolicyDenialReason.CANNOT_ACT_ON_SELF,
            GroupRolePolicy.canRestrictMember(actor, actor).denialReason,
        )
        assertEquals(
            PolicyDenialReason.CANNOT_ACT_ON_SELF,
            GroupRolePolicy.canAssignRole(actor, actor, GroupRole.MEMBER).denialReason,
        )
    }

    @Test
    fun crossGroupModerationIsRejectedBeforeRoleChecks() {
        val actor = member("owner", GroupRole.OWNER)
        val target = member("member", GroupRole.MEMBER).copy(groupId = ConversationId("other-group"))

        assertEquals(
            PolicyDenialReason.GROUP_MISMATCH,
            GroupRolePolicy.canBanMember(actor, target).denialReason,
        )
    }

    @Test
    fun rosterRequiresExactlyOneActiveOwnerAndUniqueUsers() {
        val owner = member("owner", GroupRole.OWNER)
        val regular = member("member", GroupRole.MEMBER)

        assertTrue(GroupRolePolicy.validateRoster(listOf(owner, regular)).allowed)
        assertEquals(
            PolicyDenialReason.INVALID_OWNER_COUNT,
            GroupRolePolicy.validateRoster(listOf(regular)).denialReason,
        )
        assertEquals(
            PolicyDenialReason.INVALID_OWNER_COUNT,
            GroupRolePolicy.validateRoster(listOf(owner, owner.copy(userId = UserId("owner-2")))).denialReason,
        )
        assertEquals(
            PolicyDenialReason.DUPLICATE_MEMBER,
            GroupRolePolicy.validateRoster(listOf(owner, owner.copy(role = GroupRole.MEMBER))).denialReason,
        )
    }

    @Test
    fun ownershipTransferIsAtomicAndPreservesSingleOwner() {
        val before = listOf(
            member("owner", GroupRole.OWNER),
            member("admin", GroupRole.ADMINISTRATOR),
            member("member", GroupRole.MEMBER),
        )

        val result = GroupRolePolicy.transferOwnership(
            before,
            actorUserId = UserId("owner"),
            targetUserId = UserId("member"),
        )

        assertTrue(result.decision.allowed)
        assertEquals(1, result.members.count { it.role == GroupRole.OWNER })
        assertEquals(GroupRole.ADMINISTRATOR, result.members.single { it.userId == UserId("owner") }.role)
        assertEquals(GroupRole.OWNER, result.members.single { it.userId == UserId("member") }.role)
        assertEquals(
            GroupPermissionSet.ALL,
            result.members.single { it.userId == UserId("member") }.permissions,
        )
        assertTrue(GroupRolePolicy.validateRoster(result.members).allowed)
    }

    @Test
    fun failedOwnershipTransfersNeverMutateRoster() {
        val before = listOf(
            member("owner", GroupRole.OWNER),
            member("member", GroupRole.MEMBER),
            member("restricted", GroupRole.RESTRICTED),
            member("banned", GroupRole.BANNED),
        )

        listOf(
            UserId("missing") to UserId("member"),
            UserId("member") to UserId("owner"),
            UserId("owner") to UserId("owner"),
            UserId("owner") to UserId("restricted"),
            UserId("owner") to UserId("banned"),
        ).forEach { (actor, target) ->
            val result = GroupRolePolicy.transferOwnership(before, actor, target)
            assertFalse("$actor -> $target unexpectedly succeeded", result.decision.allowed)
            assertSame("denied transfer must return the original immutable roster", before, result.members)
        }
    }

    private fun member(
        id: String,
        role: GroupRole,
        permissions: GroupPermissionSet = GroupRolePolicy.defaultPermissions(role),
    ): GroupMember {
        val removedEpoch = if (role == GroupRole.BANNED || role == GroupRole.LEFT) 5L else null
        return GroupMember(
            groupId = ConversationId("group"),
            userId = UserId(id),
            role = role,
            permissions = permissions,
            joinedEpoch = 1L,
            removedEpoch = removedEpoch,
        )
    }
}
