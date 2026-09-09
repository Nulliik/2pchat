package com.example.twopchat.group.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.group.ui.GroupSystemEventType
import com.example.twopchat.group.ui.GroupTimelineMessage

@Composable
fun GroupSystemMessageBubble(
    message: GroupTimelineMessage,
    appLanguage: String,
    modifier: Modifier = Modifier,
) {
    val formattedText = remember(message.messageId, appLanguage) {
        GroupSystemMessageFormatter.format(message, appLanguage)
    }

    if (formattedText.isBlank()) return

    val isDark = isSystemInDarkTheme()
    val bubbleColor = if (isDark) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (isDark) 0.85f else 0.75f
    )

    val icon: ImageVector = when (message.systemEventType) {
        GroupSystemEventType.MEMBER_ADDED -> Icons.Default.Add
        GroupSystemEventType.MEMBER_REMOVED -> Icons.Default.Close
        GroupSystemEventType.MEMBER_LEFT -> Icons.AutoMirrored.Filled.ExitToApp
        GroupSystemEventType.ROLE_CHANGED -> Icons.Default.Person
        GroupSystemEventType.GROUP_NAME_CHANGED -> Icons.Default.Edit
        GroupSystemEventType.OWNERSHIP_TRANSFERRED -> Icons.Default.Star
        null -> Icons.Default.Info
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formattedText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = contentColor,
                textAlign = TextAlign.Center,
            )
        }
    }
}
