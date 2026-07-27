package com.example.twopchat.group.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun GroupChatScreen(
  state: GroupChatUiState,
  controller: GroupUiController,
  modifier: Modifier = Modifier
) {
  var draft by rememberSaveable(state.groupId) { mutableStateOf("") }
  var editingMessage by remember { mutableStateOf<GroupTimelineMessage?>(null) }
  var deletingMessage by remember { mutableStateOf<GroupTimelineMessage?>(null) }
  val context = LocalContext.current
  val attachmentLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri ->
    if (uri != null) {
      controller.sendAttachment(
        state.groupId,
        uri.toString(),
        context.contentResolver.getType(uri)
      )
    }
  }

  Column(modifier = modifier.fillMaxSize()) {
    GroupChatHeader(state = state, controller = controller)

    state.pinnedMessage?.let { pinned ->
      Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("pinned_message")
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(Modifier.weight(1f)) {
            Text("Pinned · ${pinned.authorName}", fontWeight = FontWeight.SemiBold)
            Text(
              pinned.text,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.bodySmall
            )
          }
          TextButton(onClick = { controller.unpinMessage(state.groupId, pinned.messageId) }) {
            Text("Unpin")
          }
        }
      }
    }

    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .testTag("group_message_list"),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      item(key = "pagination") {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
          contentAlignment = Alignment.Center
        ) {
          when {
            state.isLoadingBefore -> CircularProgressIndicator(
              modifier = Modifier.testTag("older_messages_loading")
            )
            state.hasMoreBefore -> TextButton(
              onClick = {
                controller.loadOlderMessages(
                  state.groupId,
                  state.messages.firstOrNull()?.messageId
                )
              },
              modifier = Modifier.testTag("load_older_messages")
            ) {
              Text("Load earlier messages")
            }
            state.messages.isNotEmpty() -> Text(
              "Beginning of group history",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      if (state.messages.isEmpty() && !state.isLoadingBefore) {
        item(key = "empty") {
          Text(
            text = "No messages yet. Start the conversation when you are ready.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      items(state.messages, key = GroupTimelineMessage::messageId) { message ->
        GroupMessageCard(
          groupId = state.groupId,
          message = message,
          controller = controller,
          onEdit = { editingMessage = message },
          onDelete = { deletingMessage = message }
        )
      }
    }

    HorizontalDivider()
    GroupComposer(
      state = state,
      draft = draft,
      onDraftChange = { draft = it },
      onCancelReply = { controller.cancelReply(state.groupId) },
      onAttach = { attachmentLauncher.launch(arrayOf("*/*")) },
      onSend = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
          controller.sendMessage(state.groupId, text, state.currentReply?.messageId)
          draft = ""
        }
      }
    )
  }

  editingMessage?.let { message ->
    var editedText by remember(message.messageId) { mutableStateOf(message.text) }
    AlertDialog(
      onDismissRequest = { editingMessage = null },
      title = { Text("Edit message") },
      text = {
        OutlinedTextField(
          value = editedText,
          onValueChange = { editedText = it },
          modifier = Modifier.testTag("edit_message_input")
        )
      },
      confirmButton = {
        TextButton(
          enabled = editedText.trim().isNotEmpty(),
          onClick = {
            controller.editMessage(state.groupId, message.messageId, editedText.trim())
            editingMessage = null
          }
        ) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = { editingMessage = null }) { Text("Cancel") }
      }
    )
  }

  deletingMessage?.let { message ->
    AlertDialog(
      onDismissRequest = { deletingMessage = null },
      title = { Text("Delete message?") },
      text = { Text("This action will be recorded in the group event log.") },
      confirmButton = {
        TextButton(
          onClick = {
            controller.deleteMessage(state.groupId, message.messageId)
            deletingMessage = null
          },
          modifier = Modifier.testTag("confirm_delete_message")
        ) {
          Text("Delete")
        }
      },
      dismissButton = {
        TextButton(onClick = { deletingMessage = null }) { Text("Cancel") }
      }
    )
  }
}

