package com.example.twopchat.data.repository

import android.content.Context
import android.graphics.Bitmap

interface PeerRepository {
    fun isPeerOnline(peerName: String): Boolean
    fun getPeerTransport(peerName: String): String?
    fun getPeerTransportType(peerName: String): com.example.twopchat.relay.TransportType
    fun getPeerAvatar(peerName: String): Bitmap?
    fun isPeerVerified(context: Context, peerName: String): Boolean
    fun setPeerVerified(context: Context, peerName: String, verified: Boolean)
    fun isPeerBlocked(context: Context, peerName: String): Boolean
    fun setPeerBlocked(context: Context, peerName: String, blocked: Boolean)
}
