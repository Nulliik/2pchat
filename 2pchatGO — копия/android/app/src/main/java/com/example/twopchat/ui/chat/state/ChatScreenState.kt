package com.example.twopchat.ui.chat.state

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import com.example.twopchat.ui.chat.Message

@Immutable
data class ChatScreenState(
    val peerName: String = "",
    val isOnline: Boolean = false,
    val peerTransport: String? = null,
    val isVerified: Boolean = false,
    val isBlocked: Boolean = false,
    val avatarBitmap: Bitmap? = null,
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isTyping: Boolean = false,
    val replyToMessage: Message? = null,
    val editingMessage: Message? = null,
    val selectedMessageIds: Set<String> = emptySet(),
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchMatchCount: Int = 0,
    val currentSearchMatchIndex: Int = 0,
    val pinnedMessageId: String? = null,
    val pinnedMessageText: String? = null,
    val pinnedMessageSender: String? = null,
)
