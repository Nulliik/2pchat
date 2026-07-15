package com.example.twopchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.VoiceMessageSupport
import com.example.twopchat.data.Localizations
import com.example.twopchat.theme.StealthBlack

@Composable
internal fun ChatInputBar(
    showAttachments: Boolean,
    replyingToMessage: Message?,
    editingMessage: Message?,
    isSelectMode: Boolean,
    selectedCount: Int,
    isBlocked: Boolean,
    isRecordingVoice: Boolean,
    recordingElapsedMs: Int,
    inputText: String,
    peerName: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    surfaceVariant: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onAttachmentClick: (String) -> Unit,
    onDismissReply: () -> Unit,
    onDismissEditing: () -> Unit,
    onCancelSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onUnblock: () -> Unit,
    onToggleAttachments: () -> Unit,
    onInputTextChange: (String) -> Unit,
    onActionClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor)
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        AnimatedVisibility(
            visible = showAttachments,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            AttachmentPanel(
                primaryColor = primaryColor,
                surfaceVariant = surfaceVariant,
                onSurfaceColor = onSurfaceColor,
                onAttachmentClick = onAttachmentClick,
            )
        }

        MessagePreviewBar(
            visible = replyingToMessage != null,
            title = replyingToMessage?.let {
                if (it.isMe) {
                    if (appLanguage == "Русский") "Вы" else "You"
                } else peerName
            }.orEmpty(),
            text = replyingToMessage?.text.orEmpty(),
            primaryColor = primaryColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onDismiss = onDismissReply,
        )
        MessagePreviewBar(
            visible = editingMessage != null,
            title = if (appLanguage == "Русский") "Редактирование сообщения" else "Edit Message",
            text = editingMessage?.text.orEmpty(),
            primaryColor = primaryColor,
            onSurfaceColor = onSurfaceColor,
            onSurfaceVariant = onSurfaceVariant,
            onDismiss = onDismissEditing,
        )

        when {
            isSelectMode -> SelectionBar(
                selectedCount = selectedCount,
                appLanguage = appLanguage,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onCancel = onCancelSelection,
                onDelete = onDeleteSelected,
            )
            isBlocked && peerName != "Saved Messages" -> BlockedBar(
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                onUnblock = onUnblock,
            )
            else -> ComposerRow(
                showAttachments = showAttachments,
                isRecordingVoice = isRecordingVoice,
                recordingElapsedMs = recordingElapsedMs,
                editingMessage = editingMessage,
                inputText = inputText,
                appLanguage = appLanguage,
                primaryColor = primaryColor,
                surfaceColor = surfaceColor,
                onSurfaceColor = onSurfaceColor,
                onSurfaceVariant = onSurfaceVariant,
                onToggleAttachments = onToggleAttachments,
                onInputTextChange = onInputTextChange,
                onActionClick = onActionClick,
            )
        }
    }
}

@Composable
private fun MessagePreviewBar(
    visible: Boolean,
    title: String,
    text: String,
    primaryColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(onSurfaceColor.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(36.dp).background(primaryColor, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryColor)
                Text(text, fontSize = 11.sp, color = onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Text("×", fontSize = 18.sp, color = onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    appLanguage: String,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Text("×", fontSize = 24.sp, color = onSurfaceColor, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (appLanguage == "Русский") "Выбрано: $selectedCount" else "Selected: $selectedCount",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurfaceColor,
            )
        }
        IconButton(onClick = onDelete, enabled = selectedCount > 0) {
            Icon(
                painterResource(R.drawable.ic_delete),
                contentDescription = "Delete Selected",
                tint = if (selectedCount > 0) Color.Red else onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun BlockedBar(appLanguage: String, primaryColor: Color, onUnblock: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (appLanguage == "Русский") "Пользователь заблокирован" else "User is blocked",
            color = MaterialTheme.colorScheme.error,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (appLanguage == "Русский") "Разблокировать" else "Unblock",
            color = primaryColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onUnblock).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ComposerRow(
    showAttachments: Boolean,
    isRecordingVoice: Boolean,
    recordingElapsedMs: Int,
    editingMessage: Message?,
    inputText: String,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onToggleAttachments: () -> Unit,
    onInputTextChange: (String) -> Unit,
    onActionClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onToggleAttachments,
            modifier = Modifier.size(44.dp).background(onSurfaceColor.copy(alpha = 0.03f), CircleShape),
        ) {
            if (showAttachments || isRecordingVoice) {
                Text("×", fontSize = 22.sp, color = primaryColor, fontWeight = FontWeight.Bold)
            } else {
                Icon(painterResource(R.drawable.ic_attach_paperclip), "Attach", tint = primaryColor, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        val inputBg = if (surfaceColor.luminance() < 0.5f) Color(0xFF0F1012) else Color(0xFFE4E7EC)
        if (isRecordingVoice) {
            Row(
                modifier = Modifier.weight(1f).height(48.dp).background(inputBg, RoundedCornerShape(22.dp))
                    .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f), RoundedCornerShape(22.dp)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).background(Color.Red, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(VoiceMessageSupport.formatDuration(recordingElapsedMs), color = onSurfaceColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(if (appLanguage == "Русский") "Нажмите × для отмены" else "Tap × to cancel", color = onSurfaceVariant, fontSize = 11.sp)
            }
        } else {
            TextField(
                value = inputText,
                onValueChange = onInputTextChange,
                placeholder = { Text(Localizations.getString("write_placeholder", appLanguage), color = onSurfaceVariant.copy(alpha = 0.6f)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = inputBg,
                    unfocusedContainerColor = inputBg,
                    focusedTextColor = onSurfaceColor,
                    unfocusedTextColor = onSurfaceColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(22.dp),
                maxLines = 3,
                modifier = Modifier.weight(1f).border(
                    0.5.dp,
                    onSurfaceColor.copy(alpha = if (surfaceColor.luminance() > 0.5f) 0.09f else 0.05f),
                    RoundedCornerShape(22.dp),
                ),
            )
        }
        Spacer(Modifier.width(10.dp))
        IconButton(
            onClick = onActionClick,
            modifier = Modifier.size(44.dp).background(primaryColor, CircleShape),
        ) {
            Icon(
                painterResource(
                    when {
                        isRecordingVoice -> R.drawable.ic_voice_stop
                        editingMessage != null -> R.drawable.ic_check
                        inputText.isBlank() -> R.drawable.ic_voice_mic
                        else -> R.drawable.ic_send_airplane
                    },
                ),
                contentDescription = "Send message",
                tint = if (primaryColor == com.example.twopchat.theme.MintGreen) StealthBlack else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
