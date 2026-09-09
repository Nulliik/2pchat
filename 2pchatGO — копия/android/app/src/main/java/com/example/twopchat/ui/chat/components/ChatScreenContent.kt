package com.example.twopchat.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.twopchat.ui.chat.Message
import com.example.twopchat.ui.chat.bubbles.FileBubble
import com.example.twopchat.ui.chat.bubbles.MediaBubble
import com.example.twopchat.ui.chat.bubbles.TextBubble
import com.example.twopchat.ui.chat.bubbles.VoiceBubble
import com.example.twopchat.ui.chat.state.ChatScreenState

@Composable
fun ChatScreenContent(
    state: ChatScreenState,
    onSendMessage: (String) -> Unit,
    onMediaClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                items(
                    items = state.messages,
                    key = { it.id }
                ) { message ->
                    val textColor = if (message.isMe) Color.White else Color.Black
                    when (message.attachmentType) {
                        "photo", "video", "album" -> MediaBubble(message, textColor, onMediaClick)
                        "voice", "audio" -> VoiceBubble(message, textColor, onMediaClick)
                        "file" -> FileBubble(message, textColor, onMediaClick)
                        else -> TextBubble(message, textColor)
                    }
                }
            }
        }
    }
}
