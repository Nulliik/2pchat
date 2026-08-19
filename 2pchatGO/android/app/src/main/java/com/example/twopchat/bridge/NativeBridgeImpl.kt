package com.example.twopchat.bridge

import android.util.Log
import com.example.twopchat.NativeBridge
import com.example.twopchat.P2PMessageRelay
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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
    // Connecting is asynchronous in the native core. Keep outbound messages
    // until its authenticated-session callback arrives instead of losing the
    // first message while the X3DH handshake is still in progress.
    private val pendingMessages = ConcurrentHashMap<String, ConcurrentLinkedQueue<PendingMessage>>()

    init {
        NativeBridge.initialize()
        setupNativeCallbacks()
    }

    private fun setupNativeCallbacks() {
        NativeBridge.onPeerConnectedListener = { peerFP, endpoint ->
            Log.i(TAG, "[GoCore] Peer connected: $peerFP @ $endpoint")
            onlinePeers[peerFP] = true
            val peerName = peerNameMap[peerFP] ?: peerFP
            onlinePeers[peerName] = true
            nameToFpMap[peerName] = peerFP
            sessionListener?.onSessionEstablished(peerName, peerFP, endpoint, "direct", "")
            flushPendingMessages(peerFP)
        }

        NativeBridge.onPeerDisconnectedListener = { peerFP, reason ->
            Log.i(TAG, "[GoCore] Peer disconnected: $peerFP, reason: $reason")
            onlinePeers[peerFP] = false
            val peerName = peerNameMap[peerFP] ?: peerFP
            onlinePeers[peerName] = false
            sessionListener?.onSessionClosed(peerName, peerFP)
        }

        NativeBridge.onMessageReceivedListener = { peerFP, payload, messageID ->
            val payloadStr = String(payload, Charsets.UTF_8)
            val senderName = peerNameMap[peerFP] ?: peerFP
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
            val senderName = peerNameMap[peerFP] ?: peerFP
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
            flushPendingMessages(fingerprint)
        }
    }

    override fun configureLocalIdentity(username: String, fingerprint: String, aboutMe: String): Boolean {
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
        val trackers = com.example.twopchat.TrackerPreferences.getActiveTrackerUrls(context)

        val hashes = mutableListOf<String>()
        if (fingerprint.isNotBlank()) {
            hashes.add(fingerprint)
        }
        if (!rendezvousCode.isNullOrBlank()) {
            hashes.add(rendezvousCode)
        }

        NativeBridge.startDiscovery(trackers, hashes, port)
        if (fingerprint.isNotBlank()) {
            NativeBridge.announceSelf(fingerprint, port)
        }
        return true
    }

    override fun sendP2pMessage(peerName: String, endpoint: String, payload: String, expectedFingerprint: String?): Boolean {
        val activeFP = when {
            nameToFpMap[peerName]?.let { onlinePeers[it] == true } == true -> nameToFpMap[peerName]!!
            !expectedFingerprint.isNullOrBlank() && onlinePeers[expectedFingerprint] == true -> expectedFingerprint
            onlinePeers[peerName] == true -> nameToFpMap[peerName] ?: peerName
            else -> null
        }

        if (activeFP == null && endpoint.isNotBlank()) {
            val targetKey = expectedFingerprint?.takeIf { it.isNotBlank() } ?: peerName
            val queue = pendingMessages.getOrPut(targetKey) { ConcurrentLinkedQueue() }
            pruneExpiredPending(queue)
            queue.add(PendingMessage(payload, System.currentTimeMillis()))
            if (targetKey != peerName) {
                val nameQueue = pendingMessages.getOrPut(peerName) { ConcurrentLinkedQueue() }
                pruneExpiredPending(nameQueue)
                nameQueue.add(PendingMessage(payload, System.currentTimeMillis()))
            }
            val candidateList = endpoint.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (candidateList.size > 1) {
                NativeBridge.probePeer(candidateList, expectedFingerprint.orEmpty())
            } else {
                NativeBridge.connectPeer(endpoint, expectedFingerprint.orEmpty())
            }
            return true
        }

        val target = activeFP ?: expectedFingerprint?.takeIf { it.isNotBlank() } ?: nameToFpMap[peerName] ?: peerName
        val msgId = NativeBridge.sendMessage(target, payload)
        return msgId != null
    }

    private fun flushPendingMessages(peerFingerprint: String) {
        val peerName = peerNameMap[peerFingerprint]
        val queuesToFlush = mutableListOf<ConcurrentLinkedQueue<PendingMessage>>()
        pendingMessages.remove(peerFingerprint)?.let { queuesToFlush.add(it) }
        if (peerName != null) {
            pendingMessages.remove(peerName)?.let { queuesToFlush.add(it) }
        }
        if (queuesToFlush.isEmpty()) return

        Thread {
            val now = System.currentTimeMillis()
            for (queue in queuesToFlush) {
                while (true) {
                    val pending = queue.poll() ?: break
                    if (pending.isExpired(MESSAGE_TTL_MS, now)) {
                        Log.d(TAG, "[GoCore] Discarded expired pending message for $peerFingerprint (age: ${now - pending.timestampMs}ms)")
                        continue
                    }
                    if (NativeBridge.sendMessage(peerFingerprint, pending.payload) == null) {
                        // Preserve the unsent tail for a later reconnect if not expired
                        if (!pending.isExpired(MESSAGE_TTL_MS, System.currentTimeMillis())) {
                            queue.add(pending)
                        }
                        pendingMessages.merge(peerFingerprint, queue) { current, queued ->
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
        val activeFP = when {
            nameToFpMap[peerName]?.let { onlinePeers[it] == true } == true -> nameToFpMap[peerName]!!
            !expectedFingerprint.isNullOrBlank() && onlinePeers[expectedFingerprint] == true -> expectedFingerprint
            onlinePeers[peerName] == true -> nameToFpMap[peerName] ?: peerName
            else -> null
        }

        if (activeFP == null && endpoint.isNotBlank()) {
            val candidateList = endpoint.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (candidateList.size > 1) {
                NativeBridge.probePeer(candidateList, expectedFingerprint.orEmpty())
            } else {
                NativeBridge.connectPeer(endpoint, expectedFingerprint.orEmpty())
            }
        }

        val target = activeFP ?: expectedFingerprint?.takeIf { it.isNotBlank() } ?: nameToFpMap[peerName] ?: peerName
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
        if (!fingerprint.isNullOrBlank()) {
            peerNameMap[fingerprint] = peerName
            nameToFpMap[peerName] = fingerprint
        }
        val candidateList = endpoint.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (candidateList.size > 1) {
            NativeBridge.probePeer(candidateList, fingerprint.orEmpty())
        } else {
            NativeBridge.connectPeer(endpoint, fingerprint.orEmpty())
        }
    }

    override fun closePeerSession(peerName: String, expectedFingerprint: String?): Boolean {
        if (!expectedFingerprint.isNullOrBlank()) onlinePeers[expectedFingerprint] = false
        nameToFpMap[peerName]?.let { onlinePeers[it] = false }
        onlinePeers[peerName] = false
        return true
    }

    override fun isPeerOnline(peerName: String, expectedFingerprint: String?): Boolean {
        if (!expectedFingerprint.isNullOrBlank()) {
            if (NativeBridge.isPeerOnline(expectedFingerprint)) return true
            if (onlinePeers[expectedFingerprint] == true) return true
        }
        val fp = nameToFpMap[peerName]
        if (!fp.isNullOrBlank()) {
            if (NativeBridge.isPeerOnline(fp)) return true
            if (onlinePeers[fp] == true) return true
        }
        if (NativeBridge.isPeerOnline(peerName)) return true
        if (onlinePeers[peerName] == true) return true
        return false
    }

    override fun shutdownAllSessions(): Boolean {
        NativeBridge.stopDiscovery()
        NativeBridge.stopListener()
        onlinePeers.clear()
        pendingMessages.clear()
        return true
    }

    override fun resetStaleEndpointCooldowns(): Boolean {
        // Go discovery prober handles network interface changes
        return true
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
        val targetCode = sharedCode?.takeIf { it.isNotBlank() }
            ?: expectedFingerprint?.takeIf { it.isNotBlank() }
            ?: query
        val infoHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(targetCode.toByteArray(Charsets.UTF_8))
            .take(20)
            .toByteArray()
            .joinToString("") { "%02x".format(it) }

        NativeBridge.startDiscovery(infoHashes = listOf(infoHash))

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
}
