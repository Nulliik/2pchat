package com.example.twopchat.group.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    state: GroupChatUiState,
    controller: GroupUiController,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable(state.groupId) { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf<GroupTimelineMessage?>(null) }
    var deletingMessage by remember { mutableStateOf<GroupTimelineMessage?>(null) }
    var selectedMessageForOptions by remember { mutableStateOf<GroupTimelineMessage?>(null) }
    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(surfaceColor)
    ) {
        // Modern Glassmorphic Top App Bar
        GroupChatHeader(state = state, controller = controller)

        // Pinned Message Bar
        state.pinnedMessage?.let { pinned ->
            Surface(
                color = primaryColor.copy(alpha = 0.08f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pinned_message")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(32.dp)
                            .background(primaryColor, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_pin),
                                contentDescription = "Pinned",
                                tint = primaryColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Закреплено · ${pinned.authorName}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = primaryColor
                            )
                        }
                        Text(
                            pinned.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            color = onSurfaceColor.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(
                        onClick = { controller.unpinMessage(state.groupId, pinned.messageId) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("✕", color = onSurfaceColor.copy(alpha = 0.6f), fontSize = 14.sp)
                    }
                }
            }
        }

        // Messages List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .testTag("group_message_list"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("older_messages_loading"),
                            strokeWidth = 2.dp
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
                            Text("Загрузить ранние сообщения", fontSize = 12.sp)
                        }
                        state.messages.isNotEmpty() -> Text(
                            "Начало истории группы",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            if (state.messages.isEmpty() && !state.isLoadingBefore) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Сообщений пока нет. Начните общение в группе!",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(state.messages, key = GroupTimelineMessage::messageId) { message ->
                GroupMessageCard(
                    groupId = state.groupId,
                    message = message,
                    controller = controller,
                    onEdit = { editingMessage = message },
                    onDelete = { deletingMessage = message },
                    onOptionsClick = { selectedMessageForOptions = message }
                )
            }
        }

        HorizontalDivider(color = primaryColor.copy(alpha = 0.1f), thickness = 0.5.dp)

        // Chat Input Bar / Composer
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

    // Message Actions Options Dialog (for long press or extra menu)
    selectedMessageForOptions?.let { message ->
        AlertDialog(
            onDismissRequest = { selectedMessageForOptions = null },
            title = { Text("Действия с сообщением", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.canReply) {
                        TextButton(
                            onClick = {
                                controller.startReply(state.groupId, message.messageId)
                                selectedMessageForOptions = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("reply_${message.messageId}")
                        ) { Text("Ответить", modifier = Modifier.fillMaxWidth()) }
                    }
                    if (message.canReact) {
                        TextButton(
                            onClick = {
                                controller.toggleReaction(state.groupId, message.messageId, "👍")
                                selectedMessageForOptions = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("react_${message.messageId}")
                        ) { Text("Поставить реакцию 👍", modifier = Modifier.fillMaxWidth()) }
                    }
                    if (message.canPin) {
                        TextButton(
                            onClick = {
                                if (message.isPinned) controller.unpinMessage(state.groupId, message.messageId)
                                else controller.pinMessage(state.groupId, message.messageId)
                                selectedMessageForOptions = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("pin_${message.messageId}")
                        ) { Text(if (message.isPinned) "Открепить" else "Закрепить", modifier = Modifier.fillMaxWidth()) }
                    }
                    if (message.canEdit) {
                        TextButton(
                            onClick = {
                                editingMessage = message
                                selectedMessageForOptions = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("edit_${message.messageId}")
                        ) { Text("Редактировать", modifier = Modifier.fillMaxWidth()) }
                    }
                    if (message.canDelete) {
                        TextButton(
                            onClick = {
                                deletingMessage = message
                                selectedMessageForOptions = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("delete_${message.messageId}")
                        ) { Text("Удалить", color = Color.Red, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedMessageForOptions = null }) { Text("Отмена") }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    editingMessage?.let { message ->
        var editedText by remember(message.messageId) { mutableStateOf(message.text) }
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text("Редактировать сообщение", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_message_input"),
                    shape = RoundedCornerShape(12.dp)
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
                    Text("Сохранить", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text("Отмена") }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    deletingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { deletingMessage = null },
            title = { Text("Удалить сообщение?", fontWeight = FontWeight.Bold) },
            text = { Text("Это действие зафиксируется в журнале событий группы.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.deleteMessage(state.groupId, message.messageId)
                        deletingMessage = null
                    },
                    modifier = Modifier.testTag("confirm_delete_message")
                ) {
                    Text("Удалить", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingMessage = null }) { Text("Отмена") }
            },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun GroupChatHeader(state: GroupChatUiState, controller: GroupUiController) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Surface(
        color = surfaceColor,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = controller::onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = onSurfaceColor
                )
            }

            // Group Avatar
            val initials = state.title.take(2).uppercase().ifBlank { "GP" }
            val avatarColor = remember(state.groupId) {
                val colors = listOf(
                    Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
                    Color(0xFF8E24AA), Color(0xFFE53935), Color(0xFF00ACC1)
                )
                colors[abs(state.groupId.hashCode()) % colors.size]
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { controller.openGroupInfo(state.groupId) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = onSurfaceColor
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("🛡️", fontSize = 12.sp) // P2P Security Badge
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusDotColor = syncStatusColor(state.syncStatus)
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(statusDotColor, CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${state.memberCount} уч. · ${state.syncStatus.label}",
                        modifier = Modifier.testTag("group_sync_status"),
                        fontSize = 12.sp,
                        color = statusDotColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            IconButton(
                onClick = { controller.openGroupInfo(state.groupId) },
                modifier = Modifier.testTag("open_group_info")
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Group Info",
                    tint = primaryColor
                )
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
    onDelete: () -> Unit,
    onOptionsClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    val bubbleShape = if (message.isMine) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    val bubbleContainerColor = if (message.isMine) {
        primaryColor.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag("group_message_${message.messageId}"),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // Peer avatar on left
        if (!message.isMine) {
            val peerInitials = message.authorName.take(2).uppercase().ifBlank { "U" }
            val peerAvatarColor = remember(message.authorName) {
                val colors = listOf(
                    Color(0xFF3949AB), Color(0xFF00897B), Color(0xFFD81B60),
                    Color(0xFFF4511E), Color(0xFF7CB342), Color(0xFF00ACC1)
                )
                colors[abs(message.authorName.hashCode()) % colors.size]
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(peerAvatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peerInitials,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(6.dp))
        }

        Surface(
            shape = bubbleShape,
            color = bubbleContainerColor,
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onOptionsClick
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Header line: Author Name & Role
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.authorName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (message.isMine) primaryColor else MaterialTheme.colorScheme.tertiary
                    )
                    if (message.authorRole != GroupRole.MEMBER) {
                        Spacer(Modifier.width(6.dp))
                        RoleBadge(message.authorRole)
                    }
                    Spacer(Modifier.weight(1f))
                    if (message.isPinned) {
                        Text(
                            "📌",
                            fontSize = 11.sp
                        )
                    }
                }

                // Reply Preview
                message.replyTo?.let { reply ->
                    Surface(
                        color = surfaceColor.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(modifier = Modifier.padding(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(30.dp)
                                    .background(primaryColor, RoundedCornerShape(1.dp))
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    reply.authorName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryColor
                                )
                                Text(
                                    reply.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp,
                                    color = onSurfaceColor.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                // Message Text
                if (message.text.isNotEmpty()) {
                    Text(
                        text = message.text,
                        fontSize = 14.sp,
                        color = onSurfaceColor,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                // Attachment Card
                message.attachment?.let { attachment ->
                    Surface(
                        color = surfaceColor,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("attachment_${message.messageId}")
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_attach_paperclip),
                                contentDescription = "Attachment",
                                tint = primaryColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    attachment.fileName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${attachment.sizeLabel} · ${attachment.availableBlocks}/${attachment.totalBlocks} бл.",
                                    fontSize = 10.sp,
                                    color = onSurfaceColor.copy(alpha = 0.6f)
                                )
                            }
                            TextButton(
                                onClick = {
                                    controller.downloadAttachment(groupId, message.messageId)
                                },
                                enabled = !attachment.isDownloaded,
                                modifier = Modifier.testTag("download_${message.messageId}")
                            ) {
                                Text(
                                    if (attachment.isDownloaded) "Готово" else "Скачать",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Reactions Row
                if (message.reactions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        message.reactions.forEach { reaction ->
                            AssistChip(
                                onClick = {
                                    if (message.canReact) {
                                        controller.toggleReaction(groupId, message.messageId, reaction.emoji)
                                    }
                                },
                                enabled = message.canReact,
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (reaction.reactedByMe) primaryColor.copy(alpha = 0.2f) else surfaceColor
                                ),
                                label = {
                                    Text(
                                        "${reaction.emoji} ${reaction.count}",
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
                }

                // Message Footer: Timestamp & Delivery Status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        buildString {
                            append(message.timestampLabel)
                            if (message.isEdited) append(" · изм.")
                        },
                        fontSize = 10.sp,
                        color = onSurfaceColor.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when (message.deliveryStatus) {
                            GroupDeliveryStatus.QUEUED -> "⏳"
                            GroupDeliveryStatus.REPLICATING -> "✔"
                            GroupDeliveryStatus.REPLICATED, GroupDeliveryStatus.DELIVERED -> "✔✔"
                            GroupDeliveryStatus.READ -> "✔✔"
                            GroupDeliveryStatus.FAILED -> "❌"
                        },
                        modifier = Modifier.testTag("delivery_${message.messageId}"),
                        fontSize = 10.sp,
                        color = deliveryStatusColor(message.deliveryStatus)
                    )
                }

                // Action Bar (Visible buttons ensuring automated test tags match)
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
                        ) { Text("Повторить", fontSize = 11.sp) }
                    }
                    if (message.canReply) {
                        TextButton(
                            onClick = { controller.startReply(groupId, message.messageId) },
                            modifier = Modifier.testTag("reply_${message.messageId}")
                        ) { Text("Ответить", fontSize = 11.sp) }
                    }
                    if (message.canReact) {
                        TextButton(
                            onClick = { controller.toggleReaction(groupId, message.messageId, "👍") },
                            modifier = Modifier.testTag("react_${message.messageId}")
                        ) { Text("👍", fontSize = 11.sp) }
                    }
                    if (message.canPin) {
                        TextButton(
                            onClick = {
                                if (message.isPinned) controller.unpinMessage(groupId, message.messageId)
                                else controller.pinMessage(groupId, message.messageId)
                            },
                            modifier = Modifier.testTag("pin_${message.messageId}")
                        ) { Text(if (message.isPinned) "Открепить" else "Закрепить", fontSize = 11.sp) }
                    }
                    if (message.canEdit) {
                        TextButton(
                            onClick = onEdit,
                            modifier = Modifier.testTag("edit_${message.messageId}")
                        ) { Text("Изм.", fontSize = 11.sp) }
                    }
                    if (message.canDelete) {
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag("delete_${message.messageId}")
                        ) { Text("Удал.", fontSize = 11.sp, color = Color.Red) }
                    }
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
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        state.currentReply?.let { reply ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .background(primaryColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("reply_composer"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(28.dp)
                        .background(primaryColor, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Ответ для ${reply.authorName}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = primaryColor
                    )
                    Text(
                        reply.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                        color = onSurfaceColor.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onCancelReply, modifier = Modifier.size(24.dp)) {
                    Text("✕", fontSize = 12.sp, color = onSurfaceColor.copy(alpha = 0.6f))
                }
            }
        }

        if (!state.composerEnabled) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("read_only_composer")
            ) {
                Text(
                    text = state.readOnlyReason.ifBlank { "Вы не можете отправлять сообщения в этой группе" },
                    modifier = Modifier.padding(14.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onAttach,
                enabled = state.mediaComposerEnabled && !state.isSending,
                modifier = Modifier.testTag("group_attach_button")
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_attach_paperclip),
                    contentDescription = "Attach File",
                    tint = if (state.mediaComposerEnabled) primaryColor else onSurfaceColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .testTag("group_composer_input"),
                enabled = state.textComposerEnabled && !state.isSending,
                placeholder = {
                    Text(
                        state.composerPlaceholder.ifBlank { "Сообщение в группу..." },
                        fontSize = 14.sp
                    )
                },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryColor,
                    unfocusedBorderColor = primaryColor.copy(alpha = 0.3f),
                    focusedContainerColor = primaryColor.copy(alpha = 0.04f),
                    unfocusedContainerColor = surfaceColor
                )
            )

            Spacer(Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (draft.isNotBlank() && state.textComposerEnabled && !state.isSending) primaryColor
                        else primaryColor.copy(alpha = 0.3f)
                    )
                    .clickable(
                        enabled = draft.isNotBlank() && state.textComposerEnabled && !state.isSending,
                        onClick = onSend
                    )
                    .testTag("group_send_button"),
                contentAlignment = Alignment.Center
            ) {
                if (state.isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_send_airplane),
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleBadge(role: GroupRole) {
    val (badgeColor, textColor) = when (role) {
        GroupRole.OWNER -> Pair(Color(0xFFE53935), Color.White)
        GroupRole.ADMIN -> Pair(Color(0xFF1E88E5), Color.White)
        GroupRole.MODERATOR -> Pair(Color(0xFF43A047), Color.White)
        GroupRole.MEMBER -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(
        color = badgeColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            role.label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
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
