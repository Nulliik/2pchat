


package com.example.twopchat

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.UUID
import android.util.Base64
import androidx.compose.runtime.mutableStateMapOf
import com.chaquo.python.Python

object P2PMessageRelay {
    private const val TAG = "P2PMessageRelay"
    private const val LISTENER_PORT = 50001
    private val logTimestampFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.getDefault())
    }
    private val startStopLock = Any()
    private val identityLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isRunning = false
    private val avatarCache = PeerAvatarCache()
    private val notificationService = MessageNotificationService()

    /** All installations listen on the documented P2P port; identity is the fingerprint, not the port. */
    fun listenerPort(@Suppress("UNUSED_PARAMETER") context: Context): Int {
        return LISTENER_PORT
    }

    fun refreshAnnouncement(context: Context) {
        val appContext = context.applicationContext
        thread(start = true, name = "ManualTrackerAnnounce") {
            val prefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
            val username = prefs.getString("username_profile", "").orEmpty()
            val fingerprint = PythonBridge.getLocalFingerprint()
            if (username.isNotBlank() && fingerprint.length >= 40) {
                val success = PythonBridge.announceSelf(username, fingerprint, listenerPort(appContext), force = true)
                log(appContext, "Forced announce after transport setting change: $success")
            }
        }
    }

    // Maps peer name to their resolved IP:Port endpoint
    val peerEndpoints = mutableStateMapOf<String, String>()
    val peerConnectionTransports = mutableStateMapOf<String, String>()
    val peerSessionStates = mutableStateMapOf<String, Boolean>()
    private val fingerprintToPeerName = ConcurrentHashMap<String, String>()
    private val avatarSharesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastAvatarShareAt = ConcurrentHashMap<String, Long>()

    // Maps peer name to their profile avatar bitmap in RAM
    val peerAvatars = avatarCache.avatars

    // Maps peer name to typing state
    val peerTypingStates = mutableStateMapOf<String, Boolean>()

    private val maintenanceCoordinator = RelayMaintenanceCoordinator(
        scope = serviceScope,
        isRunning = { isRunning },
        peerEndpoints = peerEndpoints,
        peerConnectionTransports = peerConnectionTransports,
        peerSessionStates = peerSessionStates,
        log = ::log,
    )
    private val outboundMessenger by lazy {
        P2POutboundMessenger(peerEndpoints, ::log) { peerName, messageId, status ->
            messageListeners.forEach { it.onMessageStatusChanged(peerName, messageId, status) }
        }
    }

    interface MessageListener {
        fun onMessageReceived(sender: String, text: String)
        fun onMessageStatusChanged(sender: String, msgId: String, status: String)
        fun onMessageReactionChanged(sender: String, msgId: String, emoji: String, reactSender: String) {}
        fun onVerificationRequest(sender: String) {}
        fun onVerificationResponse(sender: String, success: Boolean) {}
        fun onMessagePinned(sender: String, msgId: String, text: String, isFromSender: Boolean) {}
        fun onMessageUnpinned(sender: String) {}
    }

    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<MessageListener>()
    var activeChatPeerName: String? = null

    fun registerMessageListener(listener: MessageListener) {
        messageListeners.add(listener)
    }

    fun unregisterMessageListener(listener: MessageListener) {
        messageListeners.remove(listener)
    }

    private fun showNotification(context: Context, sender: String, text: String) {
        try {
            notificationService.show(context, sender, text)
        } catch (e: Exception) {
            log(context, "Failed to show message notification: ${e.message}", "ERROR", e)
        }
    }

    private fun parseIncomingAttachment(text: String): IncomingAttachment? {
        return IncomingMessageParser.parseAttachment(text)
    }

    private fun isPlaceholderPeerName(name: String): Boolean {
        return name.startsWith("Peer (") && name.endsWith(")")
    }

    private fun moveChatState(
        context: Context,
        fromName: String,
        toName: String
    ) {
        if (fromName == toName) return
        val sharedPrefs = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
        val updatedChats = activeSet.toMutableSet()
        val hadVisibleChat = updatedChats.remove(fromName)
        var changed = hadVisibleChat
        if (hadVisibleChat && updatedChats.add(toName)) {
            changed = true
        }

        val editor = sharedPrefs.edit()
        if (changed) {
            editor.putStringSet("active_chats", updatedChats)
        }

        val keysToMove = listOf(
            "last_msg_", "transport_", "last_endpoint_", "peer_fingerprint_",
            "unread_count_", "verified_peer_", "fingerprint_mismatch_"
        )
        for (prefix in keysToMove) {
            if (!sharedPrefs.contains("$prefix$fromName")) {
                continue
            }
            when (prefix) {
                "verified_peer_", "fingerprint_mismatch_" -> {
                    val value = sharedPrefs.getBoolean("$prefix$fromName", false)
                    val existing = sharedPrefs.getBoolean("$prefix$toName", false)
                    editor.putBoolean("$prefix$toName", existing || value)
                }
                "unread_count_" -> {
                    val value = sharedPrefs.getInt("$prefix$fromName", 0)
                    val existing = sharedPrefs.getInt("$prefix$toName", 0)
                    editor.putInt("$prefix$toName", existing + value)
                }
                "last_msg_" -> {
                    // Placeholder messages are the most recently received
                    // ones which triggered this migration.
                    sharedPrefs.getString("$prefix$fromName", null)?.let {
                        editor.putString("$prefix$toName", it)
                    }
                }
                else -> {
                    val value = sharedPrefs.getString("$prefix$fromName", null)
                    if (value != null && !sharedPrefs.contains("$prefix$toName")) {
                        editor.putString("$prefix$toName", value)
                    }
                }
            }
            editor.remove("$prefix$fromName")
        }
        editor.apply()

        try {
            val db = ChatDatabaseHelper.getInstance(context)
            db.renamePeer(fromName, toName)
            refreshLastMessageFromHistory(context, db, toName)
        } catch (e: Exception) {
            log(context, "Failed to migrate chat history from $fromName to $toName", "ERROR", e)
        }
    }

    private fun refreshLastMessageFromHistory(
        context: Context,
        db: ChatDatabaseHelper,
        peerName: String
    ) {
        val prefs = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("persist_chat_history", true)) return
        val latest = db.getMessagesForPeer(peerName).lastOrNull() ?: return
        val preview = if (latest.isMe) "You: ${latest.text}" else latest.text
        prefs.edit().putString("last_msg_$peerName", SecureStorage.encrypt(preview)).apply()
    }

    private fun canonicalPeerName(
        context: Context,

        peerName: String,

        fingerprint: String

    ): String {
        if (fingerprint.isBlank()) {
            return peerName
        }
        synchronized(identityLock) {
            val knownName = fingerprintToPeerName[fingerprint]
            val persistedName = if (knownName.isNullOrBlank() && isPlaceholderPeerName(peerName)) {
                context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                    .all.entries
                    .firstOrNull { (key, value) ->
                        key.startsWith("peer_fingerprint_") &&
                            value == fingerprint &&
                            !isPlaceholderPeerName(key.removePrefix("peer_fingerprint_"))
                    }
                    ?.key
                    ?.removePrefix("peer_fingerprint_")
            } else {
                null
            }
            return when {
                !persistedName.isNullOrBlank() -> {
                    fingerprintToPeerName[fingerprint] = persistedName
                    if (peerName != persistedName && isPlaceholderPeerName(peerName)) {
                        moveChatState(context, peerName, persistedName)
                        Handler(Looper.getMainLooper()).post {
                            peerEndpoints.remove(peerName)
                            peerSessionStates.remove(peerName)
                            peerConnectionTransports.remove(peerName)
                        }
                    }
                    persistedName
                }
                knownName.isNullOrBlank() -> {
                    fingerprintToPeerName[fingerprint] = peerName
                    peerName
                }
                knownName == peerName -> peerName
                isPlaceholderPeerName(knownName) && !isPlaceholderPeerName(peerName) -> {
                    fingerprintToPeerName[fingerprint] = peerName
                    moveChatState(context, knownName, peerName)
                    Handler(Looper.getMainLooper()).post {
                        peerEndpoints.remove(knownName)
                    }
                    peerName
                }
                !isPlaceholderPeerName(knownName) && isPlaceholderPeerName(peerName) -> {
                    moveChatState(context, peerName, knownName)
                    Handler(Looper.getMainLooper()).post {
                        peerEndpoints.remove(peerName)
                    }
                    knownName
                }
                else -> {
                    fingerprintToPeerName[fingerprint] = peerName
                    peerName
                }
            }
        }
    }

    private fun migratePersistedPlaceholderChats(context: Context) {
        val prefs = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
        val placeholders = prefs.getStringSet("active_chats", emptySet()).orEmpty()
            .filter(::isPlaceholderPeerName)
        if (placeholders.isEmpty()) return

        val canonicalIdentities = prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith("peer_fingerprint_") || value !is String) return@mapNotNull null
            val name = key.removePrefix("peer_fingerprint_")
            if (isPlaceholderPeerName(name)) null else name to value
        }
        for (placeholder in placeholders) {
            val abbreviatedFingerprint = placeholder.removePrefix("Peer (").removeSuffix(")")
            val matches = canonicalIdentities.filter { (_, fingerprint) ->
                fingerprint.startsWith(abbreviatedFingerprint)
            }
            if (matches.size == 1) {
                val (canonicalName, fingerprint) = matches.single()
                fingerprintToPeerName[fingerprint] = canonicalName
                moveChatState(context, placeholder, canonicalName)
                log(context, "Migrated stale placeholder chat $placeholder to $canonicalName")
            }
        }
    }

    private fun log(context: Context, message: String, level: String = "INFO", error: Throwable? = null) {
        val fullMsg = if (error != null) "$message: ${Log.getStackTraceString(error)}" else message
        if (level == "ERROR") {
            Log.e(TAG, fullMsg)
        } else {
            Log.i(TAG, fullMsg)
        }
        try {
            val timestamp = checkNotNull(logTimestampFormatter.get()).format(Date())
            AppLog.append(context, "$timestamp [KOTLIN_$level] $TAG: $fullMsg\n")
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Start the background Python P2P server.
     */
    fun startServer(context: Context) {
        synchronized(startStopLock) {
            if (isRunning) return
            isRunning = true
        }
        val appContext = context.applicationContext
        loadPersistedAvatars(appContext)
        migratePersistedPlaceholderChats(appContext)
        val persistedPrefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
        val persistedChats = persistedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
        if (persistedPrefs.getBoolean("persist_chat_history", true)) {
            val db = ChatDatabaseHelper.getInstance(appContext)
            for (peerName in persistedChats) {
                refreshLastMessageFromHistory(appContext, db, peerName)
            }
        }
        for (peerName in persistedChats) {
            persistedPrefs.getString("last_endpoint_$peerName", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { peerEndpoints[peerName] = it }
        }
        val port = listenerPort(appContext)
        try {
            log(appContext, "Starting Python P2P Relays on port $port...")
            val ipv4Enabled = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                .getBoolean("settings_ipv4", true)
            PythonBridge.setIpv4Enabled(ipv4Enabled)
            // Start the Python P2P listener
            PythonBridge.startP2pListener(port)
            
            // Register incoming message callback from Python
            PythonBridge.registerMessageListener(object : PythonBridge.PyMessageListener {
                override fun onMessageReceived(sender: String, text: String) {
                    log(appContext, "Incoming secure P2P message (${text.toByteArray().size} bytes)")
                    val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                    try {
                        val trimmed = text.trim()
                        if (trimmed.startsWith("{")) {
                            val json = org.json.JSONObject(trimmed)
                            when (json.optString("type")) {
                                "verification_request" -> {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        messageListeners.forEach { it.onVerificationRequest(sender) }
                                    }
                                    return
                                }
                                "verification_response" -> {
                                    val success = json.optBoolean("success", false)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        messageListeners.forEach { it.onVerificationResponse(sender, success) }
                                    }
                                    return
                                }
                                "profile_avatar_share" -> {
                                    val b64 = json.optString("avatar_base64")
                                    // Avatars are control-plane thumbnails, not file transfers. Bound their
                                    // encoded size and decoded dimensions before allocating a full bitmap.
                                    if (b64.isNotEmpty() && b64.length <= 2_000_000) {
                                        try {
                                            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
                                                bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L) return
                                            var sample = 1
                                            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                                                bytes, 0, bytes.size,
                                                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                                            )
                                            if (bitmap != null) {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    avatarCache.put(sender, bitmap)
                                                }
                                                log(appContext, "Successfully received and cached avatar in RAM for $sender")
                                                
                                                // Persist avatar to disk
                                                try {
                                                    val avatarsDir = java.io.File(appContext.filesDir, "avatars")
                                                    if (!avatarsDir.exists()) avatarsDir.mkdirs()
                                                    val avatarFile = java.io.File(avatarsDir, "${sender}.jpg")
                                                    val outStream = java.io.FileOutputStream(avatarFile)
                                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outStream)
                                                    outStream.flush()
                                                    outStream.close()
                                                    log(appContext, "Saved avatar for $sender to ${avatarFile.absolutePath}")
                                                } catch (saveEx: Exception) {
                                                    log(appContext, "Failed to save avatar file: ${saveEx.message}", "ERROR", saveEx)
                                                }
                                            }
                                        } catch (e: Exception) {
                                            log(appContext, "Error decoding avatar: ${e.message}", "ERROR", e)
                                        }

                                    }
                                    return

                                }
                                "pin_message" -> {

                                    val msgId = json.optString("msg_id")
                                    val text = json.optString("text")
                                    val isFromSender = json.optBoolean("is_from_sender", false)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        messageListeners.forEach { it.onMessagePinned(sender, msgId, text, isFromSender) }
                                    }
                                    return
                                }
                                "unpin_message" -> {
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        messageListeners.forEach { it.onMessageUnpinned(sender) }
                                    }
                                    return
                                }
                                "typing_state" -> {
                                    val isTyping = json.optBoolean("is_typing", false)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        peerTypingStates[sender] = isTyping
                                    }
                                    return
                                }
                                "read_receipt" -> {
                                    val msgId = json.optString("message_id")
                                    if (msgId.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        db.updateMessageStatus(msgId, "READ")
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            messageListeners.forEach { it.onMessageStatusChanged(sender, msgId, "READ") }
                                        }
                                    }
                                    return
                                }
                                "reaction" -> {
                                    val msgId = json.optString("message_id")
                                    val emoji = json.optString("emoji")
                                    val messageText = json.optString("message_text")
                                    if (msgId.isNotEmpty() && emoji.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        val msgs = db.getMessagesForPeer(sender)
                                        // Message ids used to be generated independently on both
                                        // Android devices. Keep id as the primary key, but use the
                                        // immutable message contents for reactions from older chats.
                                        val existing = msgs.find { it.id == msgId }
                                            ?: msgs.lastOrNull { it.isMe && messageText.isNotEmpty() && it.text == messageText }
                                        if (existing != null) {
                                            val updatedMap = existing.reactions.toMutableMap()
                                            val sendersList = (updatedMap[emoji] ?: emptyList()).toMutableList()
                                            if (!sendersList.contains(sender)) {
                                                sendersList.add(sender)
                                                updatedMap[emoji] = sendersList
                                                db.updateMessageReactions(msgId, updatedMap)
                                            }
                                        }
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            messageListeners.forEach {
                                                it.onMessageReactionChanged(sender, existing?.id ?: msgId, emoji, sender)
                                            }
                                        }
                                    }
                                    return
                                }
                                "reply" -> {
                                val replyText = json.optString("text")
                                val replyToId = json.optString("reply_to_id")
                                val replyToText = json.optString("reply_to_text")
                                val replyToName = json.optString("reply_to_name")
                                
                                val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                if (!activeSet.contains(sender)) {
                                    val newSet = activeSet.toMutableSet()
                                    newSet.add(sender)
                                    sharedPrefs.edit { putStringSet("active_chats", newSet) }
                                }
                                
                                val persistEnabled = sharedPrefs.getBoolean("persist_chat_history", true)
                                val db = ChatDatabaseHelper.getInstance(appContext)
                                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                
                                val rxMsg = Message(
                                    id = UUID.randomUUID().toString(),
                                    text = replyText,
                                    isMe = false,
                                    timestamp = time,
                                    replyToId = replyToId,
                                    replyToText = replyToText,
                                    replyToName = replyToName,
                                    status = "SENT"
                                )
                                if (persistEnabled) {
                                    db.saveMessage(sender, rxMsg)
                                }
                                sharedPrefs.edit { putString("last_msg_$sender", SecureStorage.encrypt(replyText)) }
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    messageListeners.forEach { it.onMessageReceived(sender, text) }
                                    if (activeChatPeerName != sender) {
                                        val currentUnread = sharedPrefs.getInt("unread_count_$sender", 0)
                                        sharedPrefs.edit { putInt("unread_count_$sender", currentUnread + 1) }
                                        showNotification(appContext, sender, text)
                                    }
                                }
                                return
                            }
                        }
                    }

                        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                        if (!activeSet.contains(sender)) {
                            val newSet = activeSet.toMutableSet()
                            newSet.add(sender)
                            sharedPrefs.edit { putStringSet("active_chats", newSet) }
                        }
                        
                        val persistEnabled = sharedPrefs.getBoolean("persist_chat_history", true)
                        val db = ChatDatabaseHelper.getInstance(appContext)
                        var displayMessage = text
                        val incomingAttachment = parseIncomingAttachment(text)
                        if (incomingAttachment != null) {
                            displayMessage = incomingAttachment.displayMessage
                            if (persistEnabled) {
                                db.saveMessage(sender, Message(
                                    id = incomingAttachment.messageId.ifBlank { UUID.randomUUID().toString() },
                                    text = incomingAttachment.displayMessage,
                                    isMe = false,
                                    timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                    attachmentType = incomingAttachment.attachmentType,
                                    attachmentUri = incomingAttachment.attachmentUri,
                                    attachmentName = incomingAttachment.attachmentName,
                                    status = "SENT"
                                ))
                            }
                        } else {
                            if (persistEnabled) {
                                db.saveMessage(sender, Message(
                                    id = UUID.randomUUID().toString(),
                                    text = text,
                                    isMe = false,
                                    timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                    status = "SENT"
                                ))
                            }
                        }
                        sharedPrefs.edit { putString("last_msg_$sender", SecureStorage.encrypt(displayMessage)) }
                    } catch (ex: Exception) {
                        log(appContext, "Failed to persist incoming message to SharedPreferences/SQLite", "ERROR", ex)
                    }

                    // Dispatch to active chat UI listener if any
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        messageListeners.forEach { it.onMessageReceived(sender, text) }
                        if (activeChatPeerName != sender) {
                            val currentUnread = sharedPrefs.getInt("unread_count_$sender", 0)
                            sharedPrefs.edit { putInt("unread_count_$sender", currentUnread + 1) }
                            showNotification(appContext, sender, text)
                        }
                    }
                }
            })

            // Register session status callbacks from Python
            PythonBridge.registerSessionListener(object : PythonBridge.PySessionListener {
                override fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String, transport: String) {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    // Incoming handshakes are named by fingerprint until their
                    // authenticated identity_info arrives. Search probes and
                    // half-open sessions must never create visible Peer (...)
                    // chats or leave placeholder preference keys behind.
                    if (isPlaceholderPeerName(resolvedPeerName)) {
                        log(appContext, "Authenticated unnamed session awaiting identity_info ($fingerprint)")
                        return
                    }
                    PythonBridge.rememberPeerName(fingerprint, resolvedPeerName)
                    appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        .edit().apply {
                            putString("peer_fingerprint_$resolvedPeerName", fingerprint)
                            if (endpoint.isNotEmpty()) {
                                putString("last_endpoint_$resolvedPeerName", endpoint)
                            }
                            apply()
                        }
                    log(appContext, "Secure Double Ratchet session established with $resolvedPeerName ($fingerprint) at $endpoint")
                    Handler(Looper.getMainLooper()).post {
                        peerSessionStates[resolvedPeerName] = true
                        peerConnectionTransports[resolvedPeerName] = transport
                        if (endpoint.isNotEmpty()) {
                            peerEndpoints[resolvedPeerName] = endpoint
                        }
                    }

                    if (endpoint.isNotEmpty()) {
                        
                        // Save to active chats so the UI updates and shows the peer chat screen
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit()
                            .putString("transport_$resolvedPeerName", transport)

                            .apply()
                        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                        if (!activeSet.contains(resolvedPeerName)) {

                            val newSet = activeSet.toMutableSet()
                            newSet.add(resolvedPeerName)
                            sharedPrefs.edit().putStringSet("active_chats", newSet).apply()

                        }

                        shareAvatar(appContext, resolvedPeerName, endpoint)
                        processOfflineQueue(appContext, resolvedPeerName, endpoint)
                    }
                }

                override fun onSessionClosed(peerName: String) {
                    val fingerprint = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        .getString("peer_fingerprint_$peerName", null)
                    val resolvedPeerName = if (fingerprint != null) canonicalPeerName(appContext, peerName, fingerprint) else peerName
                    log(appContext, "Secure Double Ratchet session closed with $resolvedPeerName")
                    Handler(Looper.getMainLooper()).post {
                        peerConnectionTransports.remove(resolvedPeerName)
                        peerSessionStates.remove(resolvedPeerName)
                    }
                }
            })
            
            log(appContext, "Python P2P Relays started successfully")

            // Retry persisted pending messages immediately after the listener is ready.
            for (peerName in persistedChats) {
                peerEndpoints[peerName]?.let { endpoint ->
                    processOfflineQueue(appContext, peerName, endpoint)
                }
            }

            maintenanceCoordinator.start(appContext, port, ::isPlaceholderPeerName)

        } catch (e: Exception) {
            synchronized(startStopLock) {
                isRunning = false
            }
            maintenanceCoordinator.stop()
            log(appContext, "Error starting Python P2P Relays", "ERROR", e)
        }
    }

    /**
     * Stop the P2P server.
     */
    fun stopServer() {
        synchronized(startStopLock) {
            if (!isRunning) return
            isRunning = false
        }
        synchronized(identityLock) {
            fingerprintToPeerName.clear()
        }
        maintenanceCoordinator.stop()
        avatarCache.clear()
        // Trigger Python shutdown/cleanup
        thread(start = true) {
            try {
                if (PythonBridge.isInitialized) {
                    val py = Python.getInstance()
                    val bridge = py.getModule("discovery_bridge")
                    bridge.callAttr("shutdown_all_sessions")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down Python sessions", e)
            }
        }
    }

    fun shareAvatar(context: Context, peerName: String, endpoint: String) {
        val prefs = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
        val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
        val shareKey = fingerprint ?: peerName
        val now = System.currentTimeMillis()
        if (!avatarSharesInFlight.add(shareKey)) return
        if (now - (lastAvatarShareAt[shareKey] ?: 0L) < 30_000L) {
            avatarSharesInFlight.remove(shareKey)
            return
        }
        thread(start = true, name = "AvatarShareThread") {
            try {
                val file = File(context.filesDir, "profile_avatar.jpg")
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val maxDimension = 320
                        val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                            val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val width = if (aspectRatio > 1) maxDimension else (maxDimension * aspectRatio).toInt()
                            val height = if (aspectRatio > 1) (maxDimension / aspectRatio).toInt() else maxDimension
                            Bitmap.createScaledBitmap(bitmap, width, height, true)
                        } else {
                            bitmap
                        }
                        
                        val outputStream = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                        val bytes = outputStream.toByteArray()
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        
                        if (scaledBitmap !== bitmap) {
                            scaledBitmap.recycle()
                        }
                        bitmap.recycle()
                        
                        val json = JSONObject().apply {
                            put("type", "profile_avatar_share")
                            put("avatar_base64", b64)
                        }
                        val payload = json.toString()
                        val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                            .getString("peer_fingerprint_$peerName", null)
                        
                        log(context, "Sending profile avatar to $peerName (length: ${payload.length})")
                        val success = PythonBridge.sendP2pMessage(peerName, endpoint, payload, expectedFingerprint)
                        if (success) lastAvatarShareAt[shareKey] = System.currentTimeMillis()
                        log(context, "Avatar send status to $peerName: $success")

                    }
                } else {
                    log(context, "profile_avatar.jpg does not exist, skipping avatar share.")
                }
            } catch (e: Exception) {
                log(context, "Failed to share avatar with $peerName", "ERROR", e)
            } finally {
                avatarSharesInFlight.remove(shareKey)
            }
        }
    }

    fun shareAvatarWithConnectedPeers(context: Context) {
        peerEndpoints.toMap().forEach { (peerName, endpoint) ->
            shareAvatar(context.applicationContext, peerName, endpoint)
        }
    }

    /**
     * Send an encrypted Double Ratchet message to a resolved peer's endpoint.
     */
    fun sendMessage(context: Context, endpoint: String, @Suppress("UNUSED_PARAMETER") senderName: String, text: String, onResult: (Boolean) -> Unit = {}) {
        outboundMessenger.sendMessage(context, endpoint, text, onResult)
    }

    fun sendVerificationRequest(context: Context, peerName: String, onResult: (Boolean) -> Unit) {
        outboundMessenger.sendControlMessage(context, peerName, JSONObject().apply {
            put("type", "verification_request")
        }, onResult)
    }

    fun sendVerificationResponse(context: Context, peerName: String, success: Boolean, onResult: (Boolean) -> Unit = {}) {
        outboundMessenger.sendControlMessage(context, peerName, JSONObject().apply {
            put("type", "verification_response")
            put("success", success)
        }, onResult)
    }

    fun sendPinMessage(context: Context, peerName: String, msgId: String, text: String, isFromSender: Boolean, onResult: (Boolean) -> Unit = {}) {
        outboundMessenger.sendControlMessage(context, peerName, JSONObject().apply {
            put("type", "pin_message")
            put("msg_id", msgId)
            put("text", text)
            put("is_from_sender", isFromSender)
        }, onResult)
    }

    fun sendUnpinMessage(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        outboundMessenger.sendControlMessage(context, peerName, JSONObject().apply {
            put("type", "unpin_message")
        }, onResult)
    }

    fun deleteChat(context: Context, peerName: String) {
        val sharedPrefs = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
        val expectedFingerprint = sharedPrefs.getString("peer_fingerprint_$peerName", null)
        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
        val newSet = activeSet.toMutableSet()
        if (newSet.remove(peerName)) {
            sharedPrefs.edit {
                putStringSet("active_chats", newSet)
                remove("last_msg_$peerName")
                remove("unread_count_$peerName")
                remove("transport_$peerName")
                remove("peer_fingerprint_$peerName")
            }
        } else {
            sharedPrefs.edit {
                remove("last_msg_$peerName")
                remove("unread_count_$peerName")
                remove("transport_$peerName")
                remove("peer_fingerprint_$peerName")
            }
        }
        
        // Close Python session asynchronously
        thread(start = true) {
            try {
                PythonBridge.closePeerSession(peerName, expectedFingerprint)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Remove from memory caches
        peerEndpoints.remove(peerName)
        peerSessionStates.remove(peerName)
        peerTypingStates.remove(peerName)
        avatarCache.remove(peerName)
        
        // Remove avatar file if exists
        try {
            val avatarsDir = java.io.File(context.filesDir, "avatars")
            val avatarFile = java.io.File(avatarsDir, "$peerName.jpg")
            if (avatarFile.exists()) {
                avatarFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Clear messages database
        val db = ChatDatabaseHelper.getInstance(context)
        db.clearMessagesForPeer(peerName)
    }

    /**
     * Send an encrypted file to a specific peer and endpoint.
     */
    fun sendFile(context: Context, peerName: String, endpoint: String, filePath: String, messageId: String = "", onResult: (Boolean) -> Unit = {}) {
        outboundMessenger.sendFile(context, peerName, endpoint, filePath, messageId, onResult)
    }

    @Suppress("unused")
    fun sendFile(context: Context, endpoint: String, filePath: String, messageId: String = "", onResult: (Boolean) -> Unit = {}) {
        val peerName = peerEndpoints.entries.firstOrNull { it.value == endpoint }?.key ?: "Direct Peer"
        outboundMessenger.sendFile(context, peerName, endpoint, filePath, messageId, onResult)
    }

    fun reconnectSession(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        outboundMessenger.reconnect(context, peerName, onResult)
    }

    fun sendTypingState(context: Context, peerName: String, endpoint: String, isTyping: Boolean) {
        outboundMessenger.sendTypingState(context, peerName, endpoint, isTyping)
    }

    fun sendReadReceipt(context: Context, peerName: String, endpoint: String, messageId: String) {
        outboundMessenger.sendReadReceipt(context, peerName, endpoint, messageId)
    }

    fun sendReaction(context: Context, peerName: String, endpoint: String, messageId: String, messageText: String, emoji: String) {
        outboundMessenger.sendReaction(context, peerName, endpoint, messageId, messageText, emoji)
    }

    fun processOfflineQueue(context: Context, peerName: String, endpoint: String) {
        outboundMessenger.processOfflineQueue(context, peerName, endpoint)
    }

    fun loadPersistedAvatars(context: Context) {
        avatarCache.loadPersisted(context) { error ->
            Log.e(TAG, "Error loading persisted avatars", error)
        }
    }
}