@Composable
private fun GroupChatHeader(state: GroupChatUiState, controller: GroupUiController) {
  Surface(tonalElevation = 2.dp) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      TextButton(onClick = controller::onBack) { Text("Back") }
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 8.dp)
      ) {
        Text(
          state.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          "${state.memberCount} members · ${state.syncStatus.label}",
          modifier = Modifier.testTag("group_sync_status"),
          style = MaterialTheme.typography.bodySmall,
          color = syncStatusColor(state.syncStatus)
        )
      }
      TextButton(
        onClick = { controller.openGroupInfo(state.groupId) },
        modifier = Modifier.testTag("open_group_info")
      ) {
        Text("Info")
      }
    }
  }
}

@Composable
private fun GroupMessageCard(
  groupId: String,
  message: GroupTimelineMessage,
  controller: GroupUiController,
  onEdit: () -> Unit,
  onDelete: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp)
      .testTag("group_message_${message.messageId}"),
    colors = CardDefaults.cardColors(
      containerColor = if (message.isMine) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      }
    )
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          message.authorName,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary
        )
        if (message.authorRole != GroupRole.MEMBER) {
          Spacer(Modifier.width(6.dp))
          RoleBadge(message.authorRole)
        }
        Spacer(Modifier.weight(1f))
        if (message.isPinned) {
          Text(
            "Pinned",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary
          )
        }
      }

      message.replyTo?.let { reply ->
        Surface(
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
        ) {
          Column(Modifier.padding(8.dp)) {
            Text(reply.authorName, style = MaterialTheme.typography.labelMedium)
            Text(
              reply.text,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.bodySmall
            )
          }
        }
      }

      Text(message.text, modifier = Modifier.padding(vertical = 6.dp))

      message.attachment?.let { attachment ->
        Surface(
          color = MaterialTheme.colorScheme.surface,
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("attachment_${message.messageId}")
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(Modifier.weight(1f)) {
              Text(
                attachment.fileName,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                "${attachment.sizeLabel} · ${attachment.availableBlocks}/${attachment.totalBlocks} blocks",
                style = MaterialTheme.typography.bodySmall
              )
            }
            TextButton(
              onClick = {
                controller.downloadAttachment(groupId, message.messageId)
              },
              enabled = !attachment.isDownloaded,
              modifier = Modifier.testTag("download_${message.messageId}")
            ) {
              Text(if (attachment.isDownloaded) "Available" else "Download")
            }
          }
        }
      }

      if (message.reactions.isNotEmpty()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          message.reactions.forEach { reaction ->
            AssistChip(
              onClick = {
                if (message.canReact) {
                  controller.toggleReaction(groupId, message.messageId, reaction.emoji)
                }
              },
              enabled = message.canReact,
              label = {
                Text(
                  "${reaction.emoji} ${reaction.count}${if (reaction.reactedByMe) " · you" else ""}"
                )
              }
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          buildString {
            append(message.timestampLabel)
            if (message.isEdited) append(" · edited")
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
          message.deliveryStatus.label,
          modifier = Modifier.testTag("delivery_${message.messageId}"),
          style = MaterialTheme.typography.labelSmall,
          color = deliveryStatusColor(message.deliveryStatus)
        )
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.End
      ) {
        if (message.deliveryStatus == GroupDeliveryStatus.FAILED && message.isMine) {
          TextButton(
            onClick = { controller.retryMessage(groupId, message.messageId) },
            modifier = Modifier.testTag("retry_${message.messageId}")
          ) { Text("Retry") }
        }
        if (message.canReply) {
          TextButton(
            onClick = { controller.startReply(groupId, message.messageId) },
            modifier = Modifier.testTag("reply_${message.messageId}")
          ) { Text("Reply") }
        }
        if (message.canReact) {
          TextButton(
            onClick = { controller.toggleReaction(groupId, message.messageId, "👍") },
            modifier = Modifier.testTag("react_${message.messageId}")
          ) { Text("React") }
        }
        if (message.canPin) {
          TextButton(
            onClick = {
              if (message.isPinned) controller.unpinMessage(groupId, message.messageId)
              else controller.pinMessage(groupId, message.messageId)
            },
            modifier = Modifier.testTag("pin_${message.messageId}")
          ) { Text(if (message.isPinned) "Unpin" else "Pin") }
        }
        if (message.canEdit) {
          TextButton(
            onClick = onEdit,
            modifier = Modifier.testTag("edit_${message.messageId}")
          ) { Text("Edit") }
        }
        if (message.canDelete) {
          TextButton(
            onClick = onDelete,
            modifier = Modifier.testTag("delete_${message.messageId}")
          ) { Text("Delete") }
        }
      }
    }
  }
}

