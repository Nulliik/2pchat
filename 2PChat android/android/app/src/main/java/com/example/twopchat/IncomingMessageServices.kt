package com.example.twopchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONObject

internal data class IncomingAttachment(
    val messageId: String,
    val displayMessage: String,
    val attachmentType: String,
    val attachmentUri: String,
    val attachmentName: String,
)

internal object IncomingMessageParser {
    fun parseAttachment(text: String): IncomingAttachment? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val json = JSONObject(trimmed)
            if (json.optString("type") != "file") return null
            val fileName = json.optString("file_name", "file")
            val filePath = json.optString("file_path", "")
            val mime = json.optString("mime", "")
            val attachmentType = VoiceMessageSupport.attachmentType(fileName, mime)
            IncomingAttachment(
                messageId = json.optString("message_id"),
                displayMessage = VoiceMessageSupport.displayMessage(attachmentType, fileName),
                attachmentType = attachmentType,
                attachmentUri = filePath,
                attachmentName = fileName,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseNotificationText(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return text
        return try {
            val json = JSONObject(trimmed)
            when (json.optString("type")) {
                "file" -> {
                    val fileName = json.optString("file_name", "file")
                    val mime = json.optString("mime", "")
                    val attachmentType = VoiceMessageSupport.attachmentType(fileName, mime)
                    VoiceMessageSupport.displayMessage(attachmentType, fileName)
                }
                "reply" -> {
                    json.optString("text", "")
                }
                else -> text
            }
        } catch (_: Exception) {
            text
        }
    }
}

internal class MessageNotificationService {
    companion object {
        private const val CHANNEL_ID = "p2p_chat_messages"
    }

    fun show(context: Context, sender: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "P2P Messages", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent()
        val pendingIntent = PendingIntent.getActivity(
            context,
            sender.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val displayText = IncomingMessageParser.parseNotificationText(text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(sender)
            .setContentText(displayText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(sender.hashCode(), notification)
    }
}
