package com.example.twopchat.group.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun GroupInfoScreen(
  state: GroupInfoUiState,
  controller: GroupUiController,
  modifier: Modifier = Modifier
) {
  var restrictionsFor by remember { mutableStateOf<GroupMember?>(null) }
  var removeConfirmation by remember { mutableStateOf<GroupMember?>(null) }
  var banConfirmation by remember { mutableStateOf<GroupMember?>(null) }
  var transferConfirmation by remember { mutableStateOf<GroupMember?>(null) }
  var showLeaveConfirmation by remember { mutableStateOf(false) }
  var showEditMetadata by remember { mutableStateOf(false) }
  var showInviteMembers by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize()) {
    Surface(tonalElevation = 2.dp) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        TextButton(onClick = controller::onBack) { Text("Back") }
        Text(
          "Group info",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold
        )
      }
    }

    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .testTag("group_info_list"),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      item(key = "metadata") {
        GroupMetadataCard(state.metadata, state.currentUserRole)
      }

      if (state.management.canEditMetadata || state.management.canInviteMembers) {
        item(key = "management_actions") {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            if (state.management.canEditMetadata) {
              Button(
                onClick = { showEditMetadata = true },
                modifier = Modifier.weight(1f).testTag("edit_group_info")
              ) { Text("Edit group") }
            }
            if (state.management.canInviteMembers && state.inviteCandidates.isNotEmpty()) {
              Button(
                onClick = { showInviteMembers = true },
                modifier = Modifier.weight(1f).testTag("invite_group_members")
              ) { Text("Add members") }
            }
          }
        }
      }

      item(key = "members_header") {
        Text(
          "Members · ${state.members.size}",
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold
        )
      }

      items(state.members, key = GroupMember::memberId) { member ->
        GroupMemberCard(
          groupId = state.metadata.groupId,
          member = member,
          management = state.management,
          controller = controller,
          onRestrict = { restrictionsFor = member },
          onRemove = { removeConfirmation = member },
          onBan = { banConfirmation = member },
          onTransfer = { transferConfirmation = member }
        )
      }

      item(key = "admin_log") {
        AdminLogSection(state)
      }

      if (state.management.canLeave) {
        item(key = "leave") {
          Button(
            onClick = { showLeaveConfirmation = true },
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
              .testTag("leave_group_button")
          ) {
            Text(if (state.currentUserRole == GroupRole.OWNER) "Leave or transfer ownership" else "Leave group")
          }
        }
      }
    }
  }

  restrictionsFor?.let { member ->
    MemberRestrictionsDialog(
      member = member,
      onDismiss = { restrictionsFor = null },
      onApply = {
        controller.setMemberRestrictions(state.metadata.groupId, member.memberId, it)
        restrictionsFor = null
      }
    )
  }

  removeConfirmation?.let { member ->
    ConfirmationDialog(
      title = "Remove ${member.displayName}?",
      body = "The member will lose access to future group epochs. Existing downloaded history cannot be revoked.",
      confirmLabel = "Remove",
      confirmTag = "confirm_remove_member",
      onDismiss = { removeConfirmation = null },
      onConfirm = {
        controller.removeMember(state.metadata.groupId, member.memberId)
        removeConfirmation = null
      }
    )
  }

  banConfirmation?.let { member ->
    ConfirmationDialog(
      title = "Ban ${member.displayName}?",
      body = "The member will be removed and excluded from the next encryption epoch.",
      confirmLabel = "Ban",
      confirmTag = "confirm_ban_member",
      onDismiss = { banConfirmation = null },
      onConfirm = {
        controller.banMember(state.metadata.groupId, member.memberId)
        banConfirmation = null
      }
    )
  }

  transferConfirmation?.let { member ->
    ConfirmationDialog(
      title = "Transfer ownership?",
      body = "${member.displayName} will become the owner. You will become an administrator.",
      confirmLabel = "Transfer",
      confirmTag = "confirm_transfer_ownership",
      onDismiss = { transferConfirmation = null },
      onConfirm = {
        controller.transferOwnership(state.metadata.groupId, member.memberId)
        transferConfirmation = null
      }
    )
  }

  if (showLeaveConfirmation) {
    ConfirmationDialog(
      title = "Leave group?",
      body = if (state.currentUserRole == GroupRole.OWNER) {
        "Transfer ownership before leaving if other members remain."
      } else {
        "Queued messages from this device will be discarded."
      },
      confirmLabel = "Leave",
      confirmTag = "confirm_leave_group",
      onDismiss = { showLeaveConfirmation = false },
      onConfirm = {
        controller.leaveGroup(state.metadata.groupId)
        showLeaveConfirmation = false
      }
    )
  }

  if (showEditMetadata) {
    var title by remember(state.metadata.groupId) { mutableStateOf(state.metadata.title) }
    var description by remember(state.metadata.groupId) {
      mutableStateOf(state.metadata.description)
    }
    AlertDialog(
      onDismissRequest = { showEditMetadata = false },
      title = { Text("Edit group") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(160) },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth().testTag("edit_group_title")
          )
          OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(2_000) },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth()
          )
        }
      },
      confirmButton = {
        TextButton(
          enabled = title.trim().isNotEmpty(),
          onClick = {
            controller.updateGroupInfo(
              state.metadata.groupId,
              title.trim(),
              description.trim()
            )
            showEditMetadata = false
          }
        ) { Text("Save") }
      },
      dismissButton = {
        TextButton(onClick = { showEditMetadata = false }) { Text("Cancel") }
      }
    )
  }

  if (showInviteMembers) {
    var selected by remember(state.metadata.groupId) {
      mutableStateOf<Set<String>>(emptySet())
    }
    AlertDialog(
      onDismissRequest = { showInviteMembers = false },
      title = { Text("Add members") },
      text = {
        Column {
          state.inviteCandidates.forEach { contact ->
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth()
            ) {
              Checkbox(
                checked = contact.contactId in selected,
                onCheckedChange = { checked ->
                  selected = selected.toMutableSet().apply {
                    if (checked) add(contact.contactId) else remove(contact.contactId)
                  }
                }
              )
              Text(contact.displayName)
            }
          }
        }
      },
      confirmButton = {
        TextButton(
          enabled = selected.isNotEmpty(),
          onClick = {
            controller.inviteMembers(state.metadata.groupId, selected)
            showInviteMembers = false
          }
        ) { Text("Invite") }
      },
      dismissButton = {
        TextButton(onClick = { showInviteMembers = false }) { Text("Cancel") }
      }
    )
  }
}

