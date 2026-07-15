package com.example.twopchat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.R

@Composable
fun AttachmentPanel(
    primaryColor: Color,
    @Suppress("UNUSED_PARAMETER") surfaceVariant: Color,
    onSurfaceColor: Color,
    onAttachmentClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val attachments = listOf(
            AttachmentItem("Camera", R.drawable.ic_attach_camera, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("Gallery", R.drawable.ic_attach_gallery, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("Video", R.drawable.ic_voice_play, primaryColor.copy(alpha = 0.1f)),
            AttachmentItem("File", R.drawable.ic_attach_file, primaryColor.copy(alpha = 0.1f)),
        )
        attachments.forEach { item ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onAttachmentClick(item.label) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .background(item.bgColor, CircleShape)
                        .border(0.5.dp, primaryColor.copy(alpha = 0.2f), CircleShape),
                ) {
                    Icon(
                        painter = painterResource(id = item.iconRes),
                        contentDescription = item.label,
                        tint = primaryColor,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(item.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = onSurfaceColor)
            }
        }
    }
}

private data class AttachmentItem(
    val label: String,
    val iconRes: Int,
    val bgColor: Color,
)
