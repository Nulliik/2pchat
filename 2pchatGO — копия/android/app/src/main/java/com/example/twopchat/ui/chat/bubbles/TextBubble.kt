package com.example.twopchat.ui.chat.bubbles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.ui.chat.Message

@Composable
fun TextBubble(
    message: Message,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (!message.replyToText.isNullOrBlank()) {
            Text(
                text = "Replying to ${message.replyToName ?: "message"}: ${message.replyToText}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Text(
            text = message.text,
            color = textColor,
            fontSize = 15.sp
        )
    }
}
