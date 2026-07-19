


package com.example.twopchat

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.UUID
import android.util.Base64
import androidx.compose.runtime.mutableStateMapOf

internal fun isExpectedPeerFingerprint(persisted: String?, received: String): Boolean =
    persisted.isNullOrBlank() || persisted == received

object P2PMessageRelay {
    private const val TAG = "P2PMessageRelay"
    private val logTimestampFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.getDefault())
    }
    private val startStopLock = Any()
    private val identityLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isRunning = false
    private val avatarCache = PeerAvatarCache()
    private val notificationService = MessageNotificationService()
    @Volatile private var localPeerDiscovery: LocalPeerDiscovery? = null
    private val localPeerCandidates = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    private fun localPeerCandidateKey(peerName: String): String =
        peerName.trim().lowercase(Locale.ROOT)

    internal fun localDiscoveryEndpoints(peerName: String): List<String> =
        localPeerCandidates[localPeerCandidateKey(peerName)]
            ?.values
            ?.distinct()
            ?.take(12)
            .orEmpty()

    fun injectLocalDiscoveryCandidate(peerName: String, peerFingerprint: String, endpoint: String) {
        localPeerCandidates
            .computeIfAbsent(localPeerCandidateKey(peerName)) { java.util.concurrent.ConcurrentHashMap() }
            .put(peerFingerprint, endpoint)
    }

    fun listenerPort(context: Context): Int = P2PPreferences.listenerPort(context)

    fun refreshAnnouncement(context: Context) {
        val appContext = context.applicationContext
        thread(start = true, name = "ManualTrackerAnnounce") {
            val prefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
            val username = prefs.getString("username_profile", "").orEmpty()
            val fingerprint = PythonBridge.getLocalFingerprint()
            if (username.isNotBlank() && fingerprint.length >= 40) {
                val success = PythonBridge.announceSelf(username, fingerprint, listenerPort(appContext), force = true)
                log(appContext, "Forced announce after transport setting change: $success")
                setLocalDiscoveryEnabled(
                    appContext,
                    prefs.getBoolean(P2PPreferences.WIFI_DISCOVERY, true),
                )
            }
        }
    }

    // Maps peer name to their resolved IP:Port endpoint
    val peerEndpoints = mutableStateMapOf<String, String>()
    val peerConnectionTransports = mutableStateMapOf<String, String>()
    val peerSessionStates = mutableStateMapOf<String, Boolean>()
    val peerRttMs = mutableStateMapOf<String, Long>()
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
        onConnectedPeerHeartbeat = { context, peerName ->
            outboundMessenger.sendControlMessage(
                context = context,
                peerName = peerName,
                payload = JSONObject().apply {
                    put("type", "ping")
                    put("sent_at_ms", System.currentTimeMillis())
                },
            )
        },
        log = ::log,
    )
    private val outboundMessenger by lazy {
        P2POutboundMessenger(peerEndpoints, ::log) { peerName, messageId, status ->
            messageListeners.forEach { it.onMessageStatusChanged(peerName, messageId, status) }
        }
    }

    interface MessageListener {
        fun onMessageReceived(sender: String, text: String) {}
        fun onMessageReceived(sender: String, message: Message) {
            onMessageReceived(sender, message.text)
        }
        fun onMessageStatusChanged(sender: String, msgId: String, status: String)
        fun onMessageReactionChanged(sender: String, msgId: String, emoji: String, reactSender: String) {}
        fun onVerificationRequest(sender: String) {}
        fun onVerificationResponse(sender: String, success: Boolean) {}
        fun onMessagePinned(sender: String, msgId: String, text: String, isFromSender: Boolean) {}
        fun onMessageUnpinned(sender: String) {}
        fun onMessageEdited(sender: String, msgId: String, text: String) {}
        fun onMessageDeleted(sender: String, msgId: String) {}
        fun onForwardingStateChanged(sender: String, enabled: Boolean) {}
    }

    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<MessageListener>()
    private val activeChatPeer = AtomicReference<String?>(null)
    var activeChatPeerName: String?
        get() = activeChatPeer.get()
        set(value) { activeChatPeer.set(value) }

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

    fun setLocalDiscoveryEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        if (!enabled) {
            localPeerDiscovery?.stop()
            localPeerCandidates.clear()
            return
        }
        if (!isRunning) return
        startLocalDiscovery(appContext, listenerPort(appContext))
    }

    private fun startLocalDiscovery(context: Context, port: Int) {
        val prefs = P2PPreferences.prefs(context)
        if (!prefs.getBoolean(P2PPreferences.WIFI_DISCOVERY, true)) return
        val username = prefs.getString("username_profile", "").orEmpty()
        val fingerprint = PythonBridge.getLocalFingerprint()
        if (username.isBlank() || fingerprint.length < 40) return
        val discovery = localPeerDiscovery ?: LocalPeerDiscovery(context) { peerName, peerFingerprint, endpoint ->
            // NSD metadata is only a route candidate. It is deliberately not
            // trusted here: search performs the encrypted identity probe before
            // exposing a new contact to the user.
            localPeerCandidates
                .computeIfAbsent(localPeerCandidateKey(peerName)) { ConcurrentHashMap() }
                .put(peerFingerprint, endpoint)
            val currentPrefs = P2PPreferences.prefs(context)
            val knownName = currentPrefs.all.entries.firstOrNull { (key, value) ->
                key.startsWith("peer_fingerprint_") && value == peerFingerprint
            }?.key?.removePrefix("peer_fingerprint_")
            val authenticatedName = knownName ?: peerName.takeIf {
                currentPrefs.getString(P2PPreferences.peerFingerprint(it), null) == peerFingerprint
            } ?: return@LocalPeerDiscovery
            currentPrefs.edit().putString(P2PPreferences.lastEndpoint(authenticatedName), endpoint).apply()
            Handler(Looper.getMainLooper()).post { peerEndpoints[authenticatedName] = endpoint }
            outboundMessenger.reconnect(context, authenticatedName)
        }.also { localPeerDiscovery = it }
        try {
            discovery.start(username, fingerprint, port)
        } catch (error: Exception) {
            log(context, "Local Wi-Fi discovery could not start", "ERROR", error)
        }
    }

    private fun persistAndDispatchIncoming(
        context: Context,
        sender: String,
        message: Message,
        notificationText: String = message.text,
    ) {
        val prefs = P2PPreferences.prefs(context)
        val activeSet = prefs.getStringSet(P2PPreferences.ACTIVE_CHATS, emptySet()).orEmpty()
        if (sender !in activeSet) {
            prefs.edit { putStringSet(P2PPreferences.ACTIVE_CHATS, activeSet + sender) }
        }
        if (prefs.getBoolean("persist_chat_history", true)) {
            ChatDatabaseHelper.getInstance(context).saveMessage(sender, message)
        }
        prefs.edit {
            putString(P2PPreferences.lastMessage(sender), SecureStorage.encrypt(notificationText))
        }
        Handler(Looper.getMainLooper()).post {
            messageListeners.forEach { it.onMessageReceived(sender, message) }
            if (activeChatPeer.get() != sender) {
                val unreadKey = P2PPreferences.unreadCount(sender)
                prefs.edit { putInt(unreadKey, prefs.getInt(unreadKey, 0) + 1) }
                showNotification(context, sender, notificationText)
            }
        }
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
            "unread_count_", "verified_peer_", "fingerprint_mismatch_",
            "pending_peer_fingerprint_", "pending_peer_endpoint_"
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
        val latest = db.getLastMessageForPeer(peerName) ?: return
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
            Log.e(TAG, "Failed to append diagnostic log", e)
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
            val localName = persistedPrefs.getString("username_profile", "").orEmpty()
            val aboutMe = persistedPrefs.getString("about_me_profile", "").orEmpty()
            val localFingerprint = PythonBridge.getLocalFingerprint()
            check(PythonBridge.configureLocalIdentity(localName, localFingerprint, aboutMe)) {
                "Local P2P identity is not configured"
            }
            // Start the Python P2P listener
            PythonBridge.startP2pListener(port)
            startLocalDiscovery(appContext, port)
            
            // Register incoming message callback from Python
            PythonBridge.registerMessageListener(object : PythonBridge.PyMessageListener {
                override fun onMessageReceived(sender: String, text: String) {
                    log(appContext, "Incoming secure P2P message (${text.toByteArray().size} bytes)")
                    val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                    if (sharedPrefs.getBoolean("blocked_peer_$sender", false)) {
                        log(appContext, "Ignored message from blocked peer $sender")
                        return
                    }
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
                                        if (success) {
                                            // Verification responses can arrive while the chat screen is not
                                            // composed. Persist the trust decision before notifying UI listeners.
                                            P2PPreferences.setPeerVerified(appContext, sender, true)
                                        }
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
                                                
                                                try {
                                                    avatarCache.savePersisted(appContext, sender, bitmap)
                                                    log(appContext, "Saved encrypted avatar for $sender")
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
                                "edit_message" -> {
                                    val msgId = json.optString("message_id")
                                    val text = json.optString("text")
                                    if (msgId.isNotEmpty() && text.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        db.updateMessageText(msgId, text)
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            messageListeners.forEach { it.onMessageEdited(sender, msgId, text) }
                                        }
                                        val controlId = json.optString("control_id")
                                        if (controlId.isNotBlank()) {
                                            outboundMessenger.sendControlMessage(
                                                appContext,
                                                sender,
                                                JSONObject().apply {
                                                    put("type", "edit_ack")
                                                    put("control_id", controlId)
                                                    put("message_id", msgId)
                                                },
                                            )
                                        }
                                    }
                                    return
                                }
                                "delete_message" -> {
                                    val msgId = json.optString("message_id")
                                    if (msgId.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        db.deleteMessage(msgId)
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            messageListeners.forEach { it.onMessageDeleted(sender, msgId) }
                                        }
                                    }
                                    return
                                }
                                "ping" -> {
                                    outboundMessenger.sendControlMessage(
                                        appContext,
                                        sender,
                                        JSONObject().apply {
                                            put("type", "pong")
                                            put("sent_at_ms", json.optLong("sent_at_ms"))
                                        },
                                    )
                                    return
                                }
                                "pong" -> {
                                    val sentAt = json.optLong("sent_at_ms")
                                    if (sentAt > 0L) {
                                        val rtt = (System.currentTimeMillis() - sentAt).coerceIn(0L, 60_000L)
                                        Handler(Looper.getMainLooper()).post { peerRttMs[sender] = rtt }
                                    }
                                    return
                                }
                                "edit_ack" -> {
                                    outboundMessenger.acknowledgeControl(appContext, json.optString("control_id"))
                                    return
                                }
                                "forwarding_state" -> {
                                    val enabled = json.optBoolean("enabled", false)
                                    val sp = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                                    sp.edit().putBoolean("restrict_forwarding_$sender", enabled).apply()
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        messageListeners.forEach { it.onForwardingStateChanged(sender, enabled) }
                                    }
                                    return
                                }
                                "reaction" -> {
                                    val msgId = json.optString("message_id")
                                    val emoji = json.optString("emoji")
                                    val messageText = json.optString("message_text")
                                    if (msgId.isNotEmpty() && emoji.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        val existing = db.findMessageForReaction(sender, msgId, messageText)
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
                                "text" -> {
                                    val msgId = json.optString("message_id")
                                    val msgText = json.optString("text")
                                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                    val rxMsg = Message(
                                        id = if (msgId.isNotEmpty()) msgId else UUID.randomUUID().toString(),
                                        text = msgText,
                                        isMe = false,
                                        timestamp = time,
                                        status = "SENT"
                                    )
                                    persistAndDispatchIncoming(appContext, sender, rxMsg)
                                    return
                                }
                                "reply" -> {
                                val msgId = json.optString("message_id")
                                val replyText = json.optString("text")
                                val replyToId = json.optString("reply_to_id")
                                val replyToText = json.optString("reply_to_text")
                                val replyToName = json.optString("reply_to_name")
                                
                                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                
                                val rxMsg = Message(
                                    id = if (msgId.isNotEmpty()) msgId else UUID.randomUUID().toString(),
                                    text = replyText,
                                    isMe = false,
                                    timestamp = time,
                                    replyToId = replyToId,
                                    replyToText = replyToText,
                                    replyToName = replyToName,
                                    status = "SENT"
                                )
                                persistAndDispatchIncoming(appContext, sender, rxMsg)
                                return
                            }
                        }
                    }
                        val incomingAttachment = parseIncomingAttachment(text)
                        val incomingMessage = if (incomingAttachment != null) {
                            Message(
                                    id = incomingAttachment.messageId.ifBlank { UUID.randomUUID().toString() },
                                    text = incomingAttachment.displayMessage,
                                    isMe = false,
                                    timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                    attachmentType = incomingAttachment.attachmentType,
                                    attachmentUri = incomingAttachment.attachmentUri,
                                    attachmentName = incomingAttachment.attachmentName,
                                    status = "SENT"
                                )
                        } else {
                            Message(
                                    id = UUID.randomUUID().toString(),
                                    text = text,
                                    isMe = false,
                                    timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                    status = "SENT"
                                )
                        }
                        persistAndDispatchIncoming(appContext, sender, incomingMessage)
                    } catch (ex: Exception) {
                        log(appContext, "Failed to persist incoming message to SharedPreferences/SQLite", "ERROR", ex)
                    }
                }
            })

            PythonBridge.registerSessionListener(object : PythonBridge.PySessionListener {
                override fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String, transport: String, aboutMe: String): Boolean {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    val canonicalTransport = canonicalConnectionTransport(transport, endpoint)
                    // Incoming handshakes are named by fingerprint until their
                    // authenticated identity_info arrives. Search probes and
                    // half-open sessions must never create visible Peer (...)
                    // chats or leave placeholder preference keys behind.
                    if (isPlaceholderPeerName(resolvedPeerName)) {
                        log(appContext, "Authenticated unnamed session awaiting identity_info ($fingerprint)")
                        return true
                    }
                    val identityPrefs = P2PPreferences.prefs(appContext)
                    val persistedFingerprint = identityPrefs
                        .getString(P2PPreferences.peerFingerprint(resolvedPeerName), null)
                    if (!isExpectedPeerFingerprint(persistedFingerprint, fingerprint)) {
                        P2PPreferences.recordPendingPeerIdentity(
                            appContext,
                            resolvedPeerName,
                            fingerprint,
                            endpoint,
                        )
                        Handler(Looper.getMainLooper()).post {
                            peerSessionStates.remove(resolvedPeerName)
                            peerConnectionTransports.remove(resolvedPeerName)
                            peerRttMs.remove(resolvedPeerName)
                        }
                        log(
                            appContext,
                            "Rejected fingerprint change for $resolvedPeerName: expected $persistedFingerprint, received $fingerprint",
                            "ERROR",
                        )
                        return false
                    }
                    if (P2PPreferences.isPeerIdentityChangePending(appContext, resolvedPeerName)) {
                        log(
                            appContext,
                            "Rejected session for $resolvedPeerName while an identity change awaits confirmation",
                            "ERROR",
                        )
                        return false
                    }
                    PythonBridge.rememberPeerName(fingerprint, resolvedPeerName)
                    appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        .edit().apply {
                            putString("peer_fingerprint_$resolvedPeerName", fingerprint)
                            putString("peer_about_me_$resolvedPeerName", aboutMe)
                            if (endpoint.isNotEmpty()) {
                                putString("last_endpoint_$resolvedPeerName", endpoint)
                            }
                            apply()
                        }
                    log(appContext, "Secure Double Ratchet session established with $resolvedPeerName ($fingerprint) at $endpoint")
                    Handler(Looper.getMainLooper()).post {
                        peerSessionStates[resolvedPeerName] = true
                        if (canonicalTransport != null) {
                            peerConnectionTransports[resolvedPeerName] = canonicalTransport
                        }
                        if (endpoint.isNotEmpty()) {
                            peerEndpoints[resolvedPeerName] = endpoint
                        }
                    }

                    val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                    if (canonicalTransport != null) {
                        sharedPrefs.edit()
                            .putString(P2PPreferences.transport(resolvedPeerName), canonicalTransport)
                            .apply()
                    }

                    if (endpoint.isNotEmpty()) {
                        // Save to active chats so the UI updates and shows the peer chat screen
                        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                        if (!activeSet.contains(resolvedPeerName)) {

                            val newSet = activeSet.toMutableSet()
                            newSet.add(resolvedPeerName)
                            sharedPrefs.edit().putStringSet("active_chats", newSet).apply()

                        }

                        shareAvatar(appContext, resolvedPeerName, endpoint)
                        processOfflineQueue(appContext, resolvedPeerName, endpoint)
                    }
                    return true
                }

                override fun onSessionClosed(peerName: String, fingerprint: String) {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    log(appContext, "Secure Double Ratchet session closed with $resolvedPeerName")
                    Handler(Looper.getMainLooper()).post {
                        peerConnectionTransports.remove(resolvedPeerName)
                        peerSessionStates.remove(resolvedPeerName)
                        peerRttMs.remove(resolvedPeerName)
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
        localPeerDiscovery?.stop()
        localPeerCandidates.clear()
        avatarCache.clear()
        // Trigger Python shutdown/cleanup
        thread(start = true) {
            if (!PythonBridge.shutdownAllSessions()) {
                Log.e(TAG, "Python P2P runtime did not stop cleanly")
            }
        }
    }

    /** Stop every account-bound transport before identity files are erased. */
    fun shutdownForAccountDeletion(context: Context): Boolean {
        val appContext = context.applicationContext
        synchronized(startStopLock) {
            isRunning = false
        }
        synchronized(identityLock) {
            fingerprintToPeerName.clear()
        }
        maintenanceCoordinator.stop()
        localPeerDiscovery?.stop()
        localPeerDiscovery = null
        localPeerCandidates.clear()
        peerEndpoints.clear()
        avatarCache.clear()
        Handler(Looper.getMainLooper()).post {
            peerConnectionTransports.clear()
            peerSessionStates.clear()
            peerRttMs.clear()
        }
        val stopped = PythonBridge.shutdownAllSessions()
        log(
            appContext,
            "Account P2P runtime shutdown complete: $stopped",
            if (stopped) "INFO" else "ERROR",
        )
        return stopped
    }

    fun restartServer(context: Context) {
        val appContext = context.applicationContext
        synchronized(startStopLock) {
            isRunning = false
        }
        synchronized(identityLock) { fingerprintToPeerName.clear() }
        maintenanceCoordinator.stop()
        localPeerDiscovery?.stop()
        avatarCache.clear()
        Handler(Looper.getMainLooper()).post { peerRttMs.clear() }
        thread(start = true, name = "P2PRelayRestart") {
            if (!PythonBridge.shutdownAllSessions()) {
                log(appContext, "Listener restart aborted because the old identity runtime is still active", "ERROR")
                return@thread
            }
            startServer(appContext)
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
            var sourceBitmap: Bitmap? = null
            var scaledBitmap: Bitmap? = null
            try {
                val file = File(context.filesDir, "profile_avatar.jpg")
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    sourceBitmap = bitmap
                    if (bitmap != null) {
                        val maxDimension = 320
                        scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                            val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val width = if (aspectRatio > 1) maxDimension else (maxDimension * aspectRatio).toInt()
                            val height = if (aspectRatio > 1) (maxDimension / aspectRatio).toInt() else maxDimension
                            Bitmap.createScaledBitmap(bitmap, width, height, true)
                        } else {
                            bitmap
                        }
                        
                        val bytes = ByteArrayOutputStream().use { outputStream ->
                            checkNotNull(scaledBitmap).compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                            outputStream.toByteArray()
                        }
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        
                        val json = JSONObject().apply {
                            put("type", "profile_avatar_share")
                            put("avatar_base64", b64)
                        }
                        val payload = json.toString()
                        val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                            .getString("peer_fingerprint_$peerName", null)

                        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
                            log(context, "Blocked avatar share to $peerName while its identity change awaits confirmation", "ERROR")
                            return@thread
                        }
                        
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
                scaledBitmap?.takeIf { it !== sourceBitmap && !it.isRecycled }?.recycle()
                sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
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
                remove("last_endpoint_$peerName")
                remove("verified_peer_$peerName")
                remove("fingerprint_mismatch_$peerName")
                remove("pending_peer_fingerprint_$peerName")
                remove("pending_peer_endpoint_$peerName")
            }
        } else {
            sharedPrefs.edit {
                remove("last_msg_$peerName")
                remove("unread_count_$peerName")
                remove("transport_$peerName")
                remove("peer_fingerprint_$peerName")
                remove("last_endpoint_$peerName")
                remove("verified_peer_$peerName")
                remove("fingerprint_mismatch_$peerName")
                remove("pending_peer_fingerprint_$peerName")
                remove("pending_peer_endpoint_$peerName")
            }
        }
        
        // Close Python session asynchronously
        thread(start = true) {
            try {
                PythonBridge.closePeerSession(peerName, expectedFingerprint)
            } catch (e: Exception) {
                log(context, "Failed to close peer session for $peerName", "ERROR", e)
            }
        }
        
        // Remove from memory caches
        peerEndpoints.remove(peerName)
        peerSessionStates.remove(peerName)
        peerTypingStates.remove(peerName)
        avatarCache.remove(peerName)
        
        try {
            avatarCache.deletePersisted(context, peerName)
        } catch (e: Exception) {
            log(context, "Failed to delete persisted avatar for $peerName", "ERROR", e)
        }

        // Clear messages database
        val db = ChatDatabaseHelper.getInstance(context)
        db.clearMessagesForPeer(peerName)
        db.deletePendingControlsForPeer(peerName)
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

    fun acceptPendingPeerIdentity(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        val appContext = context.applicationContext
        val prefs = P2PPreferences.prefs(appContext)
        val oldFingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null).orEmpty()
        val pendingFingerprint = prefs.getString(P2PPreferences.pendingPeerFingerprint(peerName), null).orEmpty()
        if (!canAcceptPendingPeerFingerprint(oldFingerprint, pendingFingerprint)) {
            Handler(Looper.getMainLooper()).post { onResult(false) }
            return
        }
        thread(start = true) {
            // Keep the pause active while the old ratchet is closed. Only then
            // atomically replace the pin and start a completely new session.
            PythonBridge.closePeerSession(peerName, oldFingerprint)
            val accepted = P2PPreferences.acceptPendingPeerIdentity(appContext, peerName)
            if (accepted == null) {
                Handler(Looper.getMainLooper()).post { onResult(false) }
                return@thread
            }
            PythonBridge.clearRejectedFingerprint(peerName)
            PythonBridge.rememberPeerName(accepted.acceptedFingerprint, peerName)
            val endpoint = accepted.endpoint.takeIf { it.isNotBlank() }
                ?: prefs.getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
            if (endpoint.isNotBlank()) peerEndpoints[peerName] = endpoint
            Handler(Looper.getMainLooper()).post {
                peerSessionStates.remove(peerName)
                peerConnectionTransports.remove(peerName)
                peerRttMs.remove(peerName)
            }
            val success = endpoint.isNotBlank() &&
                PythonBridge.reconnectPeerSession(peerName, endpoint, accepted.acceptedFingerprint)
            Handler(Looper.getMainLooper()).post { onResult(success) }
        }
    }

    fun rejectPendingPeerIdentity(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        val appContext = context.applicationContext
        val prefs = P2PPreferences.prefs(appContext)
        val oldFingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null).orEmpty()
        val endpoint = prefs.getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
        val cleared = P2PPreferences.rejectPendingPeerIdentity(appContext, peerName)
        if (!cleared) {
            Handler(Looper.getMainLooper()).post { onResult(false) }
            return
        }
        thread(start = true) {
            val success = endpoint.isNotBlank() && oldFingerprint.isNotBlank() &&
                PythonBridge.reconnectPeerSession(peerName, endpoint, oldFingerprint)
            Handler(Looper.getMainLooper()).post { onResult(success) }
        }
    }

    fun sendTypingState(context: Context, peerName: String, endpoint: String, isTyping: Boolean) {
        outboundMessenger.sendTypingState(context, peerName, endpoint, isTyping)
    }

    fun sendReadReceipt(context: Context, peerName: String, endpoint: String?, messageId: String) {
        outboundMessenger.sendReadReceipt(context, peerName, endpoint, messageId)
    }

    fun sendReaction(context: Context, peerName: String, endpoint: String, messageId: String, messageText: String, emoji: String) {
        outboundMessenger.sendReaction(context, peerName, endpoint, messageId, messageText, emoji)
    }

    fun sendEditMessage(context: Context, peerName: String, endpoint: String?, messageId: String, newText: String) {
        outboundMessenger.sendEditMessage(context, peerName, endpoint, messageId, newText)
    }

    fun sendDeleteMessage(context: Context, peerName: String, messageId: String) {
        val endpoint = peerEndpoints[peerName]
        outboundMessenger.sendDeleteMessage(context, peerName, endpoint, messageId)
    }

    fun sendForwardingState(context: Context, peerName: String, enabled: Boolean) {
        val endpoint = peerEndpoints[peerName] ?: return
        val payload = org.json.JSONObject().apply {
            put("type", "forwarding_state")
            put("enabled", enabled)
        }
        outboundMessenger.sendControlMessage(context, peerName, payload)
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
