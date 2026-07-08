package com.example.twopchat

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message

object P2PMessageRelay {
    private const val TAG = "P2PMessageRelay"
    private var isRunning = false

    // Maps peer name to their resolved IP:Port endpoint
    val peerEndpoints = ConcurrentHashMap<String, String>()

    // Callback triggered when a new message is received
    var onMessageReceived: ((sender: String, text: String) -> Unit)? = null

    private fun log(context: android.content.Context, message: String, level: String = "INFO", error: Throwable? = null) {
        val fullMsg = if (error != null) "$message: ${Log.getStackTraceString(error)}" else message
        if (level == "ERROR") {
            Log.e(TAG, fullMsg)
        } else {
            Log.i(TAG, fullMsg)
        }
        try {
            val logDir = java.io.File(context.filesDir, "config")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "app.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("$timestamp [KOTLIN_$level] $TAG: $fullMsg\n")
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Start the background Python P2P server.
     */
    fun startServer(context: android.content.Context) {
        if (isRunning) return
        isRunning = true
        val appContext = context.applicationContext
        try {
            log(appContext, "Starting Python P2P Relays...")
            // Start the Python P2P listener
            PythonBridge.startP2pListener(50001)
            
            // Register incoming message callback from Python
            PythonBridge.registerMessageListener(object : PythonBridge.PyMessageListener {
                override fun onMessageReceived(sender: String, text: String) {
                    log(appContext, "Incoming secure P2P message from $sender: $text")
                    
                    try {
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                        val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                        if (!activeSet.contains(sender)) {
                            val newSet = activeSet.toMutableSet()
                            newSet.add(sender)
                            sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                        }
                        
                        val persistEnabled = sharedPrefs.getBoolean("persist_chat_history", true)
                        val db = ChatDatabaseHelper(appContext)
                        var displayMessage = text
                        if (text.startsWith("{\"type\":\"file\"")) {
                            try {
                                val json = org.json.JSONObject(text)
                                val fileName = json.optString("file_name", "file")
                                val filePath = json.optString("file_path", "")
                                val mime = json.optString("mime", "")
                                val isImage = mime.startsWith("image/")
                                displayMessage = if (isImage) {
                                    "Sent an image"
                                } else {
                                    "Sent a file: $fileName"
                                }
                                if (persistEnabled) {
                                    db.saveMessage(sender, Message(
                                        id = System.currentTimeMillis().toString(),
                                        text = displayMessage,
                                        isMe = false,
                                        timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                        attachmentType = if (isImage) "IMAGE" else "FILE",
                                        attachmentUri = filePath,
                                        attachmentName = fileName
                                    ))
                                }
                            } catch (e: Exception) {
                                displayMessage = "Sent a file"
                                if (persistEnabled) {
                                    db.saveMessage(sender, Message(
                                        id = System.currentTimeMillis().toString(),
                                        text = text,
                                        isMe = false,
                                        timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                    ))
                                }
                            }
                        } else {
                            if (persistEnabled) {
                                db.saveMessage(sender, Message(
                                    id = System.currentTimeMillis().toString(),
                                    text = text,
                                    isMe = false,
                                    timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                ))
                            }
                        }
                        sharedPrefs.edit().putString("last_msg_$sender", displayMessage).apply()
                    } catch (ex: Exception) {
                        log(appContext, "Failed to persist incoming message to SharedPreferences/SQLite", "ERROR", ex)
                    }

                    // Dispatch to active chat UI listener if any
                    onMessageReceived?.invoke(sender, text)
                }
            })

            // Register session status callbacks from Python
            PythonBridge.registerSessionListener(object : PythonBridge.PySessionListener {
                override fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String) {
                    log(appContext, "Secure Double Ratchet session established with $peerName ($fingerprint) at $endpoint")
                    if (endpoint.isNotEmpty()) {
                        peerEndpoints[peerName] = endpoint
                        
                        // Save to active chats so the UI updates and shows the peer chat screen
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                        val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                        if (!activeSet.contains(peerName)) {
                            val newSet = activeSet.toMutableSet()
                            newSet.add(peerName)
                            sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                            sharedPrefs.edit().putString("transport_$peerName", "DIRECT P2P").apply()
                        }
                    }
                }

                override fun onSessionClosed(peerName: String) {
                    log(appContext, "Secure Double Ratchet session closed with $peerName")
                }
            })
            
            log(appContext, "Python P2P Relays started successfully")
        } catch (e: Exception) {
            log(appContext, "Error starting Python P2P Relays", "ERROR", e)
        }
    }

    /**
     * Stop the P2P server.
     */
    fun stopServer() {
        isRunning = false
    }

    /**
     * Send an encrypted Double Ratchet message to a resolved peer's endpoint.
     */
    fun sendMessage(context: android.content.Context, endpoint: String, senderName: String, text: String, onResult: (Boolean) -> Unit = {}) {
        thread(start = true) {
            try {
                // Resolve the peer's name mapped to this endpoint
                var targetPeerName = "Direct Peer"
                for ((name, ep) in peerEndpoints) {
                    if (ep == endpoint) {
                        targetPeerName = name
                        break
                    }
                }
                
                log(context, "Sending secure message to $targetPeerName via Python transport (endpoints: $endpoint)")
                val success = PythonBridge.sendP2pMessage(targetPeerName, endpoint, text)
                log(context, "Sending status to $targetPeerName: ${if (success) "SUCCESS" else "FAILED"}")
                onResult(success)
            } catch (e: Exception) {
                log(context, "Failed to send secure message to $endpoint", "ERROR", e)
                onResult(false)
            }
        }
    }

    /**
     * Send an encrypted file to a resolved peer's endpoint.
     */
    fun sendFile(context: android.content.Context, endpoint: String, filePath: String, onResult: (Boolean) -> Unit = {}) {
        thread(start = true) {
            try {
                var targetPeerName = "Direct Peer"
                for ((name, ep) in peerEndpoints) {
                    if (ep == endpoint) {
                        targetPeerName = name
                        break
                    }
                }
                
                log(context, "Sending secure file $filePath to $targetPeerName via Python transport (endpoints: $endpoint)")
                val success = PythonBridge.sendP2pFile(targetPeerName, endpoint, filePath)
                log(context, "Sending file status to $targetPeerName: ${if (success) "SUCCESS" else "FAILED"}")
                onResult(success)
            } catch (e: Exception) {
                log(context, "Failed to send secure file to $endpoint", "ERROR", e)
                onResult(false)
            }
        }
    }
}
