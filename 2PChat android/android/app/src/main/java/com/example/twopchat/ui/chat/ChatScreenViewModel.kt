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

    private var loadedPeer: String? = null

    @Synchronized
    fun beginInitialLoad(peerName: String): Boolean {
        if (loadedPeer == peerName) return false
        loadedPeer = peerName
        isHistoryLoading.value = true
        return true
    }
}
