


package com.example.twopchat.relay

import android.util.Log
import com.example.twopchat.AppLog
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.*
import com.example.twopchat.media.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.tor.*
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

internal fun isExpectedPeerFingerprint(persisted: String?, received: String, peerName: String? = null): Boolean {
    val p = persisted?.trim().orEmpty()
    val r = received.trim()
    if (p.isEmpty() || p == r) return true
    // If the persisted string is literally the peer's nickname itself, but the incoming value is a real cryptographic key,
    // allow the upgrade to the cryptographic key.
    if (peerName != null && p.equals(peerName, ignoreCase = true) && !p.equals(r, ignoreCase = true) && P2PMessageRelay.isRawFingerprint(r)) {
        return true
    }
    return false
}

object P2PMessageRelay {
    private const val TAG = "P2PMessageRelay"
    private val logTimestampFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.getDefault())
    }
    private val startStopLock = Any()
    private val identityLock = Any()
    private val relayExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e("P2PMessageRelay", "Uncaught exception in relay scope", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + relayExceptionHandler)
    private val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4) + relayExceptionHandler)
    private val mainDispatcher get() = runCatching { Dispatchers.Main.immediate }.getOrElse { Dispatchers.Default }
    private val mainScope by lazy { CoroutineScope(SupervisorJob() + mainDispatcher) }

    internal fun runOnMain(block: () -> Unit) {
        mainScope.launch { block() }
    }

    internal fun runDelayedOnMain(delayMs: Long, block: () -> Unit) {
        mainScope.launch {
            delay(delayMs)
            block()
        }
    }

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

    @Volatile private var storedAppContext: Context? = null

    internal fun getBridge(context: Context? = null): com.example.twopchat.bridge.IP2PBridge {
        val ctx = context?.applicationContext
            ?: storedAppContext
            ?: runCatching {
                com.example.twopchat.yggdrasil.GlobalApplication.appContext
            }.getOrNull()
        checkNotNull(ctx) {
            "P2P bridge requested before the Android application context was initialized"
        }
        // NativeBridge owns process-wide JNI callback slots. Constructing an
        // unmanaged NativeBridgeImpl here replaces the provider instance's
        // message/session callbacks and causes successfully decrypted frames to
        // disappear before they reach the relay. Every caller must share the
        // provider singleton, including context-free maintenance callbacks.
        return com.example.twopchat.bridge.P2PBridgeProvider.get(ctx)
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
        val key = localPeerCandidateKey(peerName)
        if (localPeerCandidates.size >= 128 && !localPeerCandidates.containsKey(key)) {
            val oldestKey = localPeerCandidates.keys.firstOrNull()
            if (oldestKey != null) localPeerCandidates.remove(oldestKey)
        }
        val candidates = localPeerCandidates.computeIfAbsent(key) {
            CopyOnWriteArrayList()
        }
        val candidate = LocalPeerCandidate(peerFingerprint, endpoint)
        candidates.remove(candidate)
        candidates.add(candidate)
        while (candidates.size > 12) candidates.removeAt(0)
    }

    @Synchronized
    internal fun rememberAuthenticatedPeerEndpoint(peerName: String, endpoints: String, context: Context? = null): Boolean {
        val normalizedName = peerName.trim()
        val normalizedEndpoints = endpoints.trim()
        val endpointParts = normalizedEndpoints.split(',').map(String::trim).filter(String::isNotEmpty)
        if (normalizedName.isEmpty() || normalizedName.length > 160 ||
            endpointParts.isEmpty() || !isValidPeerEndpointList(normalizedEndpoints)) {
            return false
        }
        if (normalizedName !in _peerEndpoints && _peerEndpoints.size >= MAX_TRACKED_PEER_ENDPOINTS) return false
        val existingParts = _peerEndpoints[normalizedName]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val combined = (existingParts + endpointParts).distinct()
        val joined = combined.joinToString(",")
        _peerEndpoints[normalizedName] = joined
        val ctx = context?.applicationContext ?: storedAppContext
        if (ctx != null && !isPlaceholderPeerName(normalizedName)) {
            P2PPreferences.prefs(ctx).edit().putString("last_endpoint_$normalizedName", joined).apply()
            val onionPart = combined.firstOrNull { it.contains(".onion", ignoreCase = true) }
            if (onionPart != null) {
                com.example.twopchat.data.ChatDatabaseHelper.getInstance(ctx).savePeerOnionAddress(
                    normalizedName,
                    onionPart.substringBefore(':')
                )
            }
        }
        return true
    }

    fun listenerPort(context: Context): Int = P2PPreferences.listenerPort(context)

    fun getActivePeerNames(): List<String> {
        val online = peerSessionStates.entries
            .filter { it.value && !isPlaceholderPeerName(it.key) }
            .map { it.key }
        if (online.isNotEmpty()) return online
        return _peerEndpoints.keys.filter { !isPlaceholderPeerName(it) }
    }

    fun getLocalIpAddress(context: Context? = null): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getYggdrasilAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val host = addr.hostAddress.orEmpty()
                    if (host.startsWith("0200:") || host.startsWith("0300:") || host.startsWith("200:") || host.startsWith("300:")) {
                        return host.split("%")[0]
                    }
                }
            }
        } catch (_: Exception) {}
        try {
            storedAppContext?.let { ctx ->
                val prefs = P2PPreferences.prefs(ctx)
                val state = prefs.getString("yggdrasil_runtime_state", "")?.trim().orEmpty()
                val ip = prefs.getString("yggdrasil_runtime_ip", "")?.trim().orEmpty()
                if (ip.isNotEmpty() && (state.equals("enabled", ignoreCase = true) || state.equals("connected", ignoreCase = true))) {
                    return ip
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    fun refreshAnnouncement(context: Context) {
        val appContext = context.applicationContext
        relayScope.launch {
            refreshAnnouncementNow(appContext)
        }
    }

    private data class TransportAnnounceState(
        val username: String,
        val fingerprint: String,
        val port: Int,
        val ipv4Enabled: Boolean,
        val upnpEnabled: Boolean
    )

    @Volatile
    private var lastAnnouncedTransportState: TransportAnnounceState? = null

    private suspend fun refreshAnnouncementNow(context: Context): Boolean {
        val prefs = P2PPreferences.prefs(context)
        val username = prefs.getString("username_profile", "").orEmpty()
        val bridge = getBridge(context)
        val fingerprint = bridge.getLocalFingerprint()
        if (username.isBlank() || fingerprint.length < 40) return false
        val port = listenerPort(context)
        val ipv4Enabled = prefs.getBoolean("settings_ipv4", true)
        val upnpEnabled = P2PPreferences.isUpnpEnabled(context)
        val newState = TransportAnnounceState(username, fingerprint, port, ipv4Enabled, upnpEnabled)
        val isChanged = lastAnnouncedTransportState != newState
        val success = bridge.announceSelf(
            username,
            fingerprint,
            port,
            force = isChanged,
            rendezvousCode = P2PPreferences.getRendezvousCode(context),
        )
        if (success) {
            lastAnnouncedTransportState = newState
        }
        NativeBridge.onNetworkChanged()
        log(context, "Announce self (forced=$isChanged): $success")
        setLocalDiscoveryEnabled(
            context,
            prefs.getBoolean(P2PPreferences.WIFI_DISCOVERY, true),
        )
        return success
    }

    @Volatile
    private var lastManualRefreshAt = 0L

    fun triggerImmediateReconnect(context: Context) =
        relayScope.launch {
            val now = System.currentTimeMillis()
            if (now - lastManualRefreshAt < 1000L) {
                log(context, "Debounced rapid manual refresh click", "INFO")
                return@launch
            }
            lastManualRefreshAt = now
            val appContext = context.applicationContext
            resetPeerBackoffs()
            // Do not reconnect with stale discovery data while a fresh
            // announcement is still running on another coroutine.
            refreshAnnouncementNow(appContext)
            val prefs = P2PPreferences.prefs(appContext)
            val chats = prefs.getStringSet("active_chats", emptySet()).orEmpty()
                .filterNot { it == "Saved Messages" }
            for (peerName in chats) {
                val fingerprint = prefs.getString("peer_fingerprint_$peerName", "").orEmpty()
                val liveEndpoint = _peerEndpoints[peerName]
                val endpoint = P2PPreferences.getEffectiveEndpointsForPeer(appContext, peerName, liveEndpoint)
                if (endpoint.isBlank()) continue
                getBridge(appContext).reconnectPeerSession(peerName, endpoint, fingerprint)
            }
        }

    fun resetPeerBackoffs(peerName: String? = null) {
        getBridge().resetStaleEndpointCooldowns()
        outboundMessenger.resetPeerBackoffs(peerName)
    }

    fun clearAvatarShareCooldown(peerKey: String) {
        lastAvatarShareAt.remove(peerKey)
        avatarSharesInFlight.remove(peerKey)
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
    private val onionSharesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastOnionShareAt = ConcurrentHashMap<String, Long>()

    fun getPeerTransportType(context: Context, peerName: String): TransportType {
        val isOnline = isPeerOnline(context, peerName) || peerSessionStates[peerName] == true
        val fp = P2PPreferences.prefs(context).getString(P2PPreferences.peerFingerprint(peerName), null)
        val raw = peerConnectionTransports[peerName]
            ?: (if (!fp.isNullOrBlank()) peerConnectionTransports[fp] else null)
            ?: (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerConnectionTransports[it] })
            ?: P2PPreferences.prefs(context).getString(P2PPreferences.transport(peerName), null)
        val ep = peerEndpoints[peerName]
            ?: (if (!fp.isNullOrBlank()) peerEndpoints[fp] else null)
            ?: (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerEndpoints[it] })
            ?: P2PPreferences.prefs(context).getString(P2PPreferences.lastEndpoint(peerName), null)
            ?: P2PPreferences.getPeerOnionAddress(context, peerName)
        return resolveTransportType(raw, ep, isOnline)
    }

    fun getPeerTransportType(peerName: String): TransportType {
        val isOnline = peerSessionStates[peerName] == true ||
            (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerSessionStates[it] == true } == true)
        val raw = peerConnectionTransports[peerName]
            ?: (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerConnectionTransports[it] })
        val ep = peerEndpoints[peerName]
            ?: (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerEndpoints[it] })
        return resolveTransportType(raw, ep, isOnline)
    }

    fun isPeerOnline(context: Context, peerName: String): Boolean {
        if (peerSessionStates[peerName] == true) return true
        val fp = P2PPreferences.prefs(context).getString(P2PPreferences.peerFingerprint(peerName), null)
        val online = getBridge(context).isPeerOnline(peerName, fp)
        if (online) {
            runOnMain {
                peerSessionStates[peerName] = true
            }
        }
        return online
    }

    // Maps peer name to their profile avatar bitmap in RAM
    val peerAvatars = avatarCache.avatars

    fun getOriginalAvatar(context: Context, peerName: String): Bitmap? {
        return avatarCache.getOriginalAvatar(context, peerName, maxDimension = 2048)
    }

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

    fun triggerMaintenanceWakeup(reason: String = "EVENT") {
        maintenanceCoordinator.triggerImmediateMaintenance(reason)
    }

    fun onScreenOff() {
        maintenanceCoordinator.onScreenOff()
    }

    fun onScreenOn(context: Context? = null) {
        maintenanceCoordinator.onScreenOn(context)
    }

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

    internal fun sendConnectedPeerHeartbeat(context: Context, peerName: String) {
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
    internal val outboundMessenger by lazy {
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
        val state: FileTransferState = if (totalBytes > 0L && bytesTransferred >= totalBytes) {
            FileTransferState.COMPLETED
        } else {
            FileTransferState.TRANSFERRING
        },
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

    internal val messageListeners = java.util.concurrent.CopyOnWriteArrayList<MessageListener>()
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
            MessageNotificationService.show(context, sender, text, message.id)
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
        val fingerprint = getBridge(context).getLocalFingerprint()
        if (username.isBlank() || fingerprint.length < 40) return
        val discovery = localPeerDiscovery ?: LocalPeerDiscovery(context) { _, discoveryToken, endpoint ->
            val currentPrefs = P2PPreferences.prefs(context)
            val knownPeer = P2PPreferences.findPeerByDiscoveryToken(context, discoveryToken)
            if (knownPeer != null) {
                val (authenticatedName, peerFingerprint) = knownPeer
                injectLocalDiscoveryCandidate(authenticatedName, peerFingerprint, endpoint)
                currentPrefs.edit().putString(P2PPreferences.lastEndpoint(authenticatedName), endpoint).apply()
                rememberAuthenticatedPeerEndpoint(authenticatedName, endpoint)
                outboundMessenger.reconnect(context, authenticatedName)
            } else {
                injectLocalDiscoveryCandidate("Peer", discoveryToken, endpoint)
            }
        }.also { localPeerDiscovery = it }
        try {
            val hiddenMode = !P2PPreferences.isWifiDiscoveryEnabled(context)
            discovery.start(username, fingerprint, port, hiddenMode)
        } catch (error: Exception) {
            log(context, "Local Wi-Fi discovery could not start", "ERROR", error)
        }
    }

    internal fun persistAndDispatchIncoming(
        context: Context,
        sender: String,
        message: Message,
        notificationText: String = message.text,
        countAsNew: Boolean = true,
    ) {
        // 1. Immediately deliver to active in-app chat listeners so message appears instantly
        runOnMain {
            messageListeners.forEach { it.onMessageReceived(sender, message) }
        }

        // 2. Perform DB persistence, encryption, and notification asynchronously on IO dispatcher
        serviceScope.launch(Dispatchers.IO) {
            val prefs = P2PPreferences.prefs(context)
            val activeSet = prefs.getStringSet(P2PPreferences.ACTIVE_CHATS, emptySet()).orEmpty()
            if (sender !in activeSet) {
                prefs.edit { putStringSet(P2PPreferences.ACTIVE_CHATS, activeSet + sender) }
            }
            if (!P2PPreferences.isAppLocked()) {
                if (prefs.getBoolean("persist_chat_history", true)) {
                    try {
                        ChatDatabaseHelper.getInstance(context).saveMessage(sender, message)
                    } catch (e: Exception) {
                        log(context, "Failed to persist incoming message: ${e.message}", "ERROR", e)
                    }
                }
                prefs.edit {
                    putString(P2PPreferences.lastMessage(sender), SecureStorage.encrypt(notificationText))
                }
            }

            val currentActivePeer = activeChatPeer.get()
            val isChatOpenWithSender = currentActivePeer != null && P2PPreferences.isSamePeer(context, currentActivePeer, sender)
            if (countAsNew && !isChatOpenWithSender) {
                val unreadKey = P2PPreferences.unreadCount(sender)
                prefs.edit { putInt(unreadKey, prefs.getInt(unreadKey, 0) + 1) }
                showNotification(context, sender, message, notificationText)
            } else {
                MessageNotificationService.cancelNotificationForPeer(context, sender)
                if (currentActivePeer != null) {
                    MessageNotificationService.cancelNotificationForPeer(context, currentActivePeer)
                }
            }
        }
    }

    fun isRawFingerprint(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return (trimmed.length == 44 && trimmed.endsWith("=") && !trimmed.contains(" ")) ||
            (trimmed.length == 64 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
    }

    fun isPlaceholderPeerName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true) || trimmed.equals("unknown", ignoreCase = true)) {
            return true
        }
        return ((trimmed.startsWith("Peer (") || trimmed.startsWith("Tor Peer (")) && trimmed.endsWith(")")) ||
            isRawFingerprint(trimmed) ||
            trimmed.contains(".onion") ||
            trimmed.startsWith("[") ||
            trimmed.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$"""))
    }

    fun handlePeerNicknameReceived(
        context: Context,
        sender: String,
        nickname: String?,
        aboutMe: String?
    ) {
        val cleanNickname = nickname?.trim()?.takeIf { it.isNotBlank() }
        val cleanAboutMe = aboutMe?.trim()?.takeIf { it.isNotBlank() }

        val currentEffective = canonicalPeerName(context, sender, "")
        val targetKey = currentEffective.ifBlank { sender }

        val sharedPrefs = P2PPreferences.prefs(context)
        if (cleanAboutMe != null) {
            sharedPrefs.edit().putString("peer_about_me_$targetKey", cleanAboutMe).apply()
            ChatDatabaseHelper.getInstance(context).savePeerAboutMe(targetKey, cleanAboutMe)
            if (cleanNickname != null && cleanNickname != targetKey) {
                sharedPrefs.edit().putString("peer_about_me_$cleanNickname", cleanAboutMe).apply()
                ChatDatabaseHelper.getInstance(context).savePeerAboutMe(cleanNickname, cleanAboutMe)
            }
            val currentFp = sharedPrefs.getString("peer_fingerprint_$targetKey", null)
                ?: if (isRawFingerprint(targetKey)) targetKey else null
            if (currentFp != null) {
                sharedPrefs.edit().putString("peer_about_me_$currentFp", cleanAboutMe).apply()
                ChatDatabaseHelper.getInstance(context).savePeerAboutMe(currentFp, cleanAboutMe)
            }
        }

        if (cleanNickname != null && cleanNickname != targetKey) {
            val currentFp = sharedPrefs.getString("peer_fingerprint_$targetKey", null)
                ?: if (isRawFingerprint(targetKey)) targetKey else null

            if (isPlaceholderPeerName(targetKey) || targetKey == currentFp) {
                log(context, "Migrating placeholder/fingerprint peer $targetKey to received nickname: $cleanNickname")
                if (currentFp != null) {
                    sharedPrefs.edit().putString("peer_fingerprint_$cleanNickname", currentFp).apply()
                }
                renamePeer(context, targetKey, cleanNickname)
            }
        }
    }

    fun renamePeer(context: Context, oldName: String, newName: String): Boolean {
        val cleanNewName = newName.trim()
        if (cleanNewName.isBlank() || cleanNewName == oldName) return false

        synchronized(identityLock) {
            val prefs = P2PPreferences.prefs(context)
            val fp = prefs.getString("peer_fingerprint_$oldName", null)
                ?: if (isRawFingerprint(oldName)) oldName else null

            if (fp != null) {
                fingerprintToPeerName[fp] = cleanNewName
            }

            moveChatState(context, oldName, cleanNewName)

            val bridge = getBridge(context)
            if (fp != null) {
                bridge.updatePeerNameMapping(fp, cleanNewName)
            }

            serviceScope.launch(Dispatchers.Main) {
                val ep = _peerEndpoints[oldName] ?: (if (fp != null) _peerEndpoints[fp] else null)
                if (ep != null) {
                    _peerEndpoints[cleanNewName] = ep
                    _peerEndpoints.remove(oldName)
                    if (fp != null) _peerEndpoints.remove(fp)
                }
                peerPresenceVersions.remove(oldName)
                if (fp != null) peerPresenceVersions.remove(fp)

                val ss = peerSessionStates[oldName] ?: (if (fp != null) peerSessionStates[fp] else null) ?: true
                peerSessionStates[cleanNewName] = ss
                peerSessionStates.remove(oldName)
                if (fp != null) peerSessionStates.remove(fp)

                val ct = peerConnectionTransports[oldName] ?: (if (fp != null) peerConnectionTransports[fp] else null) ?: "DIRECT P2P"
                peerConnectionTransports[cleanNewName] = ct
                peerConnectionTransports.remove(oldName)
                if (fp != null) peerConnectionTransports.remove(fp)

                val av = peerAvatars[cleanNewName] ?: peerAvatars[oldName] ?: (if (fp != null) peerAvatars[fp] else null)
                if (av != null) {
                    peerAvatars[cleanNewName] = av
                    avatarCache.put(cleanNewName, av)
                    try {
                        avatarCache.savePersisted(context, cleanNewName, av)
                    } catch (_: Throwable) {}
                }
            }
        }
        return true
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
            "pending_peer_fingerprint_", "pending_peer_endpoint_",
            "peer_about_me_", "discovery_code_", "pinned_chat_",
            "blocked_peer_", "mute_notifications_", "draft_", "draft_msg_",
            "peer_transport_pref_", "peer_onion_", "peer_onion_address_",
            "direct_wallpaper_", "direct_wallpaper_dimming_", "direct_wallpaper_blur_"
        )
        for (prefix in keysToMove) {
            if (!sharedPrefs.contains("$prefix$fromName")) {
                continue
            }
            when (prefix) {
                "verified_peer_", "fingerprint_mismatch_", "direct_wallpaper_blur_", "blocked_peer_", "mute_notifications_", "pinned_chat_" -> {
                    val value = sharedPrefs.getBoolean("$prefix$fromName", false)
                    val existing = sharedPrefs.getBoolean("$prefix$toName", false)
                    editor.putBoolean("$prefix$toName", existing || value)
                }
                "unread_count_", "direct_wallpaper_dimming_" -> {
                    val value = sharedPrefs.getInt("$prefix$fromName", 0)
                    val existing = sharedPrefs.getInt("$prefix$toName", 0)
                    if (prefix == "unread_count_") {
                        editor.putInt("$prefix$toName", existing + value)
                    } else {
                        editor.putInt("$prefix$toName", if (value != 0) value else existing)
                    }
                }
                "last_msg_" -> {
                    // Placeholder messages are the most recently received
                    // ones which triggered this migration.
                    sharedPrefs.getString("$prefix$fromName", null)?.let {
                        editor.putString("$prefix$toName", it)
                    }
                }
                "peer_about_me_" -> {
                    val value = sharedPrefs.getString("$prefix$fromName", null)?.trim()?.takeIf { it.isNotBlank() }
                    val existing = sharedPrefs.getString("$prefix$toName", null)?.trim()?.takeIf { it.isNotBlank() }
                    if (value != null && (existing.isNullOrBlank() || !sharedPrefs.contains("$prefix$toName"))) {
                        editor.putString("$prefix$toName", value)
                        ChatDatabaseHelper.getInstance(context).savePeerAboutMe(toName, value)
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
            val wpDir = File(context.filesDir, "direct_wallpapers")
            val oldWpFile = File(wpDir, "wallpaper_$fromName.jpg")
            val newWpFile = File(wpDir, "wallpaper_$toName.jpg")
            if (oldWpFile.exists() && !newWpFile.exists()) {
                oldWpFile.renameTo(newWpFile)
            }
        } catch (_: Exception) {}

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
        fingerprint: String,
        endpoint: String = "",
    ): String {
        if (fingerprint.isBlank()) {
            return peerName
        }
        synchronized(identityLock) {
            val knownName = fingerprintToPeerName[fingerprint]
            val persistedName = if (knownName.isNullOrBlank() && isPlaceholderPeerName(peerName)) {
                val byFp = P2PPreferences.findPeerNameByFingerprint(context, fingerprint)?.takeIf {
                    !isPlaceholderPeerName(it)
                }
                if (!byFp.isNullOrBlank()) {
                    byFp
                } else if (endpoint.isNotBlank()) {
                    val byEp = P2PPreferences.findPeerNameByEndpoint(context, endpoint)?.takeIf {
                        !isPlaceholderPeerName(it)
                    }
                    if (byEp != null) {
                        P2PPreferences.prefs(context).edit().putString("peer_fingerprint_$byEp", fingerprint).apply()
                        byEp
                    } else null
                } else null
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
        val allActive = prefs.getStringSet("active_chats", emptySet()).orEmpty()
        val placeholders = allActive.filter(::isPlaceholderPeerName)

        val canonicalIdentities = prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith("peer_fingerprint_") || value !is String) return@mapNotNull null
            val name = key.removePrefix("peer_fingerprint_")
            if (isPlaceholderPeerName(name)) null else name to value
        }
        for (placeholder in placeholders) {
            val abbreviatedFingerprint = placeholder.removePrefix("Tor Peer (").removePrefix("Peer (").removeSuffix(")")
            val matches = canonicalIdentities.filter { (_, fingerprint) ->
                fingerprint.startsWith(abbreviatedFingerprint)
            }
            if (matches.size == 1) {
                val (canonicalName, fingerprint) = matches.single()
                fingerprintToPeerName[fingerprint] = canonicalName
                moveChatState(context, placeholder, canonicalName)
                log(context, "Migrated stale placeholder chat to an authenticated peer alias: $canonicalName")
            }
        }
    }

    internal fun log(context: Context, message: String, level: String = "INFO", error: Throwable? = null) {
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
     * Start the background Native Go P2P server.
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
        val db = ChatDatabaseHelper.getInstance(appContext)
        val dbChats = try { db.getAllChatPeerNames() } catch (_: Exception) { emptySet() }
        val prefChats = persistedPrefs.getStringSet("active_chats", emptySet()).orEmpty()
        val combinedChats = (prefChats + dbChats).filter { it.isNotBlank() && it != "null" && it != "Saved Messages" }.toSet()
        if (combinedChats != prefChats) {
            persistedPrefs.edit().putStringSet("active_chats", combinedChats).apply()
        }
        val persistedChats = combinedChats
        if (persistedPrefs.getBoolean("persist_chat_history", true)) {
            for (peerName in persistedChats) {
                refreshLastMessageFromHistory(appContext, db, peerName)
            }
        }
        for (peerName in persistedChats) {
            persistedPrefs.getString("last_endpoint_$peerName", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { rememberAuthenticatedPeerEndpoint(peerName, it) }
            val savedOnion = P2PPreferences.getPeerOnionAddress(appContext, peerName)
            if (savedOnion != null) {
                rememberAuthenticatedPeerEndpoint(peerName, savedOnion)
            }
        }
        synchronized(identityLock) {
            for (peerName in persistedChats) {
                if (peerName.isNotBlank() && !isPlaceholderPeerName(peerName)) {
                    val fp = persistedPrefs.getString("peer_fingerprint_$peerName", null)
                    if (!fp.isNullOrBlank()) {
                        fingerprintToPeerName[fp] = peerName
                        getBridge(appContext).updatePeerNameMapping(fp, peerName)
                    }
                }
            }
        }
        val port = listenerPort(appContext)
        storedAppContext = appContext
        NativeBridge.setStorageDir(appContext.filesDir.absolutePath)
        val initialOnion = TorManager.getOnionAddress(appContext) ?: P2PPreferences.getTorOnionHostname(appContext)
        if (!initialOnion.isNullOrBlank()) {
            NativeBridge.setOnionAddress(initialOnion)
        }
        val bridge = getBridge(appContext)
        try {
            log(appContext, "Starting P2P Relays on port $port...")
            val ipv4Enabled = P2PPreferences.prefs(appContext)
                .getBoolean("settings_ipv4", true)
            bridge.setIpv4Enabled(ipv4Enabled)
            val localName = persistedPrefs.getString("username_profile", "").orEmpty()
            val aboutMe = P2PPreferences.aboutMe(appContext)
            val localFingerprint = bridge.getLocalFingerprint()
            check(bridge.configureLocalIdentity(localName, localFingerprint, aboutMe)) {
                "Local P2P identity is not configured"
            }
            GroupChatCoordinator.initialize(appContext)
            // Start the P2P listener
            bridge.startP2pListener(port, P2PPreferences.isUpnpEnabled(appContext))
            startLocalDiscovery(appContext, port)
            
            // Register incoming message callback
            bridge.registerMessageListener(object : com.example.twopchat.bridge.BridgeMessageListener {
                override fun onFileProgress(sender: String, messageId: String, bytesTransferred: Long, totalBytes: Long, speedKbps: Double) {
                    val key = "$sender:$messageId"
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
                        val existing = fileProgressStates[key] ?: fileProgressStates[messageId]
                        if (existing?.state == FileTransferState.CANCELLED) {
                            return@launch
                        }
                        if (messageId.isNotEmpty()) {
                            fileProgressStates[key] = info
                            fileProgressStates[messageId] = info
                        }
                        messageListeners.forEach {
                            it.onFileProgress(sender, messageId, bytesTransferred, totalBytes, speedKbps)
                        }
                    }
                }

                override fun onMessageReceived(sender: String, text: String) {
                    log(appContext, "Incoming secure P2P message (${text.toByteArray().size} bytes)")
                    val sharedPrefs = P2PPreferences.prefs(appContext)
                    var resolvedSender = sender
                    if (sender.length > 30 || !sender.all { it.isLetterOrDigit() || it == '_' }) {
                        val allKeys = sharedPrefs.all
                        for ((key, value) in allKeys) {
                            if (key.startsWith("peer_fingerprint_") && value == sender) {
                                resolvedSender = key.removePrefix("peer_fingerprint_")
                                break
                            }
                        }
                    }
                    if (shouldRecordIncomingTrafficPayload(text)) {
                        NetworkTrafficStats.recordMessage(
                            appContext,
                            resolvedSender,
                            _peerEndpoints[resolvedSender] ?: _peerEndpoints[sender],
                            text,
                            TrafficDirection.RECEIVED,
                        )
                    }
                    if (sharedPrefs.getBoolean("blocked_peer_$resolvedSender", false) || sharedPrefs.getBoolean("blocked_peer_$sender", false)) {
                        log(appContext, "Ignored message from a blocked peer")
                        return
                    }
                    try {
                        val trimmed = text.trim()
                        if (trimmed.startsWith("{")) {
                            val json = org.json.JSONObject(trimmed)
                            val payloadNickname = json.optString("nickname").ifEmpty { json.optString("sender").ifEmpty { json.optString("sender_name") } }
                            if (payloadNickname.isNotBlank() && isValidNickname(payloadNickname)) {
                                if (resolvedSender == sender || isPlaceholderPeerName(resolvedSender) || isRawFingerprint(resolvedSender)) {
                                    if (isRawFingerprint(sender)) {
                                        sharedPrefs.edit()
                                            .putString("peer_fingerprint_$payloadNickname", sender)
                                            .apply()
                                        getBridge(appContext).updatePeerNameMapping(sender, payloadNickname)
                                    }
                                    resolvedSender = payloadNickname
                                }
                            }
                            if (GroupChatCoordinator.handleIncoming(appContext, resolvedSender, json)) {
                                return
                            }
                            when (json.optString("type")) {
                                // Reliability/liveness control frames are consumed by the
                                // session layer. They must never become visible chat rows.
                                "heartbeat", "ping", "pong" -> return
                                "identity_info" -> {
                                    val nickname = json.optString("nickname").trim().takeIf { it.isNotBlank() }
                                    val rawFp = json.optString("fingerprint").trim()
                                    val fingerprint = rawFp.takeIf { isRawFingerprint(it) } ?: (if (isRawFingerprint(sender)) sender else "")
                                    val effectiveName = nickname ?: resolvedSender
                                    if (nickname != null) {
                                        if (fingerprint.isNotBlank()) {
                                            sharedPrefs.edit().putString("peer_fingerprint_$nickname", fingerprint).apply()
                                            getBridge(appContext).updatePeerNameMapping(fingerprint, nickname)
                                        }
                                        handlePeerNicknameReceived(appContext, sender, nickname, json.optString("about_me"))
                                    }
                                    val rawOnion = json.optString("onion_address").trim()
                                    val onionPort = json.optInt("listen_port", if (json.has("listener_port")) json.optInt("listener_port") else 50001)
                                    if (rawOnion.isNotEmpty() && rawOnion.contains(".onion", ignoreCase = true)) {
                                        val formatted = com.example.twopchat.ui.main.formatInviteEndpoint(rawOnion, onionPort)
                                            ?: if (rawOnion.contains(":")) rawOnion else "$rawOnion:$onionPort"
                                        P2PPreferences.setPeerOnionAddress(appContext, effectiveName, formatted)
                                        ChatDatabaseHelper.getInstance(appContext).savePeerOnionAddress(
                                            peerName = effectiveName,
                                            onionAddress = formatted,
                                            fingerprint = fingerprint.ifBlank { null },
                                            endpoint = formatted,
                                        )
                                        rememberAuthenticatedPeerEndpoint(effectiveName, formatted)
                                    }
                                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                                    if (!activeSet.contains(effectiveName) && !isPlaceholderPeerName(effectiveName)) {
                                        sharedPrefs.edit().putStringSet("active_chats", activeSet + effectiveName).apply()
                                    }
                                    shareAvatar(appContext, effectiveName, "")
                                    return
                                }
                                "chat" -> {
                                    val body = json.optString("body")
                                    val nickname = json.optString("nickname").takeIf { it.isNotBlank() }
                                    val msgId = json.optString("id")
                                    val effectiveSender = if (resolvedSender == sender && nickname != null) {
                                        nickname
                                    } else {
                                        resolvedSender
                                    }
                                    if (nickname != null && isRawFingerprint(sender)) {
                                        sharedPrefs.edit()
                                            .putString("peer_fingerprint_$nickname", sender)
                                            .apply()
                                    }
                                    val trimmedBody = body.trim()
                                    if (trimmedBody.startsWith("{")) {
                                        onMessageReceived(effectiveSender, trimmedBody)
                                        return
                                    }
                                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    val rxMsg = Message(
                                        id = if (msgId.isNotEmpty()) msgId else UUID.randomUUID().toString(),
                                        text = body,
                                        isMe = false,
                                        timestamp = time,
                                        status = "SENT"
                                    )
                                    persistAndDispatchIncoming(appContext, effectiveSender, rxMsg)
                                    return
                                }
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
                                    val offerKey = "$resolvedSender:$messageId"
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
                                        resolvedSender,
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
                                    val key = "$resolvedSender:$messageId"
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
                                            it.onMessageStatusChanged(resolvedSender, messageId, "CANCELLED")
                                        }
                                    }
                                    return
                                }
                                "file_failed" -> {
                                    val messageId = json.optString("message_id").take(128)
                                    if (messageId.isBlank()) return
                                    val key = "$resolvedSender:$messageId"
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
                                            it.onMessageStatusChanged(resolvedSender, messageId, "FAILED")
                                        }
                                    }
                                    return
                                }
                                "verification_request" -> {
                                    serviceScope.launch(Dispatchers.Main) {
                                        messageListeners.forEach { it.onVerificationRequest(resolvedSender) }
                                    }
                                    return
                                }
                                "verification_response" -> {
                                    val success = json.optBoolean("success", false)
                                    serviceScope.launch(Dispatchers.Main) {
                                        if (success) {
                                            // Verification responses can arrive while the chat screen is not
                                            // composed. Persist the trust decision before notifying UI listeners.
                                            P2PPreferences.setPeerVerified(appContext, resolvedSender, true)
                                        }
                                        messageListeners.forEach { it.onVerificationResponse(resolvedSender, success) }
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
                                            if (bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
                                                bounds.outWidth.toLong() * bounds.outHeight.toLong() <= 16_000_000L) {
                                                var sample = 1
                                                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                                                    bytes, 0, bytes.size,
                                                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                                                )
                                                if (bitmap != null) {
                                                    serviceScope.launch(Dispatchers.Main) {
                                                        avatarCache.put(resolvedSender, bitmap)
                                                        if (resolvedSender != sender) {
                                                            avatarCache.put(sender, bitmap)
                                                        }
                                                    }
                                                    log(appContext, "Received and cached an authenticated peer avatar for $resolvedSender")
                                                    
                                                    try {
                                                        avatarCache.savePersisted(appContext, resolvedSender, bitmap)
                                                        if (resolvedSender != sender) {
                                                            avatarCache.savePersisted(appContext, sender, bitmap)
                                                        }
                                                        log(appContext, "Saved an encrypted peer avatar")
                                                    } catch (saveEx: Exception) {
                                                        log(appContext, "Failed to save avatar file: ${saveEx.message}", "ERROR", saveEx)
                                                    }

                                                    // Respond with our own profile avatar so group members get mutual avatar sync
                                                    if (lastAvatarShareAt[resolvedSender] == null) {
                                                        shareAvatar(appContext, resolvedSender)
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            log(appContext, "Error decoding avatar: ${e.message}", "ERROR", e)
                                        }
                                    }
                                    return
                                }
                                "onion_address_share", "onion_address_update" -> {
                                    val rawOnion = json.optString("onion_address").trim()
                                    val port = json.optInt("listener_port", json.optInt("port", listenerPort(appContext)))
                                    val formattedOnion = com.example.twopchat.ui.main.formatInviteEndpoint(rawOnion, port)
                                    val fp = json.optString("fingerprint").trim()
                                    val sig = json.optString("signature").trim()
                                    val timestamp = json.optLong("timestamp", 0L)

                                    if (formattedOnion != null && formattedOnion.contains(".onion", ignoreCase = true)) {
                                        val expectedFp = P2PPreferences.prefs(appContext)
                                            .getString("peer_fingerprint_$resolvedSender", null)
                                            ?: (if (isRawFingerprint(sender)) sender else null)

                                        if (sig.isNotBlank() && expectedFp != null) {
                                            val canonicalData = "onion_update:$fp:$rawOnion:$port:$timestamp"
                                            val valid = NativeBridge.verifyGroupPayload(expectedFp, canonicalData, sig)
                                            if (!valid) {
                                                log(appContext, "Rejected unverified or forged onion address update from $resolvedSender", "ERROR")
                                                return
                                            }
                                            log(appContext, "Verified signed onion address update from $resolvedSender")
                                        }

                                        log(appContext, "Received authenticated onion address from $resolvedSender: $formattedOnion")
                                        P2PPreferences.setPeerOnionAddress(appContext, resolvedSender, formattedOnion)
                                        ChatDatabaseHelper.getInstance(appContext).savePeerOnionAddress(
                                            peerName = resolvedSender,
                                            onionAddress = formattedOnion,
                                            fingerprint = expectedFp,
                                            endpoint = formattedOnion,
                                        )
                                        rememberAuthenticatedPeerEndpoint(resolvedSender, formattedOnion)

                                        // Reciprocal exchange if we haven't shared our onion address yet
                                        if (json.optString("type") == "onion_address_share" && lastOnionShareAt[resolvedSender] == null) {
                                            shareOnionAddress(appContext, resolvedSender)
                                        }
                                    }
                                    return
                                }
                                "direct_wallpaper_update" -> {
                                    val b64 = json.optString("wallpaper_data", "")
                                    val dimming = json.optInt("dimming", 30)
                                    val isBlur = json.optBoolean("is_blur", false)
                                    if (b64.isNotBlank() && b64.length <= 6_000_000) {
                                        try {
                                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                                            val dir = File(appContext.filesDir, "direct_wallpapers").also { it.mkdirs() }
                                            val destFile = File(dir, "wallpaper_$resolvedSender.jpg")
                                            destFile.writeBytes(bytes)
                                            if (resolvedSender != sender) {
                                                try {
                                                    File(dir, "wallpaper_$sender.jpg").writeBytes(bytes)
                                                } catch (_: Exception) {}
                                            }

                                            P2PPreferences.setDirectWallpaper(appContext, resolvedSender, destFile.absolutePath, dimming, isBlur)
                                            if (resolvedSender != sender) {
                                                P2PPreferences.setDirectWallpaper(appContext, sender, destFile.absolutePath, dimming, isBlur)
                                            }

                                            val defaultLang = if (Locale.getDefault().language == "ru") "Русский" else "English"
                                            val lang = P2PPreferences.prefs(appContext).getString("settings_language", defaultLang)
                                                ?: P2PPreferences.prefs(appContext).getString("app_language", defaultLang)
                                                ?: defaultLang
                                            val textRu = "Собеседник установил(а) новые обои для этого чата"
                                            val textEn = "Your peer set a new wallpaper for this chat"
                                            val sysMsg = Message(
                                                id = UUID.randomUUID().toString(),
                                                text = if (lang == "Русский") textRu else textEn,
                                                isMe = false,
                                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                                                attachmentType = "SYSTEM"
                                            )
                                            persistAndDispatchIncoming(appContext, resolvedSender, sysMsg, notificationText = if (lang == "Русский") textRu else textEn, countAsNew = false)
                                            if (resolvedSender != sender) {
                                                persistAndDispatchIncoming(appContext, sender, sysMsg, notificationText = if (lang == "Русский") textRu else textEn, countAsNew = false)
                                            }
                                        } catch (e: Exception) {
                                            log(appContext, "Failed to apply incoming wallpaper: ${e.message}", "ERROR", e)
                                        }
                                    } else if (b64.isBlank()) {
                                        try {
                                            val dir = File(appContext.filesDir, "direct_wallpapers")
                                            val destFile = File(dir, "wallpaper_$resolvedSender.jpg")
                                            if (destFile.exists()) destFile.delete()
                                            if (resolvedSender != sender) {
                                                val altFile = File(dir, "wallpaper_$sender.jpg")
                                                if (altFile.exists()) altFile.delete()
                                            }
                                            P2PPreferences.setDirectWallpaper(appContext, resolvedSender, null, 0, false)
                                            if (resolvedSender != sender) {
                                                P2PPreferences.setDirectWallpaper(appContext, sender, null, 0, false)
                                            }

                                            val defaultLang = if (Locale.getDefault().language == "ru") "Русский" else "English"
                                            val lang = P2PPreferences.prefs(appContext).getString("settings_language", defaultLang)
                                                ?: P2PPreferences.prefs(appContext).getString("app_language", defaultLang)
                                                ?: defaultLang
                                            val textRu = "Собеседник удалил(а) обои для этого чата"
                                            val textEn = "Your peer removed the wallpaper for this chat"
                                            val sysMsg = Message(
                                                id = UUID.randomUUID().toString(),
                                                text = if (lang == "Русский") textRu else textEn,
                                                isMe = false,
                                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                                                attachmentType = "SYSTEM"
                                            )
                                            persistAndDispatchIncoming(appContext, resolvedSender, sysMsg, notificationText = if (lang == "Русский") textRu else textEn, countAsNew = false)
                                            if (resolvedSender != sender) {
                                                persistAndDispatchIncoming(appContext, sender, sysMsg, notificationText = if (lang == "Русский") textRu else textEn, countAsNew = false)
                                            }
                                        } catch (e: Exception) {
                                            log(appContext, "Failed to clear wallpaper: ${e.message}", "ERROR", e)
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
                                            prefs.edit()
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
                                                .apply()
                                            runOnMain {
                                                messageListeners.forEach {
                                                    it.onMessagePinned(sender, msgId, storedText, isFromSender)
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
                                        prefs.edit()
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
                                            .apply()
                                        runOnMain {
                                            messageListeners.forEach { it.onMessageUnpinned(sender) }
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
                                        peerTypingStates[resolvedSender] = isTyping
                                        if (resolvedSender != sender) {
                                            peerTypingStates[sender] = isTyping
                                        }
                                    }
                                    return
                                }
                                "read_receipt" -> {
                                    val msgId = json.optString("message_id")
                                    if (msgId.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        db.updateMessageStatus(msgId, "READ")
                                        serviceScope.launch(Dispatchers.Main) {
                                            messageListeners.forEach {
                                                it.onMessageStatusChanged(resolvedSender, msgId, "READ")
                                                if (resolvedSender != sender) {
                                                    it.onMessageStatusChanged(sender, msgId, "READ")
                                                }
                                            }
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
                                        if (prefs.getString(P2PPreferences.pinnedMessageId(resolvedSender), null) == msgId ||
                                            prefs.getString(P2PPreferences.pinnedMessageId(sender), null) == msgId
                                        ) {
                                            prefs.edit()
                                                .putString(
                                                    P2PPreferences.pinnedMessageText(resolvedSender),
                                                    SecureStorage.encrypt(text),
                                                )
                                                .apply()
                                        }
                                        serviceScope.launch(Dispatchers.Main) {
                                            messageListeners.forEach {
                                                it.onMessageEdited(resolvedSender, msgId, text)
                                                if (resolvedSender != sender) {
                                                    it.onMessageEdited(sender, msgId, text)
                                                }
                                            }
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
                                        if (prefs.getString(P2PPreferences.pinnedMessageId(resolvedSender), null) == msgId ||
                                            prefs.getString(P2PPreferences.pinnedMessageId(sender), null) == msgId
                                        ) {
                                            prefs.edit()
                                                .remove(P2PPreferences.pinnedMessageId(resolvedSender))
                                                .remove(P2PPreferences.pinnedMessageText(resolvedSender))
                                                .remove(P2PPreferences.pinnedMessageSender(resolvedSender))
                                                .remove(P2PPreferences.pinnedBy(resolvedSender))
                                                .remove(P2PPreferences.pinnedMessageId(sender))
                                                .remove(P2PPreferences.pinnedMessageText(sender))
                                                .remove(P2PPreferences.pinnedMessageSender(sender))
                                                .remove(P2PPreferences.pinnedBy(sender))
                                                .apply()
                                        }
                                        serviceScope.launch(Dispatchers.Main) {
                                            messageListeners.forEach {
                                                it.onMessageDeleted(resolvedSender, msgId)
                                                if (resolvedSender != sender) {
                                                    it.onMessageDeleted(sender, msgId)
                                                }
                                            }
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
                                        serviceScope.launch(Dispatchers.Main) {
                                            peerRttMs[resolvedSender] = rtt
                                            peerRttMs[sender] = rtt
                                        }
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
                                    val resolvedKey = "$resolvedSender:$msgId"
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
                                            ?: fileProgressStates[resolvedKey]
                                            ?: fileProgressStates[msgId]
                                        if (existing?.state == FileTransferState.CANCELLED) {
                                            return@launch
                                        }
                                        if (msgId.isNotEmpty()) {
                                            fileProgressStates[key] = info
                                            fileProgressStates[resolvedKey] = info
                                            fileProgressStates[msgId] = info
                                        }
                                        if (fileName.isNotEmpty()) {
                                            fileProgressStates["$sender:$fileName"] = info
                                            fileProgressStates["$resolvedSender:$fileName"] = info
                                            fileProgressStates[fileName] = info
                                        }
                                        messageListeners.forEach {
                                            it.onFileProgress(resolvedSender, msgId, bytesTransferred, totalBytes, speedKbps)
                                        }
                                    }
                                    return
                                }
                                "forwarding_state" -> {
                                    val enabled = json.optBoolean("enabled", false)
                                    val sp = P2PPreferences.prefs(appContext)
                                    sp.edit().putBoolean("restrict_forwarding_$resolvedSender", enabled).apply()
                                    serviceScope.launch(Dispatchers.Main) {
                                        messageListeners.forEach { it.onForwardingStateChanged(resolvedSender, enabled) }
                                    }
                                    return
                                }
                                "reaction" -> {
                                    val msgId = json.optString("message_id")
                                    val emoji = json.optString("emoji")
                                    val messageText = json.optString("message_text")
                                    if (msgId.isNotEmpty() && emoji.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        val existing = db.findMessageForReaction(resolvedSender, msgId, messageText)
                                            ?: db.findMessageForReaction(sender, msgId, messageText)
                                        if (existing != null) {
                                            val updatedMap = existing.reactions.toMutableMap()
                                            val sendersList = (updatedMap[emoji] ?: emptyList()).toMutableList()
                                            if (!sendersList.contains(resolvedSender) && !sendersList.contains(sender)) {
                                                sendersList.add(resolvedSender)
                                                updatedMap[emoji] = sendersList
                                                db.updateMessageReactions(msgId, updatedMap)
                                            }
                                        }
                                        serviceScope.launch(Dispatchers.Main) {
                                            messageListeners.forEach {
                                                it.onMessageReactionChanged(resolvedSender, existing?.id ?: msgId, emoji, resolvedSender)
                                                if (resolvedSender != sender) {
                                                    it.onMessageReactionChanged(sender, existing?.id ?: msgId, emoji, resolvedSender)
                                                }
                                            }
                                        }
                                    }
                                    return
                                }
                                "text" -> {
                                    val msgId = json.optString("message_id").ifEmpty { json.optString("id") }
                                    val msgText = json.optString("text").ifEmpty { json.optString("body") }
                                    val nickname = json.optString("nickname").ifEmpty { json.optString("sender") }.takeIf { it.isNotBlank() }
                                    val effectiveSender = if (nickname != null && (resolvedSender == sender || isPlaceholderPeerName(resolvedSender) || isRawFingerprint(resolvedSender))) {
                                        nickname
                                    } else {
                                        resolvedSender
                                    }
                                    if (nickname != null && isRawFingerprint(sender)) {
                                        sharedPrefs.edit()
                                            .putString("peer_fingerprint_$nickname", sender)
                                            .apply()
                                        getBridge(appContext).updatePeerNameMapping(sender, nickname)
                                    }
                                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                    val rxMsg = Message(
                                        id = if (msgId.isNotEmpty()) msgId else UUID.randomUUID().toString(),
                                        text = msgText,
                                        isMe = false,
                                        timestamp = time,
                                        status = "SENT"
                                    )
                                    persistAndDispatchIncoming(appContext, effectiveSender, rxMsg)
                                    return
                                }
                                "reply" -> {
                                    val msgId = json.optString("message_id").ifEmpty { json.optString("id") }
                                    val replyText = json.optString("text").ifEmpty { json.optString("body") }
                                    val replyToId = json.optString("reply_to_id")
                                    val replyToText = json.optString("reply_to_text")
                                    val replyToName = json.optString("reply_to_name")
                                    val nickname = json.optString("nickname").ifEmpty { json.optString("sender") }.takeIf { it.isNotBlank() }
                                    val effectiveSender = if (nickname != null && (resolvedSender == sender || isPlaceholderPeerName(resolvedSender) || isRawFingerprint(resolvedSender))) {
                                        nickname
                                    } else {
                                        resolvedSender
                                    }
                                    if (nickname != null && isRawFingerprint(sender)) {
                                        sharedPrefs.edit()
                                            .putString("peer_fingerprint_$nickname", sender)
                                            .apply()
                                        getBridge(appContext).updatePeerNameMapping(sender, nickname)
                                    }
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
                                    persistAndDispatchIncoming(appContext, effectiveSender, rxMsg)
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
                            val totalParts = incomingAttachment.albumCount.coerceIn(1, 100)
                            val albumUris = existingAlbum?.albumMediaUris.orEmpty().toMutableList()
                            val albumTypes = existingAlbum?.albumMediaTypes.orEmpty().toMutableList()
                            while (albumUris.size < totalParts) albumUris.add("")
                            while (albumTypes.size < totalParts) albumTypes.add("IMAGE")
                            val partIndex = incomingAttachment.albumIndex
                            if (partIndex in 0 until totalParts) {
                                albumUris[partIndex] = incomingAttachment.attachmentUri
                                albumTypes[partIndex] = incomingAttachment.attachmentType
                            }
                            val albumComplete = albumUris.take(totalParts).all { it.isNotBlank() }
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
                                attachmentUri = albumUris.firstOrNull { it.isNotBlank() } ?: albumUris.firstOrNull(),
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
                        if (text.isNotBlank()) {
                            val onionMatch = Regex("""([a-z2-7]{56}\.onion(?::\d+)?)""", RegexOption.IGNORE_CASE).find(text)
                            if (onionMatch != null) {
                                val onionAddr = onionMatch.value.trim()
                                val formatted = com.example.twopchat.ui.main.formatInviteEndpoint(onionAddr, listenerPort(appContext))
                                if (formatted != null && formatted.contains(".onion", ignoreCase = true)) {
                                    P2PPreferences.setPeerOnionAddress(appContext, sender, formatted)
                                    val fingerprint = P2PPreferences.prefs(appContext)
                                        .getString("peer_fingerprint_$sender", null)
                                    ChatDatabaseHelper.getInstance(appContext).savePeerOnionAddress(
                                        peerName = sender,
                                        onionAddress = formatted,
                                        fingerprint = fingerprint,
                                        endpoint = _peerEndpoints[sender],
                                    )
                                    rememberAuthenticatedPeerEndpoint(sender, formatted)
                                    log(appContext, "Saved authenticated onion address discovered from message: $formatted")
                                }
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

            bridge.registerSessionListener(object : com.example.twopchat.bridge.BridgeSessionListener {
                override fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String, transport: String, aboutMe: String): Boolean {
                    val identityPrefs = P2PPreferences.prefs(appContext)
                    val activeChats = identityPrefs.getStringSet("active_chats", emptySet())
                        ?.filterNot { it == "Saved Messages" || isPlaceholderPeerName(it) }
                        .orEmpty()

                    var resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint, endpoint)
                    if (isPlaceholderPeerName(resolvedPeerName) && activeChats.size == 1) {
                        val singlePeer = activeChats.first()
                        log(appContext, "1-on-1 fallback: mapping unnamed session $fingerprint to $singlePeer")
                        identityPrefs.edit().putString("peer_fingerprint_$singlePeer", fingerprint).apply()
                        fingerprintToPeerName[fingerprint] = singlePeer
                        getBridge(appContext).updatePeerNameMapping(fingerprint, singlePeer)
                        resolvedPeerName = singlePeer
                    }

                    val canonicalTransport = canonicalConnectionTransport(transport, endpoint)
                    if (isPlaceholderPeerName(resolvedPeerName)) {
                        log(appContext, "Authenticated unnamed session awaiting identity information - sending self profile")
                        shareOnionAddress(appContext, fingerprint, endpoint)
                        return true
                    }
                    val persistedFingerprint = identityPrefs
                        .getString(P2PPreferences.peerFingerprint(resolvedPeerName), null)
                    if (!isExpectedPeerFingerprint(persistedFingerprint, fingerprint, resolvedPeerName)) {
                        if (isRawFingerprint(persistedFingerprint.orEmpty()) && isRawFingerprint(fingerprint)) {
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
                    P2PPreferences.prefs(appContext)
                        .edit().apply {
                            putString("peer_fingerprint_$resolvedPeerName", fingerprint)
                            if (aboutMe.isNotBlank()) {
                                putString("peer_about_me_$resolvedPeerName", aboutMe)
                                putString("peer_about_me_$fingerprint", aboutMe)
                                ChatDatabaseHelper.getInstance(appContext).savePeerAboutMe(resolvedPeerName, aboutMe)
                                ChatDatabaseHelper.getInstance(appContext).savePeerAboutMe(fingerprint, aboutMe)
                            }
                            if (endpoint.isNotEmpty()) {
                                putString("last_endpoint_$resolvedPeerName", endpoint)
                            }
                            apply()
                        }
                    getBridge(appContext).updatePeerNameMapping(fingerprint, resolvedPeerName)
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

                    // Save to active chats so the UI updates and shows the peer chat screen
                    val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                    if (!activeSet.contains(resolvedPeerName)) {
                        val newSet = activeSet.toMutableSet()
                        newSet.add(resolvedPeerName)
                        sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                    }

                    clearAvatarShareCooldown(resolvedPeerName)
                    clearAvatarShareCooldown(fingerprint)
                    resetPeerBackoffs(resolvedPeerName)
                    resetPeerBackoffs(fingerprint)
                    shareAvatar(appContext, resolvedPeerName, endpoint)
                    shareOnionAddress(appContext, resolvedPeerName, endpoint)
                    processOfflineQueue(appContext, resolvedPeerName, endpoint)
                    GroupChatCoordinator.onPeerConnected(appContext, resolvedPeerName)
                    return true
                }

                override fun onSessionClosed(peerName: String, fingerprint: String) {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    if (getBridge(appContext).isPeerOnline(resolvedPeerName, fingerprint)) {
                        log(appContext, "Secure session closed but active connection still online for $resolvedPeerName")
                        return
                    }
                    log(appContext, "Secure Double Ratchet session closed")
                    clearPeerPresenceImmediately(resolvedPeerName)
                    if (fingerprint.isNotBlank() && fingerprint != resolvedPeerName) {
                        clearPeerPresenceImmediately(fingerprint)
                    }
                }

                override fun onPeerDiscovered(infoHash: String, endpoint: String, source: String) {
                    if (endpoint.isBlank() || infoHash.isBlank()) return
                    val prefs = P2PPreferences.prefs(appContext)
                    val localHosts = listOfNotNull(
                        getLocalIpAddress(appContext).takeIf { it.isNotBlank() },
                        getYggdrasilAddress().takeIf { it.isNotBlank() },
                        P2PPreferences.getTorOnionHostname(appContext),
                    )
                    if (isSelfDiscoveryEndpoint(endpoint, localHosts)) {
                        log(appContext, "Ignoring own endpoint $endpoint returned by $source discovery")
                        return
                    }
                    val activeChats = prefs.getStringSet("active_chats", emptySet()) ?: emptySet()

                    for (peerName in activeChats) {
                        val fp = prefs.getString(P2PPreferences.peerFingerprint(peerName), null).orEmpty()
                        val discCode = prefs.getString("discovery_code_$peerName", null).orEmpty()

                        val fpHash = if (fp.isNotEmpty()) {
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(fp.toByteArray(Charsets.UTF_8))
                                .take(20).toByteArray().joinToString("") { "%02x".format(it) }
                        } else ""
                        val codeHash = if (discCode.isNotEmpty()) {
                            java.security.MessageDigest.getInstance("SHA-256")
                                .digest(discCode.toByteArray(Charsets.UTF_8))
                                .take(20).toByteArray().joinToString("") { "%02x".format(it) }
                        } else ""

                        if (infoHash.equals(fp, ignoreCase = true) ||
                            infoHash.equals(discCode, ignoreCase = true) ||
                            infoHash.equals(fpHash, ignoreCase = true) ||
                            infoHash.equals(codeHash, ignoreCase = true)) {

                            log(appContext, "Discovered endpoint $endpoint for $peerName via $source")
                            injectLocalDiscoveryCandidate(peerName, fp, endpoint)
                            rememberAuthenticatedPeerEndpoint(peerName, endpoint, appContext)

                            if (!getBridge(appContext).isPeerOnline(peerName, fp)) {
                                getBridge(appContext).reconnectPeerSession(peerName, endpoint, fp)
                            }
                        }
                    }
                }
            })
            
            log(appContext, "Native Go P2P Relays started successfully")

            // Retry persisted pending messages immediately after the listener is ready.
            for (peerName in persistedChats) {
                peerEndpoints[peerName]?.let { endpoint ->
                    processOfflineQueue(appContext, peerName, endpoint)
                }
            }

            maintenanceCoordinator.start(appContext, port, ::isPlaceholderPeerName)

            relayScope.launch {
                var lastBroadcastedOnion: String? = null
                TorManager.onionAddress.collect { onionHost ->
                    if (!onionHost.isNullOrBlank()) {
                        if (onionHost != lastBroadcastedOnion) {
                            lastBroadcastedOnion = onionHost
                            NativeBridge.setOnionAddress(onionHost)
                            log(appContext, "Tor onion service ready ($onionHost), broadcasting to known contacts")
                            lastOnionShareAt.clear()
                            shareOnionAddressWithKnownPeers(appContext)
                        }
                    } else {
                        if (lastBroadcastedOnion != null) {
                            lastBroadcastedOnion = null
                            NativeBridge.setOnionAddress("")
                            log(appContext, "Tor onion service stopped; cleared onion endpoint in Go Core")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            synchronized(startStopLock) {
                isRunning = false
            }
            maintenanceCoordinator.stop()
            log(appContext, "Error starting Native Go P2P Relays", "ERROR", e)
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
        // Trigger bridge shutdown/cleanup
        relayScope.launch {
            if (!getBridge().shutdownAllSessions()) {
                Log.e(TAG, "P2P runtime did not stop cleanly")
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
        val stopped = getBridge(appContext).shutdownAllSessions()
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
        serviceScope.launch(Dispatchers.Main) { peerRttMs.clear() }
        relayScope.launch {
            if (!getBridge(appContext).shutdownAllSessions()) {
                log(appContext, "Listener restart aborted because the old identity runtime is still active", "ERROR")
                return@launch
            }
            startServer(appContext)
        }
    }

    fun shareAvatar(context: Context, peerName: String, endpoint: String = "", force: Boolean = false) {
        val prefs = P2PPreferences.prefs(context)
        val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
        val shareKey = fingerprint ?: peerName
        val now = System.currentTimeMillis()
        if (!avatarSharesInFlight.add(shareKey)) return
        if (!force && now - (lastAvatarShareAt[shareKey] ?: 0L) < 30_000L) {
            avatarSharesInFlight.remove(shareKey)
            return
        }
        relayScope.launch {
            var sourceBitmap: Bitmap? = null
            var scaledBitmap: Bitmap? = null
            try {
                val profileUri = P2PPreferences.prefs(context).getString("profile_photo_uri", null)
                val file = if (!profileUri.isNullOrBlank()) File(profileUri) else null
                val b64: String? = if (file != null && file.exists()) {
                    val lastMod = file.lastModified()
                    synchronized(identityLock) {
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
                } else null

                val myUsername = P2PPreferences.username(context).trim()
                val myAboutMe = P2PPreferences.aboutMe(context).trim()

                if (b64 != null || myUsername.isNotEmpty() || myAboutMe.isNotEmpty()) {
                    val json = JSONObject().apply {
                        put("type", "profile_avatar_share")
                        if (b64 != null) {
                            put("avatar_base64", b64)
                        }
                        if (myUsername.isNotEmpty()) {
                            put("nickname", myUsername)
                        }
                        if (myAboutMe.isNotEmpty()) {
                            put("about_me", myAboutMe)
                        }
                        put("fingerprint", getBridge(context).getLocalFingerprint())
                        put("listen_port", listenerPort(context))
                        val onionHost = TorManager.onionAddress.value?.takeIf { it.isNotBlank() }
                            ?: P2PPreferences.getTorOnionHostname(context)
                        if (!onionHost.isNullOrBlank()) {
                            put("onion_address", onionHost)
                        }
                    }
                    val payload = json.toString()
                    val expectedFingerprint = P2PPreferences.prefs(context)
                        .getString("peer_fingerprint_$peerName", null)
                        ?: if (isRawFingerprint(peerName)) peerName else null

                    if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
                        log(context, "Blocked profile share while a peer identity change awaits confirmation", "ERROR")
                        return@launch
                    }

                    val bridge = getBridge(context)
                    // Do NOT gate on isPeerOnline here: the name↔fp mapping may not yet be
                    // populated in the nanoseconds after onSessionEstablished fires, causing a
                    // false-negative that silently skips the avatar share.
                    // sendP2pMessage returns false when there is no active session; that is the
                    // correct authoritative gate and handles in-flight connection gracefully.
                    log(context, "Sending profile information to $peerName (length: ${payload.length})")
                    val success = bridge.sendP2pMessage(peerName, endpoint, payload, expectedFingerprint)
                    if (success) lastAvatarShareAt[shareKey] = System.currentTimeMillis()
                    if (!success) log(context, "Profile share deferred; $peerName is not reachable yet")
                    log(context, "Profile send status: $success")
                }
            } catch (e: Exception) {
                log(context, "Failed to share profile with a peer", "ERROR", e)
            } finally {
                scaledBitmap?.takeIf { it !== sourceBitmap && !it.isRecycled }?.recycle()
                sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
                avatarSharesInFlight.remove(shareKey)
            }
        }
    }

    fun shareAvatarWithConnectedPeers(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val sharedPrefs = P2PPreferences.prefs(appContext)
        val activeChats: Set<String> = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
        val allPeers: List<String> = (activeChats + _peerEndpoints.keys)
            .filter { it.isNotBlank() && !isPlaceholderPeerName(it) && !sharedPrefs.getBoolean("blocked_peer_$it", false) }
            .distinct()
        for (peerName in allPeers) {
            val endpoint = _peerEndpoints[peerName].orEmpty()
            shareAvatar(appContext, peerName, endpoint, force = force)
        }
    }

    fun shareOnionAddressWithKnownPeers(context: Context) {
        val appContext = context.applicationContext
        val sharedPrefs = P2PPreferences.prefs(appContext)
        val activeChats: Set<String> = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
        val allPeers: List<String> = (activeChats + _peerEndpoints.keys)
            .filter { it.isNotBlank() && !isPlaceholderPeerName(it) && !sharedPrefs.getBoolean("blocked_peer_$it", false) }
            .distinct()

        log(appContext, "Broadcasting updated Tor .onion address to ${allPeers.size} known peers")
        for (peerName in allPeers) {
            val ep = _peerEndpoints[peerName].orEmpty()
            shareOnionAddress(appContext, peerName, ep)
        }
    }

    fun broadcastOnionAddressUpdate(context: Context, newOnionAddress: String) {
        val appContext = context.applicationContext
        val sharedPrefs = P2PPreferences.prefs(appContext)
        val activeChats: Set<String> = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
        val allPeers: List<String> = (activeChats + _peerEndpoints.keys)
            .filter { peer ->
                peer.isNotBlank() && 
                !isPlaceholderPeerName(peer) && 
                !sharedPrefs.getBoolean("blocked_peer_$peer", false)
            }
            .distinct()

        log(appContext, "Broadcasting rotated Tor .onion address to ${allPeers.size} trusted non-blocked peers")
        val timestamp = System.currentTimeMillis() / 1000L
        val myFp = getBridge(appContext).getLocalFingerprint()
        val port = listenerPort(appContext)
        val canonicalData = "onion_update:$myFp:$newOnionAddress:$port:$timestamp"
        val sig = NativeBridge.signGroupPayload(canonicalData)

        val json = JSONObject().apply {
            put("type", "onion_address_update")
            put("onion_address", newOnionAddress)
            put("listener_port", port)
            put("fingerprint", myFp)
            put("timestamp", timestamp)
            put("signature", sig)
        }
        val payload = json.toString()

        for (peerName in allPeers) {
            relayScope.launch {
                try {
                    val expectedFingerprint = sharedPrefs.getString("peer_fingerprint_$peerName", null)
                        ?: if (isRawFingerprint(peerName)) peerName else null
                    val resolvedEndpoint = resolvePeerEndpoint(
                        peerName = peerName,
                        liveEndpoint = _peerEndpoints[peerName],
                        persistedEndpoint = sharedPrefs.getString(P2PPreferences.lastEndpoint(peerName), null),
                        onionEndpoint = P2PPreferences.getPeerOnionAddress(appContext, peerName),
                    ).orEmpty()
                    getBridge(appContext).sendP2pMessage(peerName, resolvedEndpoint, payload, expectedFingerprint)
                } catch (e: Exception) {
                    log(appContext, "Failed to send onion address update to $peerName: ${e.message}", "ERROR")
                }
            }
        }
    }

    fun shareOnionAddress(context: Context, peerName: String, endpoint: String = "") {
        val prefs = P2PPreferences.prefs(context)
        val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
        val shareKey = fingerprint ?: peerName
        val now = System.currentTimeMillis()
        if (!onionSharesInFlight.add(shareKey)) return
        if (now - (lastOnionShareAt[shareKey] ?: 0L) < 30_000L) {
            onionSharesInFlight.remove(shareKey)
            return
        }
        relayScope.launch {
            try {
                val onionHost = TorManager.onionAddress.value?.takeIf { it.isNotBlank() }
                    ?: prefs.getString(P2PPreferences.TOR_ONION_HOSTNAME, null)?.takeIf { it.isNotBlank() }
                if (onionHost.isNullOrBlank()) {
                    log(context, "Local Tor onion address is not available; skipping onion share")
                    return@launch
                }
                val json = JSONObject().apply {
                    put("type", "onion_address_share")
                    put("onion_address", onionHost)
                    put("listener_port", listenerPort(context))
                }
                val payload = json.toString()
                val expectedFingerprint = prefs.getString("peer_fingerprint_$peerName", null)
                    ?: if (isRawFingerprint(peerName)) peerName else null

                if (P2PPreferences.isPeerIdentityChangePending(context, peerName)) {
                    log(context, "Blocked onion share while a peer identity change awaits confirmation", "ERROR")
                    return@launch
                }

                val resolvedEndpoint = endpoint.ifBlank {
                    resolvePeerEndpoint(
                        peerName = peerName,
                        liveEndpoint = _peerEndpoints[peerName],
                        persistedEndpoint = prefs.getString(P2PPreferences.lastEndpoint(peerName), null),
                        onionEndpoint = P2PPreferences.getPeerOnionAddress(context, peerName),
                    ).orEmpty()
                }
                val bridge = getBridge(context)

                if (!bridge.isPeerOnline(peerName, expectedFingerprint) && resolvedEndpoint.isBlank()) {
                    log(context, "Peer $peerName is offline and has no reachable endpoint; onion address will be shared upon connection")
                    return@launch
                }

                log(context, "Sharing Tor .onion address with $peerName")
                val success = bridge.sendP2pMessage(peerName, resolvedEndpoint, payload, expectedFingerprint)
                if (success) lastOnionShareAt[shareKey] = System.currentTimeMillis()
                log(context, "Onion address share status: $success")
            } catch (e: Exception) {
                log(context, "Failed to share onion address with peer", "ERROR", e)
            } finally {
                onionSharesInFlight.remove(shareKey)
            }
        }
    }

    /**
     * Send an encrypted Double Ratchet message to a resolved peer's endpoint.
     */
    fun sendMessage(context: Context, endpoint: String, @Suppress("UNUSED_PARAMETER") senderName: String, text: String, onResult: (Boolean) -> Unit = {}) {
        outboundMessenger.sendMessage(context, endpoint, text, onResult)
    }

    /** Send to a known contact without ever interpreting its name or peer id as an endpoint. */
    fun sendMessageToPeer(
        context: Context,
        peerName: String,
        text: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        if (text.isNotBlank()) {
            val onionMatch = Regex("""([a-z2-7]{56}\.onion(?::\d+)?)""", RegexOption.IGNORE_CASE).find(text)
            if (onionMatch != null) {
                val onionAddr = onionMatch.value.trim()
                val formatted = com.example.twopchat.ui.main.formatInviteEndpoint(onionAddr, listenerPort(context))
                if (formatted != null && formatted.contains(".onion", ignoreCase = true) && P2PPreferences.getPeerOnionAddress(context, peerName) == null) {
                    P2PPreferences.setPeerOnionAddress(context, peerName, formatted)
                    val fingerprint = P2PPreferences.prefs(context)
                        .getString("peer_fingerprint_$peerName", null)
                    ChatDatabaseHelper.getInstance(context).savePeerOnionAddress(
                        peerName = peerName,
                        onionAddress = formatted,
                        fingerprint = fingerprint,
                        endpoint = _peerEndpoints[peerName],
                    )
                }
            }
        }
        outboundMessenger.sendMessageToPeer(context, peerName, text, onResult)
    }

    fun sendVerificationRequest(context: Context, peerName: String, onResult: (Boolean) -> Unit) {
        outboundMessenger.sendControlMessage(context, peerName, JSONObject().apply {
            put("type", "verification_request")
        }, onResult)
    }

    fun sendDirectWallpaperUpdate(context: Context, peerName: String, wallpaperBitmap: Bitmap?, dimming: Int, isBlur: Boolean = false) {
        relayScope.launch(Dispatchers.IO) {
            try {
                val b64 = if (wallpaperBitmap != null) {
                    val maxDimension = 1080
                    val scaled = if (wallpaperBitmap.width > maxDimension || wallpaperBitmap.height > maxDimension) {
                        val aspectRatio = wallpaperBitmap.width.toFloat() / wallpaperBitmap.height.toFloat()
                        val width = if (aspectRatio > 1) maxDimension else (maxDimension * aspectRatio).toInt()
                        val height = if (aspectRatio > 1) (maxDimension / aspectRatio).toInt() else maxDimension
                        Bitmap.createScaledBitmap(wallpaperBitmap, width, height, true)
                    } else {
                        wallpaperBitmap
                    }
                    val outputStream = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
                    val bytes = outputStream.toByteArray()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else ""

                val payload = JSONObject().apply {
                    put("type", "direct_wallpaper_update")
                    put("wallpaper_data", b64)
                    put("dimming", dimming)
                    put("is_blur", isBlur)
                }

                outboundMessenger.sendControlMessage(context, peerName, payload)
                log(context, "Sent direct_wallpaper_update to $peerName")
            } catch (e: Exception) {
                log(context, "Failed to send direct_wallpaper_update to $peerName: ${e.message}", "ERROR", e)
            }
        }
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
        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()).orEmpty()
        val newSet = activeSet.toMutableSet().apply { remove(peerName) }
        
        sharedPrefs.edit {
            putStringSet("active_chats", newSet)
            remove("last_msg_$peerName")
            remove("unread_count_$peerName")
            remove("draft_msg_$peerName")
            remove("pinned_chat_$peerName")
            remove(P2PPreferences.pinnedMessageId(peerName))
            remove(P2PPreferences.pinnedMessageText(peerName))
            remove(P2PPreferences.pinnedMessageSender(peerName))
            remove(P2PPreferences.pinnedBy(peerName))
            remove(P2PPreferences.pinnedStateVersion(peerName))
            remove(P2PPreferences.pinnedStateActor(peerName))
        }
        
        // Remove typing state
        peerTypingStates.remove(peerName)

        // Clear messages database and pending controls for this peer
        val db = ChatDatabaseHelper.getInstance(context)
        db.clearMessagesForPeer(peerName)
        db.deletePendingControlsForPeer(peerName)

        // Clear notification history
        MessageNotificationService.clearHistory(context, peerName)
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
        asDocument: Boolean = false,
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
            asDocument,
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
        val appContext = context.applicationContext
        outboundMessenger.cancelFile(
            appContext,
            peerName,
            messageId,
        )
        ChatDatabaseHelper.getInstance(appContext)
            .updateMessageStatus(messageId, "CANCELLED")
        val key = if (peerName.isNotEmpty()) "$peerName:$messageId" else messageId
        val cancelledProgress = FileProgressInfo(0L, 0L, 0.0, FileTransferState.CANCELLED)
        fileProgressStates[key] = cancelledProgress
        fileProgressStates[messageId] = cancelledProgress
        messageListeners.forEach { it.onMessageStatusChanged(peerName, messageId, "CANCELLED") }
        return true
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
            val bridge = getBridge(appContext)
            bridge.closePeerSession(peerName, oldFingerprint)
            val accepted = P2PPreferences.acceptPendingPeerIdentity(appContext, peerName)
            if (accepted == null) {
                serviceScope.launch(Dispatchers.Main) { onResult(false) }
                return@launch
            }
            val endpoint = accepted.endpoint.takeIf { it.isNotBlank() }
                ?: prefs.getString(P2PPreferences.lastEndpoint(peerName), null).orEmpty()
            if (endpoint.isNotBlank()) rememberAuthenticatedPeerEndpoint(peerName, endpoint)
            clearPeerPresenceImmediately(peerName)
            val success = endpoint.isNotBlank() &&
                bridge.reconnectPeerSession(peerName, endpoint, accepted.acceptedFingerprint)
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
            val bridge = getBridge(appContext)
            val success = endpoint.isNotBlank() && oldFingerprint.isNotBlank() &&
                bridge.reconnectPeerSession(peerName, endpoint, oldFingerprint)
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

internal fun isSelfDiscoveryEndpoint(endpoint: String, localHosts: Collection<String>): Boolean {
    val clean = endpoint.trim()
    val host = if (clean.startsWith("[")) {
        clean.substringAfter('[').substringBefore(']')
    } else {
        clean.substringBeforeLast(':', clean)
    }.substringBefore('%').trim().lowercase()
    return host.isNotEmpty() && localHosts.any {
        it.substringBefore('%').trim().trim('[', ']').lowercase() == host
    }
}
