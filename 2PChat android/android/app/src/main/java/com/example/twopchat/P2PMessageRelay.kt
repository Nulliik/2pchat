


package com.example.twopchat

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.delay
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
import com.example.twopchat.group.runtime.GroupChatCoordinator
import com.example.twopchat.group.protocol.GroupWireProtocol

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
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))
    @Volatile private var isRunning = false
    private val avatarCache = PeerAvatarCache()
    private val notificationService = MessageNotificationService()

    @Volatile private var cachedAvatarBase64: String? = null
    @Volatile private var cachedAvatarLastModified: Long = -1L

    fun invalidateProfileAvatarCache() {
        synchronized(identityLock) {
            cachedAvatarBase64 = null
            cachedAvatarLastModified = -1L
        }
    }

    private val activeFileTransfers = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var localPeerDiscovery: LocalPeerDiscovery? = null
    private data class LocalPeerCandidate(val fingerprint: String, val endpoint: String)

    private val localPeerCandidates =
        ConcurrentHashMap<String, CopyOnWriteArrayList<LocalPeerCandidate>>()

    private fun localPeerCandidateKey(peerName: String): String =
        peerName.trim().lowercase(Locale.ROOT)

    internal fun localDiscoveryEndpoints(peerName: String): List<String> =
        localPeerCandidates[localPeerCandidateKey(peerName)]
            ?.map(LocalPeerCandidate::endpoint)
            ?.distinct()
            ?.take(12)
            .orEmpty()

    fun injectLocalDiscoveryCandidate(peerName: String, peerFingerprint: String, endpoint: String) {
        val candidates = localPeerCandidates.computeIfAbsent(localPeerCandidateKey(peerName)) {
            CopyOnWriteArrayList()
        }
        val candidate = LocalPeerCandidate(peerFingerprint, endpoint)
        candidates.remove(candidate)
        candidates.add(candidate)
        while (candidates.size > 12) candidates.removeAt(0)
    }

    @Synchronized
    internal fun rememberAuthenticatedPeerEndpoint(peerName: String, endpoints: String): Boolean {
        val normalizedName = peerName.trim()
        val normalizedEndpoints = endpoints.trim()
        val endpointParts = normalizedEndpoints.split(',').map(String::trim).filter(String::isNotEmpty)
        if (normalizedName.isEmpty() || normalizedName.length > 160 ||
            normalizedEndpoints.length > 4_096 || endpointParts.isEmpty() || endpointParts.size > 12 ||
            endpointParts.any { it.length > 512 || it.any { char -> char.isISOControl() } }) {
            return false
        }
        if (normalizedName !in _peerEndpoints && _peerEndpoints.size >= MAX_TRACKED_PEER_ENDPOINTS) return false
        _peerEndpoints[normalizedName] = endpointParts.joinToString(",")
        return true
    }

    fun listenerPort(context: Context): Int = P2PPreferences.listenerPort(context)

    fun refreshAnnouncement(context: Context) {
        val appContext = context.applicationContext
        relayScope.launch {
            val prefs = P2PPreferences.prefs(appContext)
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

    fun triggerImmediateReconnect(context: Context) {
        val appContext = context.applicationContext
        relayScope.launch {
            refreshAnnouncement(appContext)
            val prefs = P2PPreferences.prefs(appContext)
            val chats = prefs.getStringSet("active_chats", emptySet()).orEmpty()
                .filterNot { it == "Saved Messages" }
            for (peerName in chats) {
                val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
                    ?.takeIf { it.isNotBlank() } ?: continue
                val endpoint = _peerEndpoints[peerName]
                    ?: prefs.getString("last_endpoint_$peerName", null)?.takeIf { it.isNotBlank() }
                    ?: continue
                PythonBridge.reconnectPeerSession(peerName, endpoint, fingerprint)
            }
        }
    }

    private const val MAX_TRACKED_PEER_ENDPOINTS = 512
    private val _peerEndpoints = mutableStateMapOf<String, String>()
    val peerEndpoints: Map<String, String> get() = _peerEndpoints
    val peerConnectionTransports = mutableStateMapOf<String, String>()
    val peerSessionStates = mutableStateMapOf<String, Boolean>()
    val peerRttMs = mutableStateMapOf<String, Long>()
    private const val OFFLINE_UI_GRACE_MS = 2_500L
    private val peerPresenceVersions = PeerPresenceVersionTracker()
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
        peerEndpoints = _peerEndpoints,
        presenceVersion = peerPresenceVersions::current,
        onPeerObservedOnline = { context, peerName, transport, observedVersion ->
            publishPeerOnlineIfCurrent(
                context = context,
                peerName = peerName,
                transport = transport,
                expectedVersion = observedVersion,
            )
        },
        onPeerObservedOffline = ::schedulePeerOfflineIfCurrent,
        log = ::log,
    )

    private fun publishPeerOnline(
        peerName: String,
        transport: String?,
        endpoint: String = "",
    ) {
        val version = peerPresenceVersions.advance(peerName)
        serviceScope.launch(Dispatchers.Main) {
            if (peerPresenceVersions.current(peerName) != version) return@launch
            peerSessionStates[peerName] = true
            if (transport != null) peerConnectionTransports[peerName] = transport
            if (endpoint.isNotEmpty()) rememberAuthenticatedPeerEndpoint(peerName, endpoint)
        }
    }

    private fun publishPeerOnlineIfCurrent(
        context: Context,
        peerName: String,
        transport: String?,
        expectedVersion: Long,
    ) {
        val version = peerPresenceVersions.advanceIfCurrent(peerName, expectedVersion) ?: return
        serviceScope.launch(Dispatchers.Main) {
            if (peerPresenceVersions.current(peerName) != version) return@launch
            peerSessionStates[peerName] = true
            if (transport != null) peerConnectionTransports[peerName] = transport
        }
        sendConnectedPeerHeartbeat(context, peerName)
    }

    private fun sendConnectedPeerHeartbeat(context: Context, peerName: String) {
        outboundMessenger.sendControlMessage(
            context = context,
            peerName = peerName,
            payload = JSONObject().apply {
                put("type", "ping")
                put("sent_at_ms", System.currentTimeMillis())
            },
        )
    }

    private fun clearPeerPresenceImmediately(peerName: String) {
        val version = peerPresenceVersions.advance(peerName)
        serviceScope.launch(Dispatchers.Main) {
            if (peerPresenceVersions.current(peerName) != version) return@launch
            peerConnectionTransports.remove(peerName)
            peerSessionStates.remove(peerName)
            peerRttMs.remove(peerName)
        }
    }

    private fun schedulePeerOffline(peerName: String) {
        val version = peerPresenceVersions.advance(peerName)
        serviceScope.launch(Dispatchers.Main) {
            delay(OFFLINE_UI_GRACE_MS)
            if (peerPresenceVersions.current(peerName) != version) return@launch
            peerConnectionTransports.remove(peerName)
            peerSessionStates.remove(peerName)
            peerRttMs.remove(peerName)
        }
    }

    private fun schedulePeerOfflineIfCurrent(peerName: String, expectedVersion: Long) {
        val version = peerPresenceVersions.advanceIfCurrent(peerName, expectedVersion) ?: return
        serviceScope.launch(Dispatchers.Main) {
            delay(OFFLINE_UI_GRACE_MS)
            if (peerPresenceVersions.current(peerName) != version) return@launch
            peerConnectionTransports.remove(peerName)
            peerSessionStates.remove(peerName)
            peerRttMs.remove(peerName)
        }
    }
    private val outboundMessenger by lazy {
        P2POutboundMessenger(_peerEndpoints, ::log) { peerName, messageId, status ->
            serviceScope.launch(Dispatchers.Main) {
                if (status == "CANCELLED") {
                    val key = "$peerName:$messageId"
                    val current = fileProgressStates[key] ?: fileProgressStates[messageId]
                    val cancelled = (current ?: FileProgressInfo(0L, 0L, 0.0)).copy(
                        state = FileTransferState.CANCELLED,
                        speedKbps = 0.0,
                    )
                    fileProgressStates[key] = cancelled
                    fileProgressStates[messageId] = cancelled
                }
                messageListeners.forEach {
                    it.onMessageStatusChanged(peerName, messageId, status)
                }
            }
        }
    }

    private fun decodeFileTransferPreview(encoded: String): Bitmap? {
        if (encoded.isBlank() || encoded.length > 96 * 1024) return null
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size > 64 * 1024) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512 ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > 262_144L
            ) {
                return null
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    enum class FileTransferState {
        TRANSFERRING,
        COMPLETED,
        CANCELLED,
        FAILED,
    }

    data class FileProgressInfo(
        val bytesTransferred: Long,
        val totalBytes: Long,
        val speedKbps: Double,
        val state: FileTransferState = FileTransferState.TRANSFERRING,
    )

    val fileProgressStates = mutableStateMapOf<String, FileProgressInfo>()
    val fileTransferPreviews = mutableStateMapOf<String, Bitmap>()
    private val incomingFileOffers = ConcurrentHashMap.newKeySet<String>()
    private val incomingAlbums = ConcurrentHashMap<String, Message>()
    private val stickerPackRequestTimes = ConcurrentHashMap<String, Long>()

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
        fun onStickerPackInstalled(sender: String, packId: String) {}
        fun onForwardingStateChanged(sender: String, enabled: Boolean) {}
        fun onFileProgress(sender: String, msgId: String, bytesTransferred: Long, totalBytes: Long, speedKbps: Double) {}
    }

    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<MessageListener>()
    private val activeChatPeer = AtomicReference<String?>(null)
    var activeChatPeerName: String?
        get() = activeChatPeer.get()
        set(value) { activeChatPeer.set(value) }

    /**
     * Clears [activeChatPeerName] only when it still equals [peerName].
     * Uses compare-and-set so a disposing screen cannot overwrite the name
     * already set by the next chat screen during a fast peer switch.
     */
    fun clearActiveChatPeerName(peerName: String) {
        activeChatPeer.compareAndSet(peerName, null)
    }

    fun registerMessageListener(listener: MessageListener) {
        messageListeners.add(listener)
    }

    fun unregisterMessageListener(listener: MessageListener) {
        messageListeners.remove(listener)
    }

    private fun sendPinnedStateAck(context: Context, peerName: String, controlId: String) {
        if (controlId.isBlank()) return
        outboundMessenger.sendControlMessage(
            context,
            peerName,
            JSONObject().apply {
                put("type", "pin_state_ack")
                put("control_id", controlId)
            },
        )
    }

    private fun showNotification(context: Context, sender: String, message: Message, text: String) {
        try {
            notificationService.show(context, sender, text, message.id)
        } catch (e: Exception) {
            log(context, "Failed to show message notification: ${e.message}", "ERROR", e)
        }
    }

    private fun parseIncomingAttachment(context: Context, text: String): IncomingAttachment? {
        return IncomingMessageParser.parseAttachment(context, text)
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
            injectLocalDiscoveryCandidate(peerName, peerFingerprint, endpoint)
            val currentPrefs = P2PPreferences.prefs(context)
            val knownName = P2PPreferences.findPeerNameByFingerprint(context, peerFingerprint)
            val authenticatedName = knownName ?: peerName.takeIf {
                currentPrefs.getString(P2PPreferences.peerFingerprint(it), null) == peerFingerprint
            } ?: return@LocalPeerDiscovery
            currentPrefs.edit().putString(P2PPreferences.lastEndpoint(authenticatedName), endpoint).apply()
            serviceScope.launch(Dispatchers.Main) { rememberAuthenticatedPeerEndpoint(authenticatedName, endpoint) }
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
        countAsNew: Boolean = true,
    ) {
        val prefs = P2PPreferences.prefs(context)
        val activeSet = prefs.getStringSet(P2PPreferences.ACTIVE_CHATS, emptySet()).orEmpty()
        if (sender !in activeSet) {
            prefs.edit { putStringSet(P2PPreferences.ACTIVE_CHATS, activeSet + sender) }
        }
        if (!P2PPreferences.isAppLocked()) {
            if (prefs.getBoolean("persist_chat_history", true)) {
                ChatDatabaseHelper.getInstance(context).saveMessage(sender, message)
            }
            prefs.edit {
                putString(P2PPreferences.lastMessage(sender), SecureStorage.encrypt(notificationText))
            }
        }
        serviceScope.launch(Dispatchers.Main) {
            messageListeners.forEach { it.onMessageReceived(sender, message) }
            if (countAsNew && activeChatPeer.get() != sender) {
                val unreadKey = P2PPreferences.unreadCount(sender)
                prefs.edit { putInt(unreadKey, prefs.getInt(unreadKey, 0) + 1) }
                showNotification(context, sender, message, notificationText)
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
        val sharedPrefs = P2PPreferences.prefs(context)
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
            log(context, "Failed to migrate chat history between peer aliases", "ERROR", e)
        }
    }

    private fun refreshLastMessageFromHistory(
        context: Context,
        db: ChatDatabaseHelper,
        peerName: String
    ) {
        val prefs = P2PPreferences.prefs(context)
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
                P2PPreferences.findPeerNameByFingerprint(context, fingerprint)?.takeIf {
                    !isPlaceholderPeerName(it)
                }
            } else {
                null
            }
            return when {
                !persistedName.isNullOrBlank() -> {
                    fingerprintToPeerName[fingerprint] = persistedName
                    if (peerName != persistedName && isPlaceholderPeerName(peerName)) {
                        moveChatState(context, peerName, persistedName)
                        serviceScope.launch(Dispatchers.Main) {
                            _peerEndpoints.remove(peerName)
                            peerPresenceVersions.remove(peerName)
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
                    serviceScope.launch(Dispatchers.Main) {
                        _peerEndpoints.remove(knownName)
                    }
                    peerName
                }
                !isPlaceholderPeerName(knownName) && isPlaceholderPeerName(peerName) -> {
                    moveChatState(context, peerName, knownName)
                    serviceScope.launch(Dispatchers.Main) {
                        _peerEndpoints.remove(peerName)
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
        val prefs = P2PPreferences.prefs(context)
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
                log(context, "Migrated stale placeholder chat to an authenticated peer alias")
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
        val persistedPrefs = P2PPreferences.prefs(appContext)
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
                ?.let { rememberAuthenticatedPeerEndpoint(peerName, it) }
        }
        val port = listenerPort(appContext)
        try {
            log(appContext, "Starting Python P2P Relays on port $port...")
            val ipv4Enabled = P2PPreferences.prefs(appContext)
                .getBoolean("settings_ipv4", true)
            PythonBridge.setIpv4Enabled(ipv4Enabled)
            val localName = persistedPrefs.getString("username_profile", "").orEmpty()
            val aboutMe = persistedPrefs.getString("about_me_profile", "").orEmpty()
            val localFingerprint = PythonBridge.getLocalFingerprint()
            check(PythonBridge.configureLocalIdentity(localName, localFingerprint, aboutMe)) {
                "Local P2P identity is not configured"
            }
            GroupChatCoordinator.initialize(appContext)
            // Start the Python P2P listener
            PythonBridge.startP2pListener(port)
            startLocalDiscovery(appContext, port)
            
            // Register incoming message callback from Python
            PythonBridge.registerMessageListener(object : PythonBridge.PyMessageListener {
                override fun onMessageReceived(sender: String, text: String) {
                    log(appContext, "Incoming secure P2P message (${text.toByteArray().size} bytes)")
                    val sharedPrefs = P2PPreferences.prefs(appContext)
                    if (shouldRecordIncomingTrafficPayload(text)) {
                        NetworkTrafficStats.recordMessage(
                            appContext,
                            sender,
                            _peerEndpoints[sender],
                            text,
                            TrafficDirection.RECEIVED,
                        )
                    }
                    if (sharedPrefs.getBoolean("blocked_peer_$sender", false)) {
                        log(appContext, "Ignored message from a blocked peer")
                        return
                    }
                    try {
                        val trimmed = text.trim()
                        if (trimmed.startsWith("{")) {
                            val json = org.json.JSONObject(trimmed)
                            if (GroupChatCoordinator.handleIncoming(appContext, sender, json)) {
                                return
                            }
                            when (json.optString("type")) {
                                "file_offer" -> {
                                    val messageId = json.optString("message_id").take(128)
                                    val fileName = File(json.optString("file_name", "file"))
                                        .name.take(120).ifBlank { "file" }
                                    val mime = json.optString("mime")
                                    val totalBytes = json.optLong("size").coerceAtLeast(0L)
                                    if (messageId.isBlank() || totalBytes > 100L * 1024L * 1024L) return
                                    val attachmentType = VoiceMessageSupport.attachmentType(fileName, mime)
                                    val albumId = json.optString("album_id").take(128)
                                    val albumIndex = json.optInt("album_index", -1)
                                    val albumCount = json.optInt("album_count", 0)
                                    val isAlbumPart = albumId.isNotBlank() &&
                                        albumCount in 2..100 &&
                                        albumIndex in 0 until albumCount
                                    val offerKey = "$sender:$messageId"
                                    val isNewOffer = incomingFileOffers.add(offerKey)
                                    val preview = decodeFileTransferPreview(
                                        json.optString("preview_base64"),
                                    )
                                    val offerMessage = Message(
                                        id = messageId,
                                        text = VoiceMessageSupport.displayMessage(attachmentType, fileName),
                                        isMe = false,
                                        timestamp = SimpleDateFormat(
                                            "HH:mm",
                                            Locale.getDefault(),
                                        ).format(Date()),
                                        attachmentType = attachmentType,
                                        attachmentUri = null,
                                        attachmentName = fileName,
                                        status = "RECEIVING",
                                    )
                                    serviceScope.launch(Dispatchers.Main) {
                                        val info = FileProgressInfo(
                                            bytesTransferred = 0L,
                                            totalBytes = totalBytes,
                                            speedKbps = 0.0,
                                        )
                                        fileProgressStates[offerKey] = info
                                        fileProgressStates[messageId] = info
                                        if (preview != null) {
                                            fileTransferPreviews[offerKey] = preview
                                            fileTransferPreviews[messageId] = preview
                                        }
                                    }
                                    // Album parts are persisted under their shared album id
                                    // after each file completes. Creating one placeholder per
                                    // part here would split the album into unrelated bubbles.
                                    if (isAlbumPart) return
                                    persistAndDispatchIncoming(
                                        appContext,
                                        sender,
                                        offerMessage,
                                        notificationText = if (
                                            P2PPreferences.prefs(appContext)
                                                .getString("settings_language", "English") == "Русский"
                                        ) {
                                            "Началось получение файла: $fileName"
                                        } else {
                                            "Receiving file: $fileName"
                                        },
                                        countAsNew = isNewOffer,
                                    )
                                    return
                                }
                                "file_cancelled" -> {
                                    val messageId = json.optString("message_id").take(128)
                                    if (messageId.isBlank()) return
                                    val key = "$sender:$messageId"
                                    incomingFileOffers.remove(key)
                                    ChatDatabaseHelper.getInstance(appContext)
                                        .updateMessageStatus(messageId, "CANCELLED")
                                    serviceScope.launch(Dispatchers.Main) {
                                        val current = fileProgressStates[key]
                                            ?: fileProgressStates[messageId]
                                            ?: FileProgressInfo(0L, 0L, 0.0)
                                        val cancelled = current.copy(
                                            state = FileTransferState.CANCELLED,
                                            speedKbps = 0.0,
                                        )
                                        fileProgressStates[key] = cancelled
                                        fileProgressStates[messageId] = cancelled
                                        messageListeners.forEach {
                                            it.onMessageStatusChanged(sender, messageId, "CANCELLED")
                                        }
                                    }
                                    return
                                }
                                "file_failed" -> {
                                    val messageId = json.optString("message_id").take(128)
                                    if (messageId.isBlank()) return
                                    val key = "$sender:$messageId"
                                    incomingFileOffers.remove(key)
                                    ChatDatabaseHelper.getInstance(appContext)
                                        .updateMessageStatus(messageId, "FAILED")
                                    serviceScope.launch(Dispatchers.Main) {
                                        val current = fileProgressStates[key]
                                            ?: fileProgressStates[messageId]
                                            ?: FileProgressInfo(0L, 0L, 0.0)
                                        val failed = current.copy(
                                            state = FileTransferState.FAILED,
                                            speedKbps = 0.0,
                                        )
                                        fileProgressStates[key] = failed
                                        fileProgressStates[messageId] = failed
                                        messageListeners.forEach {
                                            it.onMessageStatusChanged(sender, messageId, "FAILED")
                                        }
                                    }
                                    return
                                }
                                "verification_request" -> {
                                    serviceScope.launch(Dispatchers.Main) {
                                        messageListeners.forEach { it.onVerificationRequest(sender) }
                                    }
                                    return
                                }
                                "verification_response" -> {
                                    val success = json.optBoolean("success", false)
                                    serviceScope.launch(Dispatchers.Main) {
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
                                                serviceScope.launch(Dispatchers.Main) {
                                                    avatarCache.put(sender, bitmap)
                                                }
                                                log(appContext, "Received and cached an authenticated peer avatar")
                                                
                                                try {
                                                    avatarCache.savePersisted(appContext, sender, bitmap)
                                                    log(appContext, "Saved an encrypted peer avatar")
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
                                "sticker_pack_request" -> {
                                    val packId = StickerSupport.safeId(json.optString("pack_id"))
                                    val requestKey = "$sender:$packId"
                                    val now = System.currentTimeMillis()
                                    val previous = stickerPackRequestTimes.put(requestKey, now)
                                    if (previous != null && now - previous < 10_000L) return
                                    val endpoint = _peerEndpoints[sender] ?: return
                                    relayScope.launch {
                                        val archive = StickerSupport.createPackArchive(appContext, packId)
                                            ?: return@launch
                                        outboundMessenger.sendFile(
                                            context = appContext,
                                            peerName = sender,
                                            endpoint = endpoint,
                                            filePath = archive.absolutePath,
                                            messageId = "sticker-pack-${UUID.randomUUID()}",
                                        )
                                    }
                                    return
                                }
                                "pin_message" -> {
                                    val msgId = json.optString("msg_id")
                                    val text = json.optString("text")
                                    val isFromSender = json.optBoolean("is_from_sender", false)
                                    if (msgId.isNotBlank()) {
                                        val prefs = P2PPreferences.prefs(appContext)
                                        val currentVersion = P2PPreferences.currentPinnedStateVersion(prefs, sender)
                                        val advertisedVersion = PinnedMessageStateVersion(
                                            counter = json.optLong("pin_version", 0L),
                                            actor = json.optString("pin_actor"),
                                        )
                                        val incomingVersion = if (
                                            advertisedVersion.counter > 0L && advertisedVersion.actor.isNotBlank()
                                        ) {
                                            advertisedVersion
                                        } else {
                                            nextPinnedMessageStateVersion(currentVersion, "legacy:$sender")
                                        }
                                        var stateHandled = true
                                        if (shouldApplyPinnedMessageState(currentVersion, incomingVersion)) {
                                            val storedText = ChatDatabaseHelper.getInstance(appContext)
                                                .findMessageForReaction(sender, msgId, "")
                                                ?.text
                                                ?: text
                                            stateHandled = prefs.edit()
                                                .putString(P2PPreferences.pinnedMessageId(sender), msgId)
                                                .putString(
                                                    P2PPreferences.pinnedMessageText(sender),
                                                    SecureStorage.encrypt(storedText),
                                                )
                                                .putString(
                                                    P2PPreferences.pinnedMessageSender(sender),
                                                    if (isFromSender) sender else "You",
                                                )
                                                .putString(P2PPreferences.pinnedBy(sender), sender)
                                                .putLong(
                                                    P2PPreferences.pinnedStateVersion(sender),
                                                    incomingVersion.counter,
                                                )
                                                .putString(
                                                    P2PPreferences.pinnedStateActor(sender),
                                                    incomingVersion.actor,
                                                )
                                                .commit()
                                            if (stateHandled) {
                                                serviceScope.launch(Dispatchers.Main) {
                                                    messageListeners.forEach {
                                                        it.onMessagePinned(sender, msgId, storedText, isFromSender)
                                                    }
                                                }
                                            }
                                        }
                                        if (stateHandled) {
                                            sendPinnedStateAck(appContext, sender, json.optString("control_id"))
                                        }
                                    }
                                    return
                                }
                                "unpin_message" -> {
                                    val prefs = P2PPreferences.prefs(appContext)
                                    val currentVersion = P2PPreferences.currentPinnedStateVersion(prefs, sender)
                                    val advertisedVersion = PinnedMessageStateVersion(
                                        counter = json.optLong("pin_version", 0L),
                                        actor = json.optString("pin_actor"),
                                    )
                                    val incomingVersion = if (
                                        advertisedVersion.counter > 0L && advertisedVersion.actor.isNotBlank()
                                    ) {
                                        advertisedVersion
                                    } else {
                                        nextPinnedMessageStateVersion(currentVersion, "legacy:$sender")
                                    }
                                    var stateHandled = true
                                    if (shouldApplyPinnedMessageState(currentVersion, incomingVersion)) {
                                        stateHandled = prefs.edit()
                                            .remove(P2PPreferences.pinnedMessageId(sender))
                                            .remove(P2PPreferences.pinnedMessageText(sender))
                                            .remove(P2PPreferences.pinnedMessageSender(sender))
                                            .remove(P2PPreferences.pinnedBy(sender))
                                            .putLong(
                                                P2PPreferences.pinnedStateVersion(sender),
                                                incomingVersion.counter,
                                            )
                                            .putString(
                                                P2PPreferences.pinnedStateActor(sender),
                                                incomingVersion.actor,
                                            )
                                            .commit()
                                        if (stateHandled) {
                                            serviceScope.launch(Dispatchers.Main) {
                                                messageListeners.forEach { it.onMessageUnpinned(sender) }
                                            }
                                        }
                                    }
                                    if (stateHandled) {
                                        sendPinnedStateAck(appContext, sender, json.optString("control_id"))
                                    }
                                    return
                                }
                                "typing_state" -> {
                                    val isTyping = json.optBoolean("is_typing", false)
                                    serviceScope.launch(Dispatchers.Main) {
                                        peerTypingStates[sender] = isTyping
                                    }
                                    return
                                }
                                "read_receipt" -> {
                                    val msgId = json.optString("message_id")
                                    if (msgId.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        db.updateMessageStatus(msgId, "READ")
                                        serviceScope.launch(Dispatchers.Main) {
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
                                        val prefs = P2PPreferences.prefs(appContext)
                                        if (prefs.getString(P2PPreferences.pinnedMessageId(sender), null) == msgId) {
                                            prefs.edit()
                                                .putString(
                                                    P2PPreferences.pinnedMessageText(sender),
                                                    SecureStorage.encrypt(text),
                                                )
                                                .apply()
                                        }
                                        serviceScope.launch(Dispatchers.Main) {
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
                                        val prefs = P2PPreferences.prefs(appContext)
                                        if (prefs.getString(P2PPreferences.pinnedMessageId(sender), null) == msgId) {
                                            prefs.edit()
                                                .remove(P2PPreferences.pinnedMessageId(sender))
                                                .remove(P2PPreferences.pinnedMessageText(sender))
                                                .remove(P2PPreferences.pinnedMessageSender(sender))
                                                .remove(P2PPreferences.pinnedBy(sender))
                                                .apply()
                                        }
                                        serviceScope.launch(Dispatchers.Main) {
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
                                        serviceScope.launch(Dispatchers.Main) { peerRttMs[sender] = rtt }
                                    }
                                    return
                                }
                                "edit_ack" -> {
                                    outboundMessenger.acknowledgeControl(appContext, json.optString("control_id"))
                                    return
                                }
                                "pin_state_ack" -> {
                                    outboundMessenger.acknowledgeControl(appContext, json.optString("control_id"))
                                    return
                                }
                                "file_progress" -> {
                                    val msgId = json.optString("message_id")
                                    val fileName = json.optString("file_name")
                                    val bytesTransferred = json.optLong("bytes_transferred")
                                    val totalBytes = json.optLong("total_bytes")
                                    val speedKbps = json.optDouble("speed_kbps", 0.0)
                                    val key = "$sender:$msgId"
                                    val info = FileProgressInfo(
                                        bytesTransferred = bytesTransferred,
                                        totalBytes = totalBytes,
                                        speedKbps = speedKbps,
                                        state = if (totalBytes > 0L && bytesTransferred >= totalBytes) {
                                            FileTransferState.COMPLETED
                                        } else {
                                            FileTransferState.TRANSFERRING
                                        },
                                    )
                                    serviceScope.launch(Dispatchers.Main) {
                                        val existing = fileProgressStates[key]
                                            ?: fileProgressStates[msgId]
                                        if (existing?.state == FileTransferState.CANCELLED) {
                                            return@launch
                                        }
                                        if (msgId.isNotEmpty()) {
                                            fileProgressStates[key] = info
                                            fileProgressStates[msgId] = info
                                        }
                                        if (fileName.isNotEmpty()) {
                                            fileProgressStates["$sender:$fileName"] = info
                                            fileProgressStates[fileName] = info
                                        }
                                        messageListeners.forEach {
                                            it.onFileProgress(sender, msgId, bytesTransferred, totalBytes, speedKbps)
                                        }
                                    }
                                    return
                                }
                                "forwarding_state" -> {
                                    val enabled = json.optBoolean("enabled", false)
                                    val sp = P2PPreferences.prefs(appContext)
                                    sp.edit().putBoolean("restrict_forwarding_$sender", enabled).apply()
                                    serviceScope.launch(Dispatchers.Main) {
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
                                        serviceScope.launch(Dispatchers.Main) {
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
                        val incomingAttachment = parseIncomingAttachment(appContext, text)
                        if (incomingAttachment == null && runCatching {
                                JSONObject(text.trim()).optString("type") == "file"
                            }.getOrDefault(false)
                        ) {
                            log(appContext, "Rejected an invalid incoming file payload", "ERROR")
                            return
                        }
                        val albumKey = incomingAttachment?.albumId?.let { "$sender:$it" }
                        val existingAlbum = if (albumKey != null) {
                            incomingAlbums[albumKey]
                                ?: ChatDatabaseHelper.getInstance(appContext).findMessageForReaction(
                                    sender,
                                    incomingAttachment.albumId,
                                    "",
                                )
                        } else {
                            null
                        }
                        val incomingMessage = if (
                            incomingAttachment != null &&
                            incomingAttachment.albumId != null &&
                            incomingAttachment.albumIndex != null &&
                            incomingAttachment.albumCount != null
                        ) {
                            val albumUris = existingAlbum?.albumMediaUris.orEmpty().toMutableList()
                            val albumTypes = existingAlbum?.albumMediaTypes.orEmpty().toMutableList()
                            val partIndex = incomingAttachment.albumIndex
                            if (partIndex < albumUris.size) {
                                albumUris[partIndex] = incomingAttachment.attachmentUri
                                while (albumTypes.size <= partIndex) albumTypes.add("IMAGE")
                                albumTypes[partIndex] = incomingAttachment.attachmentType
                            } else {
                                albumUris.add(incomingAttachment.attachmentUri)
                                albumTypes.add(incomingAttachment.attachmentType)
                            }
                            val albumComplete = albumUris.size >= incomingAttachment.albumCount
                            Message(
                                id = incomingAttachment.albumId,
                                text = existingAlbum?.text
                                    ?: incomingAttachment.caption
                                    ?: "Sent an album (${incomingAttachment.albumCount})",
                                isMe = false,
                                timestamp = existingAlbum?.timestamp
                                    ?: java.text.SimpleDateFormat(
                                        "HH:mm",
                                        java.util.Locale.getDefault(),
                                    ).format(java.util.Date()),
                                attachmentType = "ALBUM",
                                attachmentUri = albumUris.firstOrNull(),
                                attachmentName = "Album",
                                status = if (albumComplete) "SENT" else "RECEIVING",
                                albumMediaUris = albumUris,
                                albumMediaTypes = albumTypes,
                            ).also { albumMessage ->
                                if (albumComplete) {
                                    incomingAlbums.remove(albumKey)
                                } else if (albumKey != null) {
                                    incomingAlbums[albumKey] = albumMessage
                                }
                            }
                        } else if (incomingAttachment != null) {
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
                        val completedOffer = incomingAttachment?.let {
                            incomingFileOffers.remove("$sender:${it.messageId}")
                        } == true
                        if (incomingAttachment?.attachmentType == StickerSupport.PACK_ATTACHMENT_TYPE) {
                            StickerSupport.packIdFromArchiveFileName(incomingAttachment.attachmentName)
                                ?.let { packId ->
                                    serviceScope.launch(Dispatchers.Main) {
                                        messageListeners.forEach {
                                            it.onStickerPackInstalled(sender, packId)
                                        }
                                    }
                                }
                        }
                        if (incomingAttachment != null) {
                            NetworkTrafficStats.recordFile(
                                appContext,
                                sender,
                                _peerEndpoints[sender],
                                File(incomingAttachment.attachmentUri),
                                incomingAttachment.attachmentType,
                                TrafficDirection.RECEIVED,
                            )
                            serviceScope.launch(Dispatchers.Main) {
                                val key = "$sender:${incomingMessage.id}"
                                val current = fileProgressStates[key]
                                    ?: fileProgressStates[incomingMessage.id]
                                if (current?.state != FileTransferState.CANCELLED) {
                                    val completed = (current ?: FileProgressInfo(
                                        bytesTransferred = 0L,
                                        totalBytes = 0L,
                                        speedKbps = 0.0,
                                    )).copy(
                                        bytesTransferred = current?.totalBytes ?: 0L,
                                        state = FileTransferState.COMPLETED,
                                        speedKbps = 0.0,
                                    )
                                    fileProgressStates[key] = completed
                                    fileProgressStates[incomingMessage.id] = completed
                                }
                                fileTransferPreviews.remove(key)?.recycle()
                                fileTransferPreviews.remove(incomingMessage.id)
                                    ?.takeIf { !it.isRecycled }
                                    ?.recycle()
                            }
                        }
                        persistAndDispatchIncoming(
                            appContext,
                            sender,
                            incomingMessage,
                            countAsNew = if (albumKey != null) {
                                existingAlbum == null
                            } else {
                                !completedOffer
                            },
                        )
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
                        log(appContext, "Authenticated unnamed session awaiting identity information")
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
                        clearPeerPresenceImmediately(resolvedPeerName)
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
                        // Clear stale UI state so the peer no longer appears connected.
                        clearPeerPresenceImmediately(resolvedPeerName)
                        return false
                    }
                    PythonBridge.rememberPeerName(fingerprint, resolvedPeerName)
                    P2PPreferences.prefs(appContext)
                        .edit().apply {
                            putString("peer_fingerprint_$resolvedPeerName", fingerprint)
                            putString("peer_about_me_$resolvedPeerName", aboutMe)
                            if (endpoint.isNotEmpty()) {
                                putString("last_endpoint_$resolvedPeerName", endpoint)
                            }
                            apply()
                        }
                    log(appContext, "Secure Double Ratchet session established")
                    publishPeerOnline(
                        peerName = resolvedPeerName,
                        transport = canonicalTransport,
                        endpoint = endpoint,
                    )

                    val sharedPrefs = P2PPreferences.prefs(appContext)
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
                        GroupChatCoordinator.onPeerConnected(appContext, resolvedPeerName)
                    }
                    return true
                }

                override fun onSessionClosed(peerName: String, fingerprint: String) {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    log(appContext, "Secure Double Ratchet session closed")
                    schedulePeerOffline(resolvedPeerName)
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
        relayScope.launch {
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
        _peerEndpoints.clear()
        avatarCache.clear()
        peerPresenceVersions.clear()
        serviceScope.launch(Dispatchers.Main) {
            peerConnectionTransports.clear()
            peerSessionStates.clear()
            peerRttMs.clear()
        }
        val stopped = PythonBridge.shutdownAllSessions()
        GroupChatCoordinator.shutdown()
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
        serviceScope.launch(Dispatchers.Main) { peerRttMs.clear() }
        relayScope.launch {
            if (!PythonBridge.shutdownAllSessions()) {
                log(appContext, "Listener restart aborted because the old identity runtime is still active", "ERROR")
                return@launch
            }
            startServer(appContext)
        }
    }

    fun shareAvatar(context: Context, peerName: String, endpoint: String) {
        val prefs = P2PPreferences.prefs(context)
        val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
        val shareKey = fingerprint ?: peerName
        val now = System.currentTimeMillis()
        if (!avatarSharesInFlight.add(shareKey)) return
        if (now - (lastAvatarShareAt[shareKey] ?: 0L) < 30_000L) {
            avatarSharesInFlight.remove(shareKey)
            return
        }
        relayScope.launch {
            var sourceBitmap: Bitmap? = null
            var scaledBitmap: Bitmap? = null
            try {
                val file = File(context.filesDir, "profile_avatar.jpg")
                if (file.exists()) {
                    val lastMod = file.lastModified()
                    val b64 = synchronized(identityLock) {
                        if (cachedAvatarLastModified == lastMod && !cachedAvatarBase64.isNullOrEmpty()) {
                            cachedAvatarBase64
                        } else {
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
                                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                cachedAvatarBase64 = encoded
                                cachedAvatarLastModified = lastMod
                                encoded
                            } else null
                        }
                    }

                    if (b64 != null) {
                        val json = JSONObject().apply {
                            put("type", "profile_avatar_share")
                            put("avatar_base64", b64)
                        }
                        val payload = json.toString()
                        val expectedFingerprint = P2PPreferences.prefs(context)
                            .getString("peer_fingerprint_$peerName", null)

                        if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
                            log(context, "Blocked avatar share while a peer identity change awaits confirmation", "ERROR")
                            return@launch
                        }
                        
                        log(context, "Sending profile avatar (length: ${payload.length})")
                        val success = PythonBridge.sendP2pMessage(peerName, endpoint, payload, expectedFingerprint)
                        if (success) lastAvatarShareAt[shareKey] = System.currentTimeMillis()
                        log(context, "Avatar send status: $success")
                    }
                } else {
                    log(context, "profile_avatar.jpg does not exist, skipping avatar share.")
                }
            } catch (e: Exception) {
                log(context, "Failed to share avatar with a peer", "ERROR", e)
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
        if (peerName == "Saved Messages") {
            onResult(true)
            return
        }
        val version = P2PPreferences.nextLocalPinnedStateVersion(context.applicationContext, peerName)
        val controlId = "pinned-state:${version.actor}:${version.counter}"
        outboundMessenger.sendPinnedState(context, peerName, JSONObject().apply {
            put("type", "pin_message")
            put("msg_id", msgId)
            put("text", text)
            put("is_from_sender", isFromSender)
            put("pin_version", version.counter)
            put("pin_actor", version.actor)
            put("control_id", controlId)
        }, onResult)
    }

    fun sendUnpinMessage(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        if (peerName == "Saved Messages") {
            onResult(true)
            return
        }
        val version = P2PPreferences.nextLocalPinnedStateVersion(context.applicationContext, peerName)
        val controlId = "pinned-state:${version.actor}:${version.counter}"
        outboundMessenger.sendPinnedState(context, peerName, JSONObject().apply {
            put("type", "unpin_message")
            put("pin_version", version.counter)
            put("pin_actor", version.actor)
            put("control_id", controlId)
        }, onResult)
    }

    fun deleteChat(context: Context, peerName: String) {
        val sharedPrefs = P2PPreferences.prefs(context)
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
                remove(P2PPreferences.pinnedMessageId(peerName))
                remove(P2PPreferences.pinnedMessageText(peerName))
                remove(P2PPreferences.pinnedMessageSender(peerName))
                remove(P2PPreferences.pinnedBy(peerName))
                remove(P2PPreferences.pinnedStateVersion(peerName))
                remove(P2PPreferences.pinnedStateActor(peerName))
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
                remove(P2PPreferences.pinnedMessageId(peerName))
                remove(P2PPreferences.pinnedMessageText(peerName))
                remove(P2PPreferences.pinnedMessageSender(peerName))
                remove(P2PPreferences.pinnedBy(peerName))
                remove(P2PPreferences.pinnedStateVersion(peerName))
                remove(P2PPreferences.pinnedStateActor(peerName))
            }
        }
        
        // Close Python session asynchronously
        relayScope.launch {
            try {
                PythonBridge.closePeerSession(peerName, expectedFingerprint)
            } catch (e: Exception) {
                log(context, "Failed to close peer session", "ERROR", e)
            }
        }
        
        // Remove from memory caches
        _peerEndpoints.remove(peerName)
        peerSessionStates.remove(peerName)
        peerPresenceVersions.remove(peerName)
        peerTypingStates.remove(peerName)
        avatarCache.remove(peerName)
        
        try {
            avatarCache.deletePersisted(context, peerName)
        } catch (e: Exception) {
            log(context, "Failed to delete persisted peer avatar", "ERROR", e)
        }

        // Clear messages database
        val db = ChatDatabaseHelper.getInstance(context)
        db.clearMessagesForPeer(peerName)
        db.deletePendingControlsForPeer(peerName)
    }

    /**
     * Send an encrypted file to a specific peer and endpoint.
     */
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
        if (messageId.isNotBlank()) {
            val size = File(filePath).length().coerceAtLeast(0L)
            serviceScope.launch(Dispatchers.Main) {
                val info = FileProgressInfo(0L, size, 0.0)
                fileProgressStates["$peerName:$messageId"] = info
                fileProgressStates[messageId] = info
            }
        }
        outboundMessenger.sendFile(
            context,
            peerName,
            endpoint,
            filePath,
            messageId,
            caption,
            albumId,
            albumIndex,
            albumCount,
        ) { success ->
            if (messageId.isNotBlank()) {
                serviceScope.launch(Dispatchers.Main) {
                    val key = "$peerName:$messageId"
                    val current = fileProgressStates[key] ?: fileProgressStates[messageId]
                    if (current?.state != FileTransferState.CANCELLED) {
                        val state = if (success) {
                            FileTransferState.COMPLETED
                        } else {
                            FileTransferState.FAILED
                        }
                        val finalInfo = (current ?: FileProgressInfo(
                            0L,
                            File(filePath).length().coerceAtLeast(0L),
                            0.0,
                        )).copy(
                            bytesTransferred = if (success) {
                                current?.totalBytes ?: File(filePath).length().coerceAtLeast(0L)
                            } else {
                                current?.bytesTransferred ?: 0L
                            },
                            speedKbps = 0.0,
                            state = state,
                        )
                        fileProgressStates[key] = finalInfo
                        fileProgressStates[messageId] = finalInfo
                    }
                }
            }
            onResult(success)
        }
    }

    fun cancelFileTransfer(context: Context, peerName: String, messageId: String): Boolean {
        val cancelled = outboundMessenger.cancelFile(
            context.applicationContext,
            peerName,
            messageId,
        )
        if (cancelled) {
            ChatDatabaseHelper.getInstance(context.applicationContext)
                .updateMessageStatus(messageId, "CANCELLED")
        }
        return cancelled
    }

    fun isFileTransferActive(messageId: String): Boolean =
        outboundMessenger.isFileTransferActive(messageId)

    fun reconnectSession(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        outboundMessenger.reconnect(context, peerName, onResult)
    }

    fun acceptPendingPeerIdentity(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        val appContext = context.applicationContext
        val prefs = P2PPreferences.prefs(appContext)
        val oldFingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null).orEmpty()
        val pendingFingerprint = prefs.getString(P2PPreferences.pendingPeerFingerprint(peerName), null).orEmpty()
        if (!canAcceptPendingPeerFingerprint(oldFingerprint, pendingFingerprint)) {
            serviceScope.launch(Dispatchers.Main) { onResult(false) }
            return
        }
        relayScope.launch {
            // Keep the pause active while the old ratchet is closed. Only then
            // atomically replace the pin and start a completely new session.
            PythonBridge.closePeerSession(peerName, oldFingerprint)
            val accepted = P2PPreferences.acceptPendingPeerIdentity(appContext, peerName)
            if (accepted == null) {
                serviceScope.launch(Dispatchers.Main) { onResult(false) }
                return@launch
            }
            PythonBridge.clearRejectedFingerprint(peerName)
            PythonBridge.rememberPeerName(accepted.acceptedFingerprint, peerName)
            val endpoint = accepted.endpoint.takeIf { it.isNotBlank() }
                ?: prefs.getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
            if (endpoint.isNotBlank()) rememberAuthenticatedPeerEndpoint(peerName, endpoint)
            clearPeerPresenceImmediately(peerName)
            val success = endpoint.isNotBlank() &&
                PythonBridge.reconnectPeerSession(peerName, endpoint, accepted.acceptedFingerprint)
            serviceScope.launch(Dispatchers.Main) { onResult(success) }
        }
    }

    fun rejectPendingPeerIdentity(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        val appContext = context.applicationContext
        val prefs = P2PPreferences.prefs(appContext)
        val oldFingerprint = prefs.getString(P2PPreferences.peerFingerprint(peerName), null).orEmpty()
        val endpoint = prefs.getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
        val cleared = P2PPreferences.rejectPendingPeerIdentity(appContext, peerName)
        if (!cleared) {
            serviceScope.launch(Dispatchers.Main) { onResult(false) }
            return
        }
        relayScope.launch {
            val success = endpoint.isNotBlank() && oldFingerprint.isNotBlank() &&
                PythonBridge.reconnectPeerSession(peerName, endpoint, oldFingerprint)
            serviceScope.launch(Dispatchers.Main) { onResult(success) }
        }
    }

    fun sendTypingState(context: Context, peerName: String, endpoint: String, isTyping: Boolean) {
        outboundMessenger.sendTypingState(context, peerName, endpoint, isTyping)
    }

    fun sendReadReceipt(context: Context, peerName: String, endpoint: String?, messageId: String) {
        outboundMessenger.sendReadReceipt(context, peerName, endpoint, messageId)
    }

    fun enqueueReadReceipt(context: Context, peerName: String, messageId: String): Boolean =
        outboundMessenger.enqueueReadReceipt(context, peerName, messageId)

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

    /** Send one group protocol envelope through the authenticated pairwise session. */
    fun sendGroupFrame(
        context: Context,
        peerName: String,
        payload: JSONObject,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (!GroupWireProtocol.isGroupFrame(payload) ||
            payload.toString().toByteArray(Charsets.UTF_8).size > GroupWireProtocol.MAX_WIRE_BYTES
        ) {
            onResult(false)
            return
        }
        outboundMessenger.sendControlMessage(context, peerName, payload, onResult)
    }

    fun requestStickerPack(
        context: Context,
        peerName: String,
        packId: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        val normalizedPackId = StickerSupport.safeId(packId)
        if (peerName == "Saved Messages" || normalizedPackId.isBlank()) {
            onResult(false)
            return
        }
        outboundMessenger.sendControlMessage(
            context,
            peerName,
            JSONObject().apply {
                put("type", "sticker_pack_request")
                put("pack_id", normalizedPackId)
            },
            onResult,
        )
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
