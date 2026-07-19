package com.example.twopchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class IncomingAttachment(
    val messageId: String,
    val displayMessage: String,
    val attachmentType: String,
    val attachmentUri: String,
    val attachmentName: String,
)

internal object IncomingMessageParser {
    fun parseAttachment(context: Context, text: String): IncomingAttachment? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val json = JSONObject(trimmed)
            if (json.optString("type") != "file") return null
            val filePath = json.optString("file_path", "")
            val file = validatedIncomingFile(context, filePath) ?: return null
            val fileName = file.name
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

    private fun validatedIncomingFile(context: Context, path: String): File? {
        if (path.isBlank()) return null
        return try {
            val downloads = File(context.filesDir, "config/downloads").canonicalFile
            val candidate = File(path).canonicalFile
            candidate.takeIf { it.isFile && it.parentFile == downloads }
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
                    val fileName = File(json.optString("file_name", "file")).name.take(120).ifBlank { "file" }
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
        private const val PREFS_NAME = "2pchat_notification_ids"
        private const val NEXT_ID_KEY = "next_id"

        @Synchronized
        private fun notificationId(context: Context, sender: String): Int {
            val senderDigest = MessageDigest.getInstance("SHA-256")
                .digest(sender.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val key = "sender_${senderDigest.take(32)}"
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getInt(key, 0)
            if (existing != 0) return existing
            val next = prefs.getInt(NEXT_ID_KEY, 1_000).coerceAtLeast(1_000)
            val following = if (next == Int.MAX_VALUE) 1_000 else next + 1
            prefs.edit().putInt(key, next).putInt(NEXT_ID_KEY, following).commit()
            return next
        }
    }

    fun show(context: Context, sender: String, text: String) {
        val settings = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
        if (!settings.getBoolean("settings_notifications", true)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "P2P Messages", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent()
        val id = notificationId(context, sender)
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showPreview = settings.getBoolean("settings_previews", false)
        val displayText = if (showPreview) {
            IncomingMessageParser.parseNotificationText(text)
        } else if (settings.getString("settings_language", "English") == "Русский") {
            "Новое сообщение"
        } else {
            "New message"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(sender)
            .setContentText(displayText)
            .setSmallIcon(R.drawable.ic_logo_default_fg)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        manager.notify(id, notification)
    }
}
