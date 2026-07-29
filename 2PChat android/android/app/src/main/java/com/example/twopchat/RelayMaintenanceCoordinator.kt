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
            while (isActive && isRunning()) {
                try {
                    val prefs = P2PPreferences.prefs(appContext)
                    val chats = prefs.getStringSet("active_chats", emptySet()).orEmpty()
                        .filterNot { it == "Saved Messages" || isPlaceholderPeerName(it) }
                    // Capture this before the blocking Python heartbeat probe.
                    // Any callback received while it is running makes the
                    // corresponding result stale.
                    val presenceVersions = chats.associateWith(presenceVersion)
                    val activeFingerprints = PythonBridge.getActivePeerFingerprints().toSet()
                    Handler(Looper.getMainLooper()).post {
                        for (peerName in chats) {
                            val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
                            if (!fingerprint.isNullOrBlank() && fingerprint in activeFingerprints) {
                                reconnectDelayMs.remove(fingerprint)
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

                    val now = System.currentTimeMillis()
                    for (peerName in chats) {
                        if (P2PPreferences.isPeerIdentityChangePending(appContext, peerName)) continue
                        val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
                            ?.takeIf { it.isNotBlank() } ?: continue
                        if (fingerprint in activeFingerprints) {
                            reconnectDelayMs.remove(fingerprint)
                            continue
                        }
                        val endpoint = peerEndpoints[peerName]
                            ?: prefs.getString("last_endpoint_$peerName", null)?.takeIf { it.isNotBlank() }
                            ?: continue
                        val currentDelay = reconnectDelayMs[fingerprint] ?: 5_000L
                        if (now - (lastReconnectAttemptAt[fingerprint] ?: 0L) < currentDelay) continue
                        lastReconnectAttemptAt[fingerprint] = now
                        reconnectDelayMs[fingerprint] = (currentDelay * 2).coerceAtMost(20_000L)
                        val anonymizedPeer = (peerName ?: "").take(2) + "***"
                        log(appContext, "Background reconnection for $anonymizedPeer", "INFO", null)
                        PythonBridge.reconnectPeerSession(peerName, endpoint, fingerprint)
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
                    val fingerprint = PythonBridge.getLocalFingerprint()
                    if (username.isNotBlank() && fingerprint !in setOf("Loading...", "Not Initialized", "Error")) {
                        val diagnostics = PythonBridge.getYggdrasilNetworkDiagnostics()
                        val yggState = diagnostics["state"].orEmpty()
                        val yggReady = prefs.getBoolean("settings_yggdrasil", true) &&
                            (yggState.equals("connected", ignoreCase = true) || yggState.equals("enabled", ignoreCase = true)) &&
                            (diagnostics["routes"]?.toIntOrNull() ?: 0) >= 1
                        val addresses = buildList {
                            if (prefs.getBoolean("settings_ipv4", true)) {
                                addAll(PythonBridge.getLocalAddresses().filter { !it.contains(':') })
                            }
                            if (yggReady) PythonBridge.getYggdrasilAddress().takeIf { it.isNotBlank() }?.let(::add)
                        }.distinct().sorted()
                        val now = System.currentTimeMillis()
                        if (addresses == candidateAddresses) stableCandidateSamples++ else {
                            candidateAddresses = addresses
                            stableCandidateSamples = 1
                        }
                        val changedAndStable = addresses != lastAddresses && stableCandidateSamples >= 2
                        if (lastAnnounceTime == 0L || changedAndStable || now - lastAnnounceTime >= 90_000L) {
                            log(appContext, "Announcing self on tracker. Network changed: $changedAndStable, count: ${addresses.size}", "INFO", null)
                            val success = PythonBridge.announceSelf(username, fingerprint, port)
                            log(appContext, "Announce self status: $success", "INFO", null)
                            if (success) {
                                lastAddresses = addresses
                                lastAnnounceTime = now
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