@Composable
private fun GroupMetadataCard(metadata: GroupMetadata, currentUserRole: GroupRole) {
  OutlinedCard(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp)
      .testTag("group_metadata")
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(metadata.title, style = MaterialTheme.typography.headlineSmall)
      if (metadata.description.isNotBlank()) {
        Text(metadata.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Text("${metadata.memberCount} members · ${if (metadata.isPublic) "Public" else "Private"}")
      Text("Your role: ${currentUserRole.label}", fontWeight = FontWeight.Medium)
      if (metadata.createdByLabel.isNotBlank()) Text("Created by ${metadata.createdByLabel}")
      if (metadata.createdAtLabel.isNotBlank()) Text(metadata.createdAtLabel)
      if (metadata.replicationLabel.isNotBlank()) {
        Text(
          metadata.replicationLabel,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary
        )
      }
    }
  }
}

@Composable
private fun GroupMemberCard(
  groupId: String,
  member: GroupMember,
  management: GroupManagementPermissions,
  controller: GroupUiController,
  onRestrict: () -> Unit,
  onRemove: () -> Unit,
  onBan: () -> Unit,
  onTransfer: () -> Unit
) {
  OutlinedCard(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp)
      .testTag("member_${member.memberId}")
  ) {
    Column(Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            buildString {
              append(member.displayName)
              if (member.isCurrentUser) append(" · you")
            },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            "${member.role.label} · ${member.statusLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      if (!member.permissions.canSendMessages) {
        Text(
          "Read-only",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error
        )
      }

      val showRoleActions = management.canManageRoles &&
        member.canChangeRole &&
        !member.isCurrentUser &&
        member.role != GroupRole.OWNER
      val showRestrict = management.canRestrictMembers && member.canRestrict && !member.isCurrentUser
      val showRemove = management.canRemoveMembers && member.canRemove && !member.isCurrentUser
      val showBan = management.canBanMembers && member.canBan && !member.isCurrentUser
      val showTransfer = management.canTransferOwnership &&
        member.canTransferOwnership &&
        !member.isCurrentUser

      if (showRoleActions || showRestrict || showRemove || showBan || showTransfer) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          if (showRoleActions && member.role != GroupRole.ADMIN) {
            TextButton(
              onClick = { controller.setMemberRole(groupId, member.memberId, GroupRole.ADMIN) },
              modifier = Modifier.testTag("make_admin_${member.memberId}")
            ) { Text("Make admin") }
          }
          if (showRoleActions && member.role != GroupRole.MODERATOR) {
            TextButton(
              onClick = { controller.setMemberRole(groupId, member.memberId, GroupRole.MODERATOR) },
              modifier = Modifier.testTag("make_moderator_${member.memberId}")
            ) { Text("Make moderator") }
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          if (showRoleActions && member.role != GroupRole.MEMBER) {
            TextButton(
              onClick = { controller.setMemberRole(groupId, member.memberId, GroupRole.MEMBER) },
              modifier = Modifier.testTag("make_member_${member.memberId}")
            ) { Text("Remove role") }
          }
          if (showRestrict) {
            TextButton(
              onClick = onRestrict,
              modifier = Modifier.testTag("restrict_${member.memberId}")
            ) { Text("Restrict") }
          }
          if (showRemove) {
            TextButton(
              onClick = onRemove,
              modifier = Modifier.testTag("remove_${member.memberId}")
            ) { Text("Remove") }
          }
          if (showBan) {
            TextButton(
              onClick = onBan,
              modifier = Modifier.testTag("ban_${member.memberId}")
            ) { Text("Ban") }
          }
          if (showTransfer) {
            TextButton(
              onClick = onTransfer,
              modifier = Modifier.testTag("transfer_${member.memberId}")
            ) { Text("Transfer") }
          }
        }
      }
    }
  }
}

