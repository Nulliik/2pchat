package com.example.twopchat.bridge

import android.content.Context
import android.util.Log
import com.example.twopchat.NativeBridge
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.relay.canonicalConnectionTransport
import com.example.twopchat.relay.isExpectedPeerFingerprint
import com.example.twopchat.relay.isValidPeerEndpointList
import com.example.twopchat.config.P2PPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Go implementation of IP2PBridge backed by lib2pcore.so.
 */
class NativeBridgeImpl : IP2PBridge {
    private val TAG = "NativeBridgeImpl"

    data class PendingMessage(
        val payload: String,
        val timestampMs: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(ttlMs: Long = MESSAGE_TTL_MS, now: Long = System.currentTimeMillis()): Boolean {
            return (now - timestampMs) > ttlMs
        }
    }

    companion object {
        const val MESSAGE_TTL_MS = 5 * 60 * 1000L // 5 minutes TTL
    }

    private var messageListener: BridgeMessageListener? = null
    private var sessionListener: BridgeSessionListener? = null
    private val onlinePeers = ConcurrentHashMap<String, Boolean>()
    private val peerNameMap = ConcurrentHashMap<String, String>()
    private val nameToFpMap = ConcurrentHashMap<String, String>()
    private val activeEndpoints = ConcurrentHashMap<String, String>()
    private val activeTransports = ConcurrentHashMap<String, String>()
    // Every payload reaching this callback was authenticated by the Go ratchet
    // session. It is therefore a valid liveness signal when the native session
    // registry is briefly between replacement/removal operations.
    private val lastAuthenticatedInboundAt = ConcurrentHashMap<String, Long>()
    private val authenticatedLivenessGraceMs = 15_000L
    // Connecting is asynchronous in the native core. Keep outbound messages
    // until its authenticated-session callback arrives instead of losing the
    // first message while the X3DH handshake is still in progress.
    private val pendingMessages = ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingMessage>>()

    init {
        NativeBridge.initialize()
        val appContext = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        val yggMode = P2PPreferences.getYggdrasilMode(appContext)
        NativeBridge.setYggdrasilConfig(yggMode.id, "127.0.0.1:${P2PPreferences.DEFAULT_YGGDRASIL_PROXY_PORT}")
        setupNativeCallbacks()
        loadPersistedPeerMappings()
    }

