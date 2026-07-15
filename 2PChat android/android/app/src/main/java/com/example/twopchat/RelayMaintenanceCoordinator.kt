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
    private val peerConnectionTransports: MutableMap<String, String>,
    private val peerSessionStates: MutableMap<String, Boolean>,
    private val onConnectedPeerHeartbeat: (Context, String) -> Unit,
    private val log: (Context, String, String, Throwable?) -> Unit,
) {
    private val lastReconnectAttemptAt = ConcurrentHashMap<String, Long>()
    private var sessionJob: Job? = null
    private var announceJob: Job? = null

    fun start(context: Context, port: Int, isPlaceholderPeerName: (String) -> Boolean) {
        stop()
        val appContext = context.applicationContext
        sessionJob = scope.launch {
            while (isActive && isRunning()) {
                try {
                    val prefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                    val chats = prefs.getStringSet("active_chats", emptySet()).orEmpty()
                        .filterNot { it == "Saved Messages" || isPlaceholderPeerName(it) }
                    val activeFingerprints = PythonBridge.getActivePeerFingerprints().toSet()
                    Handler(Looper.getMainLooper()).post {
                        for (peerName in chats) {
                            val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
                            if (!fingerprint.isNullOrBlank() && fingerprint in activeFingerprints) {
                                peerSessionStates[peerName] = true
                                canonicalConnectionTransport(
                                    rawTransport = prefs.getString(P2PPreferences.transport(peerName), null),
                                    endpoint = peerEndpoints[peerName]
                                        ?: prefs.getString(P2PPreferences.lastEndpoint(peerName), null),
                                )?.let { peerConnectionTransports[peerName] = it }
                                onConnectedPeerHeartbeat(appContext, peerName)
                            } else {
                                peerSessionStates.remove(peerName)
                                peerConnectionTransports.remove(peerName)
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    for (peerName in chats) {
                        val fingerprint = prefs.getString("peer_fingerprint_$peerName", null)
                            ?.takeIf { it.isNotBlank() } ?: continue
                        if (fingerprint in activeFingerprints) continue
                        val endpoint = peerEndpoints[peerName]
                            ?: prefs.getString("last_endpoint_$peerName", null)?.takeIf { it.isNotBlank() }
                            ?: continue
                        if (now - (lastReconnectAttemptAt[fingerprint] ?: 0L) < 15_000L) continue
                        lastReconnectAttemptAt[fingerprint] = now
                        log(appContext, "Background reconnection for $peerName at '$endpoint'", "INFO", null)
                        PythonBridge.reconnectPeerSession(peerName, endpoint, fingerprint)
                    }
                } catch (error: Exception) {
                    log(appContext, "Error maintaining saved peer sessions", "ERROR", error)
                }
                delay(5_000)
            }
        }

        announceJob = scope.launch {
            var lastAddresses = emptyList<String>()
            var candidateAddresses = emptyList<String>()
            var stableCandidateSamples = 0
            var lastAnnounceTime = 0L
            while (isActive && isRunning()) {
                try {
                    val prefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                    val username = prefs.getString("username_profile", "").orEmpty()
                    val fingerprint = PythonBridge.getLocalFingerprint()
                    if (username.isNotBlank() && fingerprint !in setOf("Loading...", "Not Initialized", "Error")) {
                        val diagnostics = PythonBridge.getYggdrasilNetworkDiagnostics()
                        val yggReady = prefs.getBoolean("settings_yggdrasil", true) &&
                            diagnostics["state"] == "connected" &&
                            (diagnostics["routes"]?.toIntOrNull() ?: 0) >= 2
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
                        val changedAndStable = addresses != lastAddresses && stableCandidateSamples >= 3
                        if (lastAnnounceTime == 0L || changedAndStable || now - lastAnnounceTime >= 300_000L) {
                            log(appContext, "Announcing self on tracker. Network changed and stable: $changedAndStable, IPs: $addresses", "INFO", null)
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
                delay(10_000)
            }
        }
    }

    fun stop() {
        sessionJob?.cancel()
        announceJob?.cancel()
        sessionJob = null
        announceJob = null
        lastReconnectAttemptAt.clear()
    }
}
