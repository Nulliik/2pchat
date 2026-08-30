package com.example.twopchat

import android.util.Log
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

    private fun logI(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }

    private fun logE(msg: String, error: Throwable? = null) {
        runCatching { Log.e(TAG, msg, error) }
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

    fun setStorageDir(dir: String) {
        if (!isLoaded) return
        try {
            nativeSetStorageDir(dir)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetStorageDir failed", e)
        }
    }

    fun initialize(): Boolean {
        if (!isLoaded) return false
        return try {
            // The Go manager must know the app-private directory before its
            // first Init call. Otherwise it creates an in-memory identity and
            // every process restart looks like a hostile peer-key change.
            val context = com.example.twopchat.yggdrasil.GlobalApplication.getContext()
            nativeSetStorageDir(context.filesDir.absolutePath)
            nativeInit()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeInit failed", e)
            false
        }
    }

    fun echo(message: String): String {
        if (!isLoaded) return "Native library not loaded"
        return try {
            nativeEcho(message) ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "nativeEcho failed", e)
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
            Log.e(TAG, "nativeGetLocalIdentityJSON failed", e)
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
            Log.e(TAG, "Failed to parse local identity JSON", e)
            null
        }
    }

    fun getFingerprint(publicKey: ByteArray): String? {
        if (!isLoaded) return null
        return try {
            nativeGetFingerprint(publicKey)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeGetFingerprint failed", e)
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
            Log.e(TAG, "nativeGetSafetyNumber failed", e)
            null
        }
    }

    // --- Networking Methods ---

    fun startListener(port: Int = 50001): Boolean {
        if (!isLoaded) return false
        return try {
            nativeStartListener(port)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeStartListener failed", e)
            false
        }
    }

    fun stopListener(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeStopListener()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeStopListener failed", e)
            false
        }
    }

    fun connectPeer(endpoint: String, expectedFingerprint: String = ""): Boolean {
        if (!isLoaded) return false
        return try {
            nativeConnectPeer(endpoint, expectedFingerprint)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeConnectPeer failed", e)
            false
        }
    }

    fun updatePeerNameMapping(peerFingerprint: String, nickname: String): Boolean {
        if (!isLoaded || peerFingerprint.isBlank() || nickname.isBlank()) return false
        return try {
            nativeUpdatePeerNameMapping(peerFingerprint, nickname)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeUpdatePeerNameMapping failed", e)
            false
        }
    }

    fun sendMessage(peerFingerprint: String, text: String): String? {
        if (!isLoaded) return null
        return try {
            nativeSendMessage(peerFingerprint, text)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSendMessage failed", e)
            null
        }
    }

    fun isPeerOnline(peerFingerprint: String): Boolean {
        if (!isLoaded || peerFingerprint.isBlank()) return false
        return try {
            nativeIsPeerOnline(peerFingerprint)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeIsPeerOnline failed", e)
            false
        }
    }

    fun sendFile(peerFingerprint: String, filePath: String, messageId: String = "", fileName: String = "", caption: String = "", emoji: String = ""): String? {
        if (!isLoaded) return null
        return try {
            nativeSendFile(peerFingerprint, filePath, messageId, fileName, caption, emoji)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSendFile failed", e)
            null
        }
    }

    fun cancelFile(peerFingerprint: String, messageId: String): Boolean {
        if (!isLoaded) return false
        return try {
            nativeCancelFile(messageId)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeCancelFile failed", e)
            false
        }
    }

    fun setTorProxy(enabled: Boolean, proxyAddr: String = "127.0.0.1:9050") {
        if (!isLoaded) return
        try {
            nativeSetTorProxy(enabled, proxyAddr)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetTorProxy failed", e)
        }
    }

    fun setYggdrasilConfig(mode: String, proxyAddr: String = "127.0.0.1:9053") {
        if (!isLoaded) return
        try {
            nativeSetYggdrasilConfig(mode, proxyAddr)
            Log.i(TAG, "Updated Go core Yggdrasil mode=$mode proxy=$proxyAddr")
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetYggdrasilConfig failed", e)
        }
    }

    fun setOnionAddress(address: String) {
        if (!isLoaded) return
        try {
            nativeSetOnionAddress(address)
            Log.i(TAG, "Stored local Tor onion address in Go Core: $address")
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetOnionAddress failed", e)
        }
    }

    fun getOnionAddress(): String? {
        if (!isLoaded) return null
        return try {
            nativeGetOnionAddress()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeGetOnionAddress failed", e)
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
            Log.e(TAG, "nativeStartDiscovery failed", e)
            false
        }
    }

    fun stopDiscovery(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeStopDiscovery()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeStopDiscovery failed", e)
            false
        }
    }

    fun resetStaleEndpointCooldowns(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeResetStaleEndpointCooldowns()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeResetStaleEndpointCooldowns failed", e)
            false
        }
    }

    fun updateTrackers(trackers: List<String>): Boolean {
        if (!isLoaded) return false
        return try {
            val trackersJson = org.json.JSONArray(trackers).toString()
            nativeUpdateTrackers(trackersJson)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeUpdateTrackers failed", e)
            false
        }
    }

    fun reloadIdentity(): Boolean {
        if (!isLoaded) return false
        cachedLocalIdentity = null
        return try {
            nativeReloadIdentity()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeReloadIdentity failed", e)
            false
        }
    }

    fun announceSelf(infoHashHex: String, port: Int = 50001): Boolean {
        if (!isLoaded) return false
        return try {
            nativeAnnounceSelf(infoHashHex, port)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeAnnounceSelf failed", e)
            false
        }
    }

    fun probePeer(endpoints: List<String>, expectedFingerprint: String = ""): Boolean {
        if (!isLoaded) return false
        return try {
            val endpointsJson = JSONArray(endpoints).toString()
            nativeProbePeer(endpointsJson, expectedFingerprint)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeProbePeer failed", e)
            false
        }
    }

    fun onNetworkChanged(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeOnNetworkChanged()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeOnNetworkChanged failed", e)
            false
        }
    }

    // --- Group Cryptography Methods ---

    fun getLocalSigningPublicKey(): String {
        if (!isLoaded) return ""
        return try {
            nativeGetLocalSigningPublicKey() ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "nativeGetLocalSigningPublicKey failed", e)
            ""
        }
    }

    fun signGroupPayload(canonicalPayload: String): String {
        if (!isLoaded || canonicalPayload.isBlank()) return ""
        return try {
            nativeSignGroupPayload(canonicalPayload) ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSignGroupPayload failed", e)
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
            Log.e(TAG, "nativeVerifyGroupPayload failed", e)
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
            Log.e(TAG, "nativeGroupEncrypt failed", e)
            null
        }
    }

    fun groupDecrypt(epochSecret: ByteArray, authenticatedData: ByteArray, nonceBase64: String, ciphertextBase64: String): ByteArray? {
        if (!isLoaded) return null
        return try {
            nativeGroupDecrypt(epochSecret, authenticatedData, nonceBase64, ciphertextBase64)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeGroupDecrypt failed", e)
            null
        }
    }

    private val bridgeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    // --- JNI Callbacks from Go to Kotlin ---

    @JvmStatic
    fun onPeerConnected(peerFP: String, endpoint: String) {
        Log.i(TAG, "[P2P] Peer connected: $peerFP @ $endpoint")
        bridgeScope.launch {
            try {
                onPeerConnectedListener?.invoke(peerFP, endpoint)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in onPeerConnectedListener", e)
            }
        }
    }

    @JvmStatic
    fun onPeerDisconnected(peerFP: String, reason: String) {
        Log.i(TAG, "[P2P] Peer disconnected: $peerFP, reason: $reason")
        bridgeScope.launch {
            try {
                onPeerDisconnectedListener?.invoke(peerFP, reason)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in onPeerDisconnectedListener", e)
            }
        }
    }

    @JvmStatic
    fun onMessageReceived(peerFP: String, payload: ByteArray, messageID: String) {
        Log.d(TAG, "[P2P] Message received from $peerFP, ID: $messageID (${payload.size} bytes)")
        bridgeScope.launch {
            try {
                onMessageReceivedListener?.invoke(peerFP, payload, messageID)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in onMessageReceivedListener", e)
            }
        }
    }

    @JvmStatic
    fun onError(code: Int, message: String) {
        Log.e(TAG, "[P2P] Native error ($code): $message")
        bridgeScope.launch {
            try {
                onErrorListener?.invoke(code, message)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in onErrorListener", e)
            }
        }
    }

    @JvmStatic
    fun onPeerDiscovered(infoHashHex: String, endpoint: String, source: String) {
        Log.i(TAG, "[P2P-Discovery] Discovered peer for $infoHashHex @ $endpoint (source: $source)")
        bridgeScope.launch {
            try {
                onPeerDiscoveredListener?.invoke(infoHashHex, endpoint, source)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in onPeerDiscoveredListener", e)
            }
        }
    }

    @JvmStatic
    fun onTrackerStatus(trackerUrl: String, success: Boolean, peerCount: Int, elapsedMs: Long, detail: String) {
        val result = if (success) "OK" else "FAIL"
        val cleanDetail = detail.replace(Regex("[\\r\\n]+"), " ").take(160)
        val summary = "announce=$result, peers=${peerCount.coerceAtLeast(0)}, announce_rtt=${elapsedMs.coerceAtLeast(0)}ms" +
            cleanDetail.takeIf { it.isNotBlank() }?.let { ", detail=$it" }.orEmpty()
        if (success) Log.i(TAG, "[TRACKER] $trackerUrl $summary") else Log.w(TAG, "[TRACKER] $trackerUrl $summary")
        bridgeScope.launch {
            val context = runCatching { com.example.twopchat.yggdrasil.GlobalApplication.appContext }.getOrNull()
            if (context != null) {
                com.example.twopchat.config.TrackerPreferences.recordDiagnosticStatus(
                    context, trackerUrl, success, peerCount, elapsedMs, cleanDetail,
                )
                AppLog.append(context, "[TRACKER] $trackerUrl $summary\n")
            }
            onTrackerStatusListener?.invoke(trackerUrl, success, peerCount, elapsedMs, cleanDetail)
        }
    }

    @JvmStatic
    fun onFileProgress(peerFP: String, messageID: String, transferred: Long, total: Long, speedKbps: Double) {
        Log.d(TAG, "[P2P-File] Progress for $messageID: $transferred / $total bytes ($speedKbps kbps)")
        bridgeScope.launch {
            try {
                onFileProgressListener?.invoke(peerFP, messageID, transferred, total, speedKbps)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in onFileProgressListener", e)
            }
        }
    }

    fun triggerNatTraversal(): Boolean {
        if (!isLoaded) return false
        return try {
            nativeTriggerNatTraversal()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeTriggerNatTraversal failed", e)
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
            Log.e(TAG, "Failed to parse NAT diagnostics JSON", e)
            emptyMap()
        }
    }

    fun getLocalSeedMnemonic(): String? {
        if (!isLoaded) return null
        return try {
            nativeGetLocalSeedMnemonic()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeGetLocalSeedMnemonic failed", e)
            null
        }
    }

    fun restoreFromMnemonic(nickname: String, mnemonic: String, aboutMe: String = ""): Boolean {
        if (!isLoaded) return false
        cachedLocalIdentity = null
        return try {
            nativeRestoreFromMnemonic(nickname, mnemonic, aboutMe)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeRestoreFromMnemonic failed", e)
            false
        }
    }

    fun setNickname(nickname: String): Boolean {
        if (!isLoaded || nickname.isBlank()) return false
        cachedLocalIdentity = null
        return try {
            nativeSetNickname(nickname)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetNickname failed", e)
            false
        }
    }

    fun sendMessageBinary(peerFingerprint: String, buffer: java.nio.ByteBuffer, offset: Int, length: Int): String? {
        if (!isLoaded || !buffer.isDirect) return null
        return try {
            nativeSendMessageBinary(peerFingerprint, buffer, offset, length)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSendMessageBinary failed", e)
            null
        }
    }

    fun sendRawBytes(peerFingerprint: String, payload: ByteArray): String? {
        if (!isLoaded) return null
        return try {
            nativeSendRawBytes(peerFingerprint, payload)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSendRawBytes failed", e)
            null
        }
    }

    // --- Native JNI declarations ---
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
    private external fun nativeStartListener(port: Int): Boolean
    private external fun nativeStopListener(): Boolean
    private external fun nativeConnectPeer(endpoint: String, expectedFingerprint: String): Boolean
    private external fun nativeUpdatePeerNameMapping(peerFingerprint: String, nickname: String): Boolean
    private external fun nativeSendMessage(peerFingerprint: String, text: String): String?
    private external fun nativeSendMessageBinary(peerFingerprint: String, directBuffer: java.nio.ByteBuffer, offset: Int, length: Int): String?
    private external fun nativeSendRawBytes(peerFingerprint: String, payload: ByteArray): String?
    private external fun nativeIsPeerOnline(peerFingerprint: String): Boolean
    private external fun nativeSendFile(peerFingerprint: String, filePath: String, messageId: String, fileName: String, caption: String, emoji: String): String?
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
    private external fun nativeProbePeer(endpointsJSON: String, expectedFingerprint: String): Boolean
    private external fun nativeResetStaleEndpointCooldowns(): Boolean
    private external fun nativeGetLocalSigningPublicKey(): String?
    private external fun nativeSignGroupPayload(canonicalPayload: String): String?
    private external fun nativeVerifyGroupPayload(verificationKey: String, canonicalPayload: String, signature: String): Boolean
    private external fun nativeGroupEncrypt(epochSecret: ByteArray, authenticatedData: ByteArray, plaintext: ByteArray): String?
    private external fun nativeGroupDecrypt(epochSecret: ByteArray, authenticatedData: ByteArray, nonceBase64: String, ciphertextBase64: String): ByteArray?
    private external fun nativeTriggerNatTraversal(): Boolean
    private external fun nativeGetNatDiagnosticsJSON(): String?
    private external fun nativeOnNetworkChanged(): Boolean
}
