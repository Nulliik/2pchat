package com.example.twopchat.data.repository

import android.content.Context
import com.example.twopchat.ui.chat.Message
import java.io.File

interface ChatRepository {
    fun getMessagesForPeer(context: Context, peerName: String): List<Message>
    fun getLastMessageForPeer(context: Context, peerName: String): Message?
    fun saveMessage(context: Context, peerName: String, message: Message)
    fun updateMessageStatus(context: Context, messageId: String, status: String)
    fun updateMessageText(context: Context, messageId: String, text: String)
    fun deleteMessage(context: Context, peerName: String, messageId: String)
    fun sendMessage(context: Context, endpoint: String, text: String, onResult: (Boolean) -> Unit)
    fun sendFile(context: Context, peerName: String, endpoint: String, file: File, caption: String, messageId: String, onResult: (Boolean) -> Unit)
    fun cancelFileTransfer(context: Context, peerName: String, messageId: String): Boolean
}
