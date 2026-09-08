package com.example.twopchat

import android.content.Context
import com.example.twopchat.logging.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * JNI Bridge to high-performance native Go P2P core (lib2pcore.so).
 */
object NativeBridge {
    private const val TAG = "NativeBridge"

    @Volatile
    var isLoaded = false
        private set

    // Callbacks invoked by background Go goroutines via JNI
    var onPeerConnectedListener: ((peerFP: String, endpoint: String) -> Unit)? = null
    var onPeerDisconnectedListener: ((peerFP: String, reason: String) -> Unit)? = null
    var onMessageReceivedListener: ((peerFP: String, payload: ByteArray, messageID: String) -> Unit)? = null
    var onErrorListener: ((code: Int, message: String) -> Unit)? = null
    var onPeerDiscoveredListener: ((infoHashHex: String, endpoint: String, source: String) -> Unit)? = null
    var onTrackerStatusListener: ((trackerUrl: String, success: Boolean, peerCount: Int, elapsedMs: Long, detail: String) -> Unit)? = null
    var onFileProgressListener: ((peerFP: String, messageId: String, transferred: Long, total: Long, speedKbps: Double) -> Unit)? = null
    var onDiscoverySeqPersistListener: ((seq: Long) -> Unit)? = null
    var onHeartbeatSeqPersistListener: ((groupId: String, seq: Long) -> Unit)? = null

    private fun logI(msg: String) {
        runCatching { SafeLog.i(TAG, msg) }
    }

    private fun logE(msg: String, error: Throwable? = null) {
        runCatching { SafeLog.e(TAG, msg, error) }
    }

    init {
        try {
            System.loadLibrary("2pcore")
            isLoaded = true
            logI("lib2pcore.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            logE("Failed to load lib2pcore native library", e)
            isLoaded = false
        } catch (e: Exception) {
            logE("Unexpected error loading lib2pcore", e)
            isLoaded = false
        }
    }

    data class LocalIdentity(
        val identityPub: String,
        val verifyPub: String,
        val signedPrekeyPub: String,
        val prekeySignature: String,
        val fingerprint: String,
    )

    fun setStorageKey(key: ByteArray): Boolean {
        if (!isLoaded) return false
        return try {
            nativeSetStorageKey(key)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetStorageKey failed", e)
            false
        }
    }

    private fun ensureStorageKey(context: Context? = null) {
        if (!isLoaded) return
        try {
            val ctx = context ?: try { com.example.twopchat.yggdrasil.GlobalApplication.getContext() } catch (_: Throwable) { null }
            if (ctx != null) {
                val storageKey = com.example.twopchat.security.SecureStorage.getOrGenerateGoStorageKey(ctx)
                nativeSetStorageKey(storageKey)
                com.example.twopchat.security.SecurityUtils.zeroize(storageKey)
            }
        } catch (e: Throwable) {
            SafeLog.e(TAG, "ensureStorageKey failed", e)
        }
    }

    fun setStorageDir(dir: String) {
        if (!isLoaded) return
        try {
            ensureStorageKey()
            nativeSetStorageDir(dir)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetStorageDir failed", e)
        }
    }

    @Volatile
    var isInitializeOverride: Boolean? = null
        internal set

    fun initialize(): Boolean {
        isInitializeOverride?.let { return it }
        if (!isLoaded) return false
        return try {
            // The Go manager must know the app-private directory before its
            // first Init call. Otherwise it creates an in-memory identity and
            // every process restart looks like a hostile peer-key change.
            val context = com.example.twopchat.yggdrasil.GlobalApplication.getContext()
            ensureStorageKey(context)
            nativeSetStorageDir(context.filesDir.absolutePath)
            nativeInit()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeInit failed", e)
            false
        }
    }

    fun echo(message: String): String {
        if (!isLoaded) return "Native library not loaded"
        return try {
            nativeEcho(message) ?: ""
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeEcho failed", e)
            "Error: ${e.message}"
        }
    }

    @Volatile
    private var cachedLocalIdentity: LocalIdentity? = null

    fun getLocalIdentity(): LocalIdentity? {
        if (!isLoaded) return null
        val cached = cachedLocalIdentity
        if (cached != null && cached.fingerprint.isNotBlank()) {
            return cached
        }
        val jsonStr = try {
            nativeGetLocalIdentityJSON()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetLocalIdentityJSON failed", e)
            null
        } ?: return null

        return try {
            val json = JSONObject(jsonStr)
            val identity = LocalIdentity(
                identityPub = json.optString("identityPub"),
                verifyPub = json.optString("verifyPub"),
                signedPrekeyPub = json.optString("signedPrekeyPub"),
                prekeySignature = json.optString("prekeySignature"),
                fingerprint = json.optString("fingerprint"),
            )
            cachedLocalIdentity = identity
            identity
        } catch (e: Exception) {
            SafeLog.e(TAG, "Failed to parse local identity JSON", e)
            null
        }
    }

    fun getFingerprint(publicKey: ByteArray): String? {
        if (!isLoaded) return null
        return try {
            nativeGetFingerprint(publicKey)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetFingerprint failed", e)
            null
        }
    }

    fun getSafetyNumber(
        myPub: ByteArray,
        theirPub: ByteArray,
        myVerify: ByteArray? = null,
        theirVerify: ByteArray? = null,
    ): String? {
        if (!isLoaded) return null
        return try {
            nativeGetSafetyNumber(myPub, theirPub, myVerify ?: ByteArray(0), theirVerify ?: ByteArray(0))
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetSafetyNumber failed", e)
            null
        }
    }

    // --- Networking Methods ---

    fun startListener(port: Int = 50001): Boolean {
        if (!isLoaded) return false
        return try {
            nativeStartListener(port)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeStartListener failed", e)
            false
        }
    }

    fun stopListener(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeStopListener()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeStopListener failed", e)
            false
        }
    }

