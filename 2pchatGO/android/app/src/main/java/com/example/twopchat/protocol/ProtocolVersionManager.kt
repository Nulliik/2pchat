package com.example.twopchat.protocol

import com.example.twopchat.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

// The registry and negotiation algorithm live in Go. Kotlin only consumes the
// authenticated native snapshot; no peer-supplied JSON can enable a feature.
enum class Capability(val id: String) {
    GROUP_SUITE_V1("group_suite_v1"),
    GROUP_SUITE_V2("group_suite_v2"),
    GROUP_TOMBSTONES_V1("group_tombstones_v1"),
    GROUP_SUCCESSION_V1("group_succession_v1"),
    GROUP_SUCCESSION_QUERY_V1("group_succession_query_v1"),
    GROUP_INVITE_RELAY_V1("group_invite_relay_v1"),
}

data class NegotiatedSession(
    val protocolVersion: Int,
    val activeCapabilities: Set<String>,
    val peerIsOutdated: Boolean,
    val peerIsLegacy: Boolean,
) {
    fun supports(capability: Capability) = capability.id in activeCapabilities

    companion object {
        fun fromNative(raw: String?): NegotiatedSession? {
            if (raw.isNullOrBlank() || raw == "null") return null
            val json = JSONObject(raw)
            val caps = json.getJSONArray("active_capabilities")
            require(caps.length() <= 64)
            return NegotiatedSession(
                json.getInt("protocol_version"),
                (0 until caps.length()).map { caps.getString(it) }.toSet(),
                json.getBoolean("peer_is_outdated"),
                json.getBoolean("peer_is_legacy"),
            )
        }
    }
}

object ProtocolVersionManager {
    private val mutableSessions = MutableStateFlow<Map<String, NegotiatedSession>>(emptyMap())
    val sessions = mutableSessions.asStateFlow()

    // Fingerprints, never nicknames, index compatibility. Refresh on connect,
    // identity_info and disconnect. Action checks always query native state anew.
    fun refresh(fingerprint: String): NegotiatedSession? {
        if (fingerprint.isBlank()) return null
        val snapshot = NegotiatedSession.fromNative(NativeBridge.getPeerProtocol(fingerprint))
        mutableSessions.update { if (snapshot == null) it - fingerprint else it + (fingerprint to snapshot) }
        return snapshot
    }

    fun supports(fingerprint: String, capability: Capability): Boolean =
        refresh(fingerprint)?.supports(capability) == true
}
