package com.example.twopchat.group.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.twopchat.P2PPreferences
import com.example.twopchat.R

internal object GroupNotificationService {
    private const val CHANNEL_ID = "p2p_group_messages"

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
        val body = if (showPreview) "$authorName: $cleanText" else "Новое сообщение в группе"
        manager.notify(
            notificationId,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_default_fg)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup("group:$groupId")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
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
}
