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

        @Synchronized
        fun addMessageToHistory(context: Context, sender: String, text: String): List<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val senderDigest = MessageDigest.getInstance("SHA-256")
                .digest(sender.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val historyKey = "history_${senderDigest.take(32)}"
            val existingStr = prefs.getString(historyKey, "") ?: ""
            val list = if (existingStr.isNotBlank()) existingStr.split("|||").toMutableList() else mutableListOf()
            list.add(text)
            if (list.size > 10) list.removeAt(0)
            prefs.edit().putString(historyKey, list.joinToString("|||")).commit()
            return list
        }

        @Synchronized
        fun clearHistory(context: Context, sender: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val senderDigest = MessageDigest.getInstance("SHA-256")
                .digest(sender.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val historyKey = "history_${senderDigest.take(32)}"
            prefs.edit().remove(historyKey).commit()
        }
    }

    fun show(context: Context, sender: String, text: String) {
        val settings = P2PPreferences.prefs(context)
        if (!settings.getBoolean("settings_notifications", true)) return

        // 5. Per-Peer Mute Check
        val mutedPeers = settings.getStringSet("muted_peers", emptySet()) ?: emptySet()
        val isMuted = mutedPeers.contains(sender)
        if (isMuted) return // Suppress notifications for muted contacts

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 4. Heads-Up High Importance Channel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "P2P Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming P2P encrypted chat notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 100, 150)
            }
            manager.createNotificationChannel(channel)
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

        val isRu = settings.getString("settings_language", "English") == "Русский"
        val showPreview = settings.getBoolean("settings_previews", true)
        val displayText = if (showPreview) {
            IncomingMessageParser.parseNotificationText(text)
        } else if (isRu) {
            "Новое сообщение"
        } else {
            "New message"
        }

        // Add to history list for MessagingStyle
        val historyList = addMessageToHistory(context, sender, displayText)

        // 2. MessagingStyle Conversation Threading
        val userPerson = androidx.core.app.Person.Builder().setName(if (isRu) "Вы" else "You").build()
        val senderPerson = androidx.core.app.Person.Builder().setName(sender).build()
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
            .setConversationTitle(sender)

        historyList.forEach { item ->
            messagingStyle.addMessage(item, System.currentTimeMillis(), senderPerson)
        }

        // 1. Direct Reply RemoteInput Action
        val remoteInput = androidx.core.app.RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
            .setLabel(if (isRu) "Ответить" else "Reply")
            .build()

        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_REPLY
            putExtra(NotificationActionReceiver.EXTRA_SENDER, sender)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            id * 10 + 1,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send_airplane,
            if (isRu) "Ответить" else "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // 3. Mark as Read Action
        val readIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_SENDER, sender)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
        }
        val readPendingIntent = PendingIntent.getBroadcast(
            context,
            id * 10 + 2,
            readIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val readAction = NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            if (isRu) "Прочитано" else "Mark as Read",
            readPendingIntent
        ).build()

        // Build Notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_default_fg)
            .setStyle(messagingStyle)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .addAction(readAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setVibrate(longArrayOf(0, 150, 100, 150))
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        manager.notify(id, notification)
    }
}
