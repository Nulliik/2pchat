package com.example.twopchat.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Configuration-stable owner for chat state which must outlive a Composable instance. */
class ChatScreenViewModel : ViewModel() {
    val messages = mutableStateListOf<Message>()
    val isHistoryLoading = mutableStateOf(true)
    val inputText = mutableStateOf("")
    val replyingToMessage = mutableStateOf<Message?>(null)
    val editingMessage = mutableStateOf<Message?>(null)
    val selectedMessageForOptions = mutableStateOf<Message?>(null)
    val selectedMessages = mutableStateListOf<Message>()

}

/**
 * Combines a fresh database snapshot with messages which may have arrived on the
 * main thread while the database query was running. Persisted rows are
 * authoritative; only IDs absent from the snapshot are retained from memory.
 */
internal fun mergeHistorySnapshot(
    persistedMessages: List<Message>,
    currentMessages: List<Message>,
    defaultMessages: List<Message>,
    persistHistory: Boolean,
): List<Message> {
    val visiblePersisted = if (persistHistory) {
        if (persistedMessages.isEmpty()) defaultMessages else persistedMessages
    } else {
        defaultMessages + persistedMessages.filter { it.status == "PENDING" }
    }
    val merged = LinkedHashMap<String, Message>()
    visiblePersisted.forEach { merged[it.id] = it }
    currentMessages.forEach { message ->
        if (message.id !in merged) merged[message.id] = message
    }
    return merged.values.toList()
}
