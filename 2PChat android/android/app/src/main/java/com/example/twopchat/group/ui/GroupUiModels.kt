package com.example.twopchat.group.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Backend-neutral contract used by the group screens.
 *
 * Default implementations deliberately do nothing: previews, tests and an
 * incremental integration can render the complete UI before a group backend
 * is attached.
 */
@Stable
interface GroupUiController {
  fun onBack() = Unit
  fun openGroup(groupId: String) = Unit
  fun openGroupInfo(groupId: String) = Unit
  fun setGroupChatActive(groupId: String, active: Boolean) = Unit
  fun createGroup(title: String, description: String, contactIds: Set<String>) = Unit
  fun updateGroupInfo(groupId: String, title: String, description: String) = updateGroupInfo(groupId, title, description, null)
  fun updateGroupInfo(groupId: String, title: String, description: String, avatarUri: String?) = Unit
  fun setAdminOnlyPosting(groupId: String, enabled: Boolean) = Unit
  fun inviteMembers(groupId: String, contactIds: Set<String>) = Unit
  fun loadOlderMessages(groupId: String, beforeMessageId: String?) = Unit
  fun sendMessage(groupId: String, text: String, replyToMessageId: String?) = Unit
  fun sendAttachment(groupId: String, uri: String, mimeType: String?) = Unit
  fun downloadAttachment(groupId: String, messageId: String) = Unit
  fun startReply(groupId: String, messageId: String) = Unit
  fun cancelReply(groupId: String) = Unit
  fun editMessage(groupId: String, messageId: String, newText: String) = Unit
  fun deleteMessage(groupId: String, messageId: String) = Unit
  fun clearHistory(groupId: String) = Unit
  fun toggleReaction(groupId: String, messageId: String, emoji: String) = Unit
  fun pinMessage(groupId: String, messageId: String) = Unit
  fun unpinMessage(groupId: String, messageId: String) = Unit
  fun retryMessage(groupId: String, messageId: String) = Unit
  fun setMemberRole(groupId: String, memberId: String, role: GroupRole) = Unit
  fun setMemberRestrictions(
    groupId: String,
    memberId: String,
    permissions: GroupMemberPermissions
  ) = Unit
  fun removeMember(groupId: String, memberId: String) = Unit
  fun banMember(groupId: String, memberId: String) = Unit
  fun transferOwnership(groupId: String, memberId: String) = Unit
  fun leaveGroup(groupId: String) = Unit
  fun acceptInvite(inviteId: String) = Unit
  fun declineInvite(inviteId: String) = Unit
  fun createPoll(groupId: String, question: String, options: List<String>, isAnonymous: Boolean) = Unit
  fun votePoll(groupId: String, pollId: String, optionId: Int) = Unit
}

enum class GroupRole(val label: String) {
  OWNER("Owner"),
  ADMIN("Admin"),
  MODERATOR("Moderator"),
  MEMBER("Member")
}

enum class GroupDeliveryStatus(val label: String) {
  QUEUED("Queued"),
  REPLICATING("Replicating"),
  REPLICATED("Replicated"),
  DELIVERED("Delivered"),
  READ("Read"),
  FAILED("Failed")
}

enum class GroupSyncStatus(val label: String) {
  LIVE("Live"),
  SYNCING("Syncing"),
  OFFLINE("Offline · messages will be queued"),
  DEGRADED("Degraded · using replicas")
}

@Immutable
data class GroupSummary(
  val groupId: String,
  val title: String,
  val description: String = "",
  val memberCount: Int,
  val unreadCount: Int = 0,
  val lastMessagePreview: String = "",
  val lastActivityLabel: String = "",
  val isMuted: Boolean = false,
  val isVerified: Boolean = false,
  val avatarUri: String? = null
)

@Immutable
data class GroupContactSummary(
  val contactId: String,
  val displayName: String,
  val secondaryText: String = "",
  val isOnline: Boolean = false,
  val isAlreadySelected: Boolean = false
)

@Immutable
data class GroupReplyPreview(
  val messageId: String,
  val authorName: String,
  val text: String
)

@Immutable
data class GroupReaction(
  val emoji: String,
  val count: Int,
  val reactedByMe: Boolean = false
)

@Immutable
data class GroupPollOption(
  val id: Int,
  val text: String,
  val voteCount: Int = 0,
  val isVotedByMe: Boolean = false
)

@Immutable
data class GroupPollUi(
  val pollId: String,
  val question: String,
  val options: List<GroupPollOption> = emptyList(),
  val totalVotes: Int = 0,
  val isAnonymous: Boolean = false
)

@Immutable
data class GroupAttachmentUi(
  val attachmentId: String,
  val fileName: String,
  val mimeType: String,
  val sizeLabel: String,
  val availableBlocks: Int,
  val totalBlocks: Int,
  val isDownloaded: Boolean = false,
  val localPath: String? = null
)

