package com.example.twopchat.group.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.R
import com.example.twopchat.service.NotificationActionReceiver

internal object GroupNotificationService {
    const val CHANNEL_ID = "p2p_group_messages"
    const val INVITE_CHANNEL_ID = "p2p_group_invites"
    private const val NOTIFICATION_GROUP_KEY = "com.example.twopchat.CHAT_NOTIFICATIONS"

    fun show(
        context: Context,
        groupId: String,
        groupTitle: String,
        authorName: String,
        text: String,
    ) {
        val prefs = P2PPreferences.prefs(context)
        if (!prefs.getBoolean("settings_notifications", true) ||
            prefs.getBoolean("mute_group_$groupId", false)
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Encrypted group messages",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: Intent()
        val notificationId = (groupId.hashCode() and 0x3fffffff) + 20_000
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val myDisplayName = prefs.getString("username_profile", "") ?: ""
        val isMentioned = isGroupMention(text, myDisplayName)
        val title = if (isMentioned) "🔔 Вас упомянули в $groupTitle" else groupTitle
        val showPreview = prefs.getBoolean("settings_previews", true)
        val cleanText = formatGroupNotificationText(text)
        val body = if (showPreview) cleanText else "Новое сообщение в группе"

        // Direct Reply Action
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
            .setLabel("Ответить...")
            .build()
        val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_GROUP_REPLY
            putExtra(NotificationActionReceiver.EXTRA_GROUP_ID, groupId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            `package` = context.packageName
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 100_000,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_logo_default_fg,
            "Ответить",
            replyPendingIntent
        ).addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        // Mark as Read Action
        val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_GROUP_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_GROUP_ID, groupId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            `package` = context.packageName
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 200_000,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_logo_default_fg,
            "Прочитано",
            markReadPendingIntent
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        // Rich MessagingStyle
        val userPerson = Person.Builder()
            .setName(myDisplayName.ifBlank { "You" })
            .build()
        val authorPerson = Person.Builder()
            .setName(authorName.ifBlank { "User" })
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
            .setConversationTitle(title)
            .setGroupConversation(true)
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    body,
                    System.currentTimeMillis(),
                    authorPerson
                )
            )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_default_fg)
            .setStyle(messagingStyle)
            .setContentTitle(title)
            .setContentText(if (showPreview) "$authorName: $cleanText" else body)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(notificationId, notification)
    }

    internal fun isGroupMention(text: String, displayName: String): Boolean {
        if (text.contains("@all", ignoreCase = true) || text.contains("@everyone", ignoreCase = true)) return true
        val normalizedName = displayName.trim()
        if (normalizedName.isEmpty()) return false
        return Regex(
            pattern = "(?iu)(?<![\\p{L}\\p{N}_])@${Regex.escape(normalizedName)}(?![\\p{L}\\p{N}_])",
        ).containsMatchIn(text)
    }

    private fun formatGroupNotificationText(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("2psticker_") || trimmed.lowercase().contains("sticker") -> "Стикер"
            trimmed.startsWith("attachment-") -> "Вложение"
            else -> trimmed.take(500)
        }
    }

    fun cancelNotificationForGroup(context: Context, groupId: String) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            val notificationId = (groupId.hashCode() and 0x3fffffff) + 20_000
            manager?.cancel(notificationId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showInvite(
        context: Context,
        inviteId: String,
        groupId: String,
        groupTitle: String,
        inviterName: String,
    ) {
        val prefs = P2PPreferences.prefs(context)
        if (!prefs.getBoolean("settings_notifications", true)) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                INVITE_CHANNEL_ID,
                "Group Invitations",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications for incoming group invitations"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, com.example.twopchat.MainActivity::class.java)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(com.example.twopchat.MainActivity.EXTRA_OPEN_GROUP_INVITES, true)
        }
        val notificationId = (inviteId.hashCode() and 0x3fffffff) + 30_000
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val appLanguage = prefs.getString("app_language", "Русский") ?: "Русский"
        val isRu = appLanguage == "Русский"
        val showPreview = prefs.getBoolean("settings_previews", true)

        val title = if (showPreview) {
            if (isRu) "Приглашение в группу" else "Group Invitation"
        } else {
            if (isRu) "Новое приглашение" else "New Invitation"
        }

        val body = if (showPreview) {
            val safeInviter = inviterName.ifBlank { if (isRu) "Контакт" else "Contact" }
            val safeTitle = groupTitle.ifBlank { if (isRu) "Группа" else "Group" }
            if (isRu) "$safeInviter приглашает вас в «$safeTitle»" else "$safeInviter invited you to \"$safeTitle\""
        } else {
            if (isRu) "Вас пригласили в группу" else "You were invited to a group"
        }

        // Direct Accept Action
        val acceptIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ACCEPT_GROUP_INVITE
            putExtra(NotificationActionReceiver.EXTRA_INVITE_ID, inviteId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            `package` = context.packageName
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 300_000,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val acceptAction = NotificationCompat.Action.Builder(
            R.drawable.ic_check,
            if (isRu) "Принять" else "Accept",
            acceptPendingIntent,
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val publicNotification = NotificationCompat.Builder(context, INVITE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_default_fg)
            .setContentTitle(if (isRu) "2PChat" else "2PChat")
            .setContentText(if (isRu) "Новое приглашение в группу" else "New group invitation")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notification = NotificationCompat.Builder(context, INVITE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_default_fg)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .addAction(acceptAction)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setPublicVersion(publicNotification)
            .setVisibility(if (showPreview) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .build()

        manager.notify(notificationId, notification)
    }

    fun cancelNotificationForInvite(context: Context, inviteId: String) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            val notificationId = (inviteId.hashCode() and 0x3fffffff) + 30_000
            manager?.cancel(notificationId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