@Composable
private fun GroupComposer(
  state: GroupChatUiState,
  draft: String,
  onDraftChange: (String) -> Unit,
  onCancelReply: () -> Unit,
  onAttach: () -> Unit,
  onSend: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(12.dp)
  ) {
    state.currentReply?.let { reply ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("reply_composer"),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          "Replying to ${reply.authorName}: ${reply.text}",
          modifier = Modifier.weight(1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = onCancelReply) { Text("Cancel") }
      }
    }

    if (!state.composerEnabled) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("read_only_composer")
      ) {
        Text(
          text = state.readOnlyReason.ifBlank { "You cannot send messages in this group" },
          modifier = Modifier.padding(14.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      return
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Bottom
    ) {
      TextButton(
        onClick = onAttach,
        enabled = state.mediaComposerEnabled && !state.isSending,
        modifier = Modifier.testTag("group_attach_button")
      ) {
        Text("File")
      }
      OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = Modifier
          .weight(1f)
          .testTag("group_composer_input"),
        enabled = state.textComposerEnabled && !state.isSending,
        placeholder = { Text(state.composerPlaceholder) },
        maxLines = 5
      )
      Spacer(Modifier.width(8.dp))
      Button(
        onClick = onSend,
        enabled = draft.isNotBlank() && state.textComposerEnabled && !state.isSending,
        modifier = Modifier.testTag("group_send_button")
      ) {
        if (state.isSending) {
          CircularProgressIndicator(strokeWidth = 2.dp)
        } else {
          Text("Send")
        }
      }
    }
  }
}

@Composable
private fun RoleBadge(role: GroupRole) {
  Surface(
    color = when (role) {
      GroupRole.OWNER -> MaterialTheme.colorScheme.primary
      GroupRole.ADMIN -> MaterialTheme.colorScheme.tertiary
      GroupRole.MODERATOR -> MaterialTheme.colorScheme.secondary
      GroupRole.MEMBER -> MaterialTheme.colorScheme.surfaceVariant
    },
    shape = MaterialTheme.shapes.small
  ) {
    Text(
      role.label,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
      style = MaterialTheme.typography.labelSmall,
      color = when (role) {
        GroupRole.OWNER -> MaterialTheme.colorScheme.onPrimary
        GroupRole.ADMIN -> MaterialTheme.colorScheme.onTertiary
        GroupRole.MODERATOR -> MaterialTheme.colorScheme.onSecondary
        GroupRole.MEMBER -> MaterialTheme.colorScheme.onSurfaceVariant
      }
    )
  }
}

private fun syncStatusColor(status: GroupSyncStatus): Color = when (status) {
  GroupSyncStatus.LIVE -> Color(0xFF2E7D32)
  GroupSyncStatus.SYNCING -> Color(0xFF1565C0)
  GroupSyncStatus.OFFLINE -> Color(0xFFEF6C00)
  GroupSyncStatus.DEGRADED -> Color(0xFF6A1B9A)
}

private fun deliveryStatusColor(status: GroupDeliveryStatus): Color = when (status) {
  GroupDeliveryStatus.QUEUED -> Color(0xFFEF6C00)
  GroupDeliveryStatus.REPLICATING -> Color(0xFF1565C0)
  GroupDeliveryStatus.REPLICATED -> Color(0xFF2E7D32)
  GroupDeliveryStatus.DELIVERED -> Color(0xFF2E7D32)
  GroupDeliveryStatus.READ -> Color(0xFF00838F)
  GroupDeliveryStatus.FAILED -> Color(0xFFC62828)
}
