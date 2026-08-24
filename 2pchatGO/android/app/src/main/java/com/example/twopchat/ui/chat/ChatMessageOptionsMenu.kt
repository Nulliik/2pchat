package com.example.twopchat.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R
import com.example.twopchat.media.GifStorageManager

@Composable
fun ChatMessageOptionsMenu(
    msg: Message,
    appLanguage: String,
    primaryColor: Color,
    surfaceColor: Color,
    onSurfaceColor: Color,
    isForwardingRestricted: Boolean,
    onDismiss: () -> Unit,
    onReactionClick: (String) -> Unit,
    onReply: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onSaveGif: () -> Unit,
    onSaveAttachment: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (appLanguage == "Русский") "Действия с сообщением" else "Message Actions",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )

                // Quick Emoji Reactions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("👍", "❤️", "🔥", "😂", "😮", "😢", "👏", "💩", "🎉", "💯").forEach { emoji ->
                        val senders = msg.reactions[emoji] ?: emptyList()
                        val isSelected = senders.any { it.equals("Me", ignoreCase = true) }
                        val bgColor = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.12f)

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = bgColor,
                            border = if (isSelected) BorderStroke(1.5.dp, primaryColor) else null,
                            modifier = Modifier
                                .height(40.dp)
                                .clickable {
                                    onReactionClick(emoji)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(text = emoji, fontSize = 20.sp)
                                if (senders.isNotEmpty()) {
                                    Text(
                                        text = "${senders.size}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else primaryColor
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = onSurfaceColor.copy(alpha = 0.08f)
                )

                // Reply
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onReply() }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_reply),
                        contentDescription = "Reply",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Ответить" else "Reply",
                        fontSize = 15.sp,
                        color = onSurfaceColor
                    )
                }

                // Pin
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPin() }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_pin),
                        contentDescription = "Pin",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Закрепить" else "Pin",
                        fontSize = 15.sp,
                        color = onSurfaceColor
                    )
                }

                // Edit
                val isEditable = msg.isMe &&
                        (System.currentTimeMillis() - msg.sentAtEpochMs <= 3600_000L) &&
                        msg.attachmentType == null

                if (isEditable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onEdit() }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_edit),
                            contentDescription = "Edit",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = if (appLanguage == "Русский") "Редактировать" else "Edit",
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                    }
                }

                // Copy
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onCopy() }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_copy),
                        contentDescription = "Copy",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Копировать текст" else "Copy Text",
                        fontSize = 15.sp,
                        color = onSurfaceColor
                    )
                }

                // Save GIF
                if (
                    msg.attachmentType == GifStorageManager.ATTACHMENT_TYPE &&
                    msg.attachmentUri != null
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSaveGif() }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_add_photo_smiley),
                            contentDescription = "Save GIF",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = if (appLanguage == "Русский") {
                                "Сохранить в Мои GIF"
                            } else {
                                "Save to My GIFs"
                            },
                            fontSize = 15.sp,
                            color = onSurfaceColor,
                        )
                    }
                }

                // Save Attachment
                if (msg.attachmentUri != null) {
                    val isImage = msg.attachmentType == "IMAGE"
                    val isVideo = msg.attachmentType == "VIDEO"
                    val title = if (isImage) {
                        if (appLanguage == "Русский") "Скачать изображение" else "Save Image"
                    } else if (isVideo) {
                        if (appLanguage == "Русский") "Скачать видео" else "Save Video"
                    } else {
                        if (appLanguage == "Русский") "Скачать файл" else "Save File"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSaveAttachment() }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_download),
                            contentDescription = "Save Attachment",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = title,
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                    }
                }

                // Forward
                if (!isForwardingRestricted) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onForward() }
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_forward),
                            contentDescription = "Forward",
                            tint = onSurfaceColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = if (appLanguage == "Русский") "Переслать" else "Forward",
                            fontSize = 15.sp,
                            color = onSurfaceColor
                        )
                    }
                }

                // Delete
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onDelete() }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = "Delete",
                        tint = Color.Red,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Удалить" else "Delete",
                        fontSize = 15.sp,
                        color = Color.Red
                    )
                }

                // Select
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect() }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_select),
                        contentDescription = "Select",
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = if (appLanguage == "Русский") "Выделить" else "Select",
                        fontSize = 15.sp,
                        color = onSurfaceColor
                    )
                }
            }
        },
        containerColor = surfaceColor,
        shape = RoundedCornerShape(24.dp)
    )
}
