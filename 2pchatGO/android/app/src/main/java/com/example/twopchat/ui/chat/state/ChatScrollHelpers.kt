package com.example.twopchat.ui.chat.state

import android.graphics.BitmapFactory
import com.example.twopchat.ui.chat.Message
import java.io.File

internal fun shouldAutoScrollAfterAppend(
    previousItemCount: Int,
    lastVisibleItemIndex: Int,
    newestMessageIsMine: Boolean,
): Boolean {
    if (newestMessageIsMine || previousItemCount <= 0) return true
    val previousLastIndex = previousItemCount - 1
    return previousLastIndex - lastVisibleItemIndex <= 1
}

internal fun shouldCountIncomingMessage(
    previousItemCount: Int,
    lastVisibleItemIndex: Int,
): Boolean = lastVisibleItemIndex >= 0 && !shouldAutoScrollAfterAppend(
    previousItemCount = previousItemCount,
    lastVisibleItemIndex = lastVisibleItemIndex,
    newestMessageIsMine = false,
)

internal fun didAppendNewestMessage(
    previousMessageCount: Int,
    currentMessageCount: Int,
    previousNewestMessageId: String?,
    currentNewestMessageId: String?,
): Boolean = currentMessageCount > previousMessageCount &&
    currentNewestMessageId != null &&
    currentNewestMessageId != previousNewestMessageId

internal fun isMessageListAtBottom(
    totalItemCount: Int,
    lastVisibleItemIndex: Int,
): Boolean = totalItemCount <= 0 || lastVisibleItemIndex >= totalItemCount - 1

internal fun historyReplacementScrollIndex(
    messages: List<Message>,
    anchorMessageId: String?,
    wasAtBottom: Boolean,
    initialUnreadAnchorMessageId: String? = null,
): Int {
    if (messages.isEmpty()) return -1
    if (anchorMessageId == null && initialUnreadAnchorMessageId != null) {
        val unreadAnchorIndex = messages.indexOfFirst { it.id == initialUnreadAnchorMessageId }
        if (unreadAnchorIndex >= 0) return unreadAnchorIndex
    }
    if (wasAtBottom || anchorMessageId == null) return messages.lastIndex
    return messages.indexOfFirst { it.id == anchorMessageId }
}

internal fun initialChatScrollIndex(
    messageCount: Int,
    unreadMessageCount: Int,
): Int {
    if (messageCount <= 0) return -1
    if (unreadMessageCount <= 0) return messageCount - 1
    return (messageCount - unreadMessageCount).coerceIn(0, messageCount - 1)
}

internal fun shouldLoadOlderHistory(
    hasAppliedInitialScroll: Boolean,
    firstVisibleItemIndex: Int,
    hasMoreHistory: Boolean,
    isLoadingOlderHistory: Boolean,
    isSearchMode: Boolean,
    showProfileOverlay: Boolean,
): Boolean = hasAppliedInitialScroll &&
    firstVisibleItemIndex <= 2 &&
    hasMoreHistory &&
    !isLoadingOlderHistory &&
    !isSearchMode &&
    !showProfileOverlay

internal fun repairMisclassifiedLocalImage(message: Message): Message {
    if (message.attachmentType != "FILE") return message
    val path = message.attachmentUri?.takeIf { it.isNotBlank() && "://" !in it } ?: return message
    val file = File(path)
    if (!file.isFile) return message
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        message.copy(attachmentType = "IMAGE")
    } else {
        message
    }
}
