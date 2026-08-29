package com.example.twopchat.group.runtime

import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.group.model.GroupRole as DomainGroupRole
import com.example.twopchat.group.ui.GroupMemberPermissions
import com.example.twopchat.group.ui.GroupRole
import com.example.twopchat.group.ui.GroupUiController

class AndroidGroupUiController(
    private val onBackNavigation: () -> Unit,
    private val onOpenGroupNavigation: (String) -> Unit,
    private val onOpenGroupInfoNavigation: (String) -> Unit,
    private val onOpenDirectChatNavigation: (String) -> Unit = {},
) : GroupUiController {
    override fun onBack() = onBackNavigation()

    override fun openGroup(groupId: String) {
        GroupChatCoordinator.activeChatsSubTab = 1
        GroupChatCoordinator.chatState(groupId)
        onOpenGroupNavigation(groupId)
    }

    override fun openGroupInfo(groupId: String) = onOpenGroupInfoNavigation(groupId)

    override fun openDirectChat(peerName: String) = onOpenDirectChatNavigation(peerName)

    override fun setGroupChatActive(groupId: String, active: Boolean) =
        GroupChatCoordinator.setGroupChatActive(groupId, active)

    override fun createGroup(title: String, description: String, contactIds: Set<String>) {
        GroupChatCoordinator.createGroup(title, description, contactIds) { groupId ->
            P2PMessageRelay.runOnMain { onOpenGroupNavigation(groupId) }
        }
    }

    override fun updateGroupInfo(groupId: String, title: String, description: String) =
        GroupChatCoordinator.updateGroupInfo(groupId, title, description, null)

    override fun updateGroupInfo(groupId: String, title: String, description: String, avatarUri: String?) =
        GroupChatCoordinator.updateGroupInfo(groupId, title, description, avatarUri)

    override fun updateGroupWallpaper(groupId: String, wallpaperUri: String?, dimming: Int, isBlur: Boolean) =
        GroupChatCoordinator.updateGroupWallpaper(groupId, wallpaperUri, dimming, isBlur)

    override fun setAdminOnlyPosting(groupId: String, enabled: Boolean) =
        GroupChatCoordinator.setAdminOnlyPosting(groupId, enabled)

    override fun inviteMembers(groupId: String, contactIds: Set<String>) =
        GroupChatCoordinator.inviteMembers(groupId, contactIds)

    override fun loadOlderMessages(groupId: String, beforeMessageId: String?) =
        GroupChatCoordinator.loadOlderMessages(groupId)

    override fun sendMessage(groupId: String, text: String, replyToMessageId: String?) =
        GroupChatCoordinator.sendMessage(groupId, text, replyToMessageId)

    override fun sendAttachment(groupId: String, uri: String, mimeType: String?, caption: String?) =
        GroupChatCoordinator.sendAttachment(groupId, uri, mimeType, caption)

    override fun sendMediaAlbum(groupId: String, uris: List<String>, mimeTypes: List<String>, caption: String?) =
        GroupChatCoordinator.sendMediaAlbum(groupId, uris, mimeTypes, caption)

    override fun downloadAttachment(groupId: String, messageId: String) =
        GroupChatCoordinator.downloadAttachment(groupId, messageId)

    override fun startReply(groupId: String, messageId: String) =
        GroupChatCoordinator.startReply(groupId, messageId)

    override fun cancelReply(groupId: String) = GroupChatCoordinator.cancelReply(groupId)

    override fun editMessage(groupId: String, messageId: String, newText: String) =
        GroupChatCoordinator.editMessage(groupId, messageId, newText)

    override fun deleteMessage(groupId: String, messageId: String) =
        GroupChatCoordinator.deleteMessage(groupId, messageId)

    override fun clearHistory(groupId: String) =
        GroupChatCoordinator.clearHistory(groupId)

    override fun createPoll(groupId: String, question: String, options: List<String>, isAnonymous: Boolean) =
        GroupChatCoordinator.createPoll(groupId, question, options, isAnonymous)

    override fun votePoll(groupId: String, pollId: String, optionId: Int) =
        GroupChatCoordinator.votePoll(groupId, pollId, optionId)

    override fun toggleReaction(groupId: String, messageId: String, emoji: String) =
        GroupChatCoordinator.toggleReaction(groupId, messageId, emoji)

    override fun pinMessage(groupId: String, messageId: String) =
        GroupChatCoordinator.pinMessage(groupId, messageId)

    override fun unpinMessage(groupId: String, messageId: String) =
        GroupChatCoordinator.unpinMessage(groupId, messageId)

    override fun retryMessage(groupId: String, messageId: String) =
        GroupChatCoordinator.retryMessage(groupId, messageId)

    override fun setMemberRole(groupId: String, memberId: String, role: GroupRole) =
        GroupChatCoordinator.setMemberRole(
            groupId,
            memberId,
            when (role) {
                GroupRole.OWNER -> DomainGroupRole.OWNER
                GroupRole.ADMIN -> DomainGroupRole.ADMINISTRATOR
                GroupRole.MODERATOR -> DomainGroupRole.MODERATOR
                GroupRole.MEMBER -> DomainGroupRole.MEMBER
            },
        )

    override fun setMemberRestrictions(
        groupId: String,
        memberId: String,
        permissions: GroupMemberPermissions,
    ) = GroupChatCoordinator.setMemberRestrictions(groupId, memberId, permissions)

    override fun removeMember(groupId: String, memberId: String) =
        GroupChatCoordinator.removeMember(groupId, memberId)

    override fun banMember(groupId: String, memberId: String) =
        GroupChatCoordinator.removeMember(groupId, memberId, banned = true)

    override fun transferOwnership(groupId: String, memberId: String) =
        GroupChatCoordinator.transferOwnership(groupId, memberId)

    override fun leaveGroup(groupId: String) {
        if (!GroupChatCoordinator.canLeaveGroup(groupId)) return
        GroupChatCoordinator.leaveGroup(groupId)
        onBackNavigation()
    }

    override fun deleteGroup(groupId: String) {
        GroupChatCoordinator.deleteGroup(groupId)
        onBackNavigation()
    }

    override fun acceptInvite(inviteId: String) = GroupChatCoordinator.acceptInvite(inviteId)

    override fun declineInvite(inviteId: String) = GroupChatCoordinator.declineInvite(inviteId)

    override fun sendTyping(groupId: String, isTyping: Boolean) =
        GroupChatCoordinator.sendTyping(groupId, isTyping)
}
