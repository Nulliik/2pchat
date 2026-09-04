package com.example.twopchat.bridge

interface BridgeMessageListener {
    fun onMessageReceived(sender: String, text: String)
    fun onFileProgress(sender: String, messageId: String, bytesTransferred: Long, totalBytes: Long, speedKbps: Double) {}
}

interface BridgeSessionListener {
    fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String, transport: String, aboutMe: String): Boolean
    fun onSessionClosed(peerName: String, fingerprint: String)
    fun onPeerDiscovered(infoHash: String, endpoint: String, source: String) {}
    /** Routes received inside an authenticated encrypted session, never from a tracker. */
    fun onPeerRoutesUpdated(peerName: String, fingerprint: String, endpoints: String) {}
}

/**
 * Common bridge interface abstracting P2P cryptography, network transport, and discovery operations
 * backed by Native Go (lib2pcore.so).
 */
interface IP2PBridge {
    fun getLocalFingerprint(): String
    fun configureLocalIdentity(username: String, fingerprint: String, aboutMe: String): Boolean
    fun setIpv4Enabled(enabled: Boolean)
    fun startP2pListener(port: Int, upnpEnabled: Boolean)
    fun stopP2pListener(): Boolean
    fun announceSelf(
        nickname: String,
        fingerprint: String,
        port: Int,
        force: Boolean = false,
        rendezvousCode: String? = null,
    ): Boolean
    suspend fun sendP2pMessage(peerName: String, endpoint: String, payload: String, expectedFingerprint: String?): Boolean
    suspend fun sendFile(
        peerName: String,
        endpoint: String,
        filePath: String,
        expectedFingerprint: String? = null,
        messageId: String = "",
        caption: String = "",
        previewBase64: String = "",
        albumId: String = "",
        albumIndex: Int = -1,
        albumCount: Int = 0,
    ): Boolean
    suspend fun awaitPeerOnline(peerFP: String, timeoutMs: Long): Boolean = false
    fun cancelFile(peerName: String, messageId: String, expectedFingerprint: String? = null): Boolean
    fun reconnectPeerSession(peerName: String, endpoint: String, fingerprint: String?): Boolean
    fun closePeerSession(peerName: String, expectedFingerprint: String?): Boolean
    fun isPeerOnline(peerName: String, expectedFingerprint: String?): Boolean
    fun shutdownAllSessions(): Boolean
    fun shutdown() {
        shutdownAllSessions()
    }
    fun resetStaleEndpointCooldowns(): Boolean
    fun registerMessageListener(listener: BridgeMessageListener)
    fun registerSessionListener(listener: BridgeSessionListener)
    fun searchPeers(
        query: String,
        expectedLiveName: String? = null,
        expectedFingerprint: String? = null,
        sharedCode: String? = null,
    ): List<Map<String, Any>>
    fun updatePeerNameMapping(fingerprint: String, peerName: String) {}
    fun updateTrackers(trackers: List<String>): Boolean = false
    fun getLocalSeedMnemonic(): String? = null
    fun restoreFromMnemonic(nickname: String, mnemonic: String, aboutMe: String = ""): Boolean = false
}
