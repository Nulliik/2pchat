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
}
