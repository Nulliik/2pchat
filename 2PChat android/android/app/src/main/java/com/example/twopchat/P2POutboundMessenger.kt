package com.example.twopchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.PendingControl
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

internal class P2POutboundMessenger(
    private val peerEndpoints: Map<String, String>,
    private val log: (Context, String, String, Throwable?) -> Unit,
    private val onMessageStatusChanged: (String, String, String) -> Unit,
) {
    private val processingOfflineQueues = ConcurrentHashMap.newKeySet<String>()

    fun sendMessage(context: Context, endpoint: String, text: String, onResult: (Boolean) -> Unit = {}) {
        thread(start = true, name = "SecureMessageSend") {
            try {
                val peerName = peerEndpoints.entries.firstOrNull { it.value == endpoint }?.key ?: "Direct Peer"
                log(context, "Sending secure message via Python transport", "INFO", null)
                val fingerprint = P2PPreferences.prefs(context)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                val success = PythonBridge.sendP2pMessage(peerName, endpoint, text, fingerprint)
                log(context, "Secure message send: ${if (success) "SUCCESS" else "FAILED"}", "INFO", null)
                postResult(onResult, success)
            } catch (error: Exception) {
                log(context, "Failed to send secure message", "ERROR", error)
                postResult(onResult, false)
            }
        }
    }

    fun sendControlMessage(
        context: Context,
        peerName: String,
        payload: JSONObject,
        onResult: (Boolean) -> Unit = {},
    ) {
        val endpoint = peerEndpoints[peerName]
        if (endpoint.isNullOrBlank()) return onResult(false)
        sendMessage(context, endpoint, payload.toString(), onResult)
    }

    fun sendFile(
        context: Context,
        peerName: String,
        endpoint: String,
        filePath: String,
        messageId: String = "",
        onResult: (Boolean) -> Unit = {},
    ) {
        thread(start = true, name = "SecureFileSend") {
            try {
                val fingerprint = P2PPreferences.prefs(context)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                log(context, "Sending secure file via Python transport to $peerName", "INFO", null)
                val success = PythonBridge.sendP2pFile(peerName, endpoint, filePath, fingerprint, messageId)
                log(context, "Sending file status to $peerName: ${if (success) "SUCCESS" else "FAILED"}", "INFO", null)
                postResult(onResult, success)
            } catch (error: Exception) {
                log(context, "Failed to send secure file", "ERROR", error)
                postResult(onResult, false)
            }
        }
    }

    fun reconnect(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        thread(start = true, name = "PeerReconnect") {
            try {
                val prefs = P2PPreferences.prefs(context)
                val endpoint = peerEndpoints[peerName]
                    ?: prefs.getString(P2PPreferences.lastEndpoint(peerName), "").orEmpty()
                val fingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null)
                log(context, "Requesting reconnection for $peerName at endpoint '$endpoint'", "INFO", null)
                postResult(onResult, PythonBridge.reconnectPeerSession(peerName, endpoint, fingerprint))
            } catch (error: Exception) {
                log(context, "Failed to initiate reconnection for $peerName", "ERROR", error)
                postResult(onResult, false)
            }
        }
    }

    fun sendTypingState(context: Context, peerName: String, endpoint: String, isTyping: Boolean) {
        sendSilently(context, peerName, endpoint, JSONObject().apply {
            put("type", "typing_state")
            put("is_typing", isTyping)
        })
    }

    fun sendReadReceipt(context: Context, peerName: String, endpoint: String?, messageId: String) {
        val controlId = "read:$messageId"
        val payload = JSONObject().apply {
            put("type", "read_receipt")
            put("message_id", messageId)
            put("control_id", controlId)
        }
        sendPersistedControl(context, peerName, endpoint, controlId, "read_receipt", payload, deleteAfterSend = true)
    }

    fun sendReaction(
        context: Context,
        peerName: String,
        endpoint: String,
        messageId: String,
        messageText: String,
        emoji: String,
    ) {
        sendSilently(context, peerName, endpoint, JSONObject().apply {
            put("type", "reaction")
            put("message_id", messageId)
            put("message_text", messageText)
            put("emoji", emoji)
        })
    }

    fun sendEditMessage(
        context: Context,
        peerName: String,
        endpoint: String?,
        messageId: String,
        newText: String
    ) {
        val controlId = "edit:$messageId"
        val payload = JSONObject().apply {
            put("type", "edit_message")
            put("message_id", messageId)
            put("text", newText)
            put("control_id", controlId)
        }
        sendPersistedControl(context, peerName, endpoint, controlId, "edit_message", payload, deleteAfterSend = false)
    }

    fun processOfflineQueue(context: Context, peerName: String, endpoint: String) {
        if (endpoint.isBlank() || !processingOfflineQueues.add(peerName)) return
        thread(start = true, name = "OfflineQueueThread") {
            try {
                val db = ChatDatabaseHelper.getInstance(context)
                val pending = db.getPendingMessagesForPeer(peerName)
                if (pending.isNotEmpty()) {
                    log(context, "Processing ${pending.size} pending offline messages for $peerName", "INFO", null)
                }
                val fingerprint = P2PPreferences.prefs(context)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                for (message in pending) {
                    val payload = if (message.replyToId != null) JSONObject().apply {
                        put("type", "reply")
                        put("text", message.text)
                        put("reply_to_id", message.replyToId)
                        put("reply_to_text", message.replyToText)
                        put("reply_to_name", message.replyToName)
                    }.toString() else message.text
                    val success = if (message.attachmentType != null && !message.attachmentUri.isNullOrBlank()) {
                        val attachment = File(message.attachmentUri)
                        attachment.exists() && PythonBridge.sendP2pFile(
                            peerName, endpoint, attachment.absolutePath, fingerprint, message.id
                        )
                    } else {
                        PythonBridge.sendP2pMessage(peerName, endpoint, payload, fingerprint)
                    }
                    if (!success) {
                        log(context, "Failed to send pending message ${message.id}, stopping queue processing.", "INFO", null)
                        break
                    }
                    db.updateMessageStatus(message.id, "SENT")
                    Handler(Looper.getMainLooper()).post {
                        onMessageStatusChanged(peerName, message.id, "SENT")
                    }
                }
                processPendingControls(context, db, peerName, endpoint, fingerprint)
            } catch (error: Exception) {
                log(context, "Error in processOfflineQueue: ${error.message}", "ERROR", error)
            } finally {
                processingOfflineQueues.remove(peerName)
            }
        }
    }

    private fun sendSilently(context: Context, peerName: String, endpoint: String, payload: JSONObject) {
        thread(start = true, name = "P2PControlMessage") {
            try {
                val fingerprint = P2PPreferences.prefs(context)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                PythonBridge.sendP2pMessage(peerName, endpoint, payload.toString(), fingerprint)
            } catch (error: Exception) {
                log(context, "Failed to send ephemeral ${payload.optString("type")} control", "ERROR", error)
            }
        }
    }

    fun acknowledgeControl(context: Context, controlId: String) {
        if (controlId.isBlank()) return
        thread(start = true, name = "ControlAck") {
            try {
                ChatDatabaseHelper.getInstance(context).deletePendingControl(controlId)
            } catch (error: Exception) {
                log(context, "Failed to acknowledge control $controlId", "ERROR", error)
            }
        }
    }

    private fun sendPersistedControl(
        context: Context,
        peerName: String,
        endpoint: String?,
        controlId: String,
        type: String,
        payload: JSONObject,
        deleteAfterSend: Boolean,
    ) {
        val appContext = context.applicationContext
        thread(start = true, name = "PersistedP2PControl") {
            val db = ChatDatabaseHelper.getInstance(appContext)
            try {
                db.enqueuePendingControl(
                    PendingControl(controlId, peerName, type, payload.toString())
                )
                val resolvedEndpoint = endpoint?.takeIf { it.isNotBlank() }
                    ?: peerEndpoints[peerName]
                    ?: P2PPreferences.prefs(appContext)
                        .getString(P2PPreferences.lastEndpoint(peerName), null)
                        ?.takeIf { it.isNotBlank() }
                    ?: return@thread
                val fingerprint = P2PPreferences.prefs(appContext)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                val sent = PythonBridge.sendP2pMessage(
                    peerName,
                    resolvedEndpoint,
                    payload.toString(),
                    fingerprint,
                )
                if (sent && deleteAfterSend) db.deletePendingControl(controlId)
            } catch (error: Exception) {
                log(appContext, "Failed to queue/send $type control", "ERROR", error)
            }
        }
    }

    private fun processPendingControls(
        context: Context,
        db: ChatDatabaseHelper,
        peerName: String,
        endpoint: String,
        fingerprint: String?,
    ) {
        val controls = db.getPendingControlsForPeer(peerName)
        if (controls.isNotEmpty()) {
            log(context, "Processing ${controls.size} pending controls for $peerName", "INFO", null)
        }
        for (control in controls) {
            val success = PythonBridge.sendP2pMessage(
                peerName,
                endpoint,
                control.payload,
                fingerprint,
            )
            if (!success) break
            if (control.type == "read_receipt") db.deletePendingControl(control.id)
            // Edits remain until the receiver returns edit_ack.
        }
    }

    private fun postResult(callback: (Boolean) -> Unit, result: Boolean) {
        Handler(Looper.getMainLooper()).post { callback(result) }
    }
}
