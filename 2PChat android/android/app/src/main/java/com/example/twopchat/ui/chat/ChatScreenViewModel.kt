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

internal fun fastHistoryMessageLimit(unreadMessageCount: Int): Int =
    unreadMessageCount.coerceIn(1, 100)

/**
 * Applies a cheap recent-history lookup without disturbing the order of an
 * already visible conversation. Existing rows are refreshed in place while
 * missing rows are inserted chronologically. The timestamp-aware insertion
 * also handles a live message arriving while the database query is running.
 */
internal fun mergeRecentHistoryMessages(
    currentMessages: List<Message>,
    recentPersistedMessages: List<Message>,
): List<Message> {
    if (recentPersistedMessages.isEmpty()) return currentMessages
    return currentMessages.toMutableList().apply {
        recentPersistedMessages.forEach { persistedMessage ->
            val existingIndex = indexOfFirst { it.id == persistedMessage.id }
            if (existingIndex >= 0) {
                this[existingIndex] = persistedMessage
            } else {
                val insertionIndex = indexOfFirst { existingMessage ->
                    persistedMessage.sentAtEpochMs > 0L &&
                        existingMessage.sentAtEpochMs > persistedMessage.sentAtEpochMs
                }
                if (insertionIndex >= 0) {
                    add(insertionIndex, persistedMessage)
                } else {
                    add(persistedMessage)
                }
            }
        }
    }
}

/**
 * Combines a fresh database snapshot with messages which may have arrived on the
 * main thread while the database query was running. Persisted rows are
 * authoritative except that an in-memory READ update cannot be reverted by an
 * older query snapshot which still contains a pre-read delivery state.
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
    val currentById = currentMessages.associateBy { it.id }
    visiblePersisted.forEach { persistedMessage ->
        val currentMessage = currentById[persistedMessage.id]
        val currentStatus = currentMessage?.status
        val mergedStatus = if (
            currentStatus?.startsWith("READ") == true &&
            persistedMessage.status?.startsWith("READ") != true
        ) {
            currentStatus
        } else {
            persistedMessage.status
        }
        merged[persistedMessage.id] = if (mergedStatus == persistedMessage.status) {
            persistedMessage
        } else {
            persistedMessage.copy(status = mergedStatus)
        }
    }
    currentMessages.forEach { message ->
        if (message.id !in merged) merged[message.id] = message
    }
    return merged.values.toList()
}
