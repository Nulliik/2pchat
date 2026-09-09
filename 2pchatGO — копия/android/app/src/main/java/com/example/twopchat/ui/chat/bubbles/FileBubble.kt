package com.example.twopchat.ui.chat.bubbles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.ui.chat.Message

@Composable
fun FileBubble(
    message: Message,
    textColor: Color,
    onFileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(12.dp)
            .clickable { message.attachmentUri?.let(onFileClick) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📁 ${message.attachmentName ?: "File"}",
                color = textColor,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
        if (message.text.isNotBlank() && message.text != message.attachmentName) {
            Text(
                text = message.text,
                color = textColor.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
