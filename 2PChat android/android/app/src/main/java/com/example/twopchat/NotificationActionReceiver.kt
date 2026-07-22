package com.example.twopchat

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import java.util.UUID

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.example.twopchat.ACTION_NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "com.example.twopchat.ACTION_NOTIFICATION_MARK_READ"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (action) {
            ACTION_REPLY -> {
                val results = RemoteInput.getResultsFromIntent(intent)
                val replyText = results?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
                if (!replyText.isNullOrBlank()) {
                    val msgId = UUID.randomUUID().toString()
                    val myMsg = Message(
                        id = msgId,
                        text = replyText,
                        isMe = true,
                        timestamp = "now",
                        status = "PENDING"
                    )
                    // 1. Save to local database
                    try {
                        ChatDatabaseHelper.getInstance(context).saveMessage(sender, myMsg)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // 2. Send via P2PMessageRelay in background thread
                    kotlin.concurrent.thread {
                        try {
                            P2PMessageRelay.sendMessage(context, sender, sender, replyText) { success ->
                                if (success) {
                                    try {
                                        ChatDatabaseHelper.getInstance(context).updateMessageStatus(msgId, "SENT")
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // 3. Mark messages as read in DB & reset unread count badge
                    markPeerAsRead(context, sender)
                    if (notifId != 0) {
                        manager.cancel(notifId)
                    }
                }
            }
            ACTION_MARK_READ -> {
                markPeerAsRead(context, sender)
                if (notifId != 0) {
                    manager.cancel(notifId)
                }
            }
        }
    }

    private fun markPeerAsRead(context: Context, sender: String) {
        try {
            // 1. Reset unread badge count in preferences to 0
            P2PPreferences.prefs(context).edit().putInt("unread_count_$sender", 0).apply()

            // 2. Mark messages as read in database
            ChatDatabaseHelper.getInstance(context).markMessagesAsRead(sender)

            // 3. Clear notification history for sender
            MessageNotificationService.clearHistory(context, sender)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
