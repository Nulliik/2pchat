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
    onUnpinClick: (Message) -> Unit
) {
    AnimatedVisibility(
        visible = pinnedMessage != null,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        if (pinnedMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(surfaceColor.copy(alpha = 0.92f))
                    .clickable { onPinnedClick(pinnedMessage) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Vertical accent bar
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(34.dp)
                        .background(primaryColor, RoundedCornerShape(2.dp))
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_pin),
                            contentDescription = "Pinned",
                            tint = primaryColor,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (appLanguage == "Русский") "Закреплённое сообщение" else "Pinned Message",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor
                        )
                    }

                    val previewText = when {
                        pinnedMessage.text.isNotBlank() -> pinnedMessage.text
                        pinnedMessage.attachmentType == "IMAGE" -> if (appLanguage == "Русский") "📷 Фотография" else "📷 Photo"
                        pinnedMessage.attachmentType == "VIDEO" -> if (appLanguage == "Русский") "🎥 Видеозапись" else "🎥 Video"
                        pinnedMessage.attachmentType == "VOICE" -> if (appLanguage == "Русский") "🎤 Голосовое сообщение" else "🎤 Voice Message"
                        pinnedMessage.attachmentType == "FILE" -> "📁 ${pinnedMessage.attachmentName ?: "File"}"
                        pinnedMessage.albumMediaUris.isNotEmpty() -> if (appLanguage == "Русский") "🖼️ Альбом медиа" else "🖼️ Media Album"
                        else -> ""
                    }

                    Text(
                        text = previewText,
                        fontSize = 12.sp,
                        color = onSurfaceColor.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { onUnpinClick(pinnedMessage) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text(
                        text = "×",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = onSurfaceVariant
                    )
                }
            }
        }
    }
}
