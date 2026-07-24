package com.example.twopchat.data.repository

import android.content.Context
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import java.io.File

class DefaultChatRepository : ChatRepository {

    override fun getMessagesForPeer(context: Context, peerName: String): List<Message> {
        return ChatDatabaseHelper.getInstance(context).getMessagesForPeer(peerName)
    }

    override fun getLastMessageForPeer(context: Context, peerName: String): Message? {
        return ChatDatabaseHelper.getInstance(context).getLastMessageForPeer(peerName)
    }

    override fun saveMessage(context: Context, peerName: String, message: Message) {
        ChatDatabaseHelper.getInstance(context).saveMessage(peerName, message)
    }

    override fun updateMessageStatus(context: Context, messageId: String, status: String) {
        ChatDatabaseHelper.getInstance(context).updateMessageStatus(messageId, status)
    }

    override fun updateMessageText(context: Context, messageId: String, text: String) {
        ChatDatabaseHelper.getInstance(context).updateMessageText(messageId, text)
    }

    override fun deleteMessage(context: Context, peerName: String, messageId: String) {
        ChatDatabaseHelper.getInstance(context).deleteMessage(messageId)
    }

    override fun sendMessage(
        context: Context,
        endpoint: String,
        text: String,
        onResult: (Boolean) -> Unit
    ) {
        P2PMessageRelay.sendMessage(context, endpoint, "", text, onResult)
    }

    override fun sendFile(
        context: Context,
        peerName: String,
        endpoint: String,
        file: File,
        caption: String,
        messageId: String,
        onResult: (Boolean) -> Unit
    ) {
        P2PMessageRelay.sendFile(
            context = context,
            peerName = peerName,
            endpoint = endpoint,
            filePath = file.absolutePath,
            messageId = messageId,
            caption = caption,
            onResult = onResult,
        )
    }

    override fun cancelFileTransfer(context: Context, peerName: String, messageId: String): Boolean {
        return P2PMessageRelay.cancelFileTransfer(context, peerName, messageId)
    }
}
