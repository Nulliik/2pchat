package com.example.twopchat.ui.chat.bubbles

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twopchat.ui.chat.Message
import java.io.File

@Composable
fun MediaBubble(
    message: Message,
    textColor: Color,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(message.attachmentUri) {
        message.attachmentUri?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    Column(modifier = modifier.padding(4.dp)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Media attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { message.attachmentUri?.let(onMediaClick) }
            )
        } else {
            Text(
                text = "📷 ${message.attachmentName ?: "Photo"}",
                color = textColor,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(8.dp)
                    .clickable { message.attachmentUri?.let(onMediaClick) }
            )
        }
        if (message.text.isNotBlank() && message.text != message.attachmentName) {
            Text(
                text = message.text,
                color = textColor,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
