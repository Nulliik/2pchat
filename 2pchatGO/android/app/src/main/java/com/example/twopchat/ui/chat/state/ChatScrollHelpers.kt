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

private val verifiedImageFileCache = android.util.LruCache<String, Boolean>(256)

private fun hasImageMagicHeader(file: File): Boolean {
    if (!file.isFile || file.length() < 12) return false
    val cached = verifiedImageFileCache.get(file.absolutePath)
    if (cached != null) return cached
    val isImg = try {
        java.io.FileInputStream(file).use { stream ->
            val header = ByteArray(12)
            val read = stream.read(header)
            if (read < 12) return@use false
            // PNG: 89 50 4E 47
            if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) true
            // JPEG: FF D8 FF
            else if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) true
            // GIF: GIF87a / GIF89a
            else if (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte()) true
            // WebP: RIFF....WEBP
            else if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) true
            // BMP: BM
            else if (header[0] == 0x42.toByte() && header[1] == 0x4D.toByte()) true
            else false
        }
    } catch (_: Exception) {
        false
    }
    verifiedImageFileCache.put(file.absolutePath, isImg)
    return isImg
}

internal fun repairMisclassifiedLocalImage(message: Message): Message {
    if (message.attachmentType != "FILE") return message
    val path = message.attachmentUri?.takeIf { it.isNotBlank() && "://" !in it } ?: return message
    val file = File(path)
    if (!hasImageMagicHeader(file)) return message
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        message.copy(attachmentType = "IMAGE")
    } else {
        message
    }
}
