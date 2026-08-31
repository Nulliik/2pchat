package com.example.twopchat.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.example.twopchat.relay.P2PMessageRelay
import com.example.twopchat.config.P2PPreferences

class DefaultPeerRepository : PeerRepository {

    override fun isPeerOnline(peerName: String): Boolean {
        val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        return P2PMessageRelay.isPeerOnline(context, peerName)
    }

    override fun getPeerTransport(peerName: String): String? {
        return P2PMessageRelay.peerConnectionTransports[peerName]
    }

    override fun getPeerTransportType(peerName: String): com.example.twopchat.relay.TransportType {
        val context = com.example.twopchat.yggdrasil.GlobalApplication.appContext
        return P2PMessageRelay.getPeerTransportType(context, peerName)
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
        return P2PPreferences.isPeerBlocked(context, peerName)
    }

    override fun setPeerBlocked(context: Context, peerName: String, blocked: Boolean) {
        P2PPreferences.setPeerBlocked(context, peerName, blocked)
    }
}