    fun applyPolicy(policyFlags: Int): Boolean {
        if (!isLoaded) return false
        return try {
            nativeApplyPolicy(policyFlags)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeApplyPolicy failed", e)
            false
        }
    }

    const val POLICY_FLAG_ALLOW_LAN = 1 shl 0
    const val POLICY_FLAG_ALLOW_WAN = 1 shl 1
    const val POLICY_FLAG_ALLOW_YGGDRASIL = 1 shl 2
    const val POLICY_FLAG_ALLOW_ONION = 1 shl 3
    const val POLICY_FLAG_ALLOW_LOCAL_DNS = 1 shl 4

    fun setPeerPolicy(peerFP: String, policyFlags: Int): Boolean {
        if (!isLoaded || peerFP.isBlank()) return false
        return try {
            nativeSetPeerPolicy(peerFP, policyFlags)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetPeerPolicy failed", e)
            false
        }
    }

    fun connectPeer(endpoint: String, expectedFingerprint: String = "", policyFlags: Int = 0): Boolean {
        if (!isLoaded) return false
        return try {
            nativeConnectPeer(endpoint, expectedFingerprint, policyFlags)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeConnectPeer failed", e)
            false
        }
    }

    fun updatePeerNameMapping(peerFingerprint: String, nickname: String): Boolean {
        if (!isLoaded || peerFingerprint.isBlank() || nickname.isBlank()) return false
        return try {
            nativeUpdatePeerNameMapping(peerFingerprint, nickname)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeUpdatePeerNameMapping failed", e)
            false
        }
    }

    fun sendMessage(peerFingerprint: String, text: String): String? {
        if (!isLoaded) return null
        return try {
            nativeSendMessage(peerFingerprint, text)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSendMessage failed", e)
            null
        }
    }

    fun getPeerProtocol(peerFingerprint: String): String? {
        if (!isLoaded || peerFingerprint.isBlank()) return null
        return nativeGetPeerProtocol(peerFingerprint)
    }

    private external fun nativeGetPeerProtocol(peerFingerprint: String): String?

    fun isPeerOnline(peerFingerprint: String): Boolean {
        if (!isLoaded || peerFingerprint.isBlank()) return false
        return try {
            nativeIsPeerOnline(peerFingerprint)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeIsPeerOnline failed", e)
            false
        }
    }

    fun sendFile(
        peerFingerprint: String,
        filePath: String,
        messageId: String = "",
        fileName: String = "",
        caption: String = "",
        emoji: String = "",
        albumId: String = "",
        albumIndex: Int = -1,
        albumCount: Int = 0,
    ): String? {
        if (!isLoaded) return null
        return try {
            nativeSendFile(peerFingerprint, filePath, messageId, fileName, caption, emoji, albumId, albumIndex, albumCount)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSendFile failed", e)
            null
        }
    }

    fun cancelFile(peerFingerprint: String, messageId: String): Boolean {
        if (!isLoaded) return false
        return try {
            nativeCancelFile(messageId)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeCancelFile failed", e)
            false
        }
    }

    fun setTorProxy(enabled: Boolean, proxyAddr: String = "127.0.0.1:9050") {
        if (!isLoaded) return
        try {
            nativeSetTorProxy(enabled, proxyAddr)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetTorProxy failed", e)
        }
    }

    fun setYggdrasilConfig(mode: String, proxyAddr: String = "127.0.0.1:9053") {
        if (!isLoaded) return
        try {
            nativeSetYggdrasilConfig(mode, proxyAddr)
            SafeLog.i(TAG, "Updated Go core Yggdrasil mode=$mode proxy=$proxyAddr")
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetYggdrasilConfig failed", e)
        }
    }

