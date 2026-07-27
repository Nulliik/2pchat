package com.example.twopchat.group.ui

/**
 * Backend-neutral contract used by the group screens.
 *
 * Default implementations deliberately do nothing: previews, tests and an
 * incremental integration can render the complete UI before a group backend
 * is attached.
 */
interface GroupUiController {
  fun onBack() = Unit
  fun openGroup(groupId: String) = Unit
  fun openGroupInfo(groupId: String) = Unit
  fun createGroup(title: String, description: String, contactIds: Set<String>) = Unit
  fun updateGroupInfo(groupId: String, title: String, description: String) = Unit
  fun inviteMembers(groupId: String, contactIds: Set<String>) = Unit
  fun loadOlderMessages(groupId: String, beforeMessageId: String?) = Unit
  fun sendMessage(groupId: String, text: String, replyToMessageId: String?) = Unit
  fun sendAttachment(groupId: String, uri: String, mimeType: String?) = Unit
  fun downloadAttachment(groupId: String, messageId: String) = Unit
  fun startReply(groupId: String, messageId: String) = Unit
  fun cancelReply(groupId: String) = Unit
  fun editMessage(groupId: String, messageId: String, newText: String) = Unit
  fun deleteMessage(groupId: String, messageId: String) = Unit
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

data class GroupSummary(
  val groupId: String,
  val title: String,
  val description: String = "",
  val memberCount: Int,
  val unreadCount: Int = 0,
  val lastMessagePreview: String = "",
  val lastActivityLabel: String = "",
  val isMuted: Boolean = false,
  val isVerified: Boolean = false
)

data class GroupContactSummary(
  val contactId: String,
  val displayName: String,
  val secondaryText: String = "",
  val isOnline: Boolean = false,
  val isAlreadySelected: Boolean = false
)

data class GroupReplyPreview(
  val messageId: String,
  val authorName: String,
  val text: String
)

data class GroupReaction(
  val emoji: String,
  val count: Int,
  val reactedByMe: Boolean = false
)

data class GroupAttachmentUi(
  val attachmentId: String,
  val fileName: String,
  val mimeType: String,
  val sizeLabel: String,
  val availableBlocks: Int,
  val totalBlocks: Int,
  val isDownloaded: Boolean = false
)

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
  val canPin: Boolean = false
)

data class GroupMemberPermissions(
  val canSendMessages: Boolean = true,
  val canSendMedia: Boolean = true,
  val canSendLinks: Boolean = true,
  val canAddMembers: Boolean = false,
  val canPinMessages: Boolean = false,
  val canDeleteOthersMessages: Boolean = false
)

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

data class GroupMetadata(
  val groupId: String,
  val title: String,
  val description: String = "",
  val memberCount: Int,
  val createdByLabel: String = "",
  val createdAtLabel: String = "",
  val replicationLabel: String = "",
  val isPublic: Boolean = false
)

data class GroupAdminLogEntry(
  val entryId: String,
  val actorName: String,
  val action: String,
  val timestampLabel: String
)

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

data class CreateGroupUiState(
  val knownContacts: List<GroupContactSummary> = emptyList(),
  val isCreating: Boolean = false,
  val errorMessage: String? = null
)

data class GroupChatUiState(
  val groupId: String,
  val title: String,
  val memberCount: Int,
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
  val isSending: Boolean = false
)

data class GroupInfoUiState(
  val metadata: GroupMetadata,
  val currentUserRole: GroupRole,
  val members: List<GroupMember> = emptyList(),
  val inviteCandidates: List<GroupContactSummary> = emptyList(),
  val management: GroupManagementPermissions = GroupManagementPermissions(),
  val adminLog: List<GroupAdminLogEntry> = emptyList()
)

data class PendingGroupInvitesUiState(
  val invites: List<PendingGroupInvite> = emptyList(),
  val isLoading: Boolean = false
)
