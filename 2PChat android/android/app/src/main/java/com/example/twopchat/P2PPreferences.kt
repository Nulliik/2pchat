package com.example.twopchat

import android.content.Context
import android.content.SharedPreferences

/** Canonical keys for relay state which is intentionally small key/value metadata. */
object P2PPreferences {
    const val FILE_NAME = "2pchat_prefs"
    const val ACTIVE_CHATS = "active_chats"
    const val LISTENER_PORT = "listener_port"
    const val WIFI_DISCOVERY = "settings_wifi"
    const val DEFAULT_LISTENER_PORT = 50001
    const val MIN_LISTENER_PORT = 1024
    const val MAX_LISTENER_PORT = 65535

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun listenerPort(context: Context): Int =
        prefs(context).getInt(LISTENER_PORT, DEFAULT_LISTENER_PORT)
            .coerceIn(MIN_LISTENER_PORT, MAX_LISTENER_PORT)

    fun peerFingerprint(peerName: String) = "peer_fingerprint_$peerName"
    fun fingerprintMismatch(peerName: String) = "fingerprint_mismatch_$peerName"
    fun pendingPeerFingerprint(peerName: String) = "pending_peer_fingerprint_$peerName"
    fun pendingPeerEndpoint(peerName: String) = "pending_peer_endpoint_$peerName"
    fun lastEndpoint(peerName: String) = "last_endpoint_$peerName"
    fun lastMessage(peerName: String) = "last_msg_$peerName"
    fun unreadCount(peerName: String) = "unread_count_$peerName"
    fun transport(peerName: String) = "transport_$peerName"
    fun verifiedPeer(peerName: String) = "verified_peer_$peerName"

    fun isPeerVerified(context: Context, peerName: String): Boolean =
        prefs(context).getBoolean(verifiedPeer(peerName), false)

    fun setPeerVerified(context: Context, peerName: String, verified: Boolean) {
        prefs(context).edit().putBoolean(verifiedPeer(peerName), verified).apply()
    }

    fun isPeerIdentityChangePending(context: Context, peerName: String): Boolean {
        val prefs = prefs(context)
        return shouldBlockPeerTraffic(
            mismatch = prefs.getBoolean(fingerprintMismatch(peerName), false),
            pendingFingerprint = prefs.getString(pendingPeerFingerprint(peerName), null),
        )
    }

    /**
     * Pins the first unexpected identity until the user makes an explicit choice.
     * A later connection cannot silently replace the fingerprint shown in the warning.
     */
    fun recordPendingPeerIdentity(
        context: Context,
        peerName: String,
        fingerprint: String,
        endpoint: String,
    ) {
        if (peerName.isBlank() || fingerprint.isBlank()) return
        val prefs = prefs(context)
        val pendingKey = pendingPeerFingerprint(peerName)
        val existingPending = prefs.getString(pendingKey, null)
        val editor = prefs.edit().putBoolean(fingerprintMismatch(peerName), true)
        if (existingPending.isNullOrBlank()) {
            editor.putString(pendingKey, fingerprint)
        }
        if (endpoint.isNotBlank() && (existingPending.isNullOrBlank() || existingPending == fingerprint)) {
            editor.putString(pendingPeerEndpoint(peerName), endpoint)
        }
        // The session callback is synchronous. Persist the security boundary before
        // Python can process any application frames or another sender can race it.
        editor.commit()
    }

    internal fun acceptPendingPeerIdentity(context: Context, peerName: String): AcceptedPeerIdentity? {
        val prefs = prefs(context)
        val current = prefs.getString(peerFingerprint(peerName), null).orEmpty()
        val pending = prefs.getString(pendingPeerFingerprint(peerName), null).orEmpty()
        if (!canAcceptPendingPeerFingerprint(current, pending)) return null
        val endpoint = prefs.getString(pendingPeerEndpoint(peerName), null).orEmpty()
        val editor = prefs.edit()
            .putString(peerFingerprint(peerName), pending)
            .putBoolean(fingerprintMismatch(peerName), false)
            .putBoolean(verifiedPeer(peerName), false)
            .remove(pendingPeerFingerprint(peerName))
            .remove(pendingPeerEndpoint(peerName))
        if (endpoint.isNotBlank()) editor.putString(lastEndpoint(peerName), endpoint)
        if (!editor.commit()) return null
        return AcceptedPeerIdentity(current, pending, endpoint)
    }

    fun rejectPendingPeerIdentity(context: Context, peerName: String): Boolean =
        prefs(context).edit()
            .putBoolean(fingerprintMismatch(peerName), false)
            .remove(pendingPeerFingerprint(peerName))
            .remove(pendingPeerEndpoint(peerName))
            .commit()
}

internal data class AcceptedPeerIdentity(
    val previousFingerprint: String,
    val acceptedFingerprint: String,
    val endpoint: String,
)

internal fun shouldBlockPeerTraffic(mismatch: Boolean, pendingFingerprint: String?): Boolean =
    mismatch || !pendingFingerprint.isNullOrBlank()

internal fun canAcceptPendingPeerFingerprint(current: String?, pending: String?): Boolean =
    !current.isNullOrBlank() && !pending.isNullOrBlank() && current != pending
