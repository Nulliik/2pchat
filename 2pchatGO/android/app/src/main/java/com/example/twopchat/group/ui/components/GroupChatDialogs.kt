package com.example.twopchat.group.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.twopchat.R
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.runtime.GroupChatCoordinator
import com.example.twopchat.group.ui.GroupChatUiState
import com.example.twopchat.group.ui.GroupRole
import com.example.twopchat.group.ui.GroupTimelineMessage
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.security.SecureStorage
import com.example.twopchat.ui.chat.Message
import com.example.twopchat.ui.common.RecipientItem
import com.example.twopchat.ui.common.RecipientPickerDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun GroupProcessingAlbumDialog(appLanguage: String) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = if (appLanguage == "Русский") "Подготовка медиафайлов..." else "Preparing media files...",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun GroupEditMessageDialog(
    context: Context,
    message: GroupTimelineMessage,
    appLanguage: String,
    surfaceColor: Color,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var editedText by remember(message.messageId) { mutableStateOf(message.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (appLanguage == "Русский") "Редактировать сообщение" else "Edit Message", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = editedText,
                onValueChange = { editedText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("edit_message_input"),
                keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                    context = context,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                ),
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            TextButton(
                enabled = editedText.trim().isNotEmpty(),
                onClick = {
                    onSave(editedText.trim())
                    onDismiss()
                }
            ) {
                Text(if (appLanguage == "Русский") "Сохранить" else "Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun GroupDeleteMessageDialog(
    appLanguage: String,
    surfaceColor: Color,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (appLanguage == "Русский") "Удалить сообщение?" else "Delete Message?", fontWeight = FontWeight.Bold) },
        text = { Text(if (appLanguage == "Русский") "Это действие зафиксируется в журнале событий группы." else "This action will be logged in the group audit event log.") },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmDelete()
                    onDismiss()
                },
                modifier = Modifier.testTag("confirm_delete_message")
            ) {
                Text(if (appLanguage == "Русский") "Удалить" else "Delete", color = Color.Red, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (appLanguage == "Русский") "Отмена" else "Cancel")
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun GroupForwardDialog(
    context: Context,
    state: GroupChatUiState,
    messageToForward: GroupTimelineMessage,
    appLanguage: String,
    primaryColor: Color,
    onDismiss: () -> Unit,
) {
    val knownPeers = com.example.twopchat.config.P2PPreferences.getAllKnownPeers(context)
    val allPeers = (knownPeers + "Saved Messages").filter { it.isNotBlank() && it != "null" }.distinct()
    val allGroups = GroupChatCoordinator.visibleGroups()

    val groupItems = allGroups.filter { it.groupId != state.groupId }.map { g ->
        RecipientItem(
            id = "group_${g.groupId}",
            title = g.title,
            subtitle = if (appLanguage == "Русский") "Группа" else "Group",
            isOnline = true,
            isGroup = true,
        )
    }

    val peerItems = allPeers.map { name ->
        val avatar = P2PMessageRelay.peerAvatars[name]
        val isOnline = P2PMessageRelay.peerSessionStates[name] == true || name == "Saved Messages"
        val subtitle = when {
            name == "Saved Messages" -> if (appLanguage == "Русский") "Личное хранилище" else "Personal storage"
            isOnline -> if (appLanguage == "Русский") "В сети" else "Online"
            else -> if (appLanguage == "Русский") "Был(а) недавно" else "Offline"
        }
        val initials = if (name == "Saved Messages") {
            "🔖"
        } else if (name.contains(" ")) {
            name.split(" ").map { it.take(1) }.joinToString("")
        } else {
            name.take(2).uppercase()
        }
        RecipientItem(
            id = "peer_$name",
            title = name,
            subtitle = subtitle,
            isOnline = isOnline,
            avatarBitmap = avatar,
            initials = initials,
            isGroup = false,
        )
    }

    RecipientPickerDialog(
        title = if (appLanguage == "Русский") "Переслать сообщение" else "Forward Message",
        searchPlaceholder = if (appLanguage == "Русский") "Поиск получателя..." else "Search recipient...",
        recipients = groupItems + peerItems,
        primaryColor = primaryColor,
        onDismiss = onDismiss,
        onRecipientSelected = { item ->
            onDismiss()
            val textToForward = messageToForward.text
            val att = messageToForward.attachment
            if (item.isGroup) {
                val targetGroupId = item.id.removePrefix("group_")
                if (att != null && att.fileName.isNotBlank()) {
                    GroupChatCoordinator.sendAttachment(
                        targetGroupId,
                        att.fileName,
                        att.mimeType,
                    )
                } else {
                    GroupChatCoordinator.sendMessage(targetGroupId, textToForward, null)
                }
                Toast.makeText(context, if (appLanguage == "Русский") "Переслано в ${item.title}" else "Forwarded to ${item.title}", Toast.LENGTH_SHORT).show()
            } else {
                val chatName = item.id.removePrefix("peer_")
                if (P2PPreferences.isPeerIdentityChangePending(context, chatName)) {
                    Toast.makeText(
                        context,
                        if (appLanguage == "Русский") "В чате $chatName отправка приостановлена из-за смены ключа" else "Sending to $chatName is paused because its key changed",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@RecipientPickerDialog
                }
                val forwardTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val forwardEndpoint = P2PMessageRelay.peerEndpoints[chatName]
                val fwdInitialStatus = if (forwardEndpoint != null || chatName == "Saved Messages") "SENT" else "PENDING"
                val fwdMsg = Message(
                    id = java.util.UUID.randomUUID().toString(),
                    text = textToForward,
                    isMe = true,
                    timestamp = forwardTime,
                    attachmentType = if (att != null) (if (att.mimeType.startsWith("image/")) "IMAGE" else if (att.mimeType.startsWith("video/")) "VIDEO" else "FILE") else null,
                    attachmentUri = att?.localPath,
                    attachmentName = att?.fileName,
                    status = fwdInitialStatus,
                )

                com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).saveMessage(chatName, fwdMsg)
                com.example.twopchat.config.P2PPreferences.prefs(context).edit().putString("last_msg_$chatName", SecureStorage.encrypt("You: $textToForward")).apply()

                if (forwardEndpoint != null && chatName != "Saved Messages") {
                    val attachUri = att?.localPath
                    if (fwdMsg.attachmentType != null && attachUri != null) {
                        P2PMessageRelay.sendFile(context, chatName, forwardEndpoint, attachUri, fwdMsg.id) { success ->
                            if (!success) {
                                com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).updateMessageStatus(fwdMsg.id, "PENDING")
                            }
                        }
                    } else {
                        val username = P2PPreferences.prefs(context).getString("local_username", "Me") ?: "Me"
                        P2PMessageRelay.sendMessage(context, forwardEndpoint, username, textToForward) { success ->
                            if (!success) {
                                com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).updateMessageStatus(fwdMsg.id, "PENDING")
                            }
                        }
                    }
                }
                Toast.makeText(context, if (appLanguage == "Русский") "Переслано в $chatName" else "Forwarded to $chatName", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

@Composable
fun GroupSeenByDialog(
    msg: GroupTimelineMessage,
    state: GroupChatUiState,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = surfaceColor,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_msg_single_check),
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Просмотрели (${msg.readByMembers.size})" else "Seen by (${msg.readByMembers.size})",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceColor
                    )
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.08f))
                Spacer(Modifier.height(10.dp))

                if (msg.readByMembers.isEmpty()) {
                    Text(
                        text = if (appLanguage == "Русский") "Ещё никто не прочитал" else "No one has read this message yet",
                        fontSize = 14.sp,
                        color = onSurfaceColor.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                        items(msg.readByMembers.toList()) { memberId ->
                            val memberInfo = state.members.find { it.memberId == memberId || it.displayName == memberId }
                            val name = memberInfo?.displayName ?: memberId
                            val role = memberInfo?.role ?: GroupRole.MEMBER
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = primaryColor.copy(alpha = 0.15f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = name.take(1).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = primaryColor,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = onSurfaceColor
                                    )
                                    if (role != GroupRole.MEMBER) {
                                        Text(
                                            text = role.getLocalizedLabel(appLanguage),
                                            fontSize = 11.sp,
                                            color = primaryColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = if (appLanguage == "Русский") "Закрыть" else "Close",
                            color = primaryColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDatePickerDialog(
    selectedDateFilterMs: Long?,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onDismiss: () -> Unit,
    onDateSelected: (Long) -> Unit,
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDateFilterMs ?: System.currentTimeMillis()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { dateMs ->
                        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateMs }
                        val localCal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                            set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onDateSelected(localCal.timeInMillis)
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = primaryColor)
            ) {
                Text(
                    text = if (appLanguage == "Русский") "ОК" else "OK",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = primaryColor)
            ) {
                Text(
                    text = if (appLanguage == "Русский") "ОТМЕНА" else "CANCEL",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        colors = DatePickerDefaults.colors(
            containerColor = surfaceColor,
        )
    ) {
        DatePicker(
            state = datePickerState,
            colors = DatePickerDefaults.colors(
                titleContentColor = onSurfaceColor,
                headlineContentColor = onSurfaceColor,
                weekdayContentColor = onSurfaceColor.copy(alpha = 0.6f),
                subheadContentColor = onSurfaceColor,
                yearContentColor = onSurfaceColor,
                currentYearContentColor = primaryColor,
                selectedYearContentColor = Color.White,
                selectedYearContainerColor = primaryColor,
                dayContentColor = onSurfaceColor,
                selectedDayContentColor = Color.White,
                selectedDayContainerColor = primaryColor,
                todayContentColor = primaryColor,
                todayDateBorderColor = primaryColor,
            )
        )
    }
}

@Composable
fun CreatePollDialog(
    context: Context,
    appLanguage: String,
    onDismiss: () -> Unit,
    onCreatePoll: (question: String, options: List<String>, isAnonymous: Boolean) -> Unit
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var isAnonymous by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (appLanguage == "Русский") "Создать опрос" else "Create Poll", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text(if (appLanguage == "Русский") "Задайте вопрос..." else "Ask a question...") },
                    keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                        context = context,
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(if (appLanguage == "Русский") "Варианты ответов:" else "Options:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                options.forEachIndexed { index, opt ->
                    OutlinedTextField(
                        value = opt,
                        onValueChange = { newText ->
                            options = options.toMutableList().also { it[index] = newText }
                        },
                        placeholder = { Text(if (appLanguage == "Русский") "Вариант ${index + 1}" else "Option ${index + 1}") },
                        keyboardOptions = com.example.twopchat.ui.util.P2PKeyboardOptions.create(
                            context = context,
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (options.size < 6) {
                    TextButton(onClick = { options = options + "" }) {
                        Text(if (appLanguage == "Русский") "+ Добавить вариант" else "+ Add option")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAnonymous, onCheckedChange = { isAnonymous = it })
                    Text(if (appLanguage == "Русский") "Анонимный опрос" else "Anonymous poll", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validOpts = options.map { it.trim() }.filter { it.isNotEmpty() }
                    if (question.isNotBlank() && validOpts.size >= 2) {
                        onCreatePoll(question.trim(), validOpts, isAnonymous)
                        onDismiss()
                    }
                }
            ) { Text(if (appLanguage == "Русский") "Создать" else "Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (appLanguage == "Русский") "Отмена" else "Cancel") }
        }
    )
}
