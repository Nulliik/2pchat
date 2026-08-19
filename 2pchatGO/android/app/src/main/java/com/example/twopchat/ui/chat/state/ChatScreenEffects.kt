package com.example.twopchat.ui.chat.state

sealed interface ChatScreenEffect {
    data class ShowToast(val message: String) : ChatScreenEffect
    data class CopyToClipboard(val label: String, val text: String) : ChatScreenEffect
    data object ScrollToBottom : ChatScreenEffect
    data class OpenAttachment(val path: String, val mimeType: String?) : ChatScreenEffect
}
