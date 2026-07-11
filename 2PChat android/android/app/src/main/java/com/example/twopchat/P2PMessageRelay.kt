package com.example.twopchat

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.ui.chat.Message
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.NotificationChannel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import android.util.Base64
import androidx.compose.runtime.mutableStateMapOf
import com.chaquo.python.Python

object P2PMessageRelay {
    private const val TAG = "P2PMessageRelay"
    private const val LISTENER_PORT = 50001
    private val startStopLock = Any()
    private val identityLock = Any()
    private var isRunning = false

    /** All installations listen on the documented P2P port; identity is the fingerprint, not the port. */
    fun listenerPort(context: Context): Int {
        return LISTENER_PORT
    }

    fun refreshAnnouncement(context: Context) {
        val appContext = context.applicationContext
        thread(start = true, name = "ManualTrackerAnnounce") {
            val prefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
            val username = prefs.getString("username_profile", "").orEmpty()
            val fingerprint = PythonBridge.getLocalFingerprint()
            if (username.isNotBlank() && fingerprint.length >= 40) {
                val success = PythonBridge.announceSelf(username, fingerprint, listenerPort(appContext), force = true)
                log(appContext, "Forced announce after transport setting change: $success")
            }
        }
    }

    // Maps peer name to their resolved IP:Port endpoint
    val peerEndpoints = mutableStateMapOf<String, String>()
    val peerConnectionTransports = mutableStateMapOf<String, String>()
    val peerSessionStates = mutableStateMapOf<String, Boolean>()
    private val fingerprintToPeerName = ConcurrentHashMap<String, String>()
    private val processingOfflineQueues = ConcurrentHashMap.newKeySet<String>()

    // Maps peer name to their profile avatar bitmap in RAM
    val peerAvatars = mutableStateMapOf<String, Bitmap>()

    // Maps peer name to typing state
    val peerTypingStates = mutableStateMapOf<String, Boolean>()

    interface MessageListener {
        fun onMessageReceived(sender: String, text: String)
        fun onMessageStatusChanged(sender: String, msgId: String, status: String)
    }

    private val messageListeners = java.util.concurrent.CopyOnWriteArrayList<MessageListener>()
    var activeChatPeerName: String? = null

    fun registerMessageListener(listener: MessageListener) {
        messageListeners.add(listener)
    }

    fun unregisterMessageListener(listener: MessageListener) {
        messageListeners.remove(listener)
    }

    private const val MESSAGES_CHANNEL_ID = "p2p_chat_messages"

