package com.example.twopchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import com.example.twopchat.theme.MintGreen
import com.example.twopchat.theme.StealthBlack

data class PinnedItemModel(
    val id: String,
    val senderName: String,
    val text: String,
    val timestamp: String,
    val attachmentType: String? = null,
    val attachmentName: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedMessagesSheet(
    pinnedItems: List<PinnedItemModel>,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    onSurfaceVariant: Color,
    onDismiss: () -> Unit,
    onSelectPinnedMessage: (PinnedItemModel) -> Unit,
    onUnpinMessage: (PinnedItemModel) -> Unit,
    onUnpinAll: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = surfaceColor,
        contentColor = onSurfaceColor,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_pin),
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (appLanguage == "Русский") "Закреплённые сообщения" else "Pinned Messages",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor,
                    modifier = Modifier.weight(1f)
                )
                if (pinnedItems.isNotEmpty()) {
                    TextButton(onClick = {
                        onUnpinAll()
                        onDismiss()
                    }) {
                        Text(
                            text = if (appLanguage == "Русский") "Открепить все" else "Unpin All",
                            color = Color.Red,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            HorizontalDivider(color = onSurfaceColor.copy(alpha = 0.08f), thickness = 1.dp)

            if (pinnedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (appLanguage == "Русский") "Нет закреплённых сообщений" else "No pinned messages",
                        color = onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(pinnedItems, key = { it.id }) { item ->
                        val previewText = when {
                            item.text.isNotBlank() -> item.text
                            item.attachmentType == "IMAGE" -> if (appLanguage == "Русский") "📷 Фотография" else "📷 Photo"
                            item.attachmentType == "VIDEO" -> if (appLanguage == "Русский") "🎥 Видеозапись" else "🎥 Video"
                            item.attachmentType == "VOICE" -> if (appLanguage == "Русский") "🎤 Голосовое сообщение" else "🎤 Voice Message"
                            item.attachmentType == "FILE" -> "📁 ${item.attachmentName ?: "File"}"
                            else -> if (appLanguage == "Русский") "Вложение" else "Attachment"
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectPinnedMessage(item)
                                    onDismiss()
                                }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(primaryColor.copy(alpha = 0.14f), CircleShape)
                            ) {
                                Text(
                                    text = item.senderName.take(1).uppercase(),
                                    color = primaryColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = item.senderName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryColor
                                    )
                                    Text(
                                        text = item.timestamp,
                                        fontSize = 11.sp,
                                        color = onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = previewText,
                                    fontSize = 13.sp,
                                    color = onSurfaceColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { onUnpinMessage(item) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text(
                                    text = "×",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
