package com.example.twopchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class RelayMaintenanceCoordinator(
    private val scope: CoroutineScope,
    private val isRunning: () -> Boolean,
    private val peerEndpoints: Map<String, String>,
    private val presenceVersion: (String) -> Long,
    private val onPeerObservedOnline: (Context, String, String?, Long) -> Unit,
    private val onPeerObservedOffline: (String, Long) -> Unit,
    private val log: (Context, String, String, Throwable?) -> Unit,
) {
    private val lastReconnectAttemptAt = ConcurrentHashMap<String, Long>()
    private val reconnectDelayMs = ConcurrentHashMap<String, Long>()
    private var sessionJob: Job? = null
    private var announceJob: Job? = null

    fun start(context: Context, port: Int, isPlaceholderPeerName: (String) -> Boolean) {
        stop()
        val appContext = context.applicationContext
        sessionJob = scope.launch {
            var lastWakeLockRefreshAt = System.currentTimeMillis()
            while (isActive && isRunning()) {
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastWakeLockRefreshAt >= 8 * 60 * 1000L) {
                        lastWakeLockRefreshAt = now
                        P2PRelayService.refreshWakeLock()
                    }
                    val prefs = P2PPreferences.prefs(appContext)
                    val oneOnOneChats = prefs.getStringSet("active_chats", emptySet()).orEmpty()
                        .filterNot { it == "Saved Messages" || isPlaceholderPeerName(it) }
                    val groupMemberPeers = com.example.twopchat.group.runtime.GroupChatCoordinator
                        .listActiveGroupMemberPeerNames(appContext)
                        .filterNot { isPlaceholderPeerName(it) }
                    val chats = (oneOnOneChats + groupMemberPeers).distinct()
                    val presenceVersions = chats.associateWith(presenceVersion)
                    val bridge = com.example.twopchat.bridge.P2PBridgeProvider.get(appContext)
                    Handler(Looper.getMainLooper()).post {
                        for (peerName in chats) {
                            val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
                            val isOnline = bridge.isPeerOnline(peerName, fingerprint)
                            if (isOnline) {
                                if (!fingerprint.isNullOrBlank()) {
                                    reconnectDelayMs.remove(fingerprint)
                                }
                                val transport = canonicalConnectionTransport(
                                    rawTransport = prefs.getString(P2PPreferences.transport(peerName), null),
                                    endpoint = peerEndpoints[peerName]
                                        ?: prefs.getString(P2PPreferences.lastEndpoint(peerName), null),
                                )
                                onPeerObservedOnline(
                                    appContext,
                                    peerName,
                                    transport,
                                    presenceVersions.getValue(peerName),
                                )
                            } else {
                                onPeerObservedOffline(
                                    peerName,
                                    presenceVersions.getValue(peerName),
                                )
                            }
                        }
                    }

                    for (peerName in chats) {
                        if (P2PPreferences.isPeerIdentityChangePending(appContext, peerName)) continue
                        val isVerified = P2PPreferences.isPeerVerified(appContext, peerName)
                        val fingerprint = if (isVerified) prefs.getString("peer_fingerprint_$peerName", "").orEmpty() else ""
                        if (bridge.isPeerOnline(peerName, fingerprint)) {
                            reconnectDelayMs.remove(peerName)
                            continue
                        }
                        val endpoint = peerEndpoints[peerName]
                            ?: prefs.getString("last_endpoint_$peerName", null)?.takeIf { it.isNotBlank() }
                            ?: continue
                        val currentDelay = reconnectDelayMs[peerName] ?: 5_000L
                        if (now - (lastReconnectAttemptAt[peerName] ?: 0L) < currentDelay) continue
                        lastReconnectAttemptAt[peerName] = now
                        reconnectDelayMs[peerName] = (currentDelay * 2).coerceAtMost(20_000L)
                        val anonymizedPeer = (peerName ?: "").take(2) + "***"
                        log(appContext, "Background reconnection for $anonymizedPeer", "INFO", null)
                        bridge.reconnectPeerSession(peerName, endpoint, fingerprint)
                    }
                } catch (error: Exception) {
                    log(appContext, "Error maintaining saved peer sessions", "ERROR", error)
                }
                delay(10_000)
            }
        }

        announceJob = scope.launch {
            var lastAddresses = emptyList<String>()
            var candidateAddresses = emptyList<String>()
            var stableCandidateSamples = 0
            var lastAnnounceTime = 0L
            while (isActive && isRunning()) {
                try {
                    val prefs = P2PPreferences.prefs(appContext)
                    val username = prefs.getString("username_profile", "").orEmpty()
                    val bridge = com.example.twopchat.bridge.P2PBridgeProvider.get(appContext)
                    val fingerprint = bridge.getLocalFingerprint()
                    if (username.isNotBlank() && fingerprint !in setOf("Loading...", "Not Initialized", "Error")) {
                        val yggAddr = P2PMessageRelay.getYggdrasilAddress()
                        val yggReady = prefs.getBoolean("settings_yggdrasil", true) &&
                            yggAddr.isNotBlank() && yggAddr != "N/A" && yggAddr != "unavailable"
                        val addresses = buildList {
                            if (prefs.getBoolean("settings_ipv4", true)) {
                                val localIp = P2PMessageRelay.getLocalIpAddress(appContext)
                                if (localIp.isNotBlank() && localIp != "127.0.0.1") {
                                    add(localIp)
                                }
                            }
                            if (yggReady) yggAddr.takeIf { it.isNotBlank() }?.let(::add)
                        }.distinct().sorted()
                        val now = System.currentTimeMillis()
                        val networkChanged = addresses != lastAddresses && lastAddresses.isNotEmpty()
                        if (lastAnnounceTime == 0L || networkChanged || now - lastAnnounceTime >= 60_000L) {
                            log(appContext, "Announcing self on tracker. Network changed: $networkChanged, count: ${addresses.size}", "INFO", null)
                            val success = bridge.announceSelf(username, fingerprint, port)
                            log(appContext, "Announce self status: $success", "INFO", null)
                            if (success) {
                                lastAddresses = addresses
                                lastAnnounceTime = now
                            }
                            if (networkChanged) {
                                reconnectDelayMs.clear()
                            }
                        }
                    }
                } catch (error: Exception) {
                    log(appContext, "Error in periodic announce", "ERROR", error)
                }
                delay(25_000)
            }
        }
    }

    fun stop() {
        sessionJob?.cancel()
        announceJob?.cancel()
        sessionJob = null
        announceJob = null
        lastReconnectAttemptAt.clear()
        reconnectDelayMs.clear()
    }
}
