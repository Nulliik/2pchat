package com.example.twopchat.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Configuration-stable owner for chat state which must outlive a Composable instance. */
class ChatScreenViewModel : ViewModel() {
    val messages = mutableStateListOf<Message>()
    val isHistoryLoading = mutableStateOf(false)
    val loadedPersistedMessageCount = mutableIntStateOf(0)
    val hasMoreHistory = mutableStateOf(true)
    val isLoadingOlderHistory = mutableStateOf(false)
    val inputText = mutableStateOf("")
    val replyingToMessage = mutableStateOf<Message?>(null)
    val editingMessage = mutableStateOf<Message?>(null)
    val selectedMessageForOptions = mutableStateOf<Message?>(null)
    val selectedMessages = mutableStateListOf<Message>()

}

internal fun fastHistoryMessageLimit(unreadMessageCount: Int): Int =
    maxOf(40, unreadMessageCount).coerceAtMost(100)

internal const val HISTORY_PAGE_SIZE = 100

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

/**
 * Merges an older database page without disturbing pages already retained by the
 * ViewModel. An overlap can be larger than one row after leaving and reopening
 * a chat, because the ViewModel may still own previously loaded pages.
 */
internal fun mergeOlderHistoryPage(
    currentMessages: List<Message>,
    olderPersistedMessages: List<Message>,
): List<Message> {
    if (olderPersistedMessages.isEmpty()) return currentMessages
    val currentIds = currentMessages.asSequence().map { it.id }.toHashSet()
    if (olderPersistedMessages.none { it.id in currentIds }) {
        return olderPersistedMessages + currentMessages
    }

    val merged = currentMessages.toMutableList()
    olderPersistedMessages.forEachIndexed { pageIndex, persistedMessage ->
        val existingIndex = merged.indexOfFirst { it.id == persistedMessage.id }
        if (existingIndex >= 0) {
            val currentStatus = merged[existingIndex].status
            merged[existingIndex] = if (
                currentStatus?.startsWith("READ") == true &&
                persistedMessage.status?.startsWith("READ") != true
            ) {
                persistedMessage.copy(status = currentStatus)
            } else {
                persistedMessage
            }
        } else {
            val nextAnchorId = olderPersistedMessages
                .asSequence()
                .drop(pageIndex + 1)
                .map { it.id }
                .firstOrNull { candidateId -> merged.any { it.id == candidateId } }
            if (nextAnchorId != null) {
                merged.add(merged.indexOfFirst { it.id == nextAnchorId }, persistedMessage)
            } else {
                val previousAnchorId = olderPersistedMessages
                    .asSequence()
                    .take(pageIndex)
                    .map { it.id }
                    .lastOrNull { candidateId -> merged.any { it.id == candidateId } }
                val insertionIndex = previousAnchorId
                    ?.let { anchorId -> merged.indexOfFirst { it.id == anchorId } + 1 }
                    ?: 0
                merged.add(insertionIndex, persistedMessage)
            }
        }
    }
    return merged
}