    private fun loadPersistedPeerMappings() {
        try {
            val appContext = com.example.twopchat.yggdrasil.GlobalApplication.appContext
            val prefs = P2PPreferences.prefs(appContext)
            val activeChats = prefs.getStringSet("active_chats", emptySet()) ?: emptySet()
            for (peerName in activeChats) {
                if (peerName.isNotBlank() && !P2PMessageRelay.isPlaceholderPeerName(peerName)) {
                    val fp = prefs.getString("peer_fingerprint_$peerName", null)
                    if (!fp.isNullOrBlank()) {
                        peerNameMap[fp] = peerName
                        nameToFpMap[peerName] = fp
                        NativeBridge.updatePeerNameMapping(fp, peerName)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun resolvePeerName(fingerprint: String): String? {
        if (fingerprint.isBlank()) return null
        peerNameMap[fingerprint]?.let { return it }
        val appContext = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        var name = P2PPreferences.findPeerNameByFingerprint(appContext, fingerprint)
        if (name.isNullOrBlank()) {
            name = try {
                com.example.twopchat.data.ChatDatabaseHelper.getInstance(appContext).getPeerNameByFingerprint(fingerprint)
            } catch (_: Throwable) {
                null
            }
        }
        if (!name.isNullOrBlank()) {
            peerNameMap[fingerprint] = name
            nameToFpMap[name] = fingerprint
            NativeBridge.updatePeerNameMapping(fingerprint, name)
            return name
        }
        return null
    }

    private fun resolveFingerprint(peerName: String): String? {
        if (peerName.isBlank()) return null
        nameToFpMap[peerName]?.let { return it }
        val appContext = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        var fp = P2PPreferences.prefs(appContext).getString(P2PPreferences.peerFingerprint(peerName), null)
        if (fp.isNullOrBlank()) {
            fp = try {
                com.example.twopchat.data.ChatDatabaseHelper.getInstance(appContext).getPeerFingerprint(peerName)
            } catch (_: Throwable) {
                null
            }
        }
        if (!fp.isNullOrBlank()) {
            nameToFpMap[peerName] = fp
            peerNameMap[fp] = peerName
            NativeBridge.updatePeerNameMapping(fp, peerName)
            return fp
        }
        return null
    }

    private fun setupNativeCallbacks() {
        NativeBridge.onPeerConnectedListener = { peerFP, endpoint ->
            Log.i(TAG, "[GoCore] Peer connected: $peerFP @ $endpoint")
            // CRITICAL: populate bidirectional name↔fp maps BEFORE setting onlinePeers.
            // Any concurrent isPeerOnline(nickname, fp) call must find the mapping already
            // present; otherwise it returns false and causes offline UI / skipped avatar share.
            val resolvedName = resolvePeerName(peerFP) ?: peerNameMap[peerFP] ?: peerFP
            if (resolvedName != peerFP) {
                nameToFpMap[resolvedName] = peerFP
                peerNameMap[peerFP] = resolvedName
                NativeBridge.updatePeerNameMapping(peerFP, resolvedName)
            }
            // Now mark online under BOTH the fingerprint key and the resolved nickname key.
            onlinePeers[peerFP] = true
            onlinePeers[resolvedName] = true
            // Reset send backoff so the first message after reconnect is never silently dropped
            P2PMessageRelay.resetPeerBackoffs(peerFP)
            if (resolvedName != peerFP) {
                P2PMessageRelay.resetPeerBackoffs(resolvedName)
            }
            // Clear avatar share cooldown so the profile is immediately re-shared on reconnect
            P2PMessageRelay.clearAvatarShareCooldown(peerFP)
            if (resolvedName != peerFP) {
                P2PMessageRelay.clearAvatarShareCooldown(resolvedName)
            }
            // A TCP endpoint always has a ':' before its port. Determine the
            // route from the host range instead of treating every host:port
            // as Yggdrasil.
            val transportHint = canonicalConnectionTransport(null, endpoint) ?: "Direct P2P"
            activeEndpoints[peerFP] = endpoint
            activeTransports[peerFP] = transportHint
            Log.i(TAG, "[GoCore] Active route for $peerFP: $transportHint @ $endpoint")
            sessionListener?.onSessionEstablished(resolvedName, peerFP, endpoint, transportHint, "")
            sendAuthenticatedRouteUpdate(peerFP)
            flushPendingMessages(peerFP)
            if (resolvedName != peerFP) {
                flushPendingMessages(resolvedName)
            }
        }

        NativeBridge.onPeerDisconnectedListener = { peerFP, reason ->
            Log.i(TAG, "[GoCore] Peer disconnected: $peerFP, reason: $reason")
            val resolvedName = resolvePeerName(peerFP) ?: peerNameMap[peerFP] ?: peerFP
            onlinePeers[peerFP] = false
            onlinePeers[resolvedName] = false
            lastAuthenticatedInboundAt.remove(peerFP)
            activeEndpoints.remove(peerFP)
            activeTransports.remove(peerFP)
            sessionListener?.onSessionClosed(resolvedName, peerFP)
        }

        NativeBridge.onMessageReceivedListener = message@{ peerFP, payload, messageID ->
            lastAuthenticatedInboundAt[peerFP] = System.currentTimeMillis()
            val payloadStr = String(payload, Charsets.UTF_8)
            // If the message contains identity_info or profile_avatar_share with a nickname, bind securely!
            if (payloadStr.startsWith("{")) {
                try {
                    val json = JSONObject(payloadStr)
                    val mtype = json.optString("type")
                    if (mtype == "endpoint_update") {
                        storeAuthenticatedRouteUpdate(peerFP, json)
                        return@message
                    }
                    if (mtype == "identity_info" || mtype == "profile_avatar_share") {
                        val remoteNick = json.optString("nickname").trim()
                        val claimedFP = json.optString("fingerprint").trim().ifBlank { peerFP }
                        // Rule §14: Claimed fingerprint must match the authenticated transport session peerFP
                        if (claimedFP == peerFP && remoteNick.isNotBlank() && !P2PMessageRelay.isPlaceholderPeerName(remoteNick)) {
                            val appContext = com.example.twopchat.yggdrasil.GlobalApplication.appContext
                            val existingFP = P2PPreferences.prefs(appContext).getString(P2PPreferences.peerFingerprint(remoteNick), null)
                            if (existingFP.isNullOrBlank() || existingFP == peerFP) {
                                // The nickname is authenticated by peerFP. Merge any older
                                // contact alias for this same identity before publishing the
                                // new session state, otherwise one device appears twice.
                                P2PMessageRelay.adoptAuthenticatedPeerNickname(appContext, peerFP, remoteNick)
                                val wasNameOnline = onlinePeers[remoteNick] == true
                                val existingNameForFingerprint = peerNameMap[peerFP]
                                val existingFingerprintForName = nameToFpMap[remoteNick]
                                nameToFpMap.entries
                                    .filter { (name, fingerprint) -> fingerprint == peerFP && name != remoteNick }
                                    .map { it.key }
                                    .forEach { staleName ->
                                        nameToFpMap.remove(staleName)
                                        onlinePeers.remove(staleName)
                                    }
                                peerNameMap[peerFP] = remoteNick
                                nameToFpMap[remoteNick] = peerFP
                                onlinePeers[remoteNick] = true
                                onlinePeers[peerFP] = true
                                val peerPrefs = P2PPreferences.prefs(appContext)
                                val pendingRoutes = peerPrefs.getString(
                                    P2PPreferences.lastEndpoint(peerFP), null
                                )
                                peerPrefs.edit()
                                    .apply {
                                        putString(P2PPreferences.peerFingerprint(remoteNick), peerFP)
                                        // Route updates can arrive immediately after the
                                        // encrypted handshake, before identity_info gives
                                        // us a human-readable name. Preserve that
                                        // fingerprint-keyed cache when the name arrives.
                                        if (!pendingRoutes.isNullOrBlank()) {
                                            putString(P2PPreferences.lastEndpoint(remoteNick), pendingRoutes)
                                            remove(P2PPreferences.lastEndpoint(peerFP))
                                        }
                                    }
                                    .apply()
                                P2PPreferences.updateFingerprintCache(peerFP, remoteNick)
                                NativeBridge.updatePeerNameMapping(peerFP, remoteNick)
                                // Profile frames may be repeated. Treat them as metadata updates,
                                // not fresh transport sessions; otherwise the session callback
                                // clears the profile-share cooldown and both peers echo profile
                                // frames forever until the process exhausts resources.
                                if (shouldPublishIdentitySessionEstablished(
                                        wasNameOnline = wasNameOnline,
                                        existingNameForFingerprint = existingNameForFingerprint,
                                        existingFingerprintForName = existingFingerprintForName,
                                        remoteNick = remoteNick,
                                        peerFP = peerFP,
                                    )
                                ) {
                                    sessionListener?.onSessionEstablished(
                                        remoteNick,
                                        peerFP,
                                        activeEndpoints[peerFP].orEmpty(),
                                        activeTransports[peerFP] ?: "Direct P2P",
                                        "",
                                    )
                                }
                            } else {
                                Log.w(TAG, "[Security] TOFU key change detected for $remoteNick: existing=$existingFP, incoming=$peerFP")
                                P2PPreferences.recordPendingPeerIdentity(appContext, remoteNick, peerFP, "")
                            }
                        } else if (claimedFP != peerFP) {
                            Log.w(TAG, "[Security] Dropping spoofed $mtype packet claiming $claimedFP on session $peerFP")
                        }
                    }
                } catch (_: Exception) {}
            }
            val senderName = resolvePeerName(peerFP) ?: peerNameMap[peerFP] ?: peerFP
            messageListener?.onMessageReceived(senderName, payloadStr)
        }

        NativeBridge.onErrorListener = { code, msg ->
            Log.e(TAG, "[GoCore] Native error ($code): $msg")
        }

        NativeBridge.onPeerDiscoveredListener = { infoHash, endpoint, source ->
            Log.d(TAG, "[GoCore] Discovered candidate for $infoHash @ $endpoint ($source)")
            sessionListener?.onPeerDiscovered(infoHash, endpoint, source)
        }

        NativeBridge.onFileProgressListener = { peerFP, messageID, transferred, total, speed ->
            val senderName = resolvePeerName(peerFP) ?: peerNameMap[peerFP] ?: peerFP
            messageListener?.onFileProgress(senderName, messageID, transferred, total, speed)
        }
    }

    override fun getLocalFingerprint(): String {
        return NativeBridge.getLocalIdentity()?.fingerprint.orEmpty()
    }

    override fun updatePeerNameMapping(fingerprint: String, peerName: String) {
        if (fingerprint.isNotBlank() && peerName.isNotBlank()) {
            peerNameMap[fingerprint] = peerName
            nameToFpMap[peerName] = fingerprint
            onlinePeers[peerName] = (onlinePeers[fingerprint] == true)
            NativeBridge.updatePeerNameMapping(fingerprint, peerName)
            flushPendingMessages(fingerprint)
        }
    }

    override fun configureLocalIdentity(username: String, fingerprint: String, aboutMe: String): Boolean {
        if (username.isNotBlank()) {
            NativeBridge.setNickname(username)
        }
        return NativeBridge.initialize()
    }

    override fun setIpv4Enabled(enabled: Boolean) {
        // Handled natively by AdaptiveDialer dual-stack routing
    }

    override fun startP2pListener(port: Int, upnpEnabled: Boolean) {
        NativeBridge.startListener(port)
    }

    override fun stopP2pListener(): Boolean {
        return NativeBridge.stopListener()
    }

    override fun announceSelf(
        nickname: String,
        fingerprint: String,
        port: Int,
        force: Boolean,
        rendezvousCode: String?,
    ): Boolean {
        val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        val trackers = usableTrackerUrls(context)

        // Resolve STUN before future private route updates.  The
        // BitTorrent tracker still receives no LAN endpoint list: it derives
        // an address only from the announce socket's source address.
        val natRefreshed = NativeBridge.refreshNatDiagnostics()
        val publicIpv4Available = NativeBridge.getNatDiagnostics()["public_endpoint"]
            ?.substringBeforeLast(':')
            ?.isNotBlank() == true
        Log.i(
            TAG,
            "STUN diagnostics before announce: refreshed=$natRefreshed, publicIpv4Available=$publicIpv4Available"
        )

        val hashes = linkedSetOf<String>()
        if (!rendezvousCode.isNullOrBlank() && nickname.isNotBlank()) {
            hashes.add(discoveryInfoHash(nickname, rendezvousCode))
        }
        if (fingerprint.isNotBlank()) {
            hashes.add(discoveryInfoHash(fingerprint, fingerprint))
        }
        // Receive-only migration compatibility for Go clients which still use
        // the pre-unification SHA-256(code) namespace.
        if (!rendezvousCode.isNullOrBlank()) {
            hashes.add(legacyDiscoveryInfoHash(rendezvousCode))
        }
        if (fingerprint.isNotBlank()) hashes.add(legacyDiscoveryInfoHash(fingerprint))

        NativeBridge.startDiscovery(trackers, hashes.toList(), port)
        if (hashes.isNotEmpty()) {
            NativeBridge.announceSelf(hashes.first(), port)
        }
        return true
    }

    override fun sendP2pMessage(peerName: String, endpoint: String, payload: String, expectedFingerprint: String?): Boolean {
        val resolvedFP = expectedFingerprint?.takeIf { it.isNotBlank() }
            ?: resolveFingerprint(peerName)
            ?: nameToFpMap[peerName]

        val target = resolvedFP ?: peerName

        // 1. Attempt direct send through Go Core first. Go Core is the authoritative single source of truth for active sessions.
        val msgId = NativeBridge.sendMessage(target, payload)
        if (msgId != null) {
            return true
        }

        // 2. If Go Core has no active session, resolve endpoints and initiate connection
        val fullEndpoint = if (endpoint.isNotBlank()) {
            endpoint
        } else {
            val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
            P2PPreferences.getPeerOnionAddress(context, peerName)
                ?: try {
                    com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).getPeerOnionAddress(peerName).orEmpty()
                } catch (_: Throwable) { "" }
        }

        if (fullEndpoint.isNotBlank()) {
            val candidateList = fullEndpoint.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val hasDirect = candidateList.any { !it.contains(".onion", ignoreCase = true) }

            // Always enqueue the message first so it is never dropped during background connection/handshake
            val targetKey = resolvedFP ?: peerName
            val queue = pendingMessages.getOrPut(targetKey) { ConcurrentLinkedQueue() }
            pruneExpiredPending(queue)
            queue.add(PendingMessage(payload, System.currentTimeMillis()))

            if (candidateList.size > 1) {
                NativeBridge.probePeer(candidateList, resolvedFP.orEmpty())
            } else {
                NativeBridge.connectPeer(fullEndpoint, resolvedFP.orEmpty())
            }

            // If a direct LAN/Wi-Fi endpoint is available, do a short fast-probe check (up to 800ms)
            if (hasDirect) {
                val deadline = System.currentTimeMillis() + 800L
                while (System.currentTimeMillis() < deadline) {
                    if (isPeerOnline(peerName, resolvedFP)) {
                        flushPendingMessages(resolvedFP ?: targetKey)
                        return true
                    }
                    try {
                        Thread.sleep(100L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }

            // Message is safely queued in pendingMessages and will be flushed upon handshake completion.
            // Returning true confirms successful dispatch to the delivery subsystem.
            return true
        }

        return false
    }

    private fun flushPendingMessages(peerFingerprint: String) {
        val peerName = resolvePeerName(peerFingerprint) ?: peerNameMap[peerFingerprint]
        val queuesToFlush = mutableListOf<ConcurrentLinkedQueue<PendingMessage>>()
        pendingMessages.remove(peerFingerprint)?.let { queuesToFlush.add(it) }
        if (peerName != null && peerName != peerFingerprint) {
            pendingMessages.remove(peerName)?.let { queuesToFlush.add(it) }
        }
        if (peerName != null) {
            for (key in pendingMessages.keys()) {
                if (key.equals(peerName, ignoreCase = true)) {
                    pendingMessages.remove(key)?.let { queuesToFlush.add(it) }
                }
            }
        }
        if (queuesToFlush.isEmpty()) return

        Thread {
            val now = System.currentTimeMillis()
            val target = peerFingerprint.ifBlank { peerName ?: "" }
            for (queue in queuesToFlush) {
                while (true) {
                    val pending = queue.poll() ?: break
                    if (pending.isExpired(MESSAGE_TTL_MS, now)) {
                        Log.d(TAG, "[GoCore] Discarded expired pending message for $peerFingerprint (age: ${now - pending.timestampMs}ms)")
                        continue
                    }
                    if (NativeBridge.sendMessage(target, pending.payload) == null) {
                        // Preserve the unsent tail for a later reconnect if not expired
                        if (!pending.isExpired(MESSAGE_TTL_MS, System.currentTimeMillis())) {
                            queue.add(pending)
                        }
                        pendingMessages.merge(target, queue) { current, queued ->
                            while (true) current.poll()?.let { queued.add(it) } ?: break
                            queued
                        }
                        break
                    }
                }
            }
        }.start()
    }

    private fun pruneExpiredPending(queue: ConcurrentLinkedQueue<PendingMessage>, now: Long = System.currentTimeMillis()) {
        val it = queue.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (item.isExpired(MESSAGE_TTL_MS, now)) {
                it.remove()
            }
        }
    }

    fun getPendingMessageCount(target: String): Int {
        val queue = pendingMessages[target] ?: return 0
        pruneExpiredPending(queue)
        return queue.size
    }

    override fun sendFile(
        peerName: String,
        endpoint: String,
        filePath: String,
        expectedFingerprint: String?,
        messageId: String,
        caption: String,
        previewBase64: String,
        albumId: String,
        albumIndex: Int,
        albumCount: Int,
    ): Boolean {
        val resolvedFP = expectedFingerprint?.takeIf { it.isNotBlank() }
            ?: resolveFingerprint(peerName)
            ?: nameToFpMap[peerName]

        val target = resolvedFP ?: peerName
        val isLive = isPeerOnline(peerName, resolvedFP)

        val fullEndpoint = if (endpoint.isNotBlank()) {
            endpoint
        } else {
            val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
            P2PPreferences.getPeerOnionAddress(context, peerName)
                ?: try {
                    com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).getPeerOnionAddress(peerName).orEmpty()
                } catch (_: Throwable) { "" }
        }

        if (!isLive && fullEndpoint.isNotBlank()) {
            val candidateList = fullEndpoint.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val hasOnion = candidateList.any { it.contains(".onion") }
            if (candidateList.size > 1) {
                NativeBridge.probePeer(candidateList, resolvedFP.orEmpty())
            } else {
                NativeBridge.connectPeer(fullEndpoint, resolvedFP.orEmpty())
            }

            val waitBudgetMs = if (hasOnion) 12_000L else 4_000L
            val pollIntervalMs = 150L
            val deadline = System.currentTimeMillis() + waitBudgetMs
            while (System.currentTimeMillis() < deadline) {
                if (isPeerOnline(peerName, resolvedFP)) {
                    break
                }
                try { Thread.sleep(pollIntervalMs) } catch (_: InterruptedException) { break }
            }
        }

        val fileName = File(filePath).name
        val emoji = if (caption.length in 1..4 && caption.any { Character.isSurrogate(it) || Character.getType(it) == Character.OTHER_SYMBOL.toInt() }) caption else ""
        val resId = NativeBridge.sendFile(target, filePath, messageId, fileName, caption, emoji)
        return resId != null
    }

    override fun updateTrackers(trackers: List<String>): Boolean {
        return NativeBridge.updateTrackers(trackers)
    }

    override fun cancelFile(peerName: String, messageId: String, expectedFingerprint: String?): Boolean {
        val targetFP = expectedFingerprint?.takeIf { it.isNotBlank() }
            ?: nameToFpMap[peerName]
            ?: peerName
        return NativeBridge.cancelFile(targetFP, messageId)
    }

    override fun reconnectPeerSession(peerName: String, endpoint: String, fingerprint: String?): Boolean {
        if (endpoint.isBlank()) return false
        if (!fingerprint.isNullOrBlank()) {
            peerNameMap[fingerprint] = peerName
            nameToFpMap[peerName] = fingerprint
        }
        val rawCandidates = endpoint.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val isTorRunning = com.example.twopchat.tor.TorManager.isTorRunning.value
        val isTorConnecting = com.example.twopchat.tor.TorManager.isTorConnecting.value
        val candidateList = rawCandidates.filter { candidate ->
            !candidate.contains(".onion", ignoreCase = true) || (isTorRunning && !isTorConnecting)
        }
        if (candidateList.isEmpty()) {
            Log.d(TAG, "[GoCore] Tor is not ready yet; deferring connection to .onion endpoint for $peerName")
            return false
        }
        return if (candidateList.size > 1) {
            NativeBridge.probePeer(candidateList, fingerprint.orEmpty())
        } else {
            NativeBridge.connectPeer(candidateList.first(), fingerprint.orEmpty())
        }
    }

    private fun sendAuthenticatedRouteUpdate(peerFingerprint: String) {
        if (peerFingerprint.isBlank()) return
        val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        val port = P2PPreferences.listenerPort(context)
        val routes = linkedSetOf<String>()
        P2PMessageRelay.getLocalIpAddress(context)
            .takeIf { it.isNotBlank() && it != "127.0.0.1" }
            ?.let { routes.add("$it:$port") }
        P2PMessageRelay.getYggdrasilAddress()
            .takeIf { it.isNotBlank() }
            ?.let { routes.add("[$it]:$port") }
        P2PPreferences.getTorOnionHostname(context)
            ?.takeIf { it.isNotBlank() }
            ?.let { routes.add("$it:$port") }

        // STUN reports a UDP mapping; use only its external IPv4 and our TCP
        // listener port. UPnP, when available, makes that listener reachable.
        val stunHost = NativeBridge.getNatDiagnostics()["public_endpoint"]
            ?.substringBeforeLast(':')?.trim('[', ']')
            .orEmpty()
        if (stunHost.isNotBlank()) routes.add("$stunHost:$port")

        val safeRoutes = routes.filter(::isValidPeerEndpointList).take(12)
        if (safeRoutes.isEmpty()) return
        val payload = JSONObject().apply {
            put("type", "endpoint_update")
            put("routes", JSONArray(safeRoutes))
        }.toString()
        NativeBridge.sendMessage(peerFingerprint, payload)
    }

    private fun storeAuthenticatedRouteUpdate(peerFingerprint: String, json: JSONObject) {
        val routes = json.optJSONArray("routes") ?: return
        val endpoints = buildList {
            for (index in 0 until minOf(routes.length(), 12)) {
                val candidate = when (val route = routes.opt(index)) {
                    is String -> route.trim()
                    is JSONObject -> {
                        val host = route.optString("host").trim()
                        val port = route.optInt("port", 0)
                        when {
                            host.isBlank() || port !in 1..65_535 -> ""
                            host.contains(':') -> "[$host]:$port"
                            else -> "$host:$port"
                        }
                    }
                    else -> ""
                }
                candidate
                    .takeIf(::isValidPeerEndpointList)
                    ?.let(::add)
            }
        }.distinct().joinToString(",")
        if (endpoints.isBlank() || !isValidPeerEndpointList(endpoints)) return

        val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        // An endpoint_update normally arrives before the peer's identity_info
        // frame. Cache it under the authenticated fingerprint first, then
        // migrate it to the nickname when identity_info is processed.
        val peerName = resolvePeerName(peerFingerprint) ?: peerFingerprint
        val knownFingerprint = P2PPreferences.prefs(context)
            .getString(P2PPreferences.peerFingerprint(peerName), null)
        if (peerName != peerFingerprint && !isExpectedPeerFingerprint(knownFingerprint, peerFingerprint)) {
            Log.w(TAG, "Ignored endpoint_update with unexpected fingerprint")
            return
        }
        P2PPreferences.prefs(context).edit()
            .putString(P2PPreferences.lastEndpoint(peerName), endpoints)
            .apply()
        sessionListener?.onPeerRoutesUpdated(peerName, peerFingerprint, endpoints)
        Log.i(TAG, "Stored ${endpoints.split(',').size} authenticated peer route(s)")
    }

    override fun closePeerSession(peerName: String, expectedFingerprint: String?): Boolean {
        if (!expectedFingerprint.isNullOrBlank()) {
            onlinePeers[expectedFingerprint] = false
            lastAuthenticatedInboundAt.remove(expectedFingerprint)
        }
        nameToFpMap[peerName]?.let { onlinePeers[it] = false }
        onlinePeers[peerName] = false
        return true
    }

    override fun isPeerOnline(peerName: String, expectedFingerprint: String?): Boolean {
        // Go Core is the authoritative single source of truth for active sessions.
        val targetFP = expectedFingerprint?.takeIf { it.isNotBlank() }
            ?: nameToFpMap[peerName]
            ?: resolveFingerprint(peerName)

        if (!targetFP.isNullOrBlank()) {
            val nativeOnline = NativeBridge.isPeerOnline(targetFP)
            val authenticatedRecently = lastAuthenticatedInboundAt[targetFP]
                ?.let { System.currentTimeMillis() - it <= authenticatedLivenessGraceMs }
                ?: false
            // A ratchet-authenticated heartbeat/message is stronger evidence than
            // a transient empty lookup during simultaneous session replacement.
            val isOnline = nativeOnline || authenticatedRecently
            onlinePeers[targetFP] = isOnline
            onlinePeers[peerName] = isOnline
            return isOnline
        }
        val isOnline = NativeBridge.isPeerOnline(peerName)
        onlinePeers[peerName] = isOnline
        return isOnline
    }

    override fun shutdownAllSessions(): Boolean {
        NativeBridge.stopDiscovery()
        NativeBridge.stopListener()
        onlinePeers.clear()
        lastAuthenticatedInboundAt.clear()
        pendingMessages.clear()
        return true
    }

    override fun resetStaleEndpointCooldowns(): Boolean {
        return NativeBridge.resetStaleEndpointCooldowns()
    }

    override fun registerMessageListener(listener: BridgeMessageListener) {
        this.messageListener = listener
    }

    override fun registerSessionListener(listener: BridgeSessionListener) {
        this.sessionListener = listener
    }

    override fun searchPeers(
        query: String,
        expectedLiveName: String?,
        expectedFingerprint: String?,
        sharedCode: String?,
    ): List<Map<String, Any>> {
        val resultName = expectedLiveName?.takeIf { it.isNotBlank() } ?: query
        val normalizedSharedCode = sharedCode?.takeIf { it.isNotBlank() }
        val normalizedFingerprint = expectedFingerprint?.takeIf { it.isNotBlank() }
        val lookupNickname = when {
            normalizedSharedCode != null -> resultName
            normalizedFingerprint != null -> normalizedFingerprint
            else -> query
        }
        val lookupCode = normalizedSharedCode ?: normalizedFingerprint ?: query
        val infoHashes = linkedSetOf(
            discoveryInfoHash(lookupNickname, lookupCode),
            legacyDiscoveryInfoHash(lookupCode),
        )

        val appContext = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        val trackers = usableTrackerUrls(appContext)
        NativeBridge.startDiscovery(trackers = trackers, infoHashes = infoHashes.toList())

        val knownCandidates = mutableListOf<String>()
        knownCandidates.addAll(P2PMessageRelay.localDiscoveryEndpoints(resultName))
        knownCandidates.addAll(P2PMessageRelay.localDiscoveryEndpoints(query))

        val resultFP = expectedFingerprint.orEmpty()
        return listOf(
            mapOf(
                "nickname" to resultName,
                "fingerprint" to resultFP,
                "endpoints" to knownCandidates.distinct(),
                "verified" to true,
                "ownership_verified" to (!sharedCode.isNullOrBlank() || !expectedFingerprint.isNullOrBlank()),
                "shared_code" to (sharedCode ?: ""),
            )
        )
    }

    override fun getLocalSeedMnemonic(): String? =
        NativeBridge.getLocalSeedMnemonic()

    override fun restoreFromMnemonic(nickname: String, mnemonic: String, aboutMe: String): Boolean =
        NativeBridge.restoreFromMnemonic(nickname, mnemonic, aboutMe)
}

internal fun shouldPublishIdentitySessionEstablished(
    wasNameOnline: Boolean,
    existingNameForFingerprint: String?,
    existingFingerprintForName: String?,
    remoteNick: String,
    peerFP: String,
): Boolean = !wasNameOnline ||
    existingNameForFingerprint != remoteNick ||
    existingFingerprintForName != peerFP

internal fun discoveryInfoHash(nickname: String, sharedCode: String): String {
    val normalizedNickname = nickname.trim().lowercase(java.util.Locale.ROOT)
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    val normalizedCode = sharedCode.trim()
    require(normalizedNickname.isNotEmpty() && normalizedCode.isNotEmpty())
    val payload = "2pchat-rendezvous-v1:$normalizedNickname:$normalizedCode"
    return java.security.MessageDigest.getInstance("SHA-1")
        .digest(payload.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun legacyDiscoveryInfoHash(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(20)
        .joinToString("") { "%02x".format(it) }

/**
 * The native layer routes Yggdrasil UDP trackers through the local UDP relay
 * in proxy mode, while TCP trackers continue through SOCKS5.
 */
private fun usableTrackerUrls(context: Context): List<String> =
    com.example.twopchat.config.TrackerPreferences.getActiveTrackerUrls(context)
