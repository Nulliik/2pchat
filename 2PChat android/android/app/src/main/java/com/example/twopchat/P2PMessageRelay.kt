package com.example.twopchat

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message

object P2PMessageRelay {
    private const val TAG = "P2PMessageRelay"
    private const val LISTENER_PORT = 50001
    private val startStopLock = Any()
    private val identityLock = Any()
    private var isRunning = false

    /** All installations listen on the documented P2P port; identity is the fingerprint, not the port. */
    fun listenerPort(context: android.content.Context): Int {
        return LISTENER_PORT
    }

    // Maps peer name to their resolved IP:Port endpoint
    val peerEndpoints = ConcurrentHashMap<String, String>()
    private val fingerprintToPeerName = ConcurrentHashMap<String, String>()

    // Maps peer name to their profile avatar bitmap in RAM
    val peerAvatars = androidx.compose.runtime.mutableStateMapOf<String, android.graphics.Bitmap>()

    // Callback triggered when a new message is received
    var onMessageReceived: ((sender: String, text: String) -> Unit)? = null

    private data class IncomingAttachment(
        val displayMessage: String,
        val attachmentType: String,
        val attachmentUri: String,
        val attachmentName: String
    )

    private fun parseIncomingAttachment(text: String): IncomingAttachment? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) {
            return null
        }
        return try {
            val json = org.json.JSONObject(trimmed)
            if (json.optString("type") != "file") {
                return null
            }
            val fileName = json.optString("file_name", "file")
            val filePath = json.optString("file_path", "")
            val mime = json.optString("mime", "")
            val isImage = mime.startsWith("image/")
            IncomingAttachment(
                displayMessage = if (isImage) "Sent an image" else "Sent a file: $fileName",
                attachmentType = if (isImage) "IMAGE" else "FILE",
                attachmentUri = filePath,
                attachmentName = fileName
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isPlaceholderPeerName(name: String): Boolean {
        return name.startsWith("Peer (") && name.endsWith(")")
    }

    private fun moveChatState(
        context: android.content.Context,
        fromName: String,
        toName: String
    ) {
        if (fromName == toName) return
        val sharedPrefs = context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
        val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen"))
            ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
        val updatedChats = activeSet.toMutableSet()
        var changed = false
        if (updatedChats.remove(fromName)) {
            changed = true
        }
        if (updatedChats.add(toName)) {
            changed = true
        }

        val editor = sharedPrefs.edit()
        if (changed) {
            editor.putStringSet("active_chats", updatedChats)
        }

        val keysToMove = listOf("last_msg_", "transport_", "verified_peer_", "fingerprint_mismatch_")
        for (prefix in keysToMove) {
            if (!sharedPrefs.contains("$prefix$fromName")) {
                continue
            }
            when (prefix) {
                "verified_peer_", "fingerprint_mismatch_" -> {
                    val value = sharedPrefs.getBoolean("$prefix$fromName", false)
                    if (!sharedPrefs.contains("$prefix$toName")) {
                        editor.putBoolean("$prefix$toName", value)
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
            ChatDatabaseHelper(context).renamePeer(fromName, toName)
        } catch (e: Exception) {
            log(context, "Failed to migrate chat history from $fromName to $toName", "ERROR", e)
        }
    }

    private fun canonicalPeerName(
        context: android.content.Context,
        peerName: String,
        fingerprint: String
    ): String {
        if (fingerprint.isBlank()) {
            return peerName
        }
        synchronized(identityLock) {
            val knownName = fingerprintToPeerName[fingerprint]
            return when {
                knownName.isNullOrBlank() -> {
                    fingerprintToPeerName[fingerprint] = peerName
                    peerName
                }
                knownName == peerName -> peerName
                isPlaceholderPeerName(knownName) && !isPlaceholderPeerName(peerName) -> {
                    fingerprintToPeerName[fingerprint] = peerName
                    moveChatState(context, knownName, peerName)
                    peerEndpoints.remove(knownName)
                    peerName
                }
                !isPlaceholderPeerName(knownName) && isPlaceholderPeerName(peerName) -> {
                    moveChatState(context, peerName, knownName)
                    peerEndpoints.remove(peerName)
                    knownName
                }
                else -> {
                    fingerprintToPeerName[fingerprint] = peerName
                    peerName
                }
            }
        }
    }

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
        synchronized(startStopLock) {
            if (isRunning) return
            isRunning = true
        }
        val appContext = context.applicationContext
        val port = listenerPort(appContext)
        try {
            log(appContext, "Starting Python P2P Relays on port $port...")
            // Start the Python P2P listener
            PythonBridge.startP2pListener(port)
            
            // Register incoming message callback from Python
            PythonBridge.registerMessageListener(object : PythonBridge.PyMessageListener {
                override fun onMessageReceived(sender: String, text: String) {
                    log(appContext, "Incoming secure P2P message from $sender: $text")
                    
                    try {
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                        val trimmed = text.trim()
                        if (trimmed.startsWith("{")) {
                            val json = org.json.JSONObject(trimmed)
                            val type = json.optString("type")
                            if (type == "profile_avatar_share") {
                                val b64 = json.optString("avatar_base64")
                                if (b64.isNotEmpty()) {
                                    try {
                                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        if (bitmap != null) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                peerAvatars[sender] = bitmap
                                            }
                                            log(appContext, "Successfully received and cached avatar in RAM for $sender")
                                        }
                                    } catch (e: Exception) {
                                        log(appContext, "Error decoding avatar: ${e.message}", "ERROR", e)
                                    }
                                }
                                return
                            } else if (type == "reply") {
                                val replyText = json.optString("text")
                                val replyToId = json.optString("reply_to_id")
                                val replyToText = json.optString("reply_to_text")
                                val replyToName = json.optString("reply_to_name")
                                
                                val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                                if (!activeSet.contains(sender)) {
                                    val newSet = activeSet.toMutableSet()
                                    newSet.add(sender)
                                    sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                                }
                                
                                val persistEnabled = sharedPrefs.getBoolean("persist_chat_history", true)
                                val db = ChatDatabaseHelper(appContext)
                                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                                
                                val rxMsg = Message(
                                    id = System.currentTimeMillis().toString(),
                                    text = replyText,
                                    isMe = false,
                                    timestamp = time,
                                    replyToId = replyToId,
                                    replyToText = replyToText,
                                    replyToName = replyToName
                                )
                                if (persistEnabled) {
                                    db.saveMessage(sender, rxMsg)
                                }
                                sharedPrefs.edit().putString("last_msg_$sender", replyText).apply()
                                onMessageReceived?.invoke(sender, text)
                                return
                            }
                        }

                        val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                        if (!activeSet.contains(sender)) {
                            val newSet = activeSet.toMutableSet()
                            newSet.add(sender)
                            sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                        }
                        
                        val persistEnabled = sharedPrefs.getBoolean("persist_chat_history", true)
                        val db = ChatDatabaseHelper(appContext)
                        var displayMessage = text
                        val incomingAttachment = parseIncomingAttachment(text)
                        if (incomingAttachment != null) {
                            displayMessage = incomingAttachment.displayMessage
                            if (persistEnabled) {
                                db.saveMessage(sender, Message(
                                    id = System.currentTimeMillis().toString(),
                                    text = incomingAttachment.displayMessage,
                                    isMe = false,
                                    timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                    attachmentType = incomingAttachment.attachmentType,
                                    attachmentUri = incomingAttachment.attachmentUri,
                                    attachmentName = incomingAttachment.attachmentName
                                ))
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
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                        .edit().putString("peer_fingerprint_$resolvedPeerName", fingerprint).apply()
                    log(appContext, "Secure Double Ratchet session established with $resolvedPeerName ($fingerprint) at $endpoint")
                    if (endpoint.isNotEmpty()) {
                        peerEndpoints[resolvedPeerName] = endpoint
                        
                        // Save to active chats so the UI updates and shows the peer chat screen
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                        val activeSet = sharedPrefs.getStringSet("active_chats", setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")) ?: setOf("Eleanor Vance", "Liam O'Connor", "Sarah Chen")
                        if (!activeSet.contains(resolvedPeerName)) {
                            val newSet = activeSet.toMutableSet()
                            newSet.add(resolvedPeerName)
                            sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                            sharedPrefs.edit().putString("transport_$resolvedPeerName", "DIRECT P2P").apply()
                        }

                        shareAvatar(appContext, resolvedPeerName, endpoint)
                    }
                }

                override fun onSessionClosed(peerName: String) {
                    log(appContext, "Secure Double Ratchet session closed with $peerName")
                }
            })
            
            log(appContext, "Python P2P Relays started successfully")
            
            // Start a periodic announcement and network interface monitoring loop
            thread(start = true, name = "TrackerAnnounceLoop", isDaemon = true) {
                var lastAddresses = emptyList<String>()
                var lastAnnounceTime = 0L
                while (isRunning) {
                    try {
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                        val username = sharedPrefs.getString("username_profile", "") ?: ""
                        val fingerprint = PythonBridge.getLocalFingerprint()
                        if (username.isNotBlank() && fingerprint != "Loading..." && fingerprint != "Not Initialized" && fingerprint != "Error") {
                            val currentAddresses = PythonBridge.getLocalAddresses()
                            val now = System.currentTimeMillis()
                            // Announce immediately if network interfaces changed, or periodically every 5 minutes (300,000 ms)
                            if (currentAddresses != lastAddresses || now - lastAnnounceTime > 300000) {
                                log(appContext, "Announcing self on tracker. Network changed: ${currentAddresses != lastAddresses}, IPs: $currentAddresses")
                                val success = PythonBridge.announceSelf(username, fingerprint, port)
                                log(appContext, "Announce self status: $success")
                                lastAddresses = currentAddresses
                                lastAnnounceTime = now
                            }
                        }
                    } catch (e: Exception) {
                        log(appContext, "Error in periodic announce", "ERROR", e)
                    }
                    try {
                        Thread.sleep(10000) // Check network change status every 10 seconds
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
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
        peerAvatars.clear()
        // Trigger Python shutdown/cleanup
        thread(start = true) {
            try {
                if (PythonBridge.isInitialized) {
                    val py = com.chaquo.python.Python.getInstance()
                    val bridge = py.getModule("discovery_bridge")
                    bridge.callAttr("shutdown_all_sessions")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down Python sessions", e)
            }
        }
    }

    fun shareAvatar(context: android.content.Context, peerName: String, endpoint: String) {
        thread(start = true, name = "AvatarShareThread") {
            try {
                val file = java.io.File(context.filesDir, "profile_avatar.jpg")
                if (file.exists()) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val outputStream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
                        val bytes = outputStream.toByteArray()
                        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        
                        val json = org.json.JSONObject().apply {
                            put("type", "profile_avatar_share")
                            put("avatar_base64", b64)
                        }
                        val payload = json.toString()
                        val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                            .getString("peer_fingerprint_$peerName", null)
                        
                        log(context, "Sending profile avatar to $peerName (length: ${payload.length})")
                        val success = PythonBridge.sendP2pMessage(peerName, endpoint, payload, expectedFingerprint)
                        log(context, "Avatar send status to $peerName: $success")
                    }
                } else {
                    log(context, "profile_avatar.jpg does not exist, skipping avatar share.")
                }
            } catch (e: Exception) {
                log(context, "Failed to share avatar with $peerName", "ERROR", e)
            }
        }
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
                val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("peer_fingerprint_$targetPeerName", null)
                val success = PythonBridge.sendP2pMessage(targetPeerName, endpoint, text, expectedFingerprint)
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
                val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("peer_fingerprint_$targetPeerName", null)
                val success = PythonBridge.sendP2pFile(targetPeerName, endpoint, filePath, expectedFingerprint)
                log(context, "Sending file status to $targetPeerName: ${if (success) "SUCCESS" else "FAILED"}")
                onResult(success)
            } catch (e: Exception) {
                log(context, "Failed to send secure file to $endpoint", "ERROR", e)
                onResult(false)
            }
        }
    }

    fun reconnectSession(context: android.content.Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        thread(start = true) {
            try {
                val endpoint = peerEndpoints[peerName] ?: ""
                val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                    .getString("peer_fingerprint_$peerName", null)
                log(context, "Requesting reconnection for $peerName at endpoint '$endpoint'")
                val success = PythonBridge.reconnectPeerSession(peerName, endpoint, expectedFingerprint)
                onResult(success)
            } catch (e: Exception) {
                log(context, "Failed to initiate reconnection for $peerName", "ERROR", e)
                onResult(false)
            }
        }
    }
}
