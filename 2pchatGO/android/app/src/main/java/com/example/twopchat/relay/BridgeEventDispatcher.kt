package com.example.twopchat.relay

import android.content.Context
import com.example.twopchat.logging.SafeLog
import com.example.twopchat.AppLog
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.bridge.BridgeMessageListener
import com.example.twopchat.bridge.BridgeSessionListener
import com.example.twopchat.group.runtime.GroupChatCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * BridgeEventDispatcher connects IP2PBridge callbacks to Kotlin domain coordinators
 * and UI listeners without blocking JNI threads.
 */
internal class BridgeEventDispatcher(
    private val scope: CoroutineScope,
    private val presenceManager: PeerPresenceManager,
    private val fileTransferCoordinator: FileTransferCoordinator,
    private val discoveryRegistry: DiscoveryCandidateRegistry,
    private val incomingRouter: IncomingMessageRouter,
) : BridgeMessageListener, BridgeSessionListener {

    private val TAG = "BridgeEventDispatcher"

    var onSessionEstablishedHook: ((peerName: String, peerFP: String, endpoint: String) -> Unit)? = null
    var onSessionClosedHook: ((peerName: String, peerFP: String) -> Unit)? = null
    var onPeerDiscoveredHook: ((infoHash: String, endpoint: String, source: String) -> Unit)? = null

    override fun onSessionEstablished(
        peerName: String,
        fingerprint: String,
        endpoint: String,
        transport: String,
        aboutMe: String,
    ): Boolean {
        scope.launch(Dispatchers.Default) {
            try {
                val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
                SafeLog.i(TAG, "Session established with ${SafeLog.fp(fingerprint)} via $transport")
                SafeLog.d(TAG, "Session established with ${SafeLog.fp(fingerprint)} via $transport @ $endpoint")
                AppLog.append(context, "Session established with ${SafeLog.fp(fingerprint)} via $transport\n")

                val connTransport = com.example.twopchat.relay.canonicalConnectionTransport(transport, endpoint) ?: transport
                presenceManager.publishPeerOnline(peerName, connTransport) {
                    if (endpoint.isNotBlank()) {
                        discoveryRegistry.rememberAuthenticatedPeerEndpoint(peerName, endpoint, context)
                    }
                }

                if (fingerprint.isNotBlank() && peerName.isNotBlank()) {
                    discoveryRegistry.rememberAuthenticatedPeerEndpoint(peerName, endpoint, context)
                }

                onSessionEstablishedHook?.invoke(peerName, fingerprint, endpoint)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onSessionEstablished", e)
            }
        }
        return true
    }

    override fun onSessionClosed(peerName: String, fingerprint: String) {
        scope.launch(Dispatchers.Default) {
            try {
                SafeLog.i(TAG, "Session closed with ${SafeLog.fp(fingerprint)}")
                presenceManager.clearPeerPresenceImmediately(peerName)
                if (fingerprint.isNotBlank() && fingerprint != peerName) {
                    presenceManager.clearPeerPresenceImmediately(fingerprint)
                }
                onSessionClosedHook?.invoke(peerName, fingerprint)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onSessionClosed", e)
            }
        }
    }

    override fun onPeerDiscovered(infoHash: String, endpoint: String, source: String) {
        scope.launch(Dispatchers.Default) {
            try {
                SafeLog.d(TAG, "Discovered peer (source: $source)")
                onPeerDiscoveredHook?.invoke(infoHash, endpoint, source)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onPeerDiscovered", e)
            }
        }
    }

    override fun onMessageReceived(sender: String, text: String) {
        scope.launch(Dispatchers.Default) {
            try {
                val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
                val sharedPrefs = P2PPreferences.prefs(context)

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
                        context,
                        resolvedSender,
                        discoveryRegistry.peerEndpoints[resolvedSender] ?: discoveryRegistry.peerEndpoints[sender],
                        text,
                        TrafficDirection.RECEIVED,
                    )
                }

                if (P2PPreferences.isPeerBlocked(context, resolvedSender) ||
                    P2PPreferences.isPeerBlocked(context, sender)
                ) {
                    SafeLog.i(TAG, "Ignored message from blocked peer: $resolvedSender")
                    return@launch
                }

                val trimmed = text.trim()
                if (trimmed.startsWith("{")) {
                    try {
                        val json = JSONObject(trimmed)
                        if (GroupChatCoordinator.handleIncoming(context, resolvedSender, json)) {
                            return@launch
                        }
                    } catch (_: Throwable) {}
                }

                incomingRouter.routeIncomingMessage(
                    context = context,
                    sender = resolvedSender,
                    text = text,
                    listeners = P2PMessageRelay.messageListeners.toList(),
                    persistAndDispatch = { ctx, s, msg, notif, isNew ->
                        P2PMessageRelay.persistAndDispatchIncoming(ctx, s, msg, notif, isNew)
                    },
                    log = { ctx, msg, lvl, err ->
                        P2PMessageRelay.log(ctx, msg, lvl, err)
                    },
                    sendControlMessage = { ctx, target, ctrlJson ->
                        P2PMessageRelay.outboundMessenger.sendControlMessage(ctx, target, ctrlJson)
                    },
                    acknowledgeControl = { ctx, cId ->
                        P2PMessageRelay.outboundMessenger.acknowledgeControl(ctx, cId)
                    }
                )
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error processing incoming message", e)
            }
        }
    }

    override fun onFileProgress(
        sender: String,
        messageId: String,
        bytesTransferred: Long,
        totalBytes: Long,
        speedKbps: Double,
    ) {
        val key = "$sender:$messageId"
        fileTransferCoordinator.updateProgress(key, messageId, bytesTransferred, totalBytes, speedKbps)
        scope.launch(Dispatchers.Main) {
            P2PMessageRelay.messageListeners.forEach {
                it.onFileProgress(sender, messageId, bytesTransferred, totalBytes, speedKbps)
            }
        }
    }
}