    fun setOnionAddress(address: String) {
        if (!isLoaded) return
        try {
            nativeSetOnionAddress(address)
            SafeLog.i(TAG, "Stored local Tor onion address in Go Core (len=${address.length})")
            SafeLog.d(TAG, "Stored local Tor onion address in Go Core: $address")
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetOnionAddress failed", e)
        }
    }

    fun getOnionAddress(): String? {
        if (!isLoaded) return null
        return try {
            nativeGetOnionAddress()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetOnionAddress failed", e)
            null
        }
    }

    // --- Discovery & Fast Tiered Probing Methods ---

    fun startDiscovery(
        trackers: List<String> = emptyList(),
        infoHashes: List<String> = emptyList(),
        listenPort: Int = 50001,
    ): Boolean {
        if (!isLoaded) return false
        return try {
            val trackersJson = JSONArray(trackers).toString()
            val hashesJson = JSONArray(infoHashes).toString()
            nativeStartDiscovery(trackersJson, hashesJson, listenPort)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeStartDiscovery failed", e)
            false
        }
    }

    fun stopDiscovery(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeStopDiscovery()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeStopDiscovery failed", e)
            false
        }
    }

    fun resetStaleEndpointCooldowns(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeResetStaleEndpointCooldowns()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeResetStaleEndpointCooldowns failed", e)
            false
        }
    }

    fun updateTrackers(trackers: List<String>): Boolean {
        if (!isLoaded) return false
        return try {
            val trackersJson = org.json.JSONArray(trackers).toString()
            nativeUpdateTrackers(trackersJson)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeUpdateTrackers failed", e)
            false
        }
    }

    fun reloadIdentity(): Boolean {
        if (!isLoaded) return false
        cachedLocalIdentity = null
        return try {
            nativeReloadIdentity()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeReloadIdentity failed", e)
            false
        }
    }

    fun announceSelf(infoHashHex: String, port: Int = 50001): Boolean {
        if (!isLoaded) return false
        return try {
            nativeAnnounceSelf(infoHashHex, port)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeAnnounceSelf failed", e)
            false
        }
    }

    fun probePeer(endpoints: List<String>, expectedFingerprint: String = "", policyFlags: Int = 0): Boolean {
        if (!isLoaded) return false
        return try {
            val endpointsJson = JSONArray(endpoints).toString()
            nativeProbePeer(endpointsJson, expectedFingerprint, policyFlags)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeProbePeer failed", e)
            false
        }
    }

    fun onNetworkChanged(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeOnNetworkChanged()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeOnNetworkChanged failed", e)
            false
        }
    }

    // --- Group Cryptography Methods ---

    fun getLocalSigningPublicKey(): String {
        if (!isLoaded) return ""
        return try {
            nativeGetLocalSigningPublicKey() ?: ""
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetLocalSigningPublicKey failed", e)
            ""
        }
    }

    fun signGroupPayload(canonicalPayload: String): String {
        if (!isLoaded || canonicalPayload.isBlank()) return ""
        return try {
            nativeSignGroupPayload(canonicalPayload) ?: ""
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSignGroupPayload failed", e)
            ""
        }
    }

    fun verifyGroupPayload(verificationKeyBase64: String, canonicalPayload: String, signatureBase64: String): Boolean {
        if (!isLoaded || verificationKeyBase64.isBlank() || canonicalPayload.isBlank() || signatureBase64.isBlank()) {
            return false
        }
        return try {
            nativeVerifyGroupPayload(verificationKeyBase64, canonicalPayload, signatureBase64)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeVerifyGroupPayload failed", e)
            false
        }
    }

    fun groupEncrypt(epochSecret: ByteArray, authenticatedData: ByteArray, plaintext: ByteArray): Pair<String, String>? {
        if (!isLoaded) return null
        return try {
            val jsonStr = nativeGroupEncrypt(epochSecret, authenticatedData, plaintext) ?: return null
            val obj = JSONObject(jsonStr)
            Pair(obj.getString("nonce"), obj.getString("ciphertext"))
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGroupEncrypt failed", e)
            null
        }
    }

    fun groupDecrypt(epochSecret: ByteArray, authenticatedData: ByteArray, nonceBase64: String, ciphertextBase64: String): ByteArray? {
        if (!isLoaded) return null
        return try {
            nativeGroupDecrypt(epochSecret, authenticatedData, nonceBase64, ciphertextBase64)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGroupDecrypt failed", e)
            null
        }
    }

    private val bridgeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    // --- JNI Callbacks from Go to Kotlin ---

    @JvmStatic
    fun onPeerConnected(peerFP: String, endpoint: String) {
        SafeLog.i(TAG, "[P2P] Peer connected: ${SafeLog.fp(peerFP)}")
        SafeLog.d(TAG, "[P2P] Peer connected: ${SafeLog.fp(peerFP)} @ $endpoint")
        bridgeScope.launch {
            try {
                onPeerConnectedListener?.invoke(peerFP, endpoint)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onPeerConnectedListener", e)
            }
        }
    }

    @JvmStatic
    fun onPeerDisconnected(peerFP: String, reason: String) {
        SafeLog.i(TAG, "[P2P] Peer disconnected: ${SafeLog.fp(peerFP)}, reason: $reason")
        bridgeScope.launch {
            try {
                onPeerDisconnectedListener?.invoke(peerFP, reason)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onPeerDisconnectedListener", e)
            }
        }
    }

    @JvmStatic
    fun onMessageReceived(peerFP: String, payload: ByteArray, messageID: String) {
        SafeLog.d(TAG, "[P2P] Message received from ${SafeLog.fp(peerFP)}, ID: $messageID (${payload.size} bytes)")
        bridgeScope.launch {
            try {
                onMessageReceivedListener?.invoke(peerFP, payload, messageID)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onMessageReceivedListener", e)
            }
        }
    }

    @JvmStatic
    fun onError(code: Int, message: String) {
        if (code == 3) {
            SafeLog.d(TAG, "[P2P] Probe notice ($code): $message")
        } else {
            SafeLog.e(TAG, "[P2P] Native error ($code): $message")
        }
        bridgeScope.launch {
            try {
                onErrorListener?.invoke(code, message)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onErrorListener", e)
            }
        }
    }

    @JvmStatic
    fun onPeerDiscovered(infoHashHex: String, endpoint: String, source: String) {
        SafeLog.d(TAG, "[P2P-Discovery] Discovered peer for ${SafeLog.fp(infoHashHex)} @ $endpoint (source: $source)")
        bridgeScope.launch {
            try {
                onPeerDiscoveredListener?.invoke(infoHashHex, endpoint, source)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onPeerDiscoveredListener", e)
            }
        }
    }

    @JvmStatic
    fun onDiscoverySeqPersist(seq: Long) {
        SafeLog.d(TAG, "[P2P-Discovery] Persisting discovery sequence counter: $seq")
        bridgeScope.launch {
            try {
                onDiscoverySeqPersistListener?.invoke(seq)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onDiscoverySeqPersistListener", e)
            }
        }
    }

    @JvmStatic
    fun onHeartbeatSeqPersist(groupId: String, seq: Long) {
        SafeLog.d(TAG, "[P2P-Succession] Persisting heartbeat sequence counter for $groupId: $seq")
        bridgeScope.launch {
            try {
                onHeartbeatSeqPersistListener?.invoke(groupId, seq)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onHeartbeatSeqPersistListener", e)
            }
        }
    }

    private data class TrackerLogRecord(
        val wasSuccess: Boolean,
        val peerCount: Int,
        val detail: String,
        val lastLoggedAt: Long,
    )
    private val trackerLogHistory = java.util.concurrent.ConcurrentHashMap<String, TrackerLogRecord>()

    internal fun shouldLogTrackerEvent(
        trackerUrl: String,
        success: Boolean,
        peerCount: Int,
        detail: String,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prev = trackerLogHistory[trackerUrl]
        if (prev == null) {
            trackerLogHistory[trackerUrl] = TrackerLogRecord(success, peerCount, detail, currentTimeMs)
            return true
        }

        // 1. State change: success flipped (failed -> success or success -> failed)
        if (prev.wasSuccess != success) {
            trackerLogHistory[trackerUrl] = TrackerLogRecord(success, peerCount, detail, currentTimeMs)
            return true
        }

        // 2. If it's a success and discovered peers count changed
        if (success && prev.peerCount != peerCount) {
            trackerLogHistory[trackerUrl] = TrackerLogRecord(success, peerCount, detail, currentTimeMs)
            return true
        }

        // 3. For repetitive failures or identical successes, rate limit to at most once per 5 minutes
        val elapsed = currentTimeMs - prev.lastLoggedAt
        val intervalMs = if (success) 120_000L else 300_000L
        if (elapsed >= intervalMs) {
            trackerLogHistory[trackerUrl] = TrackerLogRecord(success, peerCount, detail, currentTimeMs)
            return true
        }

        return false
    }

    private val TRACKER_WHITESPACE_REGEX = Regex("[\\r\\n]+")

    @JvmStatic
    fun onTrackerStatus(trackerUrl: String, success: Boolean, peerCount: Int, elapsedMs: Long, detail: String) {
        val cleanDetail = detail.replace(TRACKER_WHITESPACE_REGEX, " ").take(160)
        val shouldLog = shouldLogTrackerEvent(trackerUrl, success, peerCount, cleanDetail)
        if (shouldLog) {
            val result = if (success) "OK" else "FAIL"
            val summary = "announce=$result, peers=${peerCount.coerceAtLeast(0)}, announce_rtt=${elapsedMs.coerceAtLeast(0)}ms" +
                cleanDetail.takeIf { it.isNotBlank() }?.let { ", detail=$it" }.orEmpty()
            if (success) SafeLog.i(TAG, "[TRACKER] $trackerUrl $summary") else SafeLog.w(TAG, "[TRACKER] $trackerUrl $summary")
        }

        val context = runCatching { com.example.twopchat.yggdrasil.GlobalApplication.appContext }.getOrNull()
        if (context != null) {
            com.example.twopchat.config.TrackerPreferences.recordDiagnosticStatus(
                context, trackerUrl, success, peerCount, elapsedMs, cleanDetail,
            )
            if (shouldLog) {
                val result = if (success) "OK" else "FAIL"
                val summary = "announce=$result, peers=${peerCount.coerceAtLeast(0)}, announce_rtt=${elapsedMs.coerceAtLeast(0)}ms" +
                    cleanDetail.takeIf { it.isNotBlank() }?.let { ", detail=$it" }.orEmpty()
                bridgeScope.launch {
                    AppLog.append(context, "[TRACKER] $trackerUrl $summary\n")
                }
            }
        }
        onTrackerStatusListener?.let { listener ->
            bridgeScope.launch {
                listener.invoke(trackerUrl, success, peerCount, elapsedMs, cleanDetail)
            }
        }
    }

    @JvmStatic
    fun onFileProgress(peerFP: String, messageID: String, transferred: Long, total: Long, speedKbps: Double) {
        SafeLog.d(TAG, "[P2P-File] Progress for $messageID: $transferred / $total bytes ($speedKbps kbps)")
        bridgeScope.launch {
            try {
                onFileProgressListener?.invoke(peerFP, messageID, transferred, total, speedKbps)
            } catch (e: Throwable) {
                SafeLog.e(TAG, "Error in onFileProgressListener", e)
            }
        }
    }

    fun triggerNatTraversal(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeTriggerNatTraversal()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeTriggerNatTraversal failed", e)
            false
        }
    }

    /** Refresh STUN/UPnP synchronously before publishing a contact route. */
    fun refreshNatDiagnostics(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeRefreshNatDiagnostics()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeRefreshNatDiagnostics failed", e)
            false
        }
    }

    fun getNatDiagnostics(): Map<String, String> {
        if (!isLoaded) return emptyMap()
        val jsonStr = nativeGetNatDiagnosticsJSON() ?: return emptyMap()
        return try {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.optString(key)
            }
            map
        } catch (e: Throwable) {
            SafeLog.e(TAG, "Failed to parse NAT diagnostics JSON", e)
            emptyMap()
        }
    }

    fun getLocalSeedMnemonic(): String? {
        if (!isLoaded) return null
        return try {
            nativeGetLocalSeedMnemonic()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetLocalSeedMnemonic failed", e)
            null
        }
    }

    fun restoreFromMnemonic(nickname: String, mnemonic: String, aboutMe: String = ""): Boolean {
        if (!isLoaded) return false
        cachedLocalIdentity = null
        return try {
            nativeRestoreFromMnemonic(nickname, mnemonic, aboutMe)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeRestoreFromMnemonic failed", e)
            false
        }
    }

    fun setNickname(nickname: String): Boolean {
        if (!isLoaded || nickname.isBlank()) return false
        cachedLocalIdentity = null
        return try {
            nativeSetNickname(nickname)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetNickname failed", e)
            false
        }
    }

    fun sendMessageBinary(peerFingerprint: String, buffer: java.nio.ByteBuffer, offset: Int, length: Int): String? {
        if (!isLoaded || !buffer.isDirect) return null
        return try {
            nativeSendMessageBinary(peerFingerprint, buffer, offset, length)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSendMessageBinary failed", e)
            null
        }
    }

    fun sendRawBytes(peerFingerprint: String, payload: ByteArray): String? {
        if (!isLoaded) return null
        return try {
            nativeSendRawBytes(peerFingerprint, payload)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSendRawBytes failed", e)
            null
        }
    }

    fun encryptBackupPayload(password: String, payload: ByteArray): ByteArray? {
        if (!isLoaded) return null
        return try {
            nativeEncryptBackupPayload(password, payload)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeEncryptBackupPayload failed", e)
            null
        }
    }

    fun decryptBackupPayload(password: String, encryptedData: ByteArray): ByteArray? {
        if (!isLoaded) return null
        return try {
            nativeDecryptBackupPayload(password, encryptedData)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeDecryptBackupPayload failed", e)
            null
        }
    }

    fun inspectBackupFingerprint(encryptedData: ByteArray): String? {
        if (!isLoaded) return null
        return try {
            nativeInspectBackupFingerprint(encryptedData)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeInspectBackupFingerprint failed", e)
            null
        }
    }

    data class BackupSignatureResult(val signature: String, val verifyPub: String)

    fun signBackupManifest(canonicalManifest: ByteArray): BackupSignatureResult? {
        if (!isLoaded) return null
        return try {
            val jsonStr = nativeSignBackupManifest(canonicalManifest) ?: return null
            val obj = org.json.JSONObject(jsonStr)
            BackupSignatureResult(
                signature = obj.getString("signature"),
                verifyPub = obj.getString("verify_pub")
            )
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSignBackupManifest failed", e)
            null
        }
    }

    fun verifyBackupManifest(verifyPubBase64: String, canonicalManifest: ByteArray, signatureBase64: String): Boolean {
        if (!isLoaded) return false
        return try {
            nativeVerifyBackupManifest(verifyPubBase64, canonicalManifest, signatureBase64)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeVerifyBackupManifest failed", e)
            false
        }
    }

    data class VerifiedDiscoveryRecord(
        val endpoints: List<String>,
        val seq: Long
    )

    fun createDiscoveryRecord(endpoints: List<String>, ttlSec: Long = 1800, policyFlags: Int = 0): String? {
        if (!isLoaded) return null
        return try {
            val json = org.json.JSONArray(endpoints).toString()
            nativeCreateDiscoveryRecord(json, ttlSec, policyFlags)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeCreateDiscoveryRecord failed", e)
            null
        }
    }

    fun verifyDiscoveryRecord(recordJSON: String, expectedFingerprint: String? = null, checkSeqGap: Boolean = true): VerifiedDiscoveryRecord? {
        if (!isLoaded || recordJSON.isBlank()) return null
        return try {
            val resStr = nativeVerifyDiscoveryRecord(recordJSON, expectedFingerprint, checkSeqGap) ?: return null
            val obj = org.json.JSONObject(resStr)
            val arr = obj.optJSONArray("endpoints") ?: return null
            val endpoints = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                endpoints.add(arr.getString(i))
            }
            val seq = obj.optLong("seq", 0L)
            VerifiedDiscoveryRecord(endpoints, seq)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeVerifyDiscoveryRecord failed", e)
            null
        }
    }

    fun setDiscoverySeqCounter(seq: Long) {
        if (!isLoaded) return
        try {
            nativeSetDiscoverySeqCounter(seq)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetDiscoverySeqCounter failed", e)
        }
    }

    fun getDiscoverySeqCounter(): Long {
        if (!isLoaded) return 0L
        return try {
            nativeGetDiscoverySeqCounter()
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetDiscoverySeqCounter failed", e)
            0L
        }
    }

    fun setDiscoveryStrictSignatures(strict: Boolean) {
        if (!isLoaded) return
        try {
            nativeSetDiscoveryStrictSignatures(strict)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetDiscoveryStrictSignatures failed", e)
        }
    }

    data class DeterministicTorOnionKey(
        val hostname: String,
        val secretKeyBytes: ByteArray
    )

    fun getDeterministicTorOnionKey(index: Int): DeterministicTorOnionKey? {
        if (!isLoaded || index < 0) return null
        return try {
            val jsonStr = nativeGetDeterministicTorOnionKey(index) ?: return null
            val obj = org.json.JSONObject(jsonStr)
            val hostname = obj.optString("hostname", "")
            val b64 = obj.optString("secret_key_base64", "")
            if (hostname.isBlank() || b64.isBlank()) return null
            val secretKeyBytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            DeterministicTorOnionKey(hostname, secretKeyBytes)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetDeterministicTorOnionKey failed", e)
            null
        }
    }

    fun createSuccessionCertificate(groupId: String, successorFP: String, successorPub: String, timeoutDays: Int, sequence: Long = 1L): String? {
        if (!isLoaded) return null
        return try {
            nativeCreateSuccessionCertificate(groupId, successorFP, successorPub, timeoutDays, sequence)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeCreateSuccessionCertificate failed", e)
            null
        }
    }

    fun verifySuccessionCertificate(certJson: String): Boolean {
        if (!isLoaded || certJson.isBlank()) return false
        return try {
            nativeVerifySuccessionCertificate(certJson)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeVerifySuccessionCertificate failed", e)
            false
        }
    }

    fun storeSuccessionCertificate(certJson: String): Boolean {
        if (!isLoaded || certJson.isBlank()) return false
        return try {
            nativeStoreSuccessionCertificate(certJson)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeStoreSuccessionCertificate failed", e)
            false
        }
    }

    fun getSuccessionCertificate(groupId: String): String? {
        if (!isLoaded || groupId.isBlank()) return null
        return try {
            nativeGetSuccessionCertificate(groupId)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetSuccessionCertificate failed", e)
            null
        }
    }

    fun createOwnerHeartbeat(groupId: String): String? {
        if (!isLoaded || groupId.isBlank()) return null
        return try {
            nativeCreateOwnerHeartbeat(groupId)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeCreateOwnerHeartbeat failed", e)
            null
        }
    }

    fun verifyOwnerHeartbeat(hbJson: String): Boolean {
        if (!isLoaded || hbJson.isBlank()) return false
        return try {
            nativeVerifyOwnerHeartbeat(hbJson)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeVerifyOwnerHeartbeat failed", e)
            false
        }
    }

    fun setHeartbeatSeqCounter(groupId: String, seq: Long) {
        if (!isLoaded || groupId.isBlank()) return
        try {
            nativeSetHeartbeatSeqCounter(groupId, seq)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetHeartbeatSeqCounter failed", e)
        }
    }

    fun createSuccessionRevocation(groupId: String, certHash: String): String? {
        if (!isLoaded || groupId.isBlank() || certHash.isBlank()) return null
        return try {
            nativeCreateSuccessionRevocation(groupId, certHash)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeCreateSuccessionRevocation failed", e)
            null
        }
    }

    fun verifySuccessionRevocation(revJson: String): Boolean {
        if (!isLoaded || revJson.isBlank()) return false
        return try {
            nativeVerifySuccessionRevocation(revJson)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeVerifySuccessionRevocation failed", e)
            false
        }
    }

    fun revokeCertificate(certHash: String) {
        if (!isLoaded || certHash.isBlank()) return
        try {
            nativeRevokeCertificate(certHash)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeRevokeCertificate failed", e)
        }
    }

    fun isCertificateRevoked(certHash: String): Boolean {
        if (!isLoaded || certHash.isBlank()) return false
        return try {
            nativeIsCertificateRevoked(certHash)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeIsCertificateRevoked failed", e)
            false
        }
    }

    fun createSuccessionClaim(certJson: String, lastHeartbeatJson: String): String? {
        if (!isLoaded || certJson.isBlank() || lastHeartbeatJson.isBlank()) return null
        return try {
            nativeCreateSuccessionClaim(certJson, lastHeartbeatJson)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeCreateSuccessionClaim failed", e)
            null
        }
    }

    fun verifySuccessionClaim(certJson: String, claimJson: String, lastHeartbeatJson: String): Boolean {
        if (!isLoaded || certJson.isBlank() || claimJson.isBlank() || lastHeartbeatJson.isBlank()) return false
        return try {
            nativeVerifySuccessionClaim(certJson, claimJson, lastHeartbeatJson)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeVerifySuccessionClaim failed", e)
            false
        }
    }

    fun setLastHeartbeatHash(groupId: String, hash: String) {
        if (!isLoaded || groupId.isBlank()) return
        try {
            nativeSetLastHeartbeatHash(groupId, hash)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeSetLastHeartbeatHash failed", e)
        }
    }

    fun getLastHeartbeatHash(groupId: String): String? {
        if (!isLoaded || groupId.isBlank()) return null
        return try {
            nativeGetLastHeartbeatHash(groupId)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetLastHeartbeatHash failed", e)
            null
        }
    }

    fun getCertificateHash(certJson: String): String? {
        if (!isLoaded || certJson.isBlank()) return null
        return try {
            nativeGetCertificateHash(certJson)
        } catch (e: Throwable) {
            SafeLog.e(TAG, "nativeGetCertificateHash failed", e)
            null
        }
    }

    // --- Native JNI declarations ---
    private external fun nativeSetStorageKey(key: ByteArray): Boolean
    private external fun nativeSetStorageDir(dir: String)
    private external fun nativeInit(): Boolean
    private external fun nativeSetNickname(nickname: String): Boolean
    private external fun nativeEcho(msg: String): String?
    private external fun nativeGetLocalIdentityJSON(): String?
    private external fun nativeGetLocalSeedMnemonic(): String?
    private external fun nativeRestoreFromMnemonic(nickname: String, mnemonic: String, aboutMe: String): Boolean
    private external fun nativeGetFingerprint(pub: ByteArray): String?
    private external fun nativeGetSafetyNumber(
        myPub: ByteArray,
        theirPub: ByteArray,
        myVerify: ByteArray,
        theirVerify: ByteArray,
    ): String?
    private external fun nativeApplyPolicy(policyFlags: Int): Boolean
    private external fun nativeSetPeerPolicy(peerFP: String, policyFlags: Int): Boolean
    private external fun nativeStartListener(port: Int): Boolean
    private external fun nativeStopListener(): Boolean
    private external fun nativeConnectPeer(endpoint: String, expectedFingerprint: String, policyFlags: Int): Boolean
    private external fun nativeUpdatePeerNameMapping(peerFingerprint: String, nickname: String): Boolean
    private external fun nativeSendMessage(peerFingerprint: String, text: String): String?
    private external fun nativeSendMessageBinary(peerFingerprint: String, directBuffer: java.nio.ByteBuffer, offset: Int, length: Int): String?
    private external fun nativeSendRawBytes(peerFingerprint: String, payload: ByteArray): String?
    private external fun nativeIsPeerOnline(peerFingerprint: String): Boolean
    private external fun nativeSendFile(
        peerFingerprint: String,
        filePath: String,
        messageId: String,
        fileName: String,
        caption: String,
        emoji: String,
        albumId: String,
        albumIndex: Int,
        albumCount: Int,
    ): String?
    private external fun nativeCancelFile(messageId: String): Boolean
    private external fun nativeSetTorProxy(enabled: Boolean, proxyAddr: String)
    private external fun nativeSetYggdrasilConfig(mode: String, proxyAddr: String)
    private external fun nativeSetOnionAddress(address: String)
    private external fun nativeGetOnionAddress(): String?
    private external fun nativeStartDiscovery(trackersJSON: String, infoHashesJSON: String, port: Int): Boolean
    private external fun nativeStopDiscovery(): Boolean
    private external fun nativeUpdateTrackers(trackersJSON: String): Boolean
    private external fun nativeReloadIdentity(): Boolean
    private external fun nativeAnnounceSelf(infoHashHex: String, port: Int): Boolean
    private external fun nativeProbePeer(endpointsJSON: String, expectedFingerprint: String, policyFlags: Int): Boolean
    private external fun nativeResetStaleEndpointCooldowns(): Boolean
    private external fun nativeGetLocalSigningPublicKey(): String?
    private external fun nativeSignGroupPayload(canonicalPayload: String): String?
    private external fun nativeVerifyGroupPayload(verificationKey: String, canonicalPayload: String, signature: String): Boolean
    private external fun nativeGroupEncrypt(epochSecret: ByteArray, authenticatedData: ByteArray, plaintext: ByteArray): String?
    private external fun nativeGroupDecrypt(epochSecret: ByteArray, authenticatedData: ByteArray, nonceBase64: String, ciphertextBase64: String): ByteArray?
    private external fun nativeTriggerNatTraversal(): Boolean
    private external fun nativeRefreshNatDiagnostics(): Boolean
    private external fun nativeGetNatDiagnosticsJSON(): String?
    private external fun nativeOnNetworkChanged(): Boolean
    private external fun nativeEncryptBackupPayload(password: String, payload: ByteArray): ByteArray?
    private external fun nativeDecryptBackupPayload(password: String, encryptedData: ByteArray): ByteArray?
    private external fun nativeInspectBackupFingerprint(encryptedData: ByteArray): String?
    private external fun nativeSignBackupManifest(canonicalManifest: ByteArray): String?
    private external fun nativeVerifyBackupManifest(verifyPubBase64: String, canonicalManifest: ByteArray, signatureBase64: String): Boolean
    private external fun nativeCreateDiscoveryRecord(endpointsJSON: String?, ttlSec: Long, policyFlags: Int): String?
    private external fun nativeVerifyDiscoveryRecord(recordJSON: String, expectedFingerprint: String?, checkSeqGap: Boolean): String?
    private external fun nativeSetDiscoverySeqCounter(seq: Long)
    private external fun nativeGetDiscoverySeqCounter(): Long
    private external fun nativeSetDiscoveryStrictSignatures(strict: Boolean)
    private external fun nativeGetDeterministicTorOnionKey(index: Int): String?
    private external fun nativeCreateSuccessionCertificate(groupId: String, successorFP: String, successorPub: String, timeoutDays: Int, sequence: Long): String?
    private external fun nativeVerifySuccessionCertificate(certJson: String): Boolean
    private external fun nativeStoreSuccessionCertificate(certJson: String): Boolean
    private external fun nativeGetSuccessionCertificate(groupId: String): String?
    private external fun nativeCreateOwnerHeartbeat(groupId: String): String?
    private external fun nativeVerifyOwnerHeartbeat(hbJson: String): Boolean
    private external fun nativeSetHeartbeatSeqCounter(groupId: String, seq: Long)
    private external fun nativeCreateSuccessionRevocation(groupId: String, certHash: String): String?
    private external fun nativeVerifySuccessionRevocation(revJson: String): Boolean
    private external fun nativeRevokeCertificate(certHash: String)
    private external fun nativeIsCertificateRevoked(certHash: String): Boolean
    private external fun nativeCreateSuccessionClaim(certJson: String, lastHeartbeatJson: String): String?
    private external fun nativeVerifySuccessionClaim(certJson: String, claimJson: String, lastHeartbeatJson: String): Boolean
    private external fun nativeSetLastHeartbeatHash(groupId: String, hash: String)
    private external fun nativeGetLastHeartbeatHash(groupId: String): String?
    private external fun nativeGetCertificateHash(certJson: String): String?
}
