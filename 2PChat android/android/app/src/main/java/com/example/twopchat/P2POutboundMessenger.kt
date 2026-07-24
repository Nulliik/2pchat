package com.example.twopchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.PendingControl
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class P2POutboundMessenger(
    private val peerEndpoints: Map<String, String>,
    private val log: (Context, String, String, Throwable?) -> Unit,
    private val onMessageStatusChanged: (String, String, String) -> Unit,
) {
    private val processingOfflineQueues = ConcurrentHashMap.newKeySet<String>()
    private val cancelledFileTransfers = ConcurrentHashMap.newKeySet<String>()
    private val activeFileTransfers = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val pinnedStateScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

    private fun isPaused(context: Context, peerName: String): Boolean =
        peerName != "Direct Peer" && P2PPreferences.isPeerIdentityChangePending(context, peerName)

    fun sendMessage(context: Context, endpoint: String, text: String, onResult: (Boolean) -> Unit = {}) {
        val peerName = peerEndpoints.entries.firstOrNull { it.value == endpoint }?.key ?: "Direct Peer"
        if (isPaused(context, peerName)) {
            log(context, "Blocked message to $peerName while its identity change awaits confirmation", "ERROR", null)
            return postResult(onResult, false)
        }
        scope.launch {
            try {
                if (isPaused(context, peerName)) return@launch postResult(onResult, false)
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
        if (isPaused(context, peerName)) return postResult(onResult, false)
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
        caption: String = "",
        onResult: (Boolean) -> Unit = {},
    ) {
        if (isPaused(context, peerName)) {
            log(context, "Blocked file to $peerName while its identity change awaits confirmation", "ERROR", null)
            return postResult(onResult, false)
        }
        if (messageId.isNotBlank()) activeFileTransfers.add(messageId)
        scope.launch {
            try {
                if (isPaused(context, peerName)) {
                    activeFileTransfers.remove(messageId)
                    return@launch postResult(onResult, false)
                }
                val fingerprint = P2PPreferences.prefs(context)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                log(context, "Sending secure file via Python transport to $peerName", "INFO", null)
                val previewBase64 = FileTransferPreview.createVideoPreviewBase64(filePath)
                val success = PythonBridge.sendP2pFile(
                    peerName,
                    endpoint,
                    filePath,
                    fingerprint,
                    messageId,
                    caption,
                    previewBase64,
                )
                val cancelled = messageId.isNotBlank() && cancelledFileTransfers.remove(messageId)
                activeFileTransfers.remove(messageId)
                if (cancelled) {
                    onMessageStatusChanged(peerName, messageId, "CANCELLED")
                    log(context, "File transfer to $peerName was cancelled", "INFO", null)
                    return@launch postResult(onResult, true)
                }
                log(context, "Sending file status to $peerName: ${if (success) "SUCCESS" else "FAILED"}", "INFO", null)
                postResult(onResult, success)
            } catch (error: Exception) {
                activeFileTransfers.remove(messageId)
                val cancelled = messageId.isNotBlank() && cancelledFileTransfers.remove(messageId)
                if (cancelled) {
                    onMessageStatusChanged(peerName, messageId, "CANCELLED")
                    return@launch postResult(onResult, true)
                }
                log(context, "Failed to send secure file", "ERROR", error)
                postResult(onResult, false)
            }
        }
    }

    fun cancelFile(context: Context, peerName: String, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        if (messageId !in activeFileTransfers) return false
        cancelledFileTransfers.add(messageId)
        activeFileTransfers.remove(messageId)
        onMessageStatusChanged(peerName, messageId, "CANCELLED")
        val fingerprint = P2PPreferences.prefs(context)
            .getString(P2PPreferences.peerFingerprint(peerName), null)
        scope.launch {
            PythonBridge.cancelP2pFile(peerName, messageId, fingerprint)
        }
        return true
    }

    fun isFileTransferActive(messageId: String): Boolean =
        messageId.isNotBlank() && messageId in activeFileTransfers

    fun reconnect(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        if (isPaused(context, peerName)) return postResult(onResult, false)
        scope.launch {
            try {
                if (isPaused(context, peerName)) return@launch postResult(onResult, false)
                val prefs = P2PPreferences.prefs(context)
                val endpoint = peerEndpoints[peerName]
                    ?: prefs.getString(P2PPreferences.lastEndpoint(peerName), "").orEmpty()
                val fingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null)
                log(context, "Requesting reconnection for $peerName at endpoint '$endpoint'", "INFO", null)
                val success = PythonBridge.reconnectPeerSession(peerName, endpoint, fingerprint)
                if (success) {
                    sendControlMessage(context, peerName, JSONObject().apply {
                        put("type", "ping")
                        put("sent_at_ms", System.currentTimeMillis())
                    })
                    processOfflineQueue(context, peerName, endpoint)
                }
                postResult(onResult, success)
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

    /** Persist a receipt before a short-lived component (such as a receiver) returns. */
    fun enqueueReadReceipt(context: Context, peerName: String, messageId: String): Boolean {
        if (messageId.isBlank() || isPaused(context, peerName)) return false
        val controlId = "read:$messageId"
        return try {
            ChatDatabaseHelper.getInstance(context.applicationContext).enqueuePendingControl(
                PendingControl(
                    id = controlId,
                    peerName = peerName,
                    type = "read_receipt",
                    payload = JSONObject().apply {
                        put("type", "read_receipt")
                        put("message_id", messageId)
                        put("control_id", controlId)
                    }.toString(),
                )
            )
            true
        } catch (error: Exception) {
            log(context, "Failed to queue read receipt", "ERROR", error)
            false
        }
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

    fun sendDeleteMessage(
        context: Context,
        peerName: String,
        endpoint: String?,
        messageId: String
    ) {
        val controlId = "delete:$messageId"
        val payload = JSONObject().apply {
            put("type", "delete_message")
            put("message_id", messageId)
            put("control_id", controlId)
        }
        sendPersistedControl(context, peerName, endpoint, controlId, "delete_message", payload, deleteAfterSend = true)
    }

    fun sendPinnedState(
        context: Context,
        peerName: String,
        payload: JSONObject,
        onResult: (Boolean) -> Unit = {},
    ) {
        val controlId = payload.optString("control_id")
        if (controlId.isBlank()) return postResult(onResult, false)
        sendPersistedControl(
            context = context,
            peerName = peerName,
            endpoint = peerEndpoints[peerName],
            controlId = controlId,
            type = payload.optString("type"),
            payload = payload,
            deleteAfterSend = false,
            onResult = onResult,
            operationScope = pinnedStateScope,
            replaceControlTypes = setOf("pin_message", "unpin_message"),
        )
    }

    fun processOfflineQueue(context: Context, peerName: String, endpoint: String) {
        if (endpoint.isBlank() || isPaused(context, peerName) || !processingOfflineQueues.add(peerName)) return
        scope.launch {
            try {
                if (isPaused(context, peerName)) return@launch
                val db = ChatDatabaseHelper.getInstance(context)
                val pending = db.getPendingMessagesForPeer(peerName)
                if (pending.isNotEmpty()) {
                    log(context, "Processing ${pending.size} pending offline messages for $peerName", "INFO", null)
                }
                val fingerprint = P2PPreferences.prefs(context)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                for (message in pending) {
                    if (isPaused(context, peerName)) {
                        log(context, "Paused offline queue for $peerName after an identity change", "ERROR", null)
                        break
                    }
                val payload = if (message.replyToId != null) JSONObject().apply {
                        put("type", "reply")
                        put("message_id", message.id)
                        put("text", message.text)
                        put("reply_to_id", message.replyToId)
                        put("reply_to_text", message.replyToText)
                        put("reply_to_name", message.replyToName)
                    }.toString() else JSONObject().apply {
                        put("type", "text")
                        put("message_id", message.id)
                        put("text", message.text)
                    }.toString()
                    val hasAttachment = message.attachmentType != null && !message.attachmentUri.isNullOrBlank()
                    val attachmentFile = if (hasAttachment) File(message.attachmentUri.orEmpty()) else null

                    // If the attachment file was deleted (e.g. OS cleared the temp cache)
                    // mark the message as FAILED and skip it — do NOT stop the queue.
                    if (attachmentFile != null && !attachmentFile.exists()) {
                        log(context, "Pending attachment file missing for ${message.id}, marking FAILED and skipping.", "ERROR", null)
                        db.updateMessageStatus(message.id, "FAILED")
                        Handler(Looper.getMainLooper()).post {
                            onMessageStatusChanged(peerName, message.id, "FAILED")
                        }
                        continue
                    }

                    val success = if (attachmentFile != null) {
                        PythonBridge.sendP2pFile(
                            peerName, endpoint, attachmentFile.absolutePath, fingerprint, message.id
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
                if (!isPaused(context, peerName)) {
                    processPendingControls(context, db, peerName, endpoint, fingerprint)
                }
            } catch (error: Exception) {
                log(context, "Error in processOfflineQueue: ${error.message}", "ERROR", error)
            } finally {
                processingOfflineQueues.remove(peerName)
            }
        }
    }

    private fun sendSilently(context: Context, peerName: String, endpoint: String, payload: JSONObject) {
        if (isPaused(context, peerName)) return
        scope.launch {
            try {
                if (isPaused(context, peerName)) return@launch
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
        scope.launch {
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
        onResult: (Boolean) -> Unit = {},
        operationScope: CoroutineScope = scope,
        replaceControlTypes: Set<String> = emptySet(),
    ) {
        val appContext = context.applicationContext
        // Security-sensitive controls must not be queued for automatic delivery to
        // a replacement identity which has not been accepted yet.
        if (isPaused(appContext, peerName)) return postResult(onResult, false)
        operationScope.launch {
            val db = ChatDatabaseHelper.getInstance(appContext)
            try {
                if (isPaused(appContext, peerName)) return@launch postResult(onResult, false)
                if (replaceControlTypes.isNotEmpty()) {
                    db.deletePendingControlsForPeerByTypes(peerName, replaceControlTypes)
                }
                db.enqueuePendingControl(
                    PendingControl(controlId, peerName, type, payload.toString())
                )
                val resolvedEndpoint = endpoint?.takeIf { it.isNotBlank() }
                    ?: peerEndpoints[peerName]
                    ?: P2PPreferences.prefs(appContext)
                        .getString(P2PPreferences.lastEndpoint(peerName), null)
                        ?.takeIf { it.isNotBlank() }
                    ?: return@launch postResult(onResult, false)
                val fingerprint = P2PPreferences.prefs(appContext)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                val sent = PythonBridge.sendP2pMessage(
                    peerName,
                    resolvedEndpoint,
                    payload.toString(),
                    fingerprint,
                )
                if (sent && deleteAfterSend) db.deletePendingControl(controlId)
                postResult(onResult, sent)
            } catch (error: Exception) {
                log(appContext, "Failed to queue/send $type control", "ERROR", error)
                postResult(onResult, false)
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
            if (isPaused(context, peerName)) break
            val success = PythonBridge.sendP2pMessage(
                peerName,
                endpoint,
                control.payload,
                fingerprint,
            )
            if (!success) break
            if (control.type == "read_receipt" || control.type == "delete_message") db.deletePendingControl(control.id)
            // Edits remain until the receiver returns edit_ack.
        }
    }

    private fun postResult(callback: (Boolean) -> Unit, result: Boolean) {
        Handler(Looper.getMainLooper()).post { callback(result) }
    }
}
