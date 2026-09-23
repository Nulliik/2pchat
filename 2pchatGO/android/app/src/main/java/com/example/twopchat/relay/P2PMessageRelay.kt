package com.example.twopchat.relay

import com.example.twopchat.logging.SafeLog
import com.example.twopchat.AppLog
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.*
import com.example.twopchat.media.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.tor.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import androidx.core.graphics.scale
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.UUID
import android.util.Base64
import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import com.example.twopchat.bridge.BridgeMessageListener
import com.example.twopchat.bridge.BridgeSessionListener
import com.example.twopchat.bridge.IP2PBridge
import com.example.twopchat.bridge.P2PBridgeProvider
import com.example.twopchat.bridge.discoveryInfoHash
import com.example.twopchat.data.Localizations
import com.example.twopchat.group.runtime.GroupChatCoordinator
import com.example.twopchat.group.protocol.GroupWireProtocol
import com.example.twopchat.protocol.ProtocolVersionManager
import com.example.twopchat.service.OutboxWorkScheduler
import com.example.twopchat.ui.chat.state.ChatHistoryCache
import com.example.twopchat.ui.main.formatInviteEndpoint
import com.example.twopchat.yggdrasil.GlobalApplication

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
    internal val accountRuntime = AccountRuntimeGate()

    private fun <T> withAccountCallback(version: Long, rejected: T, action: () -> T): T =
        accountRuntime.run(version, rejected, action)

    internal fun closeAccountAdmission() {
        accountRuntime.close()
        ActiveChatStore.rejectAccountWork()
        while (incomingPersistenceQueue.tryReceive().isSuccess) {
            // Drain in-flight persistence tasks
        }
    }

    internal fun awaitAccountCallbacks(): Boolean = accountRuntime.awaitIdle()

    internal fun completeProfileInstallation(): Boolean = synchronized(startStopLock) {
        if (AccountDataWiper.isWiping || !accountRuntime.open()) return false
        ActiveChatStore.openAccount()
        true
    }

    private fun guardedMessageListener(version: Long, listener: BridgeMessageListener) =
        object : BridgeMessageListener {
            override fun onMessageReceived(sender: String, text: String) =
                withAccountCallback(version, Unit) { listener.onMessageReceived(sender, text) }

            override fun onFileProgress(sender: String, messageId: String, bytesTransferred: Long, totalBytes: Long, speedKbps: Double) =
                withAccountCallback(version, Unit) { listener.onFileProgress(sender, messageId, bytesTransferred, totalBytes, speedKbps) }
        }

    private fun guardedSessionListener(version: Long, listener: BridgeSessionListener) =
        object : BridgeSessionListener {
            override fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String, transport: String, aboutMe: String, seq: Long): Boolean =
                withAccountCallback(version, false) { listener.onSessionEstablished(peerName, fingerprint, endpoint, transport, aboutMe, seq) }

            override fun onSessionClosed(peerName: String, fingerprint: String, reason: String, seq: Long) =
                withAccountCallback(version, Unit) { listener.onSessionClosed(peerName, fingerprint, reason, seq) }

            override fun onPeerRoutesUpdated(peerName: String, fingerprint: String, endpoints: String) =
                withAccountCallback(version, Unit) { listener.onPeerRoutesUpdated(peerName, fingerprint, endpoints) }

            override fun onPeerDiscovered(infoHash: String, endpoint: String, source: String) =
                withAccountCallback(version, Unit) { listener.onPeerDiscovered(infoHash, endpoint, source) }
        }
    private val relayExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        SafeLog.e("P2PMessageRelay", "Uncaught exception in relay scope", throwable)
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

    @Volatile private var cachedAvatarBase64: String? = null
    @Volatile private var cachedAvatarLastModified: Long = -1L

    @Volatile private var storedAppContext: Context? = null

    internal fun getBridge(context: Context? = null): IP2PBridge {
        val ctx = context?.applicationContext
            ?: storedAppContext
            ?: runCatching {
                GlobalApplication.appContext
            }.getOrNull()
        checkNotNull(ctx) {
            "P2P bridge requested before the Android application context was initialized"
        }
        // NativeBridge owns process-wide JNI callback slots. Constructing an
        // unmanaged NativeBridgeImpl here replaces the provider instance's
        // message/session callbacks and causes successfully decrypted frames to
        // disappear before they reach the relay. Every caller must share the
        // provider singleton, including context-free maintenance callbacks.
        return P2PBridgeProvider.get(ctx)
    }

    // Album parts are sent sequentially from serviceScope.  Cancelling the
    // individual native transfers alone is not sufficient: their callbacks
    // can arrive later and otherwise make the coordinator start another part.
    private val mediaAlbumGenerations = ConcurrentHashMap<String, Long>()

    private fun nextMediaAlbumGeneration(albumId: String): Long =
        mediaAlbumGenerations.compute(albumId) { _, previous -> (previous ?: 0L) + 1L } ?: 1L

    private fun isMediaAlbumCancelled(albumId: String, generation: Long): Boolean =
        mediaAlbumGenerations[albumId] != generation

    @Volatile private var localPeerDiscovery: LocalPeerDiscovery? = null
    private data class LocalPeerCandidate(val fingerprint: String, val endpoint: String, val seenAt: Long = System.currentTimeMillis())

    private val localPeerCandidates =
        ConcurrentHashMap<String, CopyOnWriteArrayList<LocalPeerCandidate>>()

    /**
     * An address selected from an unsigned search result may be used exactly
     * for the first, user-initiated authenticated handshake.  It must not be
     * promoted to the persistent route store merely because a contact already
     * has a fingerprint (for example after restoring an old contact).
     */
    private data class BootstrapEndpointSet(val endpoints: List<String>, val expiresAtMs: Long)
    private val bootstrapEndpointSets = ConcurrentHashMap<String, BootstrapEndpointSet>()
    private const val BOOTSTRAP_ENDPOINT_TTL_MS = 30 * 60_000L

    /**
     * A tracker result is only a reachability hint. Before a chat exists we keep
     * it in this short-lived lookup table so the search UI can show it, without
     * promoting an unauthenticated address into persistent endpoint storage.
     */
    private data class PendingDiscoveryLookup(val peerName: String, val expiresAtMs: Long)
    private val pendingDiscoveryLookups = ConcurrentHashMap<String, PendingDiscoveryLookup>()
    private const val PENDING_DISCOVERY_LOOKUP_TTL_MS = 15_000L

    internal fun registerPendingDiscoveryLookup(infoHashes: Collection<String>, peerName: String) {
        val normalizedName = peerName.trim()
        if (normalizedName.isEmpty() || normalizedName.length > 160) return
        val expiresAt = System.currentTimeMillis() + PENDING_DISCOVERY_LOOKUP_TTL_MS
        infoHashes.forEach { rawHash ->
            val hash = rawHash.trim().lowercase(Locale.ROOT)
            if (hash.matches(Regex("[0-9a-f]{40}"))) {
                pendingDiscoveryLookups[hash] = PendingDiscoveryLookup(normalizedName, expiresAt)
            }
        }
    }

    private fun pendingLookupPeerName(infoHash: String): String? {
        val hash = infoHash.trim().lowercase(Locale.ROOT)
        val lookup = pendingDiscoveryLookups[hash] ?: return null
        if (System.currentTimeMillis() >= lookup.expiresAtMs) {
            pendingDiscoveryLookups.remove(hash, lookup)
            return null
        }
        return lookup.peerName
    }

    private fun localPeerCandidateKey(peerName: String): String =
        peerName.trim().lowercase(Locale.ROOT)

    /** Returns only short-lived endpoints explicitly selected from search. */
    internal fun bootstrapEndpointsForInitialHandshake(peerName: String, now: Long = System.currentTimeMillis()): List<String> {
        val key = localPeerCandidateKey(peerName)
        val routes = bootstrapEndpointSets[key] ?: return emptyList()
        if (now >= routes.expiresAtMs) {
            bootstrapEndpointSets.remove(key, routes)
            return emptyList()
        }
        return routes.endpoints
    }

    @Synchronized
    internal fun localDiscoveryEndpoints(peerName: String): List<String> {
        val key = localPeerCandidateKey(peerName)
        val rows = localPeerCandidates[key] ?: return emptyList()
        val now = System.currentTimeMillis()
        rows.removeAll { now - it.seenAt >= 30 * 60_000L }
        if (rows.isEmpty()) localPeerCandidates.remove(key)
        return rows.map { it.endpoint }.distinct().take(12)
    }

    @Synchronized
    fun injectLocalDiscoveryCandidate(peerName: String, peerFingerprint: String, endpoint: String) {
        val normalized = EndpointRetention.normalize(endpoint) ?: return
        val key = localPeerCandidateKey(peerName)
        if (localPeerCandidates.size >= 128 && !localPeerCandidates.containsKey(key)) {
            val oldestKey = localPeerCandidates.entries.minByOrNull { entry -> entry.value.maxOfOrNull { it.seenAt } ?: 0L }?.key
            if (oldestKey != null) localPeerCandidates.remove(oldestKey)
        }
        val candidates = localPeerCandidates.computeIfAbsent(key) { CopyOnWriteArrayList() }
        candidates.removeAll { it.fingerprint == peerFingerprint && it.endpoint == normalized }
        candidates.add(LocalPeerCandidate(peerFingerprint, normalized))
        while (candidates.size > 12) candidates.removeAt(0)
    }

    @Synchronized
    internal fun rememberAuthenticatedPeerEndpoint(peerName: String, endpoints: String, context: Context? = null, source: EndpointSource = EndpointSource.AUTHENTICATED, advertisedExpires: Long = 0): Boolean {
        val normalizedName = peerName.trim()
        val normalizedEndpoints = endpoints.trim()
        val endpointParts = normalizedEndpoints.split(',').map(String::trim).filter(String::isNotEmpty)
        if (normalizedName.isEmpty() || normalizedName.length > 160 ||
            endpointParts.isEmpty() || !isValidPeerEndpointList(normalizedEndpoints)) {
            return false
        }
        if (normalizedName !in _peerEndpoints && _peerEndpoints.size >= MAX_TRACKED_PEER_ENDPOINTS) {
            // This is only a UI projection. Persistent friend routes are never evicted here.
            _peerEndpoints.keys.firstOrNull { !com.example.twopchat.presence.PresenceRepository.isOnline(it) }?.let { _peerEndpoints.remove(it) }
        }
        val existingParts = _peerEndpoints[normalizedName]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val combined = (endpointParts + existingParts).distinct().take(EndpointRetention.MAX_PER_PEER)
        val joined = combined.joinToString(",")
        _peerEndpoints[normalizedName] = joined
        val ctx = context?.applicationContext ?: storedAppContext
        if (ctx != null && !isPlaceholderPeerName(normalizedName)) {
            val fp = P2PPreferences.getPeerFingerprint(ctx, normalizedName)
                ?: canonicalEndpointFingerprint(normalizedName)
            serviceScope.launch {
                if (fp != null) {
                    if (source != EndpointSource.MIGRATED) {
                        PeerEndpointStore.observe(ctx, normalizedName, fp, endpointParts, source, advertisedExpires)
                    }
                    val retained = PeerEndpointStore.candidates(ctx, normalizedName, fp, includeReserve = true,
                        legacyEndpoints = combined)
                    P2PPreferences.prefs(ctx).edit { putString("last_endpoint_$normalizedName", retained.joinToString(",")) }
                    runOnMain { replaceEndpointProjection(normalizedName, retained.joinToString(",")) }
                } else {
                    P2PPreferences.prefs(ctx).edit { putString("last_endpoint_$normalizedName", joined) }
                }
            }
        }
        return true
    }

    /** Keeps an unverified search endpoint in memory only for the initial handshake. */
    internal fun rememberBootstrapPeerEndpoint(peerName: String, endpoints: String): Boolean {
        val normalizedName = peerName.trim()
        val endpointParts = endpoints.split(',').map(String::trim).filter(String::isNotEmpty)
        if (normalizedName.isEmpty() || normalizedName.length > 160 ||
            endpointParts.isEmpty() || !isValidPeerEndpointList(endpoints)) return false
        val joined = endpointParts.distinct().take(EndpointRetention.MAX_PER_PEER).joinToString(",")
        _peerEndpoints[normalizedName] = joined
        bootstrapEndpointSets[localPeerCandidateKey(normalizedName)] = BootstrapEndpointSet(
            endpoints = endpointParts.mapNotNull(EndpointRetention::normalize).distinct().take(EndpointRetention.MAX_PER_PEER),
            expiresAtMs = System.currentTimeMillis() + BOOTSTRAP_ENDPOINT_TTL_MS,
        )
        return true
    }

    internal fun replaceEndpointProjection(peerName: String, endpoints: String) {
        if (endpoints.isBlank()) _peerEndpoints.remove(peerName)
        else if (peerName in _peerEndpoints || _peerEndpoints.size < MAX_TRACKED_PEER_ENDPOINTS) _peerEndpoints[peerName] = endpoints
    }

    fun listenerPort(context: Context): Int = P2PPreferences.listenerPort(context)

    fun getActivePeerNames(): List<String> =
        com.example.twopchat.presence.PresenceRepository.onlineNames()
            .filter { !isPlaceholderPeerName(it) }

    @Volatile
    private var cachedLocalIp: String? = null
    @Volatile
    private var cachedLocalIpExpiryMs: Long = 0L

    @Volatile
    private var cachedYggIp: String? = null
    @Volatile
    private var cachedYggIpExpiryMs: Long = 0L

    private const val IP_CACHE_TTL_MS = 15_000L

    fun getLocalIpAddress(@Suppress("UNUSED_PARAMETER") context: Context? = null): String {
        val now = System.currentTimeMillis()
        val cached = cachedLocalIp
        if (cached != null && now < cachedLocalIpExpiryMs) {
            return cached
        }
        val ip = try {
            var found = "127.0.0.1"
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        found = addr.hostAddress ?: "127.0.0.1"
                        break
                    }
                }
                if (found != "127.0.0.1") break
            }
            found
        } catch (_: Exception) {
            "127.0.0.1"
        }
        cachedLocalIp = ip
        cachedLocalIpExpiryMs = now + IP_CACHE_TTL_MS
        return ip
    }

    fun updateCachedYggdrasilAddress(ip: String) {
        val clean = ip.trim().split("%")[0]
        if (clean.isNotEmpty() && (clean.startsWith("0200:") || clean.startsWith("0300:") || clean.startsWith("200:") || clean.startsWith("300:"))) {
            cachedYggIp = clean
            cachedYggIpExpiryMs = System.currentTimeMillis() + IP_CACHE_TTL_MS
        }
    }

    fun getYggdrasilAddress(): String {
        val now = System.currentTimeMillis()
        val cached = cachedYggIp
        if (cached != null && cached.isNotEmpty() && now < cachedYggIpExpiryMs) {
            return cached
        }
        val ip = try {
            var found = ""
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val host = addr.hostAddress.orEmpty()
                    if (host.startsWith("0200:") || host.startsWith("0300:") || host.startsWith("200:") || host.startsWith("300:")) {
                        found = host.split("%")[0]
                        break
                    }
                }
                if (found.isNotEmpty()) break
            }
            if (found.isEmpty()) {
                val ctx = storedAppContext ?: runCatching { com.example.twopchat.yggdrasil.GlobalApplication.appContext }.getOrNull()
                if (ctx != null) {
                    val prefs = P2PPreferences.prefs(ctx)
                    val state = prefs.getString("yggdrasil_runtime_state", "")?.trim().orEmpty()
                    val runtimeIp = prefs.getString("yggdrasil_runtime_ip", "")?.trim().orEmpty()
                    if (runtimeIp.isNotEmpty() && (state.equals("enabled", ignoreCase = true) || state.equals("connected", ignoreCase = true))) {
                        found = runtimeIp
                    }
                }
            }
            found
        } catch (_: Exception) {
            ""
        }
        if (ip.isNotEmpty()) {
            cachedYggIp = ip
            cachedYggIpExpiryMs = now + IP_CACHE_TTL_MS
        } else {
            cachedYggIp = null
            cachedYggIpExpiryMs = 0L
        }
        return ip
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

    private fun refreshAnnouncementNow(context: Context): Boolean {
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
        // Also reset the onion-share cooldown so that a new .onion address
        // (e.g. after Tor restart) is sent to the peer on the very next
        // session establishment, even if the 30-second window has not elapsed.
        lastOnionShareAt.remove(peerKey)
        onionSharesInFlight.remove(peerKey)
    }

    private const val MAX_TRACKED_PEER_ENDPOINTS = 512
    private val _peerEndpoints = mutableStateMapOf<String, String>()
    val peerEndpoints: Map<String, String> get() = _peerEndpoints
    val peerConnectionTransports = mutableStateMapOf<String, String>()
    val peerRttMs = mutableStateMapOf<String, Long>()
    private const val YGG_SESSION_SETTLE_MS = 1_500L
    private val fingerprintToPeerName = ConcurrentHashMap<String, String>()
    private val avatarSharesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastAvatarShareAt = ConcurrentHashMap<String, Long>()
    private val lastProfileRequestAt = ConcurrentHashMap<String, Long>()
    private val onionSharesInFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastOnionShareAt = ConcurrentHashMap<String, Long>()

    fun getPeerTransportType(context: Context, peerName: String): TransportType {
        // Composition-safe: presence from the repository, never a JNI pull.
        val isOnline = com.example.twopchat.presence.PresenceRepository.isOnline(peerName)
        if (!isOnline) return TransportType.DISCONNECTED
        val fp = P2PPreferences.prefs(context).getString(P2PPreferences.peerFingerprint(peerName), null)
        val raw = peerConnectionTransports[peerName]
            ?: (if (!fp.isNullOrBlank()) peerConnectionTransports[fp] else null)
            ?: (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerConnectionTransports[it] })
        val ep = peerEndpoints[peerName]
            ?: (if (!fp.isNullOrBlank()) peerEndpoints[fp] else null)
            ?: (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerEndpoints[it] })
        return resolveTransportType(raw, ep, true)
    }

    fun getPeerTransportType(peerName: String): TransportType {
        // Composition-safe: presence from the repository, never a JNI pull.
        val isOnline = com.example.twopchat.presence.PresenceRepository.isOnline(peerName)
        if (!isOnline) return TransportType.DISCONNECTED
        val raw = peerConnectionTransports[peerName]
            ?: (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerConnectionTransports[it] })
        val ep = peerEndpoints[peerName]
            ?: (fingerprintToPeerName.entries.firstOrNull { it.value == peerName }?.key?.let { peerEndpoints[it] })
        return resolveTransportType(raw, ep, true)
    }

    /**
     * Live query into the Go core. Read-only by contract: the pull result is
     * never written back into presence state, so a stale pull cannot
     * overwrite a newer session event.
     */
    fun isPeerOnline(context: Context, peerName: String): Boolean {
        val fp = P2PPreferences.prefs(context).getString(P2PPreferences.peerFingerprint(peerName), null)
        return getBridge(context).isPeerOnline(peerName, fp)
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
        presenceVersion = { com.example.twopchat.presence.PresenceRepository.currentVersion(it) },
        onPeerObservedOnline = { context, peerName, transport, fingerprint, observedVersion ->
            publishPeerOnlineIfCurrent(
                context = context,
                peerName = peerName,
                transport = transport,
                fingerprint = fingerprint,
                expectedVersion = observedVersion,
            )
        },
        onPeerObservedOffline = { peerName, fingerprint, observedVersion ->
            schedulePeerOfflineIfCurrent(peerName, observedVersion, fingerprint)
        },
        log = ::log,
    )

    fun triggerMaintenanceWakeup(reason: String = "EVENT") {
        maintenanceCoordinator.triggerImmediateMaintenance(reason)
    }

    fun onScreenOff() {
        maintenanceCoordinator.onScreenOff()
        // Pause local mDNS discovery while screen is off to allow Wi-Fi chip to enter DTIM sleep.
        // Nulling the reference so startLocalDiscovery() creates a fresh NSD instance on resume.
        try {
            localPeerDiscovery?.stop()
            localPeerDiscovery = null
        } catch (e: Exception) {
            SafeLog.d(TAG, "Stopping localPeerDiscovery on screen off failed: ${e.javaClass.simpleName}")
        }
    }

    fun onScreenOn(context: Context? = null) {
        maintenanceCoordinator.onScreenOn(context)
        // Resume local mDNS discovery when screen turns back on if enabled
        context?.let { ctx ->
            try {
                val appContext = ctx.applicationContext
                if (isRunning && P2PPreferences.isWifiDiscoveryEnabled(appContext)) {
                    startLocalDiscovery(appContext, listenerPort(appContext))
                }
            } catch (e: Exception) {
                SafeLog.w(TAG, "Resuming localPeerDiscovery on screen on failed", e)
            }
        }
    }

    private fun publishPeerOnline(
        peerName: String,
        transport: String?,
        fingerprint: String = "",
        endpoint: String = "",
        seq: Long = 0,
    ) {
        // Alias binding + state write happen in one serialized Main block
        // inside the repository; no separate bindName here.
        com.example.twopchat.presence.PresenceRepository.observeOnline(
            peerName,
            transport,
            endpoint.ifBlank { null },
            seq,
            fingerprint.ifBlank { null },
        )
        if (transport != null) runOnMain {
            peerConnectionTransports[peerName] = transport
        }
    }

    private fun publishPeerOnlineIfCurrent(
        context: Context,
        peerName: String,
        transport: String?,
        expectedVersion: Long,
        fingerprint: String = "",
    ) {
        // Version-gated: if a session event landed between the pull and this
        // write, the CAS fails and the stale pull result is dropped.
        com.example.twopchat.presence.PresenceRepository.observeOnlineIfVersion(
            peerName,
            transport,
            peerEndpoints[peerName].orEmpty().ifEmpty { null },
            expectedVersion,
            fingerprint.ifBlank { null },
        )
        sendConnectedPeerHeartbeat(context, peerName)
        val endpoint = peerEndpoints[peerName].orEmpty()
        processOfflineQueue(context, peerName, endpoint)
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

    private fun clearPeerPresenceImmediately(peerName: String, fingerprint: String? = null) {
        com.example.twopchat.presence.PresenceRepository.observeOffline(
            peerName,
            immediate = true,
            fingerprint = fingerprint?.takeIf { it.isNotBlank() },
        )
    }

    private fun schedulePeerOfflineVerified(
        peerName: String,
        fingerprint: String?,
        verifyOnline: () -> Boolean,
        seq: Long = 0,
    ) {
        com.example.twopchat.presence.PresenceRepository.observeOffline(
            peerName,
            verifyOnline = verifyOnline,
            seq = seq,
            fingerprint = fingerprint?.takeIf { it.isNotBlank() },
        )
    }

    private fun schedulePeerOfflineIfCurrent(
        peerName: String,
        expectedVersion: Long,
        fingerprint: String = "",
    ) {
        // Version-gated pull flip; dropped if a newer event landed first.
        com.example.twopchat.presence.PresenceRepository.observeOfflineIfVersion(
            peerName,
            expectedVersion = expectedVersion,
            fingerprint = fingerprint.ifBlank { null },
        )
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

    internal val messageListeners = CopyOnWriteArrayList<MessageListener>()
    private val activeChatPeer = AtomicReference<String?>(null)
    private val activeChatPeerCounts = ConcurrentHashMap<String, AtomicInteger>()

    var activeChatPeerName: String?
        get() = activeChatPeer.get() ?: activeChatPeerCounts.keys.firstOrNull()
        set(value) {
            val old = activeChatPeer.getAndSet(value)
            if (old != null && old != value) {
                decrementActiveChatPeer(old)
            }
            if (value != null && old != value) {
                incrementActiveChatPeer(value)
            }
        }

    /**
     * Enters an active chat session for [peerName]. Returns an [AutoCloseable] token
     * that must be closed when the chat screen is paused or disposed.
     * Uses reference counting so overlapping screen transitions never prematurely clear the active chat.
     */
    fun enterActiveChat(peerName: String): AutoCloseable {
        incrementActiveChatPeer(peerName)
        activeChatPeer.set(peerName)
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                decrementActiveChatPeer(peerName)
                // After decrement, pick the first remaining active peer that is NOT peerName
                // (in case user fast-switched to a different peer while this screen was composing).
                // If peerName still has remaining tokens (e.g. another overlapping screen), keep it.
                val remaining = activeChatPeerCounts.keys.firstOrNull()
                if (remaining == null) {
                    activeChatPeer.compareAndSet(peerName, null)
                } else if (remaining != peerName) {
                    // Another peer's screen is active — point activeChatPeer at it
                    activeChatPeer.set(remaining)
                }
                // else: peerName still has tokens from another overlapping instance, leave activeChatPeer as-is
            }
        }
    }

    private fun incrementActiveChatPeer(peerName: String) {
        if (peerName.isBlank()) return
        activeChatPeerCounts.computeIfAbsent(peerName) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun decrementActiveChatPeer(peerName: String) {
        if (peerName.isBlank()) return
        val count = activeChatPeerCounts[peerName]?.decrementAndGet() ?: 0
        if (count <= 0) {
            activeChatPeerCounts.remove(peerName)
        }
    }

    /**
     * Checks whether a chat with [sender] is currently open and active in the foreground UI.
     * Matches via exact name, alias, or cryptographic fingerprint.
     */
    fun isChatOpenWith(context: Context, sender: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        val direct = activeChatPeer.get()
        if (!direct.isNullOrBlank() && P2PPreferences.isSamePeer(context, direct, sender)) {
            return true
        }
        return activeChatPeerCounts.keys.any { activePeer ->
            P2PPreferences.isSamePeer(context, activePeer, sender)
        }
    }

    internal fun resetActiveChatForTests() {
        activeChatPeer.set(null)
        activeChatPeerCounts.clear()
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
        if (!enabled || P2PPreferences.isTorEnabled(appContext)) {
            localPeerDiscovery?.stop()
            localPeerCandidates.clear()
            return
        }
        if (!isRunning) return
        startLocalDiscovery(appContext, listenerPort(appContext))
    }

    private fun startLocalDiscovery(context: Context, port: Int) {
        if (P2PPreferences.isTorEnabled(context)) {
            localPeerDiscovery?.stop()
            return
        }
        val prefs = P2PPreferences.prefs(context)
        if (!prefs.getBoolean(P2PPreferences.WIFI_DISCOVERY, true)) return
        val username = prefs.getString("username_profile", "").orEmpty()
        val fingerprint = getBridge(context).getLocalFingerprint()
        if (username.isBlank() || fingerprint.length < 40) return
        val discovery = localPeerDiscovery ?: LocalPeerDiscovery(context) { _, discoveryToken, endpoint ->
            val knownPeer = P2PPreferences.findPeerByDiscoveryToken(context, discoveryToken)
            if (knownPeer != null) {
                val (authenticatedName, peerFingerprint) = knownPeer
                injectLocalDiscoveryCandidate(authenticatedName, peerFingerprint, endpoint)
                rememberAuthenticatedPeerEndpoint(authenticatedName, endpoint, context, EndpointSource.DISCOVERY)
                getBridge(context).reconnectPeerSessionInBackground(authenticatedName, endpoint, peerFingerprint)
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

    internal data class IncomingPersistenceTask(
        val context: Context,
        val prefs: android.content.SharedPreferences,
        val lifecycle: ActiveChatStore.Task,
        val message: Message,
        val notificationText: String,
        val countAsNew: Boolean,
        val preview: LastMessagePreviewStore.Update,
    )

    private val incomingPersistenceQueue = Channel<IncomingPersistenceTask>(Channel.UNLIMITED)
    private val incomingBatchProcessorStarted = AtomicBoolean(false)

    private fun ensureIncomingBatchProcessorStarted() {
        if (incomingBatchProcessorStarted.compareAndSet(false, true)) {
            serviceScope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val firstTask = incomingPersistenceQueue.receive()
                        val batch = mutableListOf(firstTask)
                        val deadline = System.currentTimeMillis() + 35L
                        while (batch.size < 50) {
                            val next = incomingPersistenceQueue.tryReceive().getOrNull()
                            if (next != null) {
                                batch.add(next)
                            } else {
                                val remaining = deadline - System.currentTimeMillis()
                                if (remaining > 0) {
                                    delay(minOf(remaining, 10L))
                                    val waited = incomingPersistenceQueue.tryReceive().getOrNull()
                                    if (waited != null) {
                                        batch.add(waited)
                                    } else {
                                        break
                                    }
                                } else {
                                    break
                                }
                            }
                        }
                        processIncomingPersistenceBatch(batch)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        SafeLog.e(TAG, "Error processing incoming persistence batch", e)
                    }
                }
            }
        }
    }

    internal fun processIncomingPersistenceBatch(
        batch: List<IncomingPersistenceTask>,
        saveMessages: (Context, String, List<Message>) -> Unit = { context, sender, messages ->
            ChatDatabaseHelper.getInstance(context).saveMessages(sender, messages)
        },
        encrypt: (String) -> String = SecureStorage::encrypt,
        chatOpen: (Context, String) -> Boolean = ::isChatOpenWith,
        notify: (Context, String, Message, String) -> Unit = ::showNotification,
        cancelNotification: (Context, String) -> Unit = MessageNotificationService::cancelNotificationForPeer,
    ): Unit = synchronized(ActiveChatStore.persistenceLock) {
        if (batch.isEmpty() || ActiveChatStore.accountClosed) return
        val bySender = batch.mapNotNull { task ->
            ActiveChatStore.resolve(task.lifecycle)?.let { (task.prefs to it) to task }
        }.groupBy({ it.first }, { it.second })
        for ((key, senderTasks) in bySender) {
            val (prefs, sender) = key
            val context = senderTasks.first().context
            if (ActiveChatStore.isRetired(prefs)) continue
            val isLocked = P2PPreferences.isAppLocked()
            val persistHistory = prefs.getBoolean("persist_chat_history", true)

            if (!isLocked && persistHistory) {
                try {
                    val messages = senderTasks.map { it.message }
                    saveMessages(context, sender, messages)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log(context, "Failed to persist incoming message batch: ${e.message}", "ERROR", e)
                }
            }

            val latestTask = senderTasks.last()
            val latestNotificationText = latestTask.notificationText
            val currentActivePeer = activeChatPeerName
            val isChatOpenWithSender = chatOpen(context, sender)

            val newCount = senderTasks.count { it.countAsNew }
            val unreadKey = P2PPreferences.unreadCount(sender)

            if (!isLocked) {
                senderTasks.forEach { LastMessagePreviewStore.persist(prefs, sender, it.preview, encrypt) }
            }
            ActiveChatStore.update(prefs, { it + sender }) {
                if (newCount > 0 && !isChatOpenWithSender) {
                    putInt(unreadKey, prefs.getInt(unreadKey, 0) + newCount)
                }
            }

            if (newCount > 0 && !isChatOpenWithSender) {
                notify(context, sender, latestTask.message, latestNotificationText)
            } else {
                cancelNotification(context, sender)
                if (currentActivePeer != null) {
                    cancelNotification(context, currentActivePeer)
                }
            }
        }
    }

    internal fun prepareIncomingPersistence(
        context: Context,
        sender: String,
        message: Message,
        notificationText: String = message.text,
        countAsNew: Boolean = true,
    ): IncomingPersistenceTask? = synchronized(ActiveChatStore.persistenceLock) {
        if (ActiveChatStore.accountClosed) return null
        val prefs = P2PPreferences.prefs(context)
        val lifecycle = ActiveChatStore.admit(prefs, sender) ?: return null
        val canonicalName = ActiveChatStore.resolve(lifecycle) ?: return null
        IncomingPersistenceTask(
            context, prefs, lifecycle, message, notificationText, countAsNew,
            LastMessagePreviewStore.publish(canonicalName, notificationText),
        )
    }

    internal fun persistAndDispatchIncoming(
        context: Context,
        sender: String,
        message: Message,
        notificationText: String = message.text,
        countAsNew: Boolean = true,
    ) {
        val task = prepareIncomingPersistence(context, sender, message, notificationText, countAsNew) ?: return
        runOnMain {
            synchronized(ActiveChatStore.persistenceLock) {
                val currentSender = ActiveChatStore.resolve(task.lifecycle) ?: return@synchronized
                messageListeners.forEach { it.onMessageReceived(currentSender, message) }
            }
        }
        ensureIncomingBatchProcessorStarted()
        incomingPersistenceQueue.trySend(task)
        if (!message.isMe && message.id.isNotBlank()) {
            sendDeliveryReceipt(context, sender, peerEndpoints[sender], message.id)
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

    /**
     * An inbound authenticated session does not initially carry a nickname.  Its
     * fingerprint is nevertheless a usable routing key for the profile exchange.
     * Do not schedule that exchange if the JNI callback lost a race with the
     * disconnect callback: profile data must only be sent over a currently-live
     * ratchet session.
     */
    internal fun shouldBootstrapUnnamedSessionProfile(
        resolvedPeerName: String,
        fingerprint: String,
        sessionStillOnline: Boolean,
    ): Boolean =
        isPlaceholderPeerName(resolvedPeerName) &&
            isRawFingerprint(fingerprint) &&
            sessionStillOnline

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
            val existingTargetAboutMe = sharedPrefs.getString("peer_about_me_$targetKey", null)
            if (existingTargetAboutMe != cleanAboutMe) {
                val db = ChatDatabaseHelper.getInstance(context)
                val keys = linkedSetOf<String>()
                keys.add(targetKey)
                val baseTarget = targetKey.substringBefore("#").trim()
                if (baseTarget.isNotEmpty()) keys.add(baseTarget)
                if (cleanNickname != null) {
                    keys.add(cleanNickname)
                    val baseClean = cleanNickname.substringBefore("#").trim()
                    if (baseClean.isNotEmpty()) keys.add(baseClean)
                }
                if (sender.isNotBlank()) {
                    keys.add(sender)
                    val baseSender = sender.substringBefore("#").trim()
                    if (baseSender.isNotEmpty()) keys.add(baseSender)
                }
                val currentFp = sharedPrefs.getString("peer_fingerprint_$targetKey", null)
                    ?: if (isRawFingerprint(targetKey)) targetKey else null
                if (currentFp != null) {
                    keys.add(currentFp)
                }
                sharedPrefs.edit {
                    for (k in keys) {
                        if (k.isNotEmpty()) {
                            val currentVal = sharedPrefs.getString("peer_about_me_$k", null)
                            if (currentVal != cleanAboutMe) {
                                putString("peer_about_me_$k", cleanAboutMe)
                                db.savePeerAboutMe(k, cleanAboutMe)
                            }
                        }
                    }
                }
            }
        }

        if (cleanNickname != null && cleanNickname != targetKey) {
            val currentFp = sharedPrefs.getString("peer_fingerprint_$targetKey", null)
                ?: if (isRawFingerprint(targetKey)) targetKey else null

            if (isPlaceholderPeerName(targetKey) || targetKey == currentFp) {
                log(context, "Migrating placeholder/fingerprint peer $targetKey to received nickname: $cleanNickname")
                if (currentFp != null) {
                    sharedPrefs.edit { putString("peer_fingerprint_$cleanNickname", currentFp) }
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
                com.example.twopchat.presence.PresenceRepository.rename(oldName, cleanNewName, fp)

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
                    } catch (e: Exception) {
                        SafeLog.w(TAG, "Failed saving persisted avatar during peer rename", e)
                    }
                }
            }
        }
        return true
    }

    /**
     * A peer nickname is metadata authenticated by its session fingerprint.  If that
     * peer was previously saved under another nickname, keep a single contact record
     * and carry the local chat state forward to the newly announced nickname.
     */
    fun adoptAuthenticatedPeerNickname(context: Context, fingerprint: String, nickname: String) {
        val cleanFingerprint = fingerprint.trim()
        val cleanNickname = nickname.trim()
        if (cleanFingerprint.isBlank() || cleanNickname.isBlank() || isPlaceholderPeerName(cleanNickname)) {
            return
        }

        val aliases = synchronized(identityLock) {
            P2PPreferences.prefs(context).all.entries
                .asSequence()
                .filter { (key, value) ->
                    key.startsWith("peer_fingerprint_") &&
                        value == cleanFingerprint &&
                        key.removePrefix("peer_fingerprint_") != cleanNickname
                }
                .map { (key, _) -> key.removePrefix("peer_fingerprint_") }
                .filter { it.isNotBlank() }
                .toList()
        }

        aliases.forEach { alias ->
            renamePeer(context, alias, cleanNickname)
        }
        synchronized(identityLock) {
            fingerprintToPeerName[cleanFingerprint] = cleanNickname
        }
        P2PPreferences.updateFingerprintCache(cleanFingerprint, cleanNickname)
    }

    fun sanitizeAndMergeDanglingChats(context: Context) {
        try {
            val prefs = P2PPreferences.prefs(context)
            val validChats = ActiveChatStore.update(prefs, { chats ->
                chats.filter { P2PPreferences.isValidPeerChatName(it) }.toSet()
            })
            val dangling = validChats.filter {
                it.endsWith(" ·") || it.endsWith(" · ") || it.endsWith(" .") || it.endsWith(" . ")
            }
            if (dangling.isEmpty()) return
            for (danglingName in dangling) {
                val cleanName = danglingName.substringBefore(" ·").substringBefore(" .").trim()
                if (cleanName.isNotBlank() && cleanName != danglingName) {
                    moveChatState(context, danglingName, cleanName)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            SafeLog.w(TAG, "Failed cleaning dangling chat states during nickname adoption", e)
        }
    }

    internal fun moveChatState(
        context: Context,
        fromName: String,
        toName: String,
        migrateHistory: (Context, String, String, String?) -> Unit = ::migrateChatHistory,
    ): Unit = synchronized(ActiveChatStore.persistenceLock) {
        if (fromName == toName || ActiveChatStore.accountClosed) return
        val sharedPrefs = P2PPreferences.prefs(context)
        if (!ActiveChatStore.redirect(sharedPrefs, fromName, toName)) return
        var movedAboutMe: String? = null
        ActiveChatStore.update(sharedPrefs, { chats ->
            if (fromName in chats) chats - fromName + toName else chats
        }) {
        val editor = this
        val sourcePreviewWins = LastMessagePreviewStore.move(fromName, toName)

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
                    if (sourcePreviewWins || !sharedPrefs.contains("$prefix$toName")) {
                        sharedPrefs.getString("$prefix$fromName", null)?.let {
                            editor.putString("$prefix$toName", it)
                        }
                    }
                }
                "peer_about_me_" -> {
                    val value = sharedPrefs.getString("$prefix$fromName", null)?.trim()?.takeIf { it.isNotBlank() }
                    val existing = sharedPrefs.getString("$prefix$toName", null)?.trim()?.takeIf { it.isNotBlank() }
                    if (value != null && (existing.isNullOrBlank() || !sharedPrefs.contains("$prefix$toName"))) {
                        editor.putString("$prefix$toName", value)
                        movedAboutMe = value
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
        }

        try {
            val wpDir = File(context.filesDir, "direct_wallpapers")
            val oldWpFile = File(wpDir, "wallpaper_$fromName.jpg")
            val newWpFile = File(wpDir, "wallpaper_$toName.jpg")
            if (oldWpFile.exists() && !newWpFile.exists()) {
                oldWpFile.renameTo(newWpFile)
            }
        } catch (e: Exception) {
            SafeLog.d(TAG, "Migrating chat wallpaper failed: ${e.javaClass.simpleName}")
        }

        try {
            migrateHistory(context, fromName, toName, movedAboutMe)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log(context, "Failed to migrate chat history between peer aliases", "ERROR", e)
        }
    }

    private fun migrateChatHistory(context: Context, fromName: String, toName: String, aboutMe: String?) {
        val db = ChatDatabaseHelper.getInstance(context)
        db.renamePeer(fromName, toName)
        if (aboutMe != null) db.savePeerAboutMe(toName, aboutMe)
        refreshLastMessageFromHistory(context, db, toName)
    }

    private fun refreshLastMessageFromHistory(
        context: Context,
        db: ChatDatabaseHelper,
        peerName: String,
    ) {
        refreshLastMessageFromHistory(P2PPreferences.prefs(context), peerName, { db.getLastMessageForPeer(peerName) })
    }

    internal fun refreshLastMessageFromHistory(
        prefs: android.content.SharedPreferences,
        peerName: String,
        load: () -> Message?,
        encrypt: (String) -> String = SecureStorage::encrypt,
    ) {
        if (!prefs.getBoolean("persist_chat_history", true)) return
        val revision = LastMessagePreviewStore.historyRevision(prefs, peerName) ?: return
        val latest = load() ?: return
        val preview = if (latest.isMe) "You: ${latest.text}" else latest.text
        LastMessagePreviewStore.refreshFromHistory(prefs, peerName, revision, preview, encrypt)
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
                        P2PPreferences.prefs(context).edit { putString("peer_fingerprint_$byEp", fingerprint) }
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
                            com.example.twopchat.presence.PresenceRepository.forget(peerName)
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

        val allEntries = runCatching { prefs.all }.getOrNull().orEmpty()
        val canonicalIdentities = allEntries.entries.mapNotNull { (key, value) ->
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
        val fullMsg = if (error != null) "$message: ${SafeLog.getStackTraceString(error)}" else message
        when (level) {
            "ERROR" -> SafeLog.e(TAG, fullMsg)
            "WARN" -> SafeLog.w(TAG, fullMsg)
            "DEBUG" -> SafeLog.d(TAG, fullMsg)
            else -> SafeLog.i(TAG, fullMsg)
        }
        // Only write operational/diagnostic logs to the persistent on-disk app.log file.
        // High-frequency DEBUG events (e.g. packet progress, frame pings) are kept in logcat only.
        if (level != "DEBUG") {
            try {
                val timestamp = logTimestampFormatter.get()?.format(Date()).orEmpty()
                AppLog.append(context, "$timestamp [KOTLIN_$level] $TAG: $fullMsg\n")
            } catch (e: Exception) {
                SafeLog.e(TAG, "Failed to append diagnostic log", e)
            }
        }
    }

    /**
     * Start the background Native Go P2P server.
     */
    fun startServer(context: Context) {
        val startup = synchronized(startStopLock) {
            if (isRunning || AccountLifecycle.mutations.isBusy || ActiveChatStore.accountClosed) return
            val admission = accountRuntime.enter() ?: return
            isRunning = true
            admission
        }
        startup.use {
            startAdmittedServer(context, it.version)
        }
    }

    private fun startAdmittedServer(context: Context, listenerVersion: Long) {
        val appContext = context.applicationContext
        loadPersistedAvatars(appContext)
        migratePersistedPlaceholderChats(appContext)
        val persistedPrefs = P2PPreferences.prefs(appContext)
        val db = ChatDatabaseHelper.getInstance(appContext)
        val restoreRevision = ActiveChatStore.revision()
        val dbChats = try {
            db.getAllChatPeerNames()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptySet()
        }
        val persistedChats = ActiveChatStore.mergeIfUnchanged(persistedPrefs, restoreRevision, dbChats)
        if (persistedPrefs.getBoolean("persist_chat_history", true)) {
            for (peerName in persistedChats) {
                refreshLastMessageFromHistory(appContext, db, peerName)
            }
        }
        for (peerName in persistedChats) {
            persistedPrefs.getString("last_endpoint_$peerName", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { rememberAuthenticatedPeerEndpoint(peerName, it, appContext, EndpointSource.MIGRATED) }
            val savedOnion = P2PPreferences.getPeerOnionAddress(appContext, peerName)
            if (savedOnion != null) {
                rememberAuthenticatedPeerEndpoint(peerName, savedOnion, appContext, EndpointSource.MIGRATED)
            }
            val savedYgg = P2PPreferences.getPeerYggdrasilAddress(appContext, peerName)
            if (savedYgg != null) {
                rememberAuthenticatedPeerEndpoint(peerName, savedYgg, appContext, EndpointSource.MIGRATED)
            }
        }
        synchronized(identityLock) {
            for (peerName in persistedChats) {
                if (peerName.isNotBlank() && !isPlaceholderPeerName(peerName)) {
                    val fp = persistedPrefs.getString("peer_fingerprint_$peerName", null)
                    if (!fp.isNullOrBlank()) {
                        fingerprintToPeerName[fp] = peerName
                        getBridge(appContext).updatePeerNameMapping(fp, peerName)
                        com.example.twopchat.presence.PresenceRepository.bindName(peerName, fp)
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
            
            // Register incoming message callback
            bridge.registerMessageListener(guardedMessageListener(listenerVersion, object : BridgeMessageListener {
                override fun onFileProgress(sender: String, messageId: String, bytesTransferred: Long, totalBytes: Long, speedKbps: Double) {
                    val resolved = if (isRawFingerprint(sender)) {
                        P2PPreferences.findPeerNameByFingerprint(appContext, sender) ?: sender
                    } else sender
                    val key = "$sender:$messageId"
                    val resolvedKey = "$resolved:$messageId"
                    val isComplete = totalBytes > 0L && bytesTransferred >= totalBytes
                    if (isComplete && messageId.isNotEmpty()) {
                        outboundMessenger.cleanupTempSanitizedFile(messageId)
                    }
                    val info = FileProgressInfo(
                        bytesTransferred = bytesTransferred,
                        totalBytes = totalBytes,
                        speedKbps = speedKbps,
                        state = if (isComplete) {
                            FileTransferState.COMPLETED
                        } else {
                            FileTransferState.TRANSFERRING
                        },
                    )
                    serviceScope.launch(Dispatchers.Main) {
                        val existing = fileProgressStates[key] ?: fileProgressStates[resolvedKey] ?: fileProgressStates[messageId]
                        if (existing?.state == FileTransferState.CANCELLED) {
                            return@launch
                        }
                        if (messageId.isNotEmpty()) {
                            fileProgressStates[key] = info
                            fileProgressStates[resolvedKey] = info
                            fileProgressStates[messageId] = info
                        }
                        messageListeners.forEach {
                            it.onFileProgress(resolved, messageId, bytesTransferred, totalBytes, speedKbps)
                            if (resolved != sender) {
                                it.onFileProgress(sender, messageId, bytesTransferred, totalBytes, speedKbps)
                            }
                        }
                    }
                }

                override fun onMessageReceived(sender: String, text: String) {
                    log(appContext, "Incoming secure P2P message (${text.toByteArray().size} bytes)", "DEBUG")
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
                            val json = JSONObject(trimmed)
                            val payloadNickname = json.optString("nickname").ifEmpty { json.optString("sender").ifEmpty { json.optString("sender_name") } }
                            if (payloadNickname.isNotBlank() && isValidNickname(payloadNickname)) {
                                if (resolvedSender == sender || isPlaceholderPeerName(resolvedSender) || isRawFingerprint(resolvedSender)) {
                                    if (isRawFingerprint(sender)) {
                                        if (!P2PPreferences.publishPeerIdentityIfExpected(appContext, payloadNickname, sender)) return
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
                                "heartbeat" -> return
                                "profile_request" -> {
                                    val requester = payloadNickname.ifBlank { resolvedSender }
                                    log(appContext, "Received profile request from $requester, replying with full profile", "INFO", null)
                                    shareAvatar(appContext, requester, force = true)
                                    return
                                }
                                "identity_info" -> {
                                    val nickname = json.optString("nickname").trim().takeIf { it.isNotBlank() }
                                    val rawFp = json.optString("fingerprint").trim()
                                    val fingerprint = rawFp.takeIf { isRawFingerprint(it) } ?: (if (isRawFingerprint(sender)) sender else "")
                                    val effectiveName = nickname ?: resolvedSender
                                    if (nickname != null) {
                                        if (fingerprint.isNotBlank()) {
                                            if (!P2PPreferences.publishPeerIdentityIfExpected(appContext, nickname, fingerprint)) return
                                            getBridge(appContext).updatePeerNameMapping(fingerprint, nickname)
                                        }
                                        handlePeerNicknameReceived(appContext, sender, nickname, json.optString("about_me"))
                                    }
                                    val rawOnion = json.optString("onion_address").trim()
                                    val onionPort = json.optInt("listen_port", if (json.has("listener_port")) json.optInt("listener_port") else 50001)
                                    if (rawOnion.isNotEmpty() && rawOnion.contains(".onion", ignoreCase = true)) {
                                        val formatted = formatInviteEndpoint(rawOnion, onionPort)
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
                                         if (!P2PPreferences.publishPeerIdentityIfExpected(appContext, nickname, sender)) return
                                    }
                                    val trimmedBody = body.trim()
                                    if (trimmedBody.startsWith("{")) {
                                        onMessageReceived(effectiveSender, trimmedBody)
                                        return
                                    }
                                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    val rxMsg = Message(
                                        id = msgId.ifEmpty { UUID.randomUUID().toString() },
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
                                    val caption = json.optString("caption").ifBlank { json.optString("emoji") }.ifBlank { json.optString("text") }.trim()
                                    val displayMsg = caption.ifBlank { VoiceMessageSupport.displayMessage(attachmentType, fileName) }
                                    val offerMessage = Message(
                                        id = messageId,
                                        text = displayMsg,
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
                                        notificationText = Localizations.tr(
                                            P2PPreferences.getAppLanguage(appContext),
                                            ru = "Началось получение файла: %s",
                                            en = "Receiving file: %s",
                                            de = "Dateiempfang gestartet: %s",
                                            es = "Recibiendo archivo: %s",
                                            fr = "Réception du fichier : %s",
                                            pt = "Recebendo arquivo: %s",
                                            tr = "Dosya alınıyor: %s"
                                        ).replace("%s", fileName),
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
                                    val nickname = json.optString("nickname").ifEmpty { json.optString("sender") }.takeIf { it.isNotBlank() }
                                    val aboutMe = json.optString("about_me").takeIf { it.isNotBlank() }
                                    val fingerprint = json.optString("fingerprint").takeIf { it.isNotBlank() }
                                    val effectiveName = nickname ?: resolvedSender
                                    val discCode = json.optString("discovery_code").takeIf { it.isNotBlank() }
                                    if (discCode != null) {
                                        sharedPrefs.edit {
                                            putString("discovery_code_$effectiveName", discCode)
                                            val baseEff = effectiveName.substringBefore("#").trim()
                                            if (baseEff.isNotEmpty()) putString("discovery_code_$baseEff", discCode)
                                            if (sender.isNotBlank()) {
                                                putString("discovery_code_$sender", discCode)
                                                val baseSender = sender.substringBefore("#").trim()
                                                if (baseSender.isNotEmpty()) putString("discovery_code_$baseSender", discCode)
                                            }
                                            if (fingerprint != null) {
                                                putString("discovery_code_$fingerprint", discCode)
                                            }
                                        }
                                    }
                                    if (fingerprint != null && nickname != null) {
                                        if (!P2PPreferences.publishPeerIdentityIfExpected(appContext, nickname, fingerprint)) return
                                        getBridge(appContext).updatePeerNameMapping(fingerprint, nickname)
                                    }
                                    handlePeerNicknameReceived(appContext, sender, nickname, aboutMe)

                                    val b64 = json.optString("avatar_base64")
                                    // Avatars are control-plane thumbnails, not file transfers. Bound their
                                    // encoded size and decoded dimensions before allocating a full bitmap.
                                    if (b64.length in 1..2_000_000) {
                                        try {
                                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                            if (bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
                                                bounds.outWidth.toLong() * bounds.outHeight.toLong() <= 16_000_000L) {
                                                var sample = 1
                                                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                                                val bitmap = BitmapFactory.decodeByteArray(
                                                    bytes, 0, bytes.size,
                                                    BitmapFactory.Options().apply { inSampleSize = sample }
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
                                    val formattedOnion = formatInviteEndpoint(rawOnion, port)
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
                                    if (b64.length in 1..6_000_000 && b64.isNotBlank()) {
                                        try {
                                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                                            val dir = File(appContext.filesDir, "direct_wallpapers").also { it.mkdirs() }
                                            val destFile = File(dir, "wallpaper_$resolvedSender.jpg")
                                            destFile.writeBytes(bytes)
                                            if (resolvedSender != sender) {
                                                try {
                                                    File(dir, "wallpaper_$sender.jpg").writeBytes(bytes)
                                                } catch (e: Exception) {
                                                    SafeLog.d(TAG, "Failed writing alias wallpaper for $sender: ${e.javaClass.simpleName}")
                                                }
                                            }

                                            val baseSender = resolvedSender.substringBefore("#").substringBefore(" · ").trim()
                                            if (baseSender.isNotEmpty() && baseSender != resolvedSender) {
                                                try {
                                                    File(dir, "wallpaper_$baseSender.jpg").writeBytes(bytes)
                                                } catch (e: Exception) {
                                                    SafeLog.d(TAG, "Failed writing baseSender wallpaper: ${e.javaClass.simpleName}")
                                                }
                                                P2PPreferences.setDirectWallpaper(appContext, baseSender, destFile.absolutePath, dimming, isBlur)
                                            }
                                            val lowerSender = resolvedSender.lowercase()
                                            if (lowerSender != resolvedSender && lowerSender != baseSender) {
                                                try {
                                                    File(dir, "wallpaper_$lowerSender.jpg").writeBytes(bytes)
                                                } catch (e: Exception) {
                                                    SafeLog.d(TAG, "Failed writing lowercase alias wallpaper: ${e.javaClass.simpleName}")
                                                }
                                            }

                                            P2PPreferences.setDirectWallpaper(appContext, resolvedSender, destFile.absolutePath, dimming, isBlur)
                                            if (resolvedSender != sender) {
                                                P2PPreferences.setDirectWallpaper(appContext, sender, destFile.absolutePath, dimming, isBlur)
                                            }

                                            val lang = P2PPreferences.getAppLanguage(appContext)
                                            val wallpaperSetText = Localizations.tr(
                                                lang,
                                                ru = "Собеседник установил(а) новые обои для этого чата",
                                                en = "Your peer set a new wallpaper for this chat",
                                                de = "Dein Chatpartner hat ein neues Hintergrundbild festgelegt",
                                                es = "Tu contacto estableció un nuevo fondo para este chat",
                                                fr = "Votre interlocuteur a défini un nouveau fond d'écran pour ce chat",
                                                pt = "Seu contato definiu um novo papel de parede para este chat",
                                                tr = "Eşiniz bu sohbet için yeni bir duvar kağıdı ayarladı"
                                            )
                                            val sysMsg = Message(
                                                id = UUID.randomUUID().toString(),
                                                text = wallpaperSetText,
                                                isMe = false,
                                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                                                attachmentType = "SYSTEM"
                                            )
                                            persistAndDispatchIncoming(appContext, resolvedSender, sysMsg, notificationText = wallpaperSetText, countAsNew = false)
                                            if (resolvedSender != sender) {
                                                persistAndDispatchIncoming(appContext, sender, sysMsg, notificationText = wallpaperSetText, countAsNew = false)
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

                                            val lang = P2PPreferences.getAppLanguage(appContext)
                                            val wallpaperRemovedText = Localizations.tr(
                                                lang,
                                                ru = "Собеседник удалил(а) обои для этого чата",
                                                en = "Your peer removed the wallpaper for this chat",
                                                de = "Dein Chatpartner hat das Hintergrundbild entfernt",
                                                es = "Tu contacto eliminó el fondo para este chat",
                                                fr = "Votre interlocuteur a supprimé le fond d'écran pour ce chat",
                                                pt = "Seu contato removeu o papel de parede deste chat",
                                                tr = "Eşiniz bu sohbetin duvar kağıdını kaldırdı"
                                            )
                                            val sysMsg = Message(
                                                id = UUID.randomUUID().toString(),
                                                text = wallpaperRemovedText,
                                                isMe = false,
                                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                                                attachmentType = "SYSTEM"
                                            )
                                            persistAndDispatchIncoming(appContext, resolvedSender, sysMsg, notificationText = wallpaperRemovedText, countAsNew = false)
                                            if (resolvedSender != sender) {
                                                persistAndDispatchIncoming(appContext, sender, sysMsg, notificationText = wallpaperRemovedText, countAsNew = false)
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
                                        if (shouldApplyPinnedMessageState(currentVersion, incomingVersion)) {
                                            val storedText = ChatDatabaseHelper.getInstance(appContext)
                                                .findMessageForReaction(sender, msgId, "")
                                                ?.text
                                                ?: text
                                            prefs.edit {
                                                putString(P2PPreferences.pinnedMessageId(sender), msgId)
                                                putString(
                                                    P2PPreferences.pinnedMessageText(sender),
                                                    SecureStorage.encrypt(storedText),
                                                )
                                                putString(
                                                    P2PPreferences.pinnedMessageSender(sender),
                                                    if (isFromSender) sender else "You",
                                                )
                                                putString(P2PPreferences.pinnedBy(sender), sender)
                                                putLong(
                                                    P2PPreferences.pinnedStateVersion(sender),
                                                    incomingVersion.counter,
                                                )
                                                putString(
                                                    P2PPreferences.pinnedStateActor(sender),
                                                    incomingVersion.actor,
                                                )
                                            }
                                            runOnMain {
                                                messageListeners.forEach {
                                                    it.onMessagePinned(sender, msgId, storedText, isFromSender)
                                                }
                                            }
                                        }
                                        sendPinnedStateAck(appContext, sender, json.optString("control_id"))
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
                                    if (shouldApplyPinnedMessageState(currentVersion, incomingVersion)) {
                                        prefs.edit {
                                            remove(P2PPreferences.pinnedMessageId(sender))
                                            remove(P2PPreferences.pinnedMessageText(sender))
                                            remove(P2PPreferences.pinnedMessageSender(sender))
                                            remove(P2PPreferences.pinnedBy(sender))
                                            putLong(
                                                P2PPreferences.pinnedStateVersion(sender),
                                                incomingVersion.counter,
                                            )
                                            putString(
                                                P2PPreferences.pinnedStateActor(sender),
                                                incomingVersion.actor,
                                            )
                                        }
                                        runOnMain {
                                            messageListeners.forEach { it.onMessageUnpinned(sender) }
                                        }
                                    }
                                    sendPinnedStateAck(appContext, sender, json.optString("control_id"))
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
                                "delivery_receipt" -> {
                                    val msgId = json.optString("message_id")
                                    if (msgId.isNotEmpty()) {
                                        val db = ChatDatabaseHelper.getInstance(appContext)
                                        db.updateMessageStatus(msgId, "DELIVERED")
                                        serviceScope.launch(Dispatchers.Main) {
                                            messageListeners.forEach {
                                                it.onMessageStatusChanged(resolvedSender, msgId, "DELIVERED")
                                                if (resolvedSender != sender) {
                                                    it.onMessageStatusChanged(sender, msgId, "DELIVERED")
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
                                            prefs.edit {
                                                putString(
                                                    P2PPreferences.pinnedMessageText(resolvedSender),
                                                    SecureStorage.encrypt(text),
                                                )
                                            }
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
                                            prefs.edit {
                                                remove(P2PPreferences.pinnedMessageId(resolvedSender))
                                                remove(P2PPreferences.pinnedMessageText(resolvedSender))
                                                remove(P2PPreferences.pinnedMessageSender(resolvedSender))
                                                remove(P2PPreferences.pinnedBy(resolvedSender))
                                                remove(P2PPreferences.pinnedMessageId(sender))
                                                remove(P2PPreferences.pinnedMessageText(sender))
                                                remove(P2PPreferences.pinnedMessageSender(sender))
                                                remove(P2PPreferences.pinnedBy(sender))
                                            }
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
                                    sp.edit { putBoolean("restrict_forwarding_$resolvedSender", enabled) }
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
                                         if (!P2PPreferences.publishPeerIdentityIfExpected(appContext, nickname, sender)) return
                                        getBridge(appContext).updatePeerNameMapping(sender, nickname)
                                    }
                                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    val rxMsg = Message(
                                        id = msgId.ifEmpty { UUID.randomUUID().toString() },
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
                                         if (!P2PPreferences.publishPeerIdentityIfExpected(appContext, nickname, sender)) return
                                        getBridge(appContext).updatePeerNameMapping(sender, nickname)
                                    }
                                    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                    val rxMsg = Message(
                                        id = msgId.ifEmpty { UUID.randomUUID().toString() },
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
                                "file" -> {
                                    // Completed file payload: fall through to parseIncomingAttachment below
                                }
                                else -> {
                                    val mtype = json.optString("type")
                                    if (mtype.isNotEmpty()) {
                                        log(appContext, "Silently consumed unhandled control frame of type: $mtype", "DEBUG", null)
                                        return
                                    }
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
                        val peerKey = resolvedSender.ifBlank { sender }
                        val albumKey = incomingAttachment?.albumId?.let { "$peerKey:$it" }
                        val isAlbumPart = incomingAttachment != null &&
                            incomingAttachment.albumId != null &&
                            incomingAttachment.albumIndex != null &&
                            incomingAttachment.albumCount != null

                        var albumWasNew = false
                        val incomingMessage = if (isAlbumPart) {
                            synchronized(incomingAlbums) {
                                val existingAlbum = albumKey?.let {
                                    incomingAlbums[it]
                                        ?: incomingAlbums["$sender:${incomingAttachment.albumId}"]
                                        ?: incomingAlbums["$resolvedSender:${incomingAttachment.albumId}"]
                                        ?: ChatDatabaseHelper.getInstance(appContext).findMessageForReaction(
                                            peerKey,
                                            incomingAttachment.albumId,
                                            "",
                                        )
                                }
                                albumWasNew = (existingAlbum == null)
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
                                val appLang = runCatching { P2PPreferences.getAppLanguage(appContext) }.getOrDefault("English")
                                val defaultTitle = Localizations.tr(
                                    appLang,
                                    ru = "Альбом (${incomingAttachment.albumCount})",
                                    en = "Sent an album (${incomingAttachment.albumCount})",
                                    de = "Album gesendet (${incomingAttachment.albumCount})",
                                    es = "Álbum enviado (${incomingAttachment.albumCount})",
                                    fr = "Album envoyé (${incomingAttachment.albumCount})",
                                    pt = "Álbum enviado (${incomingAttachment.albumCount})",
                                    tr = "Albüm gönderildi (${incomingAttachment.albumCount})"
                                )
                                Message(
                                    id = incomingAttachment.albumId,
                                    text = existingAlbum?.text
                                        ?: incomingAttachment.caption
                                        ?: defaultTitle,
                                    isMe = false,
                                    timestamp = existingAlbum?.timestamp
                                        ?: SimpleDateFormat(
                                            "HH:mm",
                                            Locale.getDefault(),
                                        ).format(Date()),
                                    attachmentType = "ALBUM",
                                    attachmentUri = albumUris.firstOrNull { it.isNotBlank() } ?: albumUris.firstOrNull(),
                                    attachmentName = "Album",
                                    status = if (albumComplete) "SENT" else "RECEIVING",
                                    albumMediaUris = albumUris,
                                    albumMediaTypes = albumTypes,
                                ).also { albumMessage ->
                                    if (albumKey != null) {
                                        incomingAlbums[albumKey] = albumMessage
                                        incomingAlbums["$sender:${incomingAttachment.albumId}"] = albumMessage
                                        incomingAlbums["$resolvedSender:${incomingAttachment.albumId}"] = albumMessage
                                    }
                                }
                            }
                        } else if (incomingAttachment != null) {
                            Message(
                                id = incomingAttachment.messageId.ifBlank { UUID.randomUUID().toString() },
                                text = incomingAttachment.displayMessage,
                                isMe = false,
                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
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
                                timestamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
                                status = "SENT"
                            )
                        }
                        val completedOffer = incomingAttachment?.let {
                            incomingFileOffers.remove("$resolvedSender:${it.messageId}") ||
                                incomingFileOffers.remove("$sender:${it.messageId}")
                        } == true
                        if (incomingAttachment?.attachmentType == StickerSupport.PACK_ATTACHMENT_TYPE) {
                            StickerSupport.packIdFromArchiveFileName(incomingAttachment.attachmentName)
                                ?.let { packId ->
                                    serviceScope.launch(Dispatchers.Main) {
                                        messageListeners.forEach {
                                            it.onStickerPackInstalled(resolvedSender, packId)
                                            if (resolvedSender != sender) {
                                                it.onStickerPackInstalled(sender, packId)
                                            }
                                        }
                                    }
                                }
                        }
                        if (incomingAttachment != null) {
                            NetworkTrafficStats.recordFile(
                                appContext,
                                resolvedSender,
                                _peerEndpoints[resolvedSender] ?: _peerEndpoints[sender],
                                File(incomingAttachment.attachmentUri),
                                incomingAttachment.attachmentType,
                                TrafficDirection.RECEIVED,
                            )
                            serviceScope.launch(Dispatchers.Main) {
                                val key = "$resolvedSender:${incomingMessage.id}"
                                val rawKey = "$sender:${incomingMessage.id}"
                                val current = fileProgressStates[key]
                                    ?: fileProgressStates[rawKey]
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
                                    fileProgressStates[rawKey] = completed
                                    fileProgressStates[incomingMessage.id] = completed
                                }
                                fileTransferPreviews.remove(key)?.recycle()
                                fileTransferPreviews.remove(rawKey)?.takeIf { !it.isRecycled }?.recycle()
                                fileTransferPreviews.remove(incomingMessage.id)
                                    ?.takeIf { !it.isRecycled }
                                    ?.recycle()
                            }
                        }
                        if (text.isNotBlank()) {
                            val onionMatch = Regex("""([a-z2-7]{56}\.onion(?::\d+)?)""", RegexOption.IGNORE_CASE).find(text)
                            if (onionMatch != null) {
                                val onionAddr = onionMatch.value.trim()
                                val formatted = formatInviteEndpoint(onionAddr, listenerPort(appContext))
                                if (formatted != null && formatted.contains(".onion", ignoreCase = true)) {
                                    P2PPreferences.setPeerOnionAddress(appContext, resolvedSender, formatted)
                                    P2PPreferences.setPeerOnionAddress(appContext, sender, formatted)
                                    val fingerprint = P2PPreferences.prefs(appContext)
                                        .getString("peer_fingerprint_$resolvedSender", null)
                                        ?: P2PPreferences.prefs(appContext).getString("peer_fingerprint_$sender", null)
                                    ChatDatabaseHelper.getInstance(appContext).savePeerOnionAddress(
                                        peerName = resolvedSender,
                                        onionAddress = formatted,
                                        fingerprint = fingerprint,
                                        endpoint = _peerEndpoints[resolvedSender] ?: _peerEndpoints[sender],
                                    )
                                    rememberAuthenticatedPeerEndpoint(resolvedSender, formatted)
                                    rememberAuthenticatedPeerEndpoint(sender, formatted)
                                    log(appContext, "Saved authenticated onion address discovered from message: $formatted")
                                }
                            }
                        }

                        persistAndDispatchIncoming(
                            appContext,
                            resolvedSender,
                            incomingMessage,
                            countAsNew = if (isAlbumPart) {
                                albumWasNew
                            } else {
                                !completedOffer
                            },
                        )
                    } catch (ex: Exception) {
                        if (ex is CancellationException) throw ex
                        log(appContext, "Failed to persist incoming message to SharedPreferences/SQLite", "ERROR", ex)
                    }
                }
            }))

            bridge.registerSessionListener(guardedSessionListener(listenerVersion, object : BridgeSessionListener {
                override fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String, transport: String, aboutMe: String, seq: Long): Boolean {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint, endpoint)
                    val canonicalTransport = canonicalConnectionTransport(transport, endpoint)
                    if (isPlaceholderPeerName(resolvedPeerName)) {
                        if (!shouldBootstrapUnnamedSessionProfile(
                                resolvedPeerName = resolvedPeerName,
                                fingerprint = fingerprint,
                                sessionStillOnline = getBridge(appContext).isPeerOnline(fingerprint, fingerprint),
                            )
                        ) {
                            log(appContext, "Ignoring stale unnamed-session callback after close")
                            return true
                        }
                        // The peer has authenticated cryptographically but has not yet sent
                        // its nickname.  Send the profile envelope addressed by its fingerprint;
                        // it contains the identity information needed to bind the nickname.
                        // This used to send only the optional onion-address control message,
                        // leaving anonymous peers unable to complete profile discovery.
                        log(appContext, "Authenticated unnamed session awaiting identity information - sending self profile")
                        shareAvatar(appContext, fingerprint, endpoint, force = true)
                        return true
                    }
                    if (!P2PPreferences.publishPeerIdentityIfExpected(appContext, resolvedPeerName, fingerprint, endpoint, aboutMe)) {
                        clearPeerPresenceImmediately(resolvedPeerName, fingerprint)
                        return false
                    }
                    if (aboutMe.isNotBlank()) {
                        ChatDatabaseHelper.getInstance(appContext).savePeerAboutMe(resolvedPeerName, aboutMe)
                        ChatDatabaseHelper.getInstance(appContext).savePeerAboutMe(fingerprint, aboutMe)
                    }
                    getBridge(appContext).updatePeerNameMapping(fingerprint, resolvedPeerName)
                    log(appContext, "Secure Double Ratchet session established")
                    publishPeerOnline(
                        peerName = resolvedPeerName,
                        transport = canonicalTransport,
                        fingerprint = fingerprint,
                        endpoint = endpoint,
                        seq = seq,
                    )

                    val sharedPrefs = P2PPreferences.prefs(appContext)
                    if (canonicalTransport != null) {
                        sharedPrefs.edit {
                            putString(P2PPreferences.transport(resolvedPeerName), canonicalTransport)
                        }
                    }

                    // Save to active chats so the UI updates and shows the peer chat screen
                    ActiveChatStore.add(sharedPrefs, resolvedPeerName)

                    clearAvatarShareCooldown(resolvedPeerName)
                    clearAvatarShareCooldown(fingerprint)
                    resetPeerBackoffs(resolvedPeerName)
                    resetPeerBackoffs(fingerprint)
                    // A simultaneous Ygg dial can briefly expose the losing
                    // stream to this callback before Go's deterministic
                    // tie-break has closed it.  Do not pour a full profile
                    // onto that stream: wait for the canonical session to
                    // survive, then re-check the authenticated fingerprint.
                    if (canonicalTransport == "Yggdrasil") {
                        relayScope.launch {
                            delay(YGG_SESSION_SETTLE_MS)
                            if (getBridge(appContext).isPeerOnline(resolvedPeerName, fingerprint)) {
                                shareAvatar(appContext, resolvedPeerName, endpoint, force = true)
                            } else {
                                log(appContext, "Skipped profile share on unsettled Ygg session for $resolvedPeerName")
                            }
                        }
                    } else {
                        shareAvatar(appContext, resolvedPeerName, endpoint, force = true)
                    }
                    shareOnionAddress(appContext, resolvedPeerName, endpoint)
                    processOfflineQueue(appContext, resolvedPeerName, endpoint)
                    GroupChatCoordinator.onPeerConnected(appContext, resolvedPeerName)
                    OutboxWorkScheduler.triggerImmediateDrain(appContext)
                    return true
                }

                override fun onSessionClosed(peerName: String, fingerprint: String, reason: String, seq: Long) {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    log(appContext, "Secure Double Ratchet session closed (peer: $resolvedPeerName, reason: $reason)")
                    // Re-check the Go core inside the offline grace window so a session
                    // replacement (old close arriving after the new connect) does not
                    // flip a live peer to offline.
                    val verify = { getBridge(appContext).isPeerOnline(fingerprint, fingerprint) }
                    schedulePeerOfflineVerified(resolvedPeerName, fingerprint.ifBlank { null }, verify, seq)
                }

                override fun onPeerRoutesUpdated(peerName: String, fingerprint: String, endpoints: String) {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    rememberAuthenticatedPeerEndpoint(resolvedPeerName, endpoints)
                    if (fingerprint.isNotBlank() && fingerprint != resolvedPeerName) {
                        rememberAuthenticatedPeerEndpoint(fingerprint, endpoints)
                    }

                    // Extract and persist Tor .onion address if present in route list
                    val onionRoute = endpoints.split(",")
                        .map { it.trim() }
                        .firstOrNull { it.contains(".onion", ignoreCase = true) }
                    if (!onionRoute.isNullOrBlank()) {
                        val onionHost = when {
                            onionRoute.startsWith("[") -> onionRoute.substringAfter('[').substringBefore(']')
                            onionRoute.contains(":") -> onionRoute.substringBefore(':')
                            else -> onionRoute
                        }
                        if (onionHost.endsWith(".onion", ignoreCase = true)) {
                            P2PPreferences.setPeerOnionAddress(appContext, resolvedPeerName, onionHost)
                            if (fingerprint.isNotBlank() && fingerprint != resolvedPeerName) {
                                P2PPreferences.setPeerOnionAddress(appContext, fingerprint, onionHost)
                            }
                            log(appContext, "Saved authenticated Tor .onion address for $resolvedPeerName: $onionHost")
                        }
                    }

                    // Extract and persist the Yggdrasil endpoint if present in route list.
                    // This ensures the peer's mesh address is available for reconnection
                    // after an app restart even when Tor is not running.
                    val yggRoute = endpoints.split(",")
                        .map { it.trim() }
                        .firstOrNull { P2PPreferences.isYggdrasilEndpoint(it) }
                    if (!yggRoute.isNullOrBlank()) {
                        P2PPreferences.setPeerYggdrasilAddress(appContext, resolvedPeerName, yggRoute)
                        if (fingerprint.isNotBlank() && fingerprint != resolvedPeerName) {
                            P2PPreferences.setPeerYggdrasilAddress(appContext, fingerprint, yggRoute)
                        }
                        log(appContext, "Saved authenticated Yggdrasil endpoint for $resolvedPeerName: $yggRoute")
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
                    val discoveryMode = P2PPreferences.getDiscoverySecurityMode(appContext)
                    // Searches made before a contact exists must be able to
                    // collect candidates. Do not persist or dial them here:
                    // a tracker cannot prove ownership of a nickname or key.
                    // Strict discovery never accepts raw tracker addresses.
                    if (discoveryMode != P2PPreferences.DiscoverySecurityMode.STRICT) {
                        pendingLookupPeerName(infoHash)?.let { pendingPeerName ->
                            injectLocalDiscoveryCandidate(pendingPeerName, "", endpoint)
                            log(appContext, "Collected unverified discovery endpoint for $pendingPeerName via $source")
                        }
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

                        val currentFpHash = fp.takeIf { it.isNotBlank() }?.let {
                            discoveryInfoHash(it, it)
                        }
                        val currentCodeHash = discCode.takeIf { it.isNotBlank() }?.let {
                            discoveryInfoHash(peerName, it)
                        }

                        if (infoHash.equals(currentFpHash, ignoreCase = true) ||
                            infoHash.equals(currentCodeHash, ignoreCase = true) ||
                            infoHash == fp ||
                            infoHash.equals(discCode, ignoreCase = true) ||
                            infoHash.equals(fpHash, ignoreCase = true) ||
                            infoHash.equals(codeHash, ignoreCase = true)) {

                            // Check if endpoint is a signed DiscoveryRecord JSON
                            if (endpoint.trimStart().startsWith("{")) {
                                val verified = getBridge(appContext).verifyDiscoveryRecord(endpoint, fp.takeIf { it.isNotEmpty() }, checkSeqGap = true)
                                if (verified == null) {
                                    log(appContext, "Rejected invalid, expired, or replayed discovery record for $peerName via $source")
                                    continue
                                }
                                if (fp.isNotEmpty()) {
                                    ChatDatabaseHelper.getInstance(appContext).upsertDiscoverySeq(fp, verified.seq)
                                }
                                log(appContext, "Discovered ${verified.endpoints.size} signed endpoints for $peerName (seq=${verified.seq}) via $source")
                                for (verifiedEp in verified.endpoints) {
                                    injectLocalDiscoveryCandidate(peerName, fp, verifiedEp)
                                    rememberAuthenticatedPeerEndpoint(peerName, verifiedEp, appContext, EndpointSource.DISCOVERY, JSONObject(endpoint).optLong("expires_at", 0))
                                    if (!getBridge(appContext).isPeerOnline(peerName, fp)) {
                                        getBridge(appContext).reconnectPeerSessionInBackground(peerName, verifiedEp, fp)
                                    }
                                }
                                continue
                            }

                            // Handling unsigned endpoint according to DiscoverySecurityMode
                            if (discoveryMode == P2PPreferences.DiscoverySecurityMode.STRICT) {
                                log(appContext, "Rejecting unsigned endpoint $endpoint for $peerName via $source (STRICT mode active)")
                                continue
                            } else if (discoveryMode == P2PPreferences.DiscoverySecurityMode.TRANSITIONAL) {
                                SafeLog.w("P2P-Discovery", "Accepting unsigned endpoint $endpoint from $source for $peerName (TRANSITIONAL legacy mode)")
                            }

                            log(appContext, "Discovered endpoint $endpoint for $peerName via $source")
                            injectLocalDiscoveryCandidate(peerName, fp, endpoint)

                            // A BitTorrent tracker reports only a socket address for an
                            // info-hash. It does not attest that the address still belongs
                            // to this identity, so keep it in the bounded in-memory
                            // candidate set until an authenticated handshake succeeds.
                            // Persisting it here poisoned later automatic reconnects with
                            // stale or third-party routes.

                            if (!getBridge(appContext).isPeerOnline(peerName, fp)) {
                                getBridge(appContext).reconnectPeerSessionInBackground(peerName, endpoint, fp)
                            }
                        }
                    }
                }
            }))

            // Start the native listener only after the listeners are registered,
            // so the first session/message events cannot hit null listeners.
            bridge.startP2pListener(port, P2PPreferences.isUpnpEnabled(appContext))
            startLocalDiscovery(appContext, port)
            
            log(appContext, "Native Go P2P Relays started successfully")

            // Retry persisted pending messages immediately after the listener is ready.
            for (peerName in persistedChats) {
                peerEndpoints[peerName]?.let { endpoint ->
                    processOfflineQueue(appContext, peerName, endpoint)
                }
            }

            maintenanceCoordinator.start(appContext, port, ::isPlaceholderPeerName)
            relayScope.launch(Dispatchers.IO) {
                ChatDatabaseHelper.getInstance(appContext).pruneStaleDiscoverySeqs()
            }

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
            if (e is CancellationException) throw e
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
        // Clear presence so a stopped relay never keeps peers displayed as online.
        com.example.twopchat.presence.PresenceRepository.clearAll()
        serviceScope.launch(Dispatchers.Main) {
            peerConnectionTransports.clear()
            peerRttMs.clear()
        }
        // Trigger bridge shutdown/cleanup
        relayScope.launch {
            if (!getBridge().shutdownAllSessions()) {
                SafeLog.e(TAG, "P2P runtime did not stop cleanly")
            }
        }
    }

    /** Stop every account-bound transport before identity files are erased. */
    fun shutdownForAccountDeletion(context: Context): Boolean {
        if (accountRuntime.isEntered() || Thread.holdsLock(ActiveChatStore.persistenceLock)) return false
        val appContext = context.applicationContext
        closeAccountAdmission()
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
        com.example.twopchat.presence.PresenceRepository.clearAll()
        serviceScope.launch(Dispatchers.Main) {
            peerConnectionTransports.clear()
            peerRttMs.clear()
        }
        val stopped = getBridge(appContext).shutdownAllSessions()
        awaitAccountCallbacks()
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
                                    bitmap.scale(width, height)
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
                        put("discovery_code", P2PPreferences.getRendezvousCode(context))
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
                if (e is CancellationException) throw e
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

    fun requestPeerProfile(context: Context, peerName: String) {
        if (peerName == "Saved Messages" || isPlaceholderPeerName(peerName)) return
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val lastReq = lastProfileRequestAt[peerName] ?: 0L
        if (now - lastReq < 30_000L) return
        lastProfileRequestAt[peerName] = now
        // This can be called from Compose. sendP2pMessage may wait briefly
        // for a direct peer session, so it must never run on the UI thread.
        relayScope.launch {
            val endpoint = _peerEndpoints[peerName].orEmpty()
            val expectedFingerprint = P2PPreferences.findPeerFingerprint(appContext, peerName)
            val payload = JSONObject().apply {
                put("type", "profile_request")
                put("sender", P2PPreferences.username(appContext))
                put("fingerprint", getBridge(appContext).getLocalFingerprint())
            }.toString()
            getBridge(appContext).sendP2pMessage(peerName, endpoint, payload, expectedFingerprint)
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
                    if (e is CancellationException) throw e
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
        relayScope.launch {
            try {
                val onionHost = TorManager.onionAddress.value?.takeIf { it.isNotBlank() }
                    ?: prefs.getString(P2PPreferences.TOR_ONION_HOSTNAME, null)?.takeIf { it.isNotBlank() }
                if (onionHost.isNullOrBlank()) {
                    log(context, "Local Tor onion address is not available; skipping onion share")
                    return@launch
                }
                // If the onion address has not changed, apply the 30-second cooldown to
                // avoid redundant traffic. If it *has* changed (e.g. Tor restarted and
                // produced a new hidden service key) always send immediately so the peer
                // learns the new address without waiting for the cooldown to expire.
                val peerKnownOnion = P2PPreferences.getPeerOnionAddress(context, peerName)
                val onionChanged = !onionHost.equals(peerKnownOnion?.substringBefore(':'), ignoreCase = true)
                if (!onionChanged && now - (lastOnionShareAt[shareKey] ?: 0L) < 30_000L) {
                    log(context, "Onion share suppressed (cooldown active, address unchanged)")
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
                if (e is CancellationException) throw e
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
                val formatted = formatInviteEndpoint(onionAddr, listenerPort(context))
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
                        wallpaperBitmap.scale(width, height)
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
                if (e is CancellationException) throw e
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
        deleteChat(context, peerName, { action -> serviceScope.launch(Dispatchers.IO) { action() } })
    }

    internal fun deleteChat(
        context: Context,
        peerName: String,
        scheduleDelete: (() -> Unit) -> Unit,
        deleteHistory: (Context, String, Set<String>, String?) -> Unit = { ctx, name, aliases, fingerprint ->
            val db = ChatDatabaseHelper.getInstance(ctx)
            db.clearMessagesForPeer(name, aliases)
            aliases.forEach(db::deletePendingControlsForPeer)
            if (!fingerprint.isNullOrBlank()) PeerEndpointStore.delete(ctx.applicationContext, fingerprint)
            db.deletePeer(name, aliases)
        },
        clearNotification: (Context, String) -> Unit = MessageNotificationService::clearHistory,
    ): Unit = synchronized(ActiveChatStore.persistenceLock) {
        if (ActiveChatStore.accountClosed) return
        val clean = peerName.trim()
        if (clean.isBlank()) return
        val lower = clean.lowercase()
        val fp = P2PPreferences.getPeerFingerprint(context, clean)
        val resolvedName = if (!fp.isNullOrBlank()) P2PPreferences.findPeerNameByFingerprint(context, fp) else P2PPreferences.findPeerNameByFingerprint(context, clean)
        val sharedPrefs = P2PPreferences.prefs(context)
        val initialAliases = listOfNotNull(clean, lower, fp, resolvedName).filter { it.isNotBlank() }.distinct()
        if (initialAliases.any { ActiveChatStore.isDeleting(sharedPrefs, it) }) return
        val aliases = ActiveChatStore.beginDelete(sharedPrefs, initialAliases)
        ActiveChatStore.update(sharedPrefs, { chats ->
            chats.filterNot { item -> aliases.any { alias -> item.equals(alias, ignoreCase = true) } }.toSet()
        }) {
            aliases.forEach { alias ->
                LastMessagePreviewStore.remove(alias)
                remove("last_msg_$alias")
                remove("unread_count_$alias")
                remove("draft_msg_$alias")
                remove("pinned_chat_$alias")
                remove("transport_$alias")
                remove("last_endpoint_$alias")
                remove("verified_peer_$alias")
                remove("fingerprint_mismatch_$alias")
                remove(P2PPreferences.pinnedMessageId(alias))
                remove(P2PPreferences.pinnedMessageText(alias))
                remove(P2PPreferences.pinnedMessageSender(alias))
                remove(P2PPreferences.pinnedBy(alias))
                remove(P2PPreferences.pinnedStateVersion(alias))
                remove(P2PPreferences.pinnedStateActor(alias))
                remove(P2PPreferences.directWallpaperPath(alias))
                remove(P2PPreferences.directWallpaperDimming(alias))
                remove(P2PPreferences.directWallpaperBlur(alias))
            }
        }
        
        // Remove typing, connection, and memory cache states
        aliases.forEach { alias ->
            peerTypingStates.remove(alias)
            peerConnectionTransports.remove(alias)
            _peerEndpoints.remove(alias)
            ChatHistoryCache.remove(alias)
        }

        // Clear messages database, pending controls, and peer table for this peer and all aliases
        scheduleDelete {
            synchronized(ActiveChatStore.persistenceLock) {
                if (ActiveChatStore.accountClosed || ActiveChatStore.isRetired(sharedPrefs)) return@synchronized
                deleteHistory(context, peerName, aliases, fp)
                ActiveChatStore.completeDelete(sharedPrefs, aliases)
            }
        }

        // Clear notification history
        aliases.forEach { alias ->
            clearNotification(context, alias)
        }
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
        outboundMessenger.cleanupTempSanitizedFile(messageId)
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
        runOnMain {
            messageListeners.forEach { it.onMessageStatusChanged(peerName, messageId, "CANCELLED") }
        }
        return true
    }

    fun cancelMediaAlbum(context: Context, peerName: String, albumId: String, albumCount: Int): Boolean {
        // Invalidate the active coordinator before cancelling its individual
        // transfers. A retry uses the same album id, so a generation is safer
        // than a boolean cancellation flag.
        nextMediaAlbumGeneration(albumId)
        cancelFileTransfer(context, peerName, albumId)
        for (idx in 0 until albumCount) {
            cancelFileTransfer(context, peerName, "${albumId}_$idx")
        }
        return true
    }

    fun sendMediaAlbum(
        context: Context,
        peerName: String,
        endpoint: String,
        files: List<File>,
        albumId: String,
        caption: String = "",
        onAlbumStatusChanged: (status: String) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        // Retrying a previously cancelled album intentionally starts a new
        // transfer with the same stable album id but a fresh generation.
        val albumGeneration = nextMediaAlbumGeneration(albumId)
        serviceScope.launch {
            try {
                var allTransfersOk = true
                val db = ChatDatabaseHelper.getInstance(appContext)
                for ((idx, file) in files.withIndex()) {
                    if (isMediaAlbumCancelled(albumId, albumGeneration)) return@launch

                    val fileCaption = if (idx == 0) caption else ""
                    val fileTransferId = "${albumId}_$idx"
                    val deferred = CompletableDeferred<Boolean>()
                    sendFile(
                        context = appContext,
                        peerName = peerName,
                        endpoint = endpoint,
                        filePath = file.absolutePath,
                        messageId = fileTransferId,
                        caption = fileCaption,
                        albumId = albumId,
                        albumIndex = idx,
                        albumCount = files.size,
                    ) { success ->
                        deferred.complete(success)
                    }
                    val transferOk = withTimeoutOrNull(5 * 60 * 1000L) {
                        deferred.await()
                    } ?: false

                    // A cancelled transfer deliberately reports completion to
                    // the low-level callback so that progress cleanup can
                    // finish.  It must never be treated as a sent album part.
                    if (isMediaAlbumCancelled(albumId, albumGeneration)) {
                        outboundMessenger.cleanupTempSanitizedFile(fileTransferId)
                        return@launch
                    }

                    if (!transferOk) {
                        outboundMessenger.cleanupTempSanitizedFile(fileTransferId)
                        allTransfersOk = false
                        try {
                            db.updateMessageStatus(albumId, "PENDING")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            SafeLog.w(TAG, "Failed updating album status to PENDING in database", e)
                        }
                        runOnMain { onAlbumStatusChanged("PENDING") }
                        break
                    }
                }

                if (allTransfersOk && !isMediaAlbumCancelled(albumId, albumGeneration)) {
                    try {
                        db.updateMessageStatus(albumId, "SENT")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        SafeLog.w(TAG, "Failed updating album status to SENT in database", e)
                    }
                    runOnMain { onAlbumStatusChanged("SENT") }
                }
            } finally {
                mediaAlbumGenerations.remove(albumId, albumGeneration)
            }
        }
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

    fun sendDeliveryReceipt(context: Context, peerName: String, endpoint: String?, messageId: String) {
        outboundMessenger.sendDeliveryReceipt(context, peerName, endpoint, messageId)
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
        if (peerName !in peerEndpoints) return
        val payload = JSONObject().apply {
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
        val fingerprint = P2PPreferences.getPeerFingerprint(context, peerName).orEmpty()
        val session = ProtocolVersionManager.refresh(fingerprint)
        val supported = runCatching {
            GroupWireProtocol.requiredCapabilities(payload).all { session?.supports(it) == true }
        }.getOrDefault(false)
        if (session != null && !supported) {
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

    fun processAllOfflineQueues(context: Context) {
        outboundMessenger.processAllOfflineQueues(context)
    }

    fun loadPersistedAvatars(context: Context) {
        avatarCache.loadPersisted(context) { error ->
            SafeLog.e(TAG, "Error loading persisted avatars", error)
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
