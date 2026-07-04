package com.example.twopchat

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object P2PMessageRelay {
    private const val TAG = "P2PMessageRelay"
    private var isRunning = false

    // Maps peer name to their resolved IP:Port endpoint
    val peerEndpoints = ConcurrentHashMap<String, String>()

    // Callback triggered when a new message is received
    var onMessageReceived: ((sender: String, text: String) -> Unit)? = null
    var onNetworkStatusChanged: ((peerName: String, status: String) -> Unit)? = null

    private fun recordLog(level: String, message: String) {
        AppDiagnostics.addLog(level, message)
    }

    private fun publishPeerStatus(peerName: String, status: String) {
        AppDiagnostics.setPeerStatus(peerName, status)
        onNetworkStatusChanged?.invoke(peerName, status)
    }

    /**
     * Start the background Python P2P server.
     */
    fun startServer(context: android.content.Context) {
        if (isRunning) return
        isRunning = true
        val appContext = context.applicationContext
        try {
            // Start the Python P2P listener
            PythonBridge.startP2pListener(50001)
            recordLog("INFO", "Python P2P listener started on 0.0.0.0:50001")
            
            // Register incoming message callback from Python
            PythonBridge.registerMessageListener(object : PythonBridge.PyMessageListener {
                override fun onMessageReceived(sender: String, text: String) {
                    Log.i(TAG, "Incoming secure P2P message from $sender: $text")
                    recordLog("INFO", "Incoming secure P2P message from $sender: $text")
                    publishPeerStatus(sender, "Incoming message received")
                    
                    try {
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                        val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                        if (!activeSet.contains(sender)) {
                            val newSet = activeSet.toMutableSet()
                            newSet.add(sender)
                            sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                        }
                        sharedPrefs.edit().putString("last_msg_$sender", text).apply()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to persist incoming message to SharedPreferences", ex)
                    }

                    // Dispatch to active chat UI listener if any
                    onMessageReceived?.invoke(sender, text)
                }
            })

            // Register session status callbacks from Python
            PythonBridge.registerSessionListener(object : PythonBridge.PySessionListener {
                override fun onSessionEstablished(peerName: String, fingerprint: String) {
                    Log.i(TAG, "Secure Double Ratchet session established with $peerName ($fingerprint)")
                    recordLog("INFO", "Secure Double Ratchet session established with $peerName ($fingerprint)")
                    publishPeerStatus(peerName, "Secure session established")
                }

                override fun onSessionClosed(peerName: String) {
                    Log.i(TAG, "Secure Double Ratchet session closed with $peerName")
                    recordLog("INFO", "Secure Double Ratchet session closed with $peerName")
                    publishPeerStatus(peerName, "Session closed")
                }
            })

            PythonBridge.registerStatusListener(object : PythonBridge.PyStatusListener {
                override fun onStatus(text: String) {
                    recordLog("PY", text)
                }
            })
            
            Log.i(TAG, "Python P2P Relays started successfully")
            recordLog("INFO", "Python P2P relays started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Python P2P Relays", e)
            recordLog("ERROR", "Error starting Python P2P relays: ${e.message}")
        }
    }

    /**
     * Stop the P2P server.
     */
    fun stopServer() {
        isRunning = false
        recordLog("INFO", "Python P2P relay stopped")
    }

    /**
     * Send an encrypted Double Ratchet message to a resolved peer's endpoint.
     */
    fun sendMessage(endpoint: String, senderName: String, text: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        thread(start = true) {
            var targetPeerName = "Direct Peer"
            try {
                // Resolve the peer's name mapped to this endpoint
                for ((name, ep) in peerEndpoints) {
                    if (ep == endpoint) {
                        targetPeerName = name
                        break
                    }
                }
                
                Log.i(TAG, "Sending secure message to $targetPeerName via Python transport")
                recordLog("INFO", "Sending secure message to $targetPeerName via $endpoint")
                publishPeerStatus(targetPeerName, "Dialing $endpoint")
                val result = PythonBridge.sendP2pMessageDetailed(targetPeerName, endpoint, text)
                if (result.ok) {
                    publishPeerStatus(targetPeerName, if (text.isBlank()) "Handshake probe sent" else "Message sent over secure channel")
                    recordLog("INFO", "Secure send to $targetPeerName succeeded")
                } else {
                    val reason = result.error ?: "unknown transport error"
                    publishPeerStatus(targetPeerName, "P2P failed: $reason")
                    recordLog("ERROR", "Secure send to $targetPeerName failed: $reason")
                }
                onResult(result.ok, result.error)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send secure message to $endpoint", e)
                val reason = e.message ?: e.javaClass.simpleName
                publishPeerStatus(targetPeerName, "P2P failed: $reason")
                recordLog("ERROR", "Failed to send secure message to $endpoint: $reason")
                onResult(false, reason)
            }
        }
    }
}
