package com.example.twopchat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R

@Composable
internal fun PinnedMessageBar(
    pinnedMessage: Message?,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onPinnedClick: (Message) -> Unit,
    onUnpinClick: (Message) -> Unit,
    pinnedCount: Int = 1,
    currentIndex: Int = 1,
    onOpenSheet: (() -> Unit)? = null,
) {
    val previewText = pinnedMessage?.let { message ->
        when {
            message.text.isNotBlank() -> message.text
            message.attachmentType == "IMAGE" -> if (appLanguage == "Русский") "📷 Фотография" else "📷 Photo"
            message.attachmentType == "VIDEO" -> if (appLanguage == "Русский") "🎥 Видеозапись" else "🎥 Video"
            message.attachmentType == "VOICE" -> if (appLanguage == "Русский") "🎤 Голосовое сообщение" else "🎤 Voice Message"
            message.attachmentType == "FILE" -> "📁 ${message.attachmentName ?: "File"}"
            message.albumMediaUris.isNotEmpty() -> if (appLanguage == "Русский") "🖼️ Альбом медиа" else "🖼️ Media Album"
            else -> ""
        }
    }.orEmpty()
    ConversationPinnedMessageBar(
        visible = pinnedMessage != null,
        title = if (appLanguage == "Русский") "Закреплённое сообщение" else "Pinned Message",
        preview = previewText,
        primaryColor = primaryColor,
        surfaceColor = surfaceColor,
        onSurfaceColor = onSurfaceColor,
        onSurfaceVariant = onSurfaceVariant,
        onClick = { pinnedMessage?.let(onPinnedClick) },
        onUnpin = { pinnedMessage?.let(onUnpinClick) },
        pinnedCount = pinnedCount,
        currentIndex = currentIndex,
        onOpenSheet = onOpenSheet,
    )
}
