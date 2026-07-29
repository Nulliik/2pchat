package com.example.twopchat.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.P2PPreferences
import com.example.twopchat.SecureStorage
import com.example.twopchat.VoiceMessageSupport
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class IncomingMessageRouter(
    private val presenceManager: PeerPresenceManager,
    private val fileTransferCoordinator: FileTransferCoordinator,
    private val avatarManager: AvatarManager,
    private val pinnedMessageManager: PinnedMessageManager,
) {
    fun routeIncomingMessage(
        context: Context,
        sender: String,
        text: String,
        listeners: List<P2PMessageRelay.MessageListener>,
        persistAndDispatch: (Context, String, Message, String, Boolean) -> Unit,
        log: (Context, String, String, Throwable?) -> Unit,
        sendControlMessage: (Context, String, JSONObject) -> Unit,
        acknowledgeControl: (Context, String) -> Unit,
    ) {
        val sharedPrefs = P2PPreferences.prefs(context)
        if (sharedPrefs.getBoolean("blocked_peer_$sender", false)) {
            log(context, "Ignored message from a blocked peer", "INFO", null)
            return
        }

        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)
                when (json.optString("type")) {
                    "file_offer" -> {
                        handleFileOffer(context, sender, json, persistAndDispatch)
                        return
                    }
                    "file_cancelled" -> {
                        handleFileCancelled(context, sender, json, listeners)
                        return
                    }
                    "file_failed" -> {
                        handleFileFailed(context, sender, json, listeners)
                        return
                    }
                    "verification_request" -> {
                        Handler(Looper.getMainLooper()).post {
                            listeners.forEach { it.onVerificationRequest(sender) }
                        }
                        return
                    }
                    "verification_response" -> {
                        val success = json.optBoolean("success", false)
                        Handler(Looper.getMainLooper()).post {
                            if (success) {
                                P2PPreferences.setPeerVerified(context, sender, true)
                            }
                            listeners.forEach { it.onVerificationResponse(sender, success) }
                        }
                        return
                    }
                    "profile_avatar_share" -> {
                        avatarManager.handleAvatarShare(context, sender, json.optString("avatar_base64"), log)
                        return
                    }
                    "pin_message" -> {
                        pinnedMessageManager.handlePinMessage(
                            context = context,
                            sender = sender,
                            msgId = json.optString("msg_id"),
                            text = json.optString("text"),
                            isFromSender = json.optBoolean("is_from_sender", false),
                            pinVersionCounter = json.optLong("pin_version", 0L),
                            pinActor = json.optString("pin_actor"),
                            controlId = json.optString("control_id"),
                            onPinned = { s, id, t, isFrom ->
                                Handler(Looper.getMainLooper()).post {
                                    listeners.forEach { it.onMessagePinned(s, id, t, isFrom) }
                                }
                            },
                            sendAck = { ctx, s, cId ->
                                sendControlMessage(ctx, s, JSONObject().apply {
                                    put("type", "pin_state_ack")
                                    put("control_id", cId)
                                })
                            }
                        )
                        return
                    }
                    "unpin_message" -> {
                        pinnedMessageManager.handleUnpinMessage(
                            context = context,
                            sender = sender,
                            pinVersionCounter = json.optLong("pin_version", 0L),
                            pinActor = json.optString("pin_actor"),
                            controlId = json.optString("control_id"),
                            onUnpinned = { s ->
                                Handler(Looper.getMainLooper()).post {
                                    listeners.forEach { it.onMessageUnpinned(s) }
                                }
                            },
                            sendAck = { ctx, s, cId ->
                                sendControlMessage(ctx, s, JSONObject().apply {
                                    put("type", "pin_state_ack")
                                    put("control_id", cId)
                                })
                            }
                        )
                        return
                    }
                    "typing_state" -> {
                        presenceManager.updateTypingState(sender, json.optBoolean("is_typing", false))
                        return
                    }
                    "read_receipt" -> {
                        val msgId = json.optString("message_id")
                        if (msgId.isNotEmpty()) {
                            ChatDatabaseHelper.getInstance(context).updateMessageStatus(msgId, "READ")
                            Handler(Looper.getMainLooper()).post {
                                listeners.forEach { it.onMessageStatusChanged(sender, msgId, "READ") }
                            }
                        }
                        return
                    }
                    "edit_message" -> {
                        handleEditMessage(context, sender, json, listeners, sendControlMessage)
                        return
                    }
                    "delete_message" -> {
                        handleDeleteMessage(context, sender, json, listeners)
                        return
                    }
                    "ping" -> {
                        sendControlMessage(context, sender, JSONObject().apply {
                            put("type", "pong")
                            put("sent_at_ms", json.optLong("sent_at_ms"))
                        })
                        return
                    }
                    "pong" -> {
                        val sentAt = json.optLong("sent_at_ms")
                        if (sentAt > 0L) {
                            val rtt = (System.currentTimeMillis() - sentAt).coerceIn(0L, 60_000L)
                            presenceManager.updateRtt(sender, rtt)
                        }
                        return
                    }
                    "edit_ack", "pin_state_ack" -> {
                        acknowledgeControl(context, json.optString("control_id"))
                        return
                    }
                    "file_progress" -> {
                        handleFileProgress(sender, json, listeners)
                        return
                    }
                }
            } catch (e: Exception) {
                log(context, "Failed to parse JSON control message", "ERROR", e)
            }
        }

        // Regular text message or file payload
        val attachment = com.example.twopchat.IncomingMessageParser.parseAttachment(context, text)
        val plainText = text.takeIf { attachment == null } ?: attachment!!.displayMessage
        val message = Message(
            id = attachment?.messageId?.ifBlank { java.util.UUID.randomUUID().toString() } ?: java.util.UUID.randomUUID().toString(),
            text = plainText,
            isMe = false,
            timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            attachmentType = attachment?.attachmentType,
            attachmentUri = attachment?.attachmentUri,
            attachmentName = attachment?.attachmentName,
            status = "READ",
        )
        persistAndDispatch(context, sender, message, message.text, true)
    }

    private fun handleFileOffer(
        context: Context,
        sender: String,
        json: JSONObject,
        persistAndDispatch: (Context, String, Message, String, Boolean) -> Unit
    ) {
        val messageId = json.optString("message_id").take(128)
        val fileName = File(json.optString("file_name", "file")).name.take(120).ifBlank { "file" }
        val mime = json.optString("mime")
        val totalBytes = json.optLong("size").coerceAtLeast(0L)
        if (messageId.isBlank() || totalBytes > 100L * 1024L * 1024L) return
        val attachmentType = VoiceMessageSupport.attachmentType(fileName, mime)
        val offerKey = "$sender:$messageId"
        val isNewOffer = fileTransferCoordinator.incomingFileOffers.add(offerKey)
        val preview = fileTransferCoordinator.decodeFileTransferPreview(json.optString("preview_base64"))
        val offerMessage = Message(
            id = messageId,
            text = VoiceMessageSupport.displayMessage(attachmentType, fileName),
            isMe = false,
            timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            attachmentType = attachmentType,
            attachmentUri = null,
            attachmentName = fileName,
            status = "RECEIVING",
        )
        fileTransferCoordinator.updateProgress(offerKey, messageId, 0L, totalBytes, 0.0)
        if (preview != null) {
            fileTransferCoordinator.fileTransferPreviews[offerKey] = preview
            fileTransferCoordinator.fileTransferPreviews[messageId] = preview
        }
        val isRu = P2PPreferences.prefs(context).getString("settings_language", "English") == "Русский"
        val notifText = if (isRu) "Началось получение файла: $fileName" else "Receiving file: $fileName"
        persistAndDispatch(context, sender, offerMessage, notifText, isNewOffer)
    }

    private fun handleFileCancelled(
        context: Context,
        sender: String,
        json: JSONObject,
        listeners: List<P2PMessageRelay.MessageListener>
    ) {
        val messageId = json.optString("message_id").take(128)
        if (messageId.isBlank()) return
        val key = "$sender:$messageId"
        fileTransferCoordinator.incomingFileOffers.remove(key)
        ChatDatabaseHelper.getInstance(context).updateMessageStatus(messageId, "CANCELLED")
        fileTransferCoordinator.updateTransferState(key, messageId, FileTransferCoordinator.FileTransferState.CANCELLED)
        Handler(Looper.getMainLooper()).post {
            listeners.forEach { it.onMessageStatusChanged(sender, messageId, "CANCELLED") }
        }
    }

    private fun handleFileFailed(
        context: Context,
        sender: String,
        json: JSONObject,
        listeners: List<P2PMessageRelay.MessageListener>
    ) {
        val messageId = json.optString("message_id").take(128)
        if (messageId.isBlank()) return
        val key = "$sender:$messageId"
        fileTransferCoordinator.incomingFileOffers.remove(key)
        ChatDatabaseHelper.getInstance(context).updateMessageStatus(messageId, "FAILED")
        fileTransferCoordinator.updateTransferState(key, messageId, FileTransferCoordinator.FileTransferState.FAILED)
        Handler(Looper.getMainLooper()).post {
            listeners.forEach { it.onMessageStatusChanged(sender, messageId, "FAILED") }
        }
    }

    private fun handleFileProgress(
        sender: String,
        json: JSONObject,
        listeners: List<P2PMessageRelay.MessageListener>
    ) {
        val messageId = json.optString("message_id")
        val bytesTransferred = json.optLong("bytes_transferred")
        val totalBytes = json.optLong("total_bytes")
        val speedKbps = json.optDouble("speed_kbps", 0.0)
        if (messageId.isNotBlank()) {
            val key = "$sender:$messageId"
            fileTransferCoordinator.updateProgress(key, messageId, bytesTransferred, totalBytes, speedKbps)
            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it.onFileProgress(sender, messageId, bytesTransferred, totalBytes, speedKbps) }
            }
        }
    }

    private fun handleEditMessage(
        context: Context,
        sender: String,
        json: JSONObject,
        listeners: List<P2PMessageRelay.MessageListener>,
        sendControlMessage: (Context, String, JSONObject) -> Unit
    ) {
        val msgId = json.optString("message_id")
        val text = json.optString("text")
        if (msgId.isNotEmpty() && text.isNotEmpty()) {
            val db = ChatDatabaseHelper.getInstance(context)
            // CRIT-01 Fix: Only update if the message belongs to sender and was sent BY sender (isMe == 0)
            val updated = db.updateMessageTextForPeer(msgId, sender, text)
            if (!updated) return

            val prefs = P2PPreferences.prefs(context)
            if (prefs.getString(P2PPreferences.pinnedMessageId(sender), null) == msgId) {
                prefs.edit().putString(P2PPreferences.pinnedMessageText(sender), SecureStorage.encrypt(text)).apply()
            }
            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it.onMessageEdited(sender, msgId, text) }
            }
            val controlId = json.optString("control_id")
            if (controlId.isNotBlank()) {
                sendControlMessage(context, sender, JSONObject().apply {
                    put("type", "edit_ack")
                    put("control_id", controlId)
                    put("message_id", msgId)
                })
            }
        }
    }

    private fun handleDeleteMessage(
        context: Context,
        sender: String,
        json: JSONObject,
        listeners: List<P2PMessageRelay.MessageListener>
    ) {
        val msgId = json.optString("message_id")
        if (msgId.isNotEmpty()) {
            val db = ChatDatabaseHelper.getInstance(context)
            // CRIT-01 Fix: Only delete if the message belongs to sender and was sent BY sender (isMe == 0)
            val deleted = db.deleteMessageForPeer(msgId, sender)
            if (!deleted) return

            val prefs = P2PPreferences.prefs(context)
            if (prefs.getString(P2PPreferences.pinnedMessageId(sender), null) == msgId) {
                prefs.edit()
                    .remove(P2PPreferences.pinnedMessageId(sender))
                    .remove(P2PPreferences.pinnedMessageText(sender))
                    .remove(P2PPreferences.pinnedMessageSender(sender))
                    .remove(P2PPreferences.pinnedBy(sender))
                    .apply()
            }
            Handler(Looper.getMainLooper()).post {
                listeners.forEach { it.onMessageDeleted(sender, msgId) }
            }
        }
    }
}
