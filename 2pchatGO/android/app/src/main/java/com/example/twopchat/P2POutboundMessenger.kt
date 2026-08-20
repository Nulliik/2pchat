package com.example.twopchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.data.PendingControl
import com.example.twopchat.security.ImageSanitizer
import com.example.twopchat.security.TemporaryCacheSanitizer
import com.example.twopchat.ui.chat.Message
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val peerFailureBackoffMs = ConcurrentHashMap<String, Long>()
    private val lastPeerFailureAt = ConcurrentHashMap<String, Long>()
    private val peerSendLocks = ConcurrentHashMap<String, Mutex>()

    private fun normalizePeerKey(peerName: String): String =
        peerName.trim().lowercase()

    private fun getBridge(context: Context): com.example.twopchat.bridge.IP2PBridge =
        com.example.twopchat.bridge.P2PBridgeProvider.get(context)

    private fun getPeerLock(peerName: String): Mutex =
        peerSendLocks.computeIfAbsent(normalizePeerKey(peerName)) { Mutex() }

    fun resetPeerBackoffs(peerName: String? = null) {
        if (peerName != null) {
            val key = normalizePeerKey(peerName)
            peerFailureBackoffMs.remove(key)
            lastPeerFailureAt.remove(key)
        } else {
            peerFailureBackoffMs.clear()
            lastPeerFailureAt.clear()
        }
    }

    internal fun normalizePeerKeyForTest(peerName: String): String =
        normalizePeerKey(peerName)

    internal fun recordFailureForTest(peerName: String, failureTimeMs: Long = System.currentTimeMillis()) {
        val key = normalizePeerKey(peerName)
        val currentBackoff = peerFailureBackoffMs[key] ?: 1000L
        lastPeerFailureAt[key] = failureTimeMs
        val nextBackoff = (currentBackoff * 2).coerceAtMost(30_000L)
        val jitterFactor = java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.85, 1.15)
        peerFailureBackoffMs[key] = (nextBackoff * jitterFactor).toLong()
    }

    internal fun getFailureBackoffMs(peerName: String): Long? =
        peerFailureBackoffMs[normalizePeerKey(peerName)]

    internal fun getLastFailureAtMs(peerName: String): Long? =
        lastPeerFailureAt[normalizePeerKey(peerName)]

    internal fun isPeerInBackoff(peerName: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = normalizePeerKey(peerName)
        val lastFail = lastPeerFailureAt[key] ?: 0L
        val backoff = peerFailureBackoffMs[key] ?: 0L
        return nowMs - lastFail < backoff
    }

    private fun isPaused(context: Context, peerName: String): Boolean =
        peerName != "Direct Peer" && P2PPreferences.isPeerIdentityChangePending(context, peerName)

    fun sendMessage(context: Context, endpoint: String, text: String, onResult: (Boolean) -> Unit = {}) {
        val peerName = peerEndpoints.entries.firstOrNull { it.value == endpoint }?.key ?: "Direct Peer"
        sendResolvedMessage(context, peerName, endpoint, text, onResult)
    }

    fun sendMessageToPeer(
        context: Context,
        peerName: String,
        text: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        val fingerprint = P2PPreferences.prefs(context).getString(P2PPreferences.peerFingerprint(peerName), null)
        val isLive = getBridge(context).isPeerOnline(peerName, fingerprint)
        val endpoint = resolvePeerEndpoint(
            peerName = peerName,
            liveEndpoint = peerEndpoints[peerName],
            persistedEndpoint = P2PPreferences.prefs(context)
                .getString(P2PPreferences.lastEndpoint(peerName), null),
            onionEndpoint = P2PPreferences.getPeerOnionAddress(context, peerName),
        ) ?: if (isLive) "" else run {
            val peerKey = normalizePeerKey(peerName)
            val lastFail = lastPeerFailureAt[peerKey] ?: 0L
            val now = System.currentTimeMillis()
            if (now - lastFail > 10_000L) {
                lastPeerFailureAt[peerKey] = now
                log(
                    context,
                    "No valid transport endpoint for $peerName; peer is offline",
                    "DEBUG",
                    null,
                )
            }
            return postResult(onResult, false)
        }
        sendResolvedMessage(context, peerName, endpoint, text, onResult)
    }

    private fun sendResolvedMessage(
        context: Context,
        peerName: String,
        endpoint: String,
        text: String,
        onResult: (Boolean) -> Unit,
    ) {
        if (isPaused(context, peerName)) {
            log(context, "Blocked message to $peerName while its identity change awaits confirmation", "ERROR", null)
            return postResult(onResult, false)
        }
        val peerKey = normalizePeerKey(peerName)
        val lastFail = lastPeerFailureAt[peerKey] ?: 0L
        val backoff = peerFailureBackoffMs[peerKey] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastFail < backoff) {
            return postResult(onResult, false)
        }
        scope.launch {
            val lock = getPeerLock(peerName)
            lock.withLock {
                val currentLastFail = lastPeerFailureAt[peerKey] ?: 0L
                val currentBackoff = peerFailureBackoffMs[peerKey] ?: 0L
                val currentNow = System.currentTimeMillis()
                if (currentNow - currentLastFail < currentBackoff) {
                    return@withLock postResult(onResult, false)
                }
                try {
                    val fingerprint = P2PPreferences.prefs(context).getString(P2PPreferences.peerFingerprint(peerName), null).orEmpty()
                    log(context, "Sending secure message via active P2P bridge", "INFO", null)
                    val success = getBridge(context).sendP2pMessage(peerName, endpoint, text, fingerprint)
                    if (success) {
                        peerFailureBackoffMs.remove(peerKey)
                        lastPeerFailureAt.remove(peerKey)
                        NetworkTrafficStats.recordMessage(
                            context,
                            peerName,
                            endpoint,
                            text,
                            TrafficDirection.SENT,
                        )
                    } else {
                        val currentBackoffVal = peerFailureBackoffMs[peerKey] ?: 1000L
                        lastPeerFailureAt[peerKey] = System.currentTimeMillis()
                        val nextBackoff = (currentBackoffVal * 2).coerceAtMost(30_000L)
                        val jitterFactor = java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.85, 1.15)
                        peerFailureBackoffMs[peerKey] = (nextBackoff * jitterFactor).toLong()
                    }
                    log(context, "Secure message send: ${if (success) "SUCCESS" else "FAILED"}", "INFO", null)
                    postResult(onResult, success)
                } catch (error: Exception) {
                    log(context, "Failed to send secure message", "ERROR", error)
                    postResult(onResult, false)
                }
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
        sendMessageToPeer(context, peerName, payload.toString(), onResult)
    }

    fun sendFile(
        context: Context,
        peerName: String,
        endpoint: String,
        filePath: String,
        messageId: String = "",
        caption: String = "",
        albumId: String = "",
        albumIndex: Int = -1,
        albumCount: Int = 0,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (isPaused(context, peerName)) {
            log(context, "Blocked file to $peerName while its identity change awaits confirmation", "ERROR", null)
            return postResult(onResult, false)
        }
        if (messageId.isNotBlank()) activeFileTransfers.add(messageId)
        scope.launch {
            var tempSanitizedFile: File? = null
            try {
                if (isPaused(context, peerName)) {
                    activeFileTransfers.remove(messageId)
                    return@launch postResult(onResult, false)
                }
                val fingerprint = P2PPreferences.prefs(context)
                    .getString(P2PPreferences.peerFingerprint(peerName), null)
                log(context, "Sending secure file via Native Go transport to $peerName", "INFO", null)

                // Transparently strip EXIF metadata from outbound images
                tempSanitizedFile = ImageSanitizer.sanitizeImageExif(context, filePath)
                val effectiveFilePath = tempSanitizedFile?.absolutePath ?: filePath

                val previewBase64 = FileTransferPreview.createVideoPreviewBase64(effectiveFilePath)
                val success = getBridge(context).sendFile(
                    peerName,
                    endpoint,
                    effectiveFilePath,
                    fingerprint,
                    messageId,
                    caption,
                    previewBase64,
                    albumId,
                    albumIndex,
                    albumCount,
                )
                val cancelled = messageId.isNotBlank() && cancelledFileTransfers.remove(messageId)
                activeFileTransfers.remove(messageId)
                if (cancelled) {
                    onMessageStatusChanged(peerName, messageId, "CANCELLED")
                    log(context, "File transfer to $peerName was cancelled", "INFO", null)
                    return@launch postResult(onResult, true)
                }
                if (success) {
                    NetworkTrafficStats.recordFile(
                        context,
                        peerName,
                        endpoint,
                        File(effectiveFilePath),
                        direction = TrafficDirection.SENT,
                    )
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
            } finally {
                tempSanitizedFile?.let {
                    TemporaryCacheSanitizer.shredFile(it)
                }
            }
        }
    }

    fun cancelFile(context: Context, peerName: String, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        cancelledFileTransfers.add(messageId)
        activeFileTransfers.remove(messageId)
        onMessageStatusChanged(peerName, messageId, "CANCELLED")
        val fingerprint = P2PPreferences.prefs(context)
            .getString(P2PPreferences.peerFingerprint(peerName), null)
        scope.launch {
            getBridge(context).cancelFile(peerName, messageId, fingerprint)
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
                val rawEndpoint = peerEndpoints[peerName]
                    ?: prefs.getString(P2PPreferences.lastEndpoint(peerName), "").orEmpty()
                val endpoint = P2PPreferences.getEffectiveEndpointsForPeer(context, peerName, rawEndpoint)
                val fingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null)
                if (endpoint.isBlank()) {
                    log(context, "Cannot reconnect to $peerName: endpoint is unknown. Peer must share invite link or Tor .onion address", "WARNING", null)
                    return@launch postResult(onResult, false)
                }
                log(context, "Requesting reconnection for $peerName at endpoint '$endpoint'", "INFO", null)
                val success = getBridge(context).reconnectPeerSession(peerName, endpoint, fingerprint.orEmpty())
                // Do NOT call processOfflineQueue here: reconnectPeerSession returns True immediately
                // (fire-and-forget), meaning the session is not yet established at this point.
                // The offline queue is flushed from onSessionEstablished once the session is live.
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
        val peerKey = normalizePeerKey(peerName)
        if (endpoint.isBlank() || isPaused(context, peerName) || !processingOfflineQueues.add(peerKey)) return
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
                if (pending.isNotEmpty() && !getBridge(context).isPeerOnline(peerName, fingerprint.orEmpty())) {
                    log(context, "Peer $peerName is offline; keeping ${pending.size} pending messages in offline queue", "INFO", null)
                    return@launch
                }
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
                    val albumFiles = message.albumMediaUris.map(::File)
                    val hasAlbum = message.attachmentType == "ALBUM" && albumFiles.size > 1
                    val hasAttachment = message.attachmentType != null && !message.attachmentUri.isNullOrBlank()
                    val attachmentFile = if (hasAttachment && !hasAlbum) {
                        File(message.attachmentUri.orEmpty())
                    } else {
                        null
                    }
                    val missingAttachment = if (hasAlbum) {
                        albumFiles.firstOrNull { !it.exists() }
                    } else {
                        attachmentFile?.takeIf { !it.exists() }
                    }

                    // If the attachment file was deleted (e.g. OS cleared the temp cache)
                    // mark the message as FAILED and skip it — do NOT stop the queue.
                    if (missingAttachment != null) {
                        log(context, "Pending attachment file missing for ${message.id}, marking FAILED and skipping.", "ERROR", null)
                        db.updateMessageStatus(message.id, "FAILED")
                        Handler(Looper.getMainLooper()).post {
                            onMessageStatusChanged(peerName, message.id, "FAILED")
                        }
                        continue
                    }

                    val caption = attachmentCaption(message)
                    val success = if (hasAlbum) {
                        run {
                            for ((index, file) in albumFiles.withIndex()) {
                                var tempSanitized: File? = null
                                val fileToSend = try {
                                    tempSanitized = ImageSanitizer.sanitizeImageExif(context, file.absolutePath)
                                    tempSanitized ?: file
                                } catch (e: Exception) {
                                    file
                                }
                                val fileSent = try {
                                    getBridge(context).sendFile(
                                        peerName = peerName,
                                        endpoint = endpoint,
                                        filePath = fileToSend.absolutePath,
                                        expectedFingerprint = fingerprint,
                                        messageId = "${message.id}_$index",
                                        caption = if (index == 0) caption else "",
                                        albumId = message.id,
                                        albumIndex = index,
                                        albumCount = albumFiles.size,
                                    )
                                } finally {
                                    tempSanitized?.let { TemporaryCacheSanitizer.shredFile(it) }
                                }
                                if (!fileSent) return@run false
                                NetworkTrafficStats.recordFile(
                                    context,
                                    peerName,
                                    endpoint,
                                    file,
                                    direction = TrafficDirection.SENT,
                                )
                            }
                            true
                        }
                    } else if (attachmentFile != null) {
                        var tempSanitized: File? = null
                        val fileToSend = try {
                            tempSanitized = ImageSanitizer.sanitizeImageExif(context, attachmentFile.absolutePath)
                            tempSanitized ?: attachmentFile
                        } catch (e: Exception) {
                            attachmentFile
                        }
                        val fileSent = try {
                            getBridge(context).sendFile(
                                peerName = peerName,
                                endpoint = endpoint,
                                filePath = fileToSend.absolutePath,
                                expectedFingerprint = fingerprint,
                                messageId = message.id,
                                caption = caption,
                                albumId = "",
                                albumIndex = -1,
                                albumCount = 0,
                            )
                        } finally {
                            tempSanitized?.let { TemporaryCacheSanitizer.shredFile(it) }
                        }
                        if (fileSent) {
                            NetworkTrafficStats.recordFile(
                                context,
                                peerName,
                                endpoint,
                                attachmentFile,
                                direction = TrafficDirection.SENT,
                            )
                        }
                        fileSent
                    } else {
                        val msgSent = getBridge(context).sendP2pMessage(peerName, endpoint, payload, fingerprint.orEmpty())
                        if (msgSent) {
                            NetworkTrafficStats.recordMessage(
                                context,
                                peerName,
                                endpoint,
                                payload,
                                TrafficDirection.SENT,
                            )
                        }
                        msgSent
                    }

                    if (!success) {
                        log(context, "Failed to send pending message ${message.id}, stopping queue processing.", "INFO", null)
                        break
                    }
                    if (attachmentFile != null) {
                        NetworkTrafficStats.recordFile(
                            context,
                            peerName,
                            endpoint,
                            attachmentFile,
                            attachmentType = message.attachmentType.orEmpty(),
                            direction = TrafficDirection.SENT,
                        )
                    } else if (!hasAlbum) {
                        NetworkTrafficStats.recordMessage(
                            context,
                            peerName,
                            endpoint,
                            payload,
                            TrafficDirection.SENT,
                        )
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
                processingOfflineQueues.remove(peerKey)
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
                val text = payload.toString()
                if (getBridge(context).sendP2pMessage(peerName, endpoint, text, fingerprint.orEmpty())) {
                    NetworkTrafficStats.recordMessage(
                        context,
                        peerName,
                        endpoint,
                        text,
                        TrafficDirection.SENT,
                    )
                }
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
                val sent = getBridge(appContext).sendP2pMessage(
                    peerName,
                    resolvedEndpoint,
                    payload.toString(),
                    fingerprint.orEmpty(),
                )
                if (sent) {
                    NetworkTrafficStats.recordMessage(
                        appContext,
                        peerName,
                        resolvedEndpoint,
                        payload.toString(),
                        TrafficDirection.SENT,
                    )
                }
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
            val success = getBridge(context).sendP2pMessage(
                peerName,
                endpoint,
                control.payload,
                fingerprint.orEmpty(),
            )
            if (!success) break
            NetworkTrafficStats.recordMessage(
                context,
                peerName,
                endpoint,
                control.payload,
                TrafficDirection.SENT,
            )
            if (control.type == "read_receipt" || control.type == "delete_message") db.deletePendingControl(control.id)
            // Edits remain until the receiver returns edit_ack.
        }
    }

    private fun postResult(callback: (Boolean) -> Unit, result: Boolean) {
        Handler(Looper.getMainLooper()).post { callback(result) }
    }

    private fun attachmentCaption(message: Message): String {
        val text = message.text.trim()
        if (text.isBlank()) return ""
        val defaultText = when (message.attachmentType) {
            "IMAGE" -> text.equals("Sent an image", ignoreCase = true) ||
                text.equals("Фотография", ignoreCase = true) ||
                text.equals("Отправлена фотография", ignoreCase = true)
            "VIDEO" -> text.equals("Sent a video", ignoreCase = true) ||
                text.equals("Видеозапись", ignoreCase = true)
            "VOICE" -> text.equals("Voice message", ignoreCase = true) ||
                text.equals("Голосовое сообщение", ignoreCase = true)
            GifStorageManager.ATTACHMENT_TYPE -> text.equals("GIF", ignoreCase = true)
            "ALBUM" -> text.startsWith("Sent an album", ignoreCase = true) ||
                text.startsWith("Album", ignoreCase = true) ||
                text.startsWith("Альбом", ignoreCase = true)
            StickerSupport.ATTACHMENT_TYPE -> false
            StickerSupport.PACK_ATTACHMENT_TYPE -> true
            else -> true
        }
        return text.takeUnless { defaultText }.orEmpty()
    }
}
