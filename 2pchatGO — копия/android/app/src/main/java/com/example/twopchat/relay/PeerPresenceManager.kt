package com.example.twopchat.relay

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.example.twopchat.relay.PeerPresenceVersionTracker
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
        P2PMessageRelay.runOnMain {
            if (peerPresenceVersions.current(peerName) != version) return@runOnMain
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
        P2PMessageRelay.runOnMain {
            if (peerPresenceVersions.current(peerName) != version) return@runOnMain
            peerSessionStates[peerName] = true
            if (transport != null) peerConnectionTransports[peerName] = transport
            sendHeartbeat(context, peerName)
        }
    }

    fun clearPeerPresenceImmediately(peerName: String) {
        val version = peerPresenceVersions.advance(peerName)
        P2PMessageRelay.runOnMain {
            if (peerPresenceVersions.current(peerName) != version) return@runOnMain
            peerConnectionTransports.remove(peerName)
            peerSessionStates.remove(peerName)
            peerRttMs.remove(peerName)
        }
    }

    fun schedulePeerOffline(peerName: String) {
        val version = peerPresenceVersions.advance(peerName)
        P2PMessageRelay.runDelayedOnMain(OFFLINE_UI_GRACE_MS) {
            if (peerPresenceVersions.current(peerName) != version) return@runDelayedOnMain
            peerConnectionTransports.remove(peerName)
            peerSessionStates.remove(peerName)
            peerRttMs.remove(peerName)
        }
    }

    fun schedulePeerOfflineIfCurrent(peerName: String, expectedVersion: Long) {
        val version = peerPresenceVersions.advanceIfCurrent(peerName, expectedVersion) ?: return
        P2PMessageRelay.runDelayedOnMain(OFFLINE_UI_GRACE_MS) {
            if (peerPresenceVersions.current(peerName) != version) return@runDelayedOnMain
            peerConnectionTransports.remove(peerName)
            peerSessionStates.remove(peerName)
            peerRttMs.remove(peerName)
        }
    }

    fun updateRtt(sender: String, rttMs: Long) {
        P2PMessageRelay.runOnMain {
            peerRttMs[sender] = rttMs
        }
    }

    fun updateTypingState(sender: String, isTyping: Boolean) {
        P2PMessageRelay.runOnMain {
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