@Composable
private fun AdminLogSection(state: GroupInfoUiState) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(16.dp)
      .testTag("admin_log")
  ) {
    Text("Recent administrative actions", style = MaterialTheme.typography.titleMedium)
    if (!state.management.canViewAdminLog) {
      Text(
        "Only moderators and administrators can view the admin log.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      return
    }
    if (state.adminLog.isEmpty()) {
      Text(
        "No administrative actions yet",
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      return
    }
    state.adminLog.forEach { entry ->
      Column(Modifier.padding(vertical = 6.dp)) {
        Text("${entry.actorName} · ${entry.action}")
        Text(
          entry.timestampLabel,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun MemberRestrictionsDialog(
  member: GroupMember,
  onDismiss: () -> Unit,
  onApply: (GroupMemberPermissions) -> Unit
) {
  var permissions by remember(member.memberId) { mutableStateOf(member.permissions) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Permissions for ${member.displayName}") },
    text = {
      Column {
        PermissionToggle("Send messages", permissions.canSendMessages) {
          permissions = permissions.copy(canSendMessages = it)
        }
        PermissionToggle("Send media", permissions.canSendMedia) {
          permissions = permissions.copy(canSendMedia = it)
        }
        PermissionToggle("Send links", permissions.canSendLinks) {
          permissions = permissions.copy(canSendLinks = it)
        }
        PermissionToggle("Add members", permissions.canAddMembers) {
          permissions = permissions.copy(canAddMembers = it)
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onApply(permissions) },
        modifier = Modifier.testTag("apply_member_restrictions")
      ) { Text("Apply") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    }
  )
}

@Composable
private fun PermissionToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth()
  ) {
    Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    Text(label)
  }
}

@Composable
private fun ConfirmationDialog(
  title: String,
  body: String,
  confirmLabel: String,
  confirmTag: String,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(body) },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        modifier = Modifier.testTag(confirmTag)
      ) { Text(confirmLabel) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    }
  )
}