@Immutable
data class GroupTimelineMessage(
  val messageId: String,
  val authorId: String,
  val authorName: String,
  val authorRole: GroupRole = GroupRole.MEMBER,
  val text: String,
  val timestampLabel: String,
  val isMine: Boolean = false,
  val isEdited: Boolean = false,
  val isPinned: Boolean = false,
  val attachment: GroupAttachmentUi? = null,
  val replyTo: GroupReplyPreview? = null,
  val reactions: List<GroupReaction> = emptyList(),
  val deliveryStatus: GroupDeliveryStatus = GroupDeliveryStatus.REPLICATED,
  val canReply: Boolean = true,
  val canEdit: Boolean = false,
  val canDelete: Boolean = false,
  val canReact: Boolean = true,
  val canPin: Boolean = false,
  val poll: GroupPollUi? = null,
  val readByMembers: List<String> = emptyList()
)

@Immutable
data class GroupMemberPermissions(
  val canSendMessages: Boolean = true,
  val canSendMedia: Boolean = true,
  val canSendLinks: Boolean = true,
  val canAddMembers: Boolean = false,
  val canPinMessages: Boolean = false,
  val canDeleteOthersMessages: Boolean = false
)

@Immutable
data class GroupMember(
  val memberId: String,
  val displayName: String,
  val role: GroupRole = GroupRole.MEMBER,
  val statusLabel: String = "Offline",
  val isCurrentUser: Boolean = false,
  val permissions: GroupMemberPermissions = GroupMemberPermissions(),
  val canChangeRole: Boolean = false,
  val canRestrict: Boolean = false,
  val canRemove: Boolean = false,
  val canBan: Boolean = false,
  val canTransferOwnership: Boolean = false
)

@Immutable
data class GroupManagementPermissions(
  val canEditMetadata: Boolean = false,
  val canInviteMembers: Boolean = false,
  val canViewAdminLog: Boolean = false,
  val canManageRoles: Boolean = false,
  val canRestrictMembers: Boolean = false,
  val canRemoveMembers: Boolean = false,
  val canBanMembers: Boolean = false,
  val canTransferOwnership: Boolean = false,
  val canLeave: Boolean = true
)

@Immutable
data class GroupMetadata(
  val groupId: String,
  val title: String,
  val description: String = "",
  val avatarUri: String? = null,
  val memberCount: Int,
  val createdByLabel: String = "",
  val createdAtLabel: String = "",
  val replicationLabel: String = "",
  val isPublic: Boolean = false,
  val inviteToken: String = "",
  val adminOnlyPosting: Boolean = false
)

@Immutable
data class GroupAdminLogEntry(
  val entryId: String,
  val actorName: String,
  val action: String,
  val timestampLabel: String
)

@Immutable
data class PendingGroupInvite(
  val inviteId: String,
  val groupId: String,
  val groupTitle: String,
  val groupDescription: String = "",
  val inviterName: String,
  val memberCount: Int,
  val receivedAtLabel: String = "",
  val isProcessing: Boolean = false
)

@Immutable
data class CreateGroupUiState(
  val knownContacts: List<GroupContactSummary> = emptyList(),
  val isCreating: Boolean = false,
  val errorMessage: String? = null
)

@Immutable
data class GroupChatUiState(
  val groupId: String,
  val title: String,
  val memberCount: Int,
  val avatarUri: String? = null,
  val syncStatus: GroupSyncStatus = GroupSyncStatus.SYNCING,
  val messages: List<GroupTimelineMessage> = emptyList(),
  val hasMoreBefore: Boolean = false,
  val isLoadingBefore: Boolean = false,
  val currentReply: GroupReplyPreview? = null,
  val pinnedMessage: GroupReplyPreview? = null,
  val composerEnabled: Boolean = true,
  val textComposerEnabled: Boolean = true,
  val mediaComposerEnabled: Boolean = true,
  val composerPlaceholder: String = "Message",
  val readOnlyReason: String = "",
  val isSending: Boolean = false,
  val typingStatus: String = "",
  val isMuted: Boolean = false
)

@Immutable
data class GroupInfoUiState(
  val metadata: GroupMetadata,
  val currentUserRole: GroupRole,
  val members: List<GroupMember> = emptyList(),
  val inviteCandidates: List<GroupContactSummary> = emptyList(),
  val management: GroupManagementPermissions = GroupManagementPermissions(),
  val adminLog: List<GroupAdminLogEntry> = emptyList(),
  val timelineMessages: List<GroupTimelineMessage> = emptyList()
)

@Immutable
data class PendingGroupInvitesUiState(
  val invites: List<PendingGroupInvite> = emptyList(),
  val isLoading: Boolean = false
)
