package com.example.twopchat.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.twopchat.P2PMessageRelay
import com.example.twopchat.P2PPreferences

class DefaultPeerRepository : PeerRepository {

    override fun isPeerOnline(peerName: String): Boolean {
        return P2PMessageRelay.peerSessionStates[peerName] == true
    }

    override fun getPeerTransport(peerName: String): String? {
        return P2PMessageRelay.peerConnectionTransports[peerName]
    }

    override fun getPeerTransportType(peerName: String): com.example.twopchat.TransportType {
        return P2PMessageRelay.getPeerTransportType(peerName)
    }

    override fun getPeerAvatar(peerName: String): Bitmap? {
        return P2PMessageRelay.peerAvatars[peerName]
    }

    override fun isPeerVerified(context: Context, peerName: String): Boolean {
        return P2PPreferences.isPeerVerified(context, peerName)
    }

    override fun setPeerVerified(context: Context, peerName: String, verified: Boolean) {
        P2PPreferences.setPeerVerified(context, peerName, verified)
    }

    override fun isPeerBlocked(context: Context, peerName: String): Boolean {
        return P2PPreferences.prefs(context).getBoolean("blocked_peer_$peerName", false)
    }

    override fun setPeerBlocked(context: Context, peerName: String, blocked: Boolean) {
        P2PPreferences.prefs(context).edit().putBoolean("blocked_peer_$peerName", blocked).apply()
    }
}
