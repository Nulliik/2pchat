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
    val caption: String?,
    val albumId: String?,
    val albumIndex: Int?,
    val albumCount: Int?,
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
            val storedFile = if (attachmentType == StickerSupport.ATTACHMENT_TYPE) {
                StickerSupport.cacheIncomingSticker(context, file) ?: return null
            } else if (attachmentType == StickerSupport.PACK_ATTACHMENT_TYPE) {
                // P2P packs are cached for an in-chat preview first. Installing them
                // into the user's collection remains an explicit action.
                StickerSupport.cachePeerPackPreview(context, file) ?: return null
                file
            } else if (attachmentType == GifStorageManager.ATTACHMENT_TYPE) {
                GifStorageManager.validateGif(file) ?: return null
                file
            } else {
                file
            }
            val caption = json.optString("caption").ifBlank { json.optString("text", "") }.trim()
            val displayMsg = if (caption.isNotBlank()) caption else VoiceMessageSupport.displayMessage(attachmentType, fileName)
            val albumId = json.optString("album_id").take(128)
            val albumIndex = json.optInt("album_index", -1)
            val albumCount = json.optInt("album_count", 0)
            val hasValidAlbum = albumId.isNotBlank() &&
                albumCount in 2..100 &&
                albumIndex in 0 until albumCount
            IncomingAttachment(
                messageId = json.optString("message_id"),
                displayMessage = displayMsg,
                attachmentType = attachmentType,
                attachmentUri = storedFile.absolutePath,
                attachmentName = fileName,
                caption = caption.takeIf { it.isNotBlank() },
                albumId = albumId.takeIf { hasValidAlbum },
                albumIndex = albumIndex.takeIf { hasValidAlbum },
                albumCount = albumCount.takeIf { hasValidAlbum },
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
    private data class NotificationHistory(
        val messages: List<String>,
        val messageIds: List<String>,
    )

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
            prefs.edit().putInt(key, next).putInt(NEXT_ID_KEY, following).apply()
            return next
        }

        @Synchronized
        private fun addMessageToHistory(
            context: Context,
            sender: String,
            text: String,
            messageId: String,
        ): NotificationHistory {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val senderDigest = MessageDigest.getInstance("SHA-256")
                .digest(sender.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val historyKey = "history_${senderDigest.take(32)}"
            val messageIdsKey = "message_ids_${senderDigest.take(32)}"
            val rawExisting = prefs.getString(historyKey, "") ?: ""
            val existingStr = if (rawExisting.startsWith("2PCHAT_ENC:")) {
                SecureStorage.decrypt(rawExisting).orEmpty()
            } else {
                rawExisting
            }
            val list = if (existingStr.isNotBlank()) existingStr.split("|||").toMutableList() else mutableListOf()
            list.add(text)
            if (list.size > 10) list.removeAt(0)

            val existingIds = prefs.getString(messageIdsKey, "").orEmpty()
            val messageIds = if (existingIds.isNotBlank()) {
                existingIds.split("|||").filter(String::isNotBlank).toMutableList()
            } else {
                mutableListOf()
            }
            if (messageId.isNotBlank() && messageId !in messageIds) messageIds.add(messageId)
            // Keep the PendingIntent comfortably below Android's Binder limit even if
            // notifications remain untouched for a long time.
            while (messageIds.size > 1_000) messageIds.removeAt(0)

            val encryptedHistory = SecureStorage.encrypt(list.joinToString("|||"))
            prefs.edit()
                .putString(historyKey, encryptedHistory)
                .putString(messageIdsKey, messageIds.joinToString("|||"))
                .apply()
            return NotificationHistory(list, messageIds)
        }

        @Synchronized
        fun clearHistory(context: Context, sender: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val senderDigest = MessageDigest.getInstance("SHA-256")
                .digest(sender.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val historyKey = "history_${senderDigest.take(32)}"
            val messageIdsKey = "message_ids_${senderDigest.take(32)}"
            prefs.edit().remove(historyKey).remove(messageIdsKey).apply()
        }

        @Synchronized
        fun clearAllHistory(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
        }

        fun getPeerAvatarIcon(context: Context, sender: String): androidx.core.graphics.drawable.IconCompat {
            // 1. Try RAM cache
            val cached = P2PMessageRelay.peerAvatars[sender]
            if (cached != null) {
                return androidx.core.graphics.drawable.IconCompat.createWithBitmap(cached)
            }

            // 2. Try encrypted avatar storage on disk
            try {
                val avatarDir = File(context.filesDir, "avatars")
                if (avatarDir.exists()) {
                    val files = avatarDir.listFiles().orEmpty()
                    for (file in files) {
                        if (file.name.endsWith(".avatar")) {
                            val bytes = file.readBytes()
                            val clear = SecureStorage.decryptBytes(bytes)
                            if (clear.size > 2) {
                                val nameLen = clear[0].toInt() and 0xFF
                                val name = String(clear, 2, nameLen, Charsets.UTF_8)
                                if (name == sender) {
                                    val imgBytes = clear.copyOfRange(2 + nameLen, clear.size)
                                    val bmp = android.graphics.BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                                    if (bmp != null) {
                                        P2PMessageRelay.peerAvatars[sender] = bmp
                                        return androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // 3. Fallback: Draw a crisp circular avatar bitmap with sender's initial letter & brand color
            val sizePx = 144
            val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)

            val hash = sender.hashCode()
            val hue = (Math.abs(hash) % 360).toFloat()
            val bgColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))

            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                style = android.graphics.Paint.Style.FILL
            }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)

            val initial = sender.trim().take(1).uppercase()
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = sizePx * 0.52f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val textY = (sizePx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(initial, sizePx / 2f, textY, textPaint)

            return androidx.core.graphics.drawable.IconCompat.createWithBitmap(bitmap)
        }
    }

    fun show(context: Context, sender: String, text: String, messageId: String) {
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
        val history = addMessageToHistory(context, sender, displayText, messageId)

        // 2. MessagingStyle Conversation Threading with Avatar Icon
        val avatarIcon = getPeerAvatarIcon(context, sender)
        val userPerson = androidx.core.app.Person.Builder().setName(if (isRu) "Вы" else "You").build()
        val senderPerson = androidx.core.app.Person.Builder()
            .setName(sender)
            .setIcon(avatarIcon)
            .build()
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
            .setConversationTitle(sender)

        history.messages.forEach { item ->
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
            putStringArrayListExtra(
                NotificationActionReceiver.EXTRA_MESSAGE_IDS,
                ArrayList(history.messageIds),
            )
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
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        // 3. Mark as Read Action
        val readIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_SENDER, sender)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
            putStringArrayListExtra(
                NotificationActionReceiver.EXTRA_MESSAGE_IDS,
                ArrayList(history.messageIds),
            )
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
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

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