    private fun showNotification(context: Context, sender: String, text: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    MESSAGES_CHANNEL_ID,
                    "P2P Messages",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
            }

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            } ?: Intent()

            var pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                pendingFlags = pendingFlags or PendingIntent.FLAG_IMMUTABLE
            }
            val pendingIntent = PendingIntent.getActivity(context, sender.hashCode(), intent, pendingFlags)

            val displayMessage = parseIncomingAttachment(text)?.displayMessage ?: text

            val builder = NotificationCompat.Builder(context, MESSAGES_CHANNEL_ID)
                .setContentTitle(sender)
                .setContentText(displayMessage)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            notificationManager.notify(sender.hashCode(), builder.build())
        } catch (e: Exception) {
            log(context, "Failed to show message notification: ${e.message}", "ERROR", e)
        }
    }

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
            val json = JSONObject(trimmed)
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
        context: Context,
        fromName: String,
        toName: String
    ) {
        if (fromName == toName) return
        val sharedPrefs = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
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

        val keysToMove = listOf("last_msg_", "transport_", "last_endpoint_", "verified_peer_", "fingerprint_mismatch_")
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
        context: Context,
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
                    Handler(Looper.getMainLooper()).post {
                        peerEndpoints.remove(knownName)
                    }
                    peerName
                }
                !isPlaceholderPeerName(knownName) && isPlaceholderPeerName(peerName) -> {
                    moveChatState(context, peerName, knownName)
                    Handler(Looper.getMainLooper()).post {
                        peerEndpoints.remove(peerName)
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

    private fun log(context: Context, message: String, level: String = "INFO", error: Throwable? = null) {
        val fullMsg = if (error != null) "$message: ${Log.getStackTraceString(error)}" else message
        if (level == "ERROR") {
            Log.e(TAG, fullMsg)
        } else {
            Log.i(TAG, fullMsg)
        }
        try {
            val logDir = File(context.filesDir, "config")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(logDir, "app.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", Locale.getDefault()).format(Date())
            logFile.appendText("$timestamp [KOTLIN_$level] $TAG: $fullMsg\n")
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Start the background Python P2P server.
     */
    fun startServer(context: Context) {
        synchronized(startStopLock) {
            if (isRunning) return
            isRunning = true
        }
        val appContext = context.applicationContext
        loadPersistedAvatars(appContext)
        val port = listenerPort(appContext)
        try {
            log(appContext, "Starting Python P2P Relays on port $port...")
            val ipv4Enabled = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                .getBoolean("settings_ipv4", true)
            PythonBridge.setIpv4Enabled(ipv4Enabled)
            // Start the Python P2P listener
            PythonBridge.startP2pListener(port)
            
            // Register incoming message callback from Python
            PythonBridge.registerMessageListener(object : PythonBridge.PyMessageListener {
                override fun onMessageReceived(sender: String, text: String) {
                    log(appContext, "Incoming secure P2P message (${text.toByteArray().size} bytes)")
                    
                    try {
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", android.content.Context.MODE_PRIVATE)
                        val trimmed = text.trim()
                        if (trimmed.startsWith("{")) {
                            val json = org.json.JSONObject(trimmed)
                            val type = json.optString("type")
                            if (type == "profile_avatar_share") {
                                val b64 = json.optString("avatar_base64")
                                // Avatars are control-plane thumbnails, not file transfers. Bound their
                                // encoded size and decoded dimensions before allocating a full bitmap.
                                if (b64.isNotEmpty() && b64.length <= 2_000_000) {
                                    try {
                                        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
                                            bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L) return
                                        var sample = 1
                                        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                                            bytes, 0, bytes.size,
                                            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                                        )
                                        if (bitmap != null) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                peerAvatars[sender] = bitmap
                                            }
                                            log(appContext, "Successfully received and cached avatar in RAM for $sender")
                                            
                                            // Persist avatar to disk
                                            try {
                                                val avatarsDir = java.io.File(appContext.filesDir, "avatars")
                                                if (!avatarsDir.exists()) avatarsDir.mkdirs()
                                                val avatarFile = java.io.File(avatarsDir, "${sender}.jpg")
                                                val outStream = java.io.FileOutputStream(avatarFile)
                                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outStream)
                                                outStream.flush()
                                                outStream.close()
                                                log(appContext, "Saved avatar for $sender to ${avatarFile.absolutePath}")
                                            } catch (saveEx: Exception) {
                                                log(appContext, "Failed to save avatar file: ${saveEx.message}", "ERROR", saveEx)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        log(appContext, "Error decoding avatar: ${e.message}", "ERROR", e)
                                    }
                                }
                                return
                            } else if (type == "typing_state") {
                                val isTyping = json.optBoolean("is_typing", false)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    peerTypingStates[sender] = isTyping
                                }
                                return
                            } else if (type == "read_receipt") {
                                val msgId = json.optString("message_id")
                                if (msgId.isNotEmpty()) {
                                    val db = ChatDatabaseHelper(appContext)
                                    db.updateMessageStatus(msgId, "READ")
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        messageListeners.forEach { it.onMessageStatusChanged(sender, msgId, "READ") }
                                    }
                                }
                                return
                            } else if (type == "reply") {
                                val replyText = json.optString("text")
                                val replyToId = json.optString("reply_to_id")
                                val replyToText = json.optString("reply_to_text")
                                val replyToName = json.optString("reply_to_name")
                                
                                val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
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
                                    replyToName = replyToName,
                                    status = "SENT"
                                )
                                if (persistEnabled) {
                                    db.saveMessage(sender, rxMsg)
                                }
                                sharedPrefs.edit().putString("last_msg_$sender", com.example.twopchat.SecureStorage.encrypt(replyText)).apply()
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    messageListeners.forEach { it.onMessageReceived(sender, text) }
                                    if (activeChatPeerName != sender) {
                                        showNotification(appContext, sender, text)
                                    }
                                }
                                return
                            }
                        }

                        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
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
                                    attachmentName = incomingAttachment.attachmentName,
                                    status = "SENT"
                                ))
                            }
                        } else {
                            if (persistEnabled) {
                                db.saveMessage(sender, Message(
                                    id = System.currentTimeMillis().toString(),
                                    text = text,
                                    isMe = false,
                                    timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                                    status = "SENT"
                                ))
                            }
                        }
                        sharedPrefs.edit().putString("last_msg_$sender", com.example.twopchat.SecureStorage.encrypt(displayMessage)).apply()
                    } catch (ex: Exception) {
                        log(appContext, "Failed to persist incoming message to SharedPreferences/SQLite", "ERROR", ex)
                    }

                    // Dispatch to active chat UI listener if any
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        messageListeners.forEach { it.onMessageReceived(sender, text) }
                        if (activeChatPeerName != sender) {
                            showNotification(appContext, sender, text)
                        }
                    }
                }
            })

            // Register session status callbacks from Python
            PythonBridge.registerSessionListener(object : PythonBridge.PySessionListener {
                override fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String, transport: String) {
                    val resolvedPeerName = canonicalPeerName(appContext, peerName, fingerprint)
                    appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        .edit().apply {
                            putString("peer_fingerprint_$resolvedPeerName", fingerprint)
                            if (endpoint.isNotEmpty()) {
                                putString("last_endpoint_$resolvedPeerName", endpoint)
                            }
                            apply()
                        }
                    log(appContext, "Secure Double Ratchet session established with $resolvedPeerName ($fingerprint) at $endpoint")
                    Handler(Looper.getMainLooper()).post {
                        peerSessionStates[resolvedPeerName] = true
                        peerConnectionTransports[resolvedPeerName] = transport
                        if (endpoint.isNotEmpty()) {
                            peerEndpoints[resolvedPeerName] = endpoint
                        }
                    }

                    if (endpoint.isNotEmpty()) {
                        
                        // Save to active chats so the UI updates and shows the peer chat screen
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit()
                            .putString("transport_$resolvedPeerName", transport)
                            .apply()
                        val activeSet = sharedPrefs.getStringSet("active_chats", emptySet()) ?: emptySet()
                        if (!activeSet.contains(resolvedPeerName)) {
                            val newSet = activeSet.toMutableSet()
                            newSet.add(resolvedPeerName)
                            sharedPrefs.edit().putStringSet("active_chats", newSet).apply()
                        }

                        shareAvatar(appContext, resolvedPeerName, endpoint)
                        processOfflineQueue(appContext, resolvedPeerName, endpoint)
                    }
                }

                override fun onSessionClosed(peerName: String) {
                    val fingerprint = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        .getString("peer_fingerprint_$peerName", null)
                    val resolvedPeerName = if (fingerprint != null) canonicalPeerName(appContext, peerName, fingerprint) else peerName
                    log(appContext, "Secure Double Ratchet session closed with $resolvedPeerName")
                    Handler(Looper.getMainLooper()).post {
                        peerConnectionTransports.remove(resolvedPeerName)
                        peerSessionStates.remove(resolvedPeerName)
                    }
                }
            })
            
            log(appContext, "Python P2P Relays started successfully")
            
            // Start a periodic announcement and network interface monitoring loop
            thread(start = true, name = "TrackerAnnounceLoop", isDaemon = true) {
                var lastAddresses = emptyList<String>()
                var candidateAddresses = emptyList<String>()
                var stableCandidateSamples = 0
                var lastAnnounceTime = 0L
                while (isRunning) {
                    try {
                        val sharedPrefs = appContext.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        val username = sharedPrefs.getString("username_profile", "") ?: ""
                        val fingerprint = PythonBridge.getLocalFingerprint()
                        if (username.isNotBlank() && fingerprint != "Loading..." && fingerprint != "Not Initialized" && fingerprint != "Error") {
                            val currentAddresses = PythonBridge.getLocalAddresses().sorted()
                            val now = System.currentTimeMillis()
                            if (currentAddresses == candidateAddresses) {
                                stableCandidateSamples++
                            } else {
                                candidateAddresses = currentAddresses
                                stableCandidateSamples = 1
                            }

                            val firstAnnounce = lastAnnounceTime == 0L
                            val networkChangedAndStable = currentAddresses != lastAddresses && stableCandidateSamples >= 3
                            val periodicRefreshDue = now - lastAnnounceTime >= 300_000L
                            if (firstAnnounce || networkChangedAndStable || periodicRefreshDue) {
                                log(appContext, "Announcing self on tracker. Network changed and stable: $networkChangedAndStable, IPs: $currentAddresses")
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
                    val py = Python.getInstance()
                    val bridge = py.getModule("discovery_bridge")
                    bridge.callAttr("shutdown_all_sessions")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error shutting down Python sessions", e)
            }
        }
    }

    fun shareAvatar(context: Context, peerName: String, endpoint: String) {
        thread(start = true, name = "AvatarShareThread") {
            try {
                val file = File(context.filesDir, "profile_avatar.jpg")
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                        val bytes = outputStream.toByteArray()
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        
                        val json = JSONObject().apply {
                            put("type", "profile_avatar_share")
                            put("avatar_base64", b64)
                        }
                        val payload = json.toString()
                        val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
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

    fun shareAvatarWithConnectedPeers(context: Context) {
        peerEndpoints.toMap().forEach { (peerName, endpoint) ->
            shareAvatar(context.applicationContext, peerName, endpoint)
        }
    }

    /**
     * Send an encrypted Double Ratchet message to a resolved peer's endpoint.
     */
    fun sendMessage(context: Context, endpoint: String, senderName: String, text: String, onResult: (Boolean) -> Unit = {}) {
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
                
                log(context, "Sending secure message via Python transport")
                val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                    .getString("peer_fingerprint_$targetPeerName", null)
                val success = PythonBridge.sendP2pMessage(targetPeerName, endpoint, text, expectedFingerprint)
                log(context, "Secure message send: ${if (success) "SUCCESS" else "FAILED"}")
                Handler(Looper.getMainLooper()).post { onResult(success) }
            } catch (e: Exception) {
                log(context, "Failed to send secure message", "ERROR")
                Handler(Looper.getMainLooper()).post { onResult(false) }
            }
        }
    }

    /**
     * Send an encrypted file to a resolved peer's endpoint.
     */
    fun sendFile(context: Context, endpoint: String, filePath: String, onResult: (Boolean) -> Unit = {}) {
        thread(start = true) {
            try {
                var targetPeerName = "Direct Peer"
                for ((name, ep) in peerEndpoints) {
                    if (ep == endpoint) {
                        targetPeerName = name
                        break
                    }
                }
                
                log(context, "Sending secure file via Python transport")
                val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                    .getString("peer_fingerprint_$targetPeerName", null)
                val success = PythonBridge.sendP2pFile(targetPeerName, endpoint, filePath, expectedFingerprint)
                log(context, "Sending file status to $targetPeerName: ${if (success) "SUCCESS" else "FAILED"}")
                Handler(Looper.getMainLooper()).post { onResult(success) }
            } catch (e: Exception) {
                log(context, "Failed to send secure file", "ERROR")
                Handler(Looper.getMainLooper()).post { onResult(false) }
            }
        }
    }

    fun reconnectSession(context: Context, peerName: String, onResult: (Boolean) -> Unit = {}) {
        thread(start = true) {
            try {
                val prefs = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                val endpoint = peerEndpoints[peerName]
                    ?: prefs.getString("last_endpoint_$peerName", "").orEmpty()
                val expectedFingerprint = prefs.getString("peer_fingerprint_$peerName", null)
                log(context, "Requesting reconnection for $peerName at endpoint '$endpoint'")
                val success = PythonBridge.reconnectPeerSession(peerName, endpoint, expectedFingerprint)
                onResult(success)
            } catch (e: Exception) {
                log(context, "Failed to initiate reconnection for $peerName", "ERROR", e)
                onResult(false)
            }
        }
    }

    fun sendTypingState(context: Context, peerName: String, endpoint: String, isTyping: Boolean) {
        thread(start = true, name = "TypingStateThread") {
            try {
                val json = JSONObject().apply {
                    put("type", "typing_state")
                    put("is_typing", isTyping)
                }
                val payload = json.toString()
                val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                    .getString("peer_fingerprint_$peerName", null)
                PythonBridge.sendP2pMessage(peerName, endpoint, payload, expectedFingerprint)
            } catch (e: Exception) {
                // Ignore silent typing state send errors
            }
        }
    }

    fun sendReadReceipt(context: Context, peerName: String, endpoint: String, messageId: String) {
        thread(start = true, name = "ReadReceiptThread") {
            try {
                val json = JSONObject().apply {
                    put("type", "read_receipt")
                    put("message_id", messageId)
                }
                val payload = json.toString()
                val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                    .getString("peer_fingerprint_$peerName", null)
                PythonBridge.sendP2pMessage(peerName, endpoint, payload, expectedFingerprint)
            } catch (e: Exception) {
                // Ignore silent read receipt send errors
            }
        }
    }

    fun processOfflineQueue(context: Context, peerName: String, endpoint: String) {
        if (endpoint.isBlank() || !processingOfflineQueues.add(peerName)) return
        thread(start = true, name = "OfflineQueueThread") {
            try {
                val db = ChatDatabaseHelper(context)
                val pending = db.getPendingMessagesForPeer(peerName)
                if (pending.isNotEmpty()) {
                    log(context, "Processing ${pending.size} pending offline messages for $peerName")
                    val expectedFingerprint = context.getSharedPreferences("2pchat_prefs", Context.MODE_PRIVATE)
                        .getString("peer_fingerprint_$peerName", null)
                    
                    for (msg in pending) {
                        val payload = if (msg.replyToId != null) {
                            JSONObject().apply {
                                put("type", "reply")
                                put("text", msg.text)
                                put("reply_to_id", msg.replyToId)
                                put("reply_to_text", msg.replyToText)
                                put("reply_to_name", msg.replyToName)
                            }.toString()
                        } else {
                            msg.text
                        }
                        
                        val success = if (msg.attachmentType != null && !msg.attachmentUri.isNullOrBlank()) {
                            val attachment = File(msg.attachmentUri)
                            attachment.exists() && PythonBridge.sendP2pFile(
                                peerName,
                                endpoint,
                                attachment.absolutePath,
                                expectedFingerprint
                            )
                        } else {
                            PythonBridge.sendP2pMessage(peerName, endpoint, payload, expectedFingerprint)
                        }
                        if (success) {
                            db.updateMessageStatus(msg.id, "SENT")
                            Handler(Looper.getMainLooper()).post {
                                messageListeners.forEach { it.onMessageStatusChanged(peerName, msg.id, "SENT") }
                            }
                        } else {
                            log(context, "Failed to send pending message ${msg.id}, stopping queue processing.")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                log(context, "Error in processOfflineQueue: ${e.message}", "ERROR", e)
            } finally {
                processingOfflineQueues.remove(peerName)
            }
        }
    }

    fun loadPersistedAvatars(context: Context) {
        thread(start = true, name = "LoadAvatarsThread") {
            try {
                val avatarsDir = File(context.filesDir, "avatars")
                if (avatarsDir.exists() && avatarsDir.isDirectory) {
                    val files = avatarsDir.listFiles()
                    if (files != null) {
                        for (file in files) {
                            if (file.isFile && file.name.endsWith(".jpg")) {
                                val peerName = file.name.substringBeforeLast(".jpg")
                                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                if (bitmap != null) {
                                    Handler(Looper.getMainLooper()).post {
                                        peerAvatars[peerName] = bitmap
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading persisted avatars", e)
            }
        }
    }
}
