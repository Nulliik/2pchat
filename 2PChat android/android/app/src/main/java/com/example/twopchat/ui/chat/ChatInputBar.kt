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

import androidx.compose.foundation.layout.navigationBarsPadding

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
    recordingAmplitudes: List<Float> = emptyList(),
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
            .background(surfaceColor)
            .navigationBarsPadding()
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

        ConversationMessagePreviewBar(
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
        ConversationMessagePreviewBar(
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
            else -> ConversationComposerRow(
                attachmentsOpen = showAttachments,
                isRecordingVoice = isRecordingVoice,
                recordingElapsedMs = recordingElapsedMs,
                recordingAmplitudes = recordingAmplitudes,
                isEditing = editingMessage != null,
                inputText = inputText,
                placeholder = Localizations.getString("write_placeholder", appLanguage),
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

