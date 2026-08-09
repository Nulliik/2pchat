package com.example.twopchat

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.RemoteInput
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REPLY = "com.example.twopchat.ACTION_NOTIFICATION_REPLY"
        const val ACTION_MARK_READ = "com.example.twopchat.ACTION_NOTIFICATION_MARK_READ"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_SENDER = "extra_sender"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_MESSAGE_IDS = "extra_message_ids"
        private const val TAG = "NotificationAction"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val sender = intent.getStringExtra(EXTRA_SENDER) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val messageIds = intent.getStringArrayListExtra(EXTRA_MESSAGE_IDS).orEmpty()
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = P2PPreferences.prefs(appContext)
                val activeChats = prefs.getStringSet(P2PPreferences.ACTIVE_CHATS, emptySet()).orEmpty()
                val hasPeerRecord = prefs.contains(P2PPreferences.peerFingerprint(sender)) ||
                    prefs.contains(P2PPreferences.lastEndpoint(sender))
                if (sender !in activeChats && !hasPeerRecord) {
                    Log.w(TAG, "Rejected notification action for unverified sender: $sender")
                    return@launch
                }

                ensureRelayRunning(appContext)
                when (action) {
                    ACTION_REPLY -> {
                        val results = RemoteInput.getResultsFromIntent(intent)
                        val replyText = results?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
                        if (!replyText.isNullOrBlank()) {
                            savePendingReply(appContext, sender, replyText)
                            markPeerAsRead(appContext, sender, messageIds)
                            dispatchPendingActions(appContext, sender)
                            cancelNotification(appContext, notifId)
                        }
                    }
                    ACTION_MARK_READ -> {
                        markPeerAsRead(appContext, sender, messageIds)
                        dispatchPendingActions(appContext, sender)
                        cancelNotification(appContext, notifId)
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Notification action failed for $sender", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun savePendingReply(context: Context, sender: String, replyText: String) {
        val msg = Message(
            id = UUID.randomUUID().toString(),
            text = replyText,
            isMe = true,
            timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            status = "PENDING",
        )
        ChatDatabaseHelper.getInstance(context).saveMessage(sender, msg)

        val prefs = P2PPreferences.prefs(context)
        val activeChats = prefs.getStringSet(P2PPreferences.ACTIVE_CHATS, emptySet()).orEmpty()
        prefs.edit().apply {
            if (sender !in activeChats) putStringSet(P2PPreferences.ACTIVE_CHATS, activeChats + sender)
            putString(P2PPreferences.lastMessage(sender), SecureStorage.encrypt("You: $replyText"))
            apply()
        }
    }

    private fun ensureRelayRunning(context: Context) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, P2PRelayService::class.java),
            )
        } catch (error: Exception) {
            // A notification action normally has a background-start exemption. If an
            // OEM denies it, the already-running relay can still flush the durable queue.
            Log.w(TAG, "Could not request relay service start", error)
        }
    }

    private fun dispatchPendingActions(context: Context, sender: String) {
        val prefs = P2PPreferences.prefs(context)
        val endpoint = P2PMessageRelay.peerEndpoints[sender]
            ?: prefs.getString(P2PPreferences.lastEndpoint(sender), null)
        if (!endpoint.isNullOrBlank()) {
            P2PMessageRelay.processOfflineQueue(context, sender, endpoint)
        } else {
            P2PMessageRelay.reconnectSession(context, sender)
        }
    }

    private fun cancelNotification(context: Context, notificationId: Int) {
        if (notificationId != 0) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId)
        }
    }

    private fun markPeerAsRead(context: Context, sender: String, notificationMessageIds: List<String>) {
        try {
            val databaseMessageIds = ChatDatabaseHelper.getInstance(context).markMessagesAsRead(sender)
            val readMessageIds = (notificationMessageIds + databaseMessageIds)
                .filter(String::isNotBlank)
                .distinct()

            // Queue receipts synchronously: BroadcastReceiver may be reclaimed as soon
            // as it finishes, but the relay can deliver these controls later if offline.
            readMessageIds.forEach { messageId ->
                P2PMessageRelay.enqueueReadReceipt(context, sender, messageId)
            }

            P2PPreferences.prefs(context).edit()
                .putInt(P2PPreferences.unreadCount(sender), 0)
                .apply()
            MessageNotificationService.clearHistory(context, sender)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to mark messages as read for $sender", error)
        }
    }
}
