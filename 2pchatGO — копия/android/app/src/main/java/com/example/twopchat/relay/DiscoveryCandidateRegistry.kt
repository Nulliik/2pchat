package com.example.twopchat.relay

import android.content.Context
import com.example.twopchat.config.P2PPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DiscoveryCandidateRegistry coordinates discovered LAN endpoints, BitTorrent tracker peers,
 * and authenticated direct IP/Tor endpoints for remote peers.
 */
internal class DiscoveryCandidateRegistry {
    companion object {
        const val MAX_TRACKED_PEER_ENDPOINTS = 256
        const val MAX_CANDIDATES_PER_PEER = 12
        const val MAX_PEER_CANDIDATE_KEYS = 128
    }

    data class LocalPeerCandidate(val fingerprint: String, val endpoint: String)

    val localPeerCandidates = ConcurrentHashMap<String, CopyOnWriteArrayList<LocalPeerCandidate>>()
    val peerEndpoints = ConcurrentHashMap<String, String>()

    private fun localPeerCandidateKey(peerName: String): String =
        peerName.trim().lowercase(Locale.ROOT)

    fun localDiscoveryEndpoints(peerName: String): List<String> =
        localPeerCandidates[localPeerCandidateKey(peerName)]
            ?.map(LocalPeerCandidate::endpoint)
            ?.distinct()
            ?.take(MAX_CANDIDATES_PER_PEER)
            .orEmpty()

    fun injectLocalDiscoveryCandidate(peerName: String, peerFingerprint: String, endpoint: String) {
        val key = localPeerCandidateKey(peerName)
        if (localPeerCandidates.size >= MAX_PEER_CANDIDATE_KEYS && !localPeerCandidates.containsKey(key)) {
            val oldestKey = localPeerCandidates.keys.firstOrNull()
            if (oldestKey != null) localPeerCandidates.remove(oldestKey)
        }
        val candidates = localPeerCandidates.computeIfAbsent(key) {
            CopyOnWriteArrayList()
        }
        val candidate = LocalPeerCandidate(peerFingerprint, endpoint)
        candidates.remove(candidate)
        candidates.add(candidate)
        while (candidates.size > MAX_CANDIDATES_PER_PEER) {
            candidates.removeAt(0)
        }
    }

    fun rememberAuthenticatedPeerEndpoint(peerName: String, endpoints: String, context: Context? = null): Boolean {
        val normalizedName = peerName.trim()
        val normalizedEndpoints = endpoints.trim()
        val endpointParts = normalizedEndpoints.split(',').map(String::trim).filter(String::isNotEmpty)
        if (normalizedName.isEmpty() || normalizedName.length > 160 ||
            endpointParts.isEmpty() || !isValidPeerEndpointList(normalizedEndpoints)) {
            return false
        }
        if (!peerEndpoints.containsKey(normalizedName) && peerEndpoints.size >= MAX_TRACKED_PEER_ENDPOINTS) return false
        val existingParts = peerEndpoints[normalizedName]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val prioritized = (existingParts + endpointParts).distinct().sortedWith(
            compareBy { ep ->
                when {
                    ep.contains(".onion", ignoreCase = true) -> 0
                    ep.contains(":") && !ep.startsWith("127.") -> 1
                    ep.startsWith("192.168.") || ep.startsWith("10.") || ep.startsWith("172.") -> 2
                    else -> 3
                }
            }
        ).take(8)
        val joined = prioritized.joinToString(",")
        peerEndpoints[normalizedName] = joined
        if (context != null) {
            P2PPreferences.prefs(context).edit().putString("last_endpoint_$normalizedName", joined).apply()
            val onionPart = prioritized.firstOrNull { it.contains(".onion", ignoreCase = true) }
            if (onionPart != null) {
                com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).savePeerOnionAddress(
                    normalizedName,
                    onionPart.substringBefore(':')
                )
            }
        }
        return true
    }

    fun getDirectEndpoints(peerName: String): List<String> {
        val raw = peerEndpoints[peerName]?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    }

    fun clear() {
        localPeerCandidates.clear()
        peerEndpoints.clear()
    }
}
