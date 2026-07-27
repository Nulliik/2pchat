package com.example.twopchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    isIdentityPaused: Boolean,
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
    suggestedStickers: List<com.example.twopchat.BuiltinSticker> = emptyList(),
    onSelectSuggestedSticker: (com.example.twopchat.BuiltinSticker) -> Unit = {},
    onAttachmentClick: (String) -> Unit,
    onDismissReply: () -> Unit,
    onDismissEditing: () -> Unit,
    onCancelSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onUnblock: () -> Unit,
    onReviewIdentity: () -> Unit,
    onToggleAttachments: () -> Unit,
    onOpenStickerPicker: () -> Unit,
    onInputTextChange: (String) -> Unit,
    onActionClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surfaceColor.copy(alpha = 0.65f))
            .border(0.5.dp, onSurfaceColor.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        StickerSuggestionBar(
            stickers = suggestedStickers,
            primaryColor = primaryColor,
            surfaceVariant = surfaceVariant,
            onStickerSelect = onSelectSuggestedSticker,
        )

        AnimatedVisibility(
            visible = showAttachments,
            enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom, animationSpec = tween(160)) + fadeOut(animationSpec = tween(120)),
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
            isIdentityPaused && peerName != "Saved Messages" -> IdentityPausedBar(
                appLanguage = appLanguage,
                onReviewIdentity = onReviewIdentity,
            )
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
                onOpenStickerPicker = onOpenStickerPicker,
                onInputTextChange = onInputTextChange,
                onActionClick = onActionClick,
            )
        }
    }
}

@Composable
private fun IdentityPausedBar(appLanguage: String, onReviewIdentity: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (appLanguage == "Русский") "Отправка приостановлена" else "Sending paused",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                if (appLanguage == "Русский") "Сначала подтвердите новый ключ" else "Confirm the new key first",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onReviewIdentity) {
            Text(if (appLanguage == "Русский") "Проверить" else "Review")
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
    onOpenStickerPicker: () -> Unit,
    onInputTextChange: (String) -> Unit,
    onActionClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onToggleAttachments,
            modifier = Modifier
                .size(42.dp)
                .background(primaryColor.copy(alpha = 0.12f), CircleShape),
        ) {
            if (showAttachments || isRecordingVoice) {
                Text("×", fontSize = 22.sp, color = primaryColor, fontWeight = FontWeight.Bold)
            } else {
                Icon(
                    painterResource(R.drawable.ic_attach_paperclip),
                    "Attach",
                    tint = primaryColor,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val isDark = surfaceColor.luminance() < 0.5f
        val inputBg = if (isDark) Color(0xFF14161A).copy(alpha = 0.9f) else Color(0xFFEFEFEF).copy(alpha = 0.9f)
        if (isRecordingVoice) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .background(inputBg, RoundedCornerShape(23.dp))
                    .border(0.5.dp, primaryColor.copy(alpha = 0.25f), RoundedCornerShape(23.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(VoiceMessageSupport.formatDuration(recordingElapsedMs), color = onSurfaceColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text(if (appLanguage == "Русский") "Нажмите × для отмены" else "Tap × to cancel", color = onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        } else {
            TextField(
                value = inputText,
                onValueChange = onInputTextChange,
                placeholder = {
                    Text(
                        Localizations.getString("write_placeholder", appLanguage),
                        color = onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = inputBg,
                    unfocusedContainerColor = inputBg,
                    focusedTextColor = onSurfaceColor,
                    unfocusedTextColor = onSurfaceColor,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(23.dp),
                maxLines = 4,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        0.5.dp,
                        if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f),
                        RoundedCornerShape(23.dp),
                    ),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (!isRecordingVoice) {
            IconButton(
                onClick = onOpenStickerPicker,
                modifier = Modifier
                    .size(42.dp)
                    .semantics {
                        contentDescription = if (appLanguage == "Русский") {
                            "Открыть стикеры"
                        } else {
                            "Open stickers"
                        }
                    }
                    .background(primaryColor.copy(alpha = 0.12f), CircleShape),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_sticker_smile),
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        IconButton(
            onClick = onActionClick,
            modifier = Modifier
                .size(42.dp)
                .background(primaryColor, CircleShape),
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
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
