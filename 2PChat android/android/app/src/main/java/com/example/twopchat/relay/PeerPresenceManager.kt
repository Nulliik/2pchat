package com.example.twopchat.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.example.twopchat.PeerPresenceVersionTracker
import java.util.concurrent.ConcurrentHashMap

private const val OFFLINE_UI_GRACE_MS = 2_500L

internal class PeerPresenceManager {
    val peerSessionStates = mutableStateMapOf<String, Boolean>()
    val peerConnectionTransports = mutableStateMapOf<String, String>()
    val peerRttMs = mutableStateMapOf<String, Long>()
    val peerTypingStates = mutableStateMapOf<String, Boolean>()

    private val peerPresenceVersions = PeerPresenceVersionTracker()

    fun currentVersion(peerName: String): Long = peerPresenceVersions.current(peerName)

    fun publishPeerOnline(
        peerName: String,
        transport: String?,
        rememberEndpoint: (String) -> Unit
    ) {
        val version = peerPresenceVersions.advance(peerName)
        Handler(Looper.getMainLooper()).post {
            if (peerPresenceVersions.current(peerName) != version) return@post
            peerSessionStates[peerName] = true
            if (transport != null) peerConnectionTransports[peerName] = transport
            rememberEndpoint(peerName)
        }
    }

    fun publishPeerOnlineIfCurrent(
        context: Context,
        peerName: String,
        transport: String?,
        expectedVersion: Long,
        sendHeartbeat: (Context, String) -> Unit
    ) {
        val version = peerPresenceVersions.advanceIfCurrent(peerName, expectedVersion) ?: return
        Handler(Looper.getMainLooper()).post {
            if (peerPresenceVersions.current(peerName) != version) return@post
            peerSessionStates[peerName] = true
            if (transport != null) peerConnectionTransports[peerName] = transport
            sendHeartbeat(context, peerName)
        }
    }

    fun clearPeerPresenceImmediately(peerName: String) {
        val version = peerPresenceVersions.advance(peerName)
        Handler(Looper.getMainLooper()).post {
            if (peerPresenceVersions.current(peerName) != version) return@post
            peerConnectionTransports.remove(peerName)
            peerSessionStates.remove(peerName)
            peerRttMs.remove(peerName)
        }
    }

    fun schedulePeerOfflineIfCurrent(peerName: String, expectedVersion: Long) {
        val version = peerPresenceVersions.advanceIfCurrent(peerName, expectedVersion) ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            if (peerPresenceVersions.current(peerName) != version) return@postDelayed
            peerConnectionTransports.remove(peerName)
            peerSessionStates.remove(peerName)
            peerRttMs.remove(peerName)
        }, OFFLINE_UI_GRACE_MS)
    }

    fun updateRtt(sender: String, rttMs: Long) {
        Handler(Looper.getMainLooper()).post {
            peerRttMs[sender] = rttMs
        }
    }

    fun updateTypingState(sender: String, isTyping: Boolean) {
        Handler(Looper.getMainLooper()).post {
            peerTypingStates[sender] = isTyping
        }
    }

    fun removePeer(peerName: String) {
        peerPresenceVersions.remove(peerName)
        peerSessionStates.remove(peerName)
        peerConnectionTransports.remove(peerName)
        peerRttMs.remove(peerName)
        peerTypingStates.remove(peerName)
    }
}
