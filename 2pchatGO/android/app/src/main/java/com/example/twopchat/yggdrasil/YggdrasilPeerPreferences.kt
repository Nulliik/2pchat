package com.example.twopchat.yggdrasil

import android.content.Context
import com.example.twopchat.config.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class CustomYggdrasilPeer(
    val id: String,
    val name: String,
    val uri: String,
    val enabled: Boolean = true,
)

enum class YggdrasilPeerSort(val storedValue: String) {
    STATUS("status"),
    ADDRESS("address"),
    PROTOCOL("protocol");

    companion object {
        fun fromStored(value: String?): YggdrasilPeerSort =
            entries.firstOrNull { it.storedValue == value } ?: STATUS
    }
}

object YggdrasilPeerPreferences {
    const val PUBLIC_PEERS_ENABLED = "yggdrasil_public_peers_enabled"
    const val KNOWN_PUBLIC_PEERS = "yggdrasil_known_public_peers"
    const val DISABLED_PUBLIC_PEERS = "yggdrasil_disabled_public_peers"
    const val CUSTOM_PEERS_JSON = "yggdrasil_custom_peers_json"
    const val PEER_SORT = "yggdrasil_peer_sort"
    const val MAX_CUSTOM_PEERS = 32
    private const val MAX_PUBLIC_PEERS = 256
    private val supportedProtocols = setOf("tcp", "tls")

    val TLS_BYPASS_PEERS = listOf(
        "tls://45.95.202.21:443",
        "tls://ygg.mikaela.info:443",
        "tls://ygg-msk-1.averyan.ru:8362",
        "tls://yggno.de:18227",
        "tls://95.217.35.92:1337",
    )

    fun applyDpiBypassPeers(context: Context) {
        setPublicPeersEnabled(context, true)
        replacePublicPeers(context, TLS_BYPASS_PEERS)
        P2PPreferences.prefs(context).edit().remove(DISABLED_PUBLIC_PEERS).apply()
    }

    fun publicPeersEnabled(context: Context): Boolean =
        P2PPreferences.prefs(context).getBoolean(PUBLIC_PEERS_ENABLED, true)

    fun setPublicPeersEnabled(context: Context, enabled: Boolean) {
        P2PPreferences.prefs(context).edit().putBoolean(PUBLIC_PEERS_ENABLED, enabled).apply()
    }

    fun publicPeers(context: Context): List<String> {
        val stored = P2PPreferences.prefs(context)
            .getStringSet(KNOWN_PUBLIC_PEERS, emptySet())
            .orEmpty()
            .mapNotNull(::normalizedPeerUri)
            .distinctBy(String::lowercase)
        return (stored.ifEmpty { ConfigurationProxy.DEFAULT_PUBLIC_PEERS })
            .take(MAX_PUBLIC_PEERS)
    }

    internal fun hasStoredPublicPeers(context: Context): Boolean =
        P2PPreferences.prefs(context).contains(KNOWN_PUBLIC_PEERS)

    internal fun replacePublicPeers(context: Context, peers: Collection<String>) {
        val normalized = peers.asSequence()
            .mapNotNull(::normalizedPeerUri)
            .distinctBy(String::lowercase)
            .take(MAX_PUBLIC_PEERS)
            .toSet()
        P2PPreferences.prefs(context).edit()
            .putStringSet(KNOWN_PUBLIC_PEERS, normalized)
            .apply()
    }

    fun disabledPublicPeers(context: Context): Set<String> =
        P2PPreferences.prefs(context)
            .getStringSet(DISABLED_PUBLIC_PEERS, emptySet())
            .orEmpty()
            .mapNotNullTo(mutableSetOf(), ::normalizedPeerUri)

    fun setPublicPeerEnabled(context: Context, uri: String, enabled: Boolean) {
        val normalized = normalizedPeerUri(uri) ?: return
        val disabled = disabledPublicPeers(context).toMutableSet().apply {
            removeAll { it.equals(normalized, ignoreCase = true) }
            if (!enabled) add(normalized)
        }
        P2PPreferences.prefs(context).edit().putStringSet(DISABLED_PUBLIC_PEERS, disabled).apply()
    }

    fun customPeers(context: Context): List<CustomYggdrasilPeer> {
        val raw = P2PPreferences.prefs(context).getString(CUSTOM_PEERS_JSON, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_CUSTOM_PEERS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val uri = normalizedPeerUri(item.optString("uri")) ?: continue
                    if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || !validName(name)) continue
                    add(CustomYggdrasilPeer(id, name, uri, item.optBoolean("enabled", true)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCustomPeer(context: Context, name: String, uri: String): String? {
        val cleanName = name.trim()
        if (!validName(cleanName)) return "Peer name must contain 1 to 60 characters"
        val normalized = normalizedPeerUri(uri)
            ?: return "URI must use tcp or tls and include a host and port"
        val custom = customPeers(context).toMutableList()
        if (custom.size >= MAX_CUSTOM_PEERS) return "No more than $MAX_CUSTOM_PEERS custom peers"
        if (custom.any { it.uri.equals(normalized, ignoreCase = true) } ||
            publicPeers(context).any { it.equals(normalized, ignoreCase = true) }
        ) {
            return "This peer already exists"
        }
        custom += CustomYggdrasilPeer(UUID.randomUUID().toString(), cleanName, normalized)
        saveCustomPeers(context, custom)
        return null
    }

    fun setCustomPeerEnabled(context: Context, id: String, enabled: Boolean) {
        saveCustomPeers(
            context,
            customPeers(context).map { if (it.id == id) it.copy(enabled = enabled) else it },
        )
    }

    fun deleteCustomPeer(context: Context, id: String) {
        saveCustomPeers(context, customPeers(context).filterNot { it.id == id })
    }

    fun sort(context: Context): YggdrasilPeerSort = YggdrasilPeerSort.fromStored(
        P2PPreferences.prefs(context).getString(PEER_SORT, null),
    )

    fun setSort(context: Context, sort: YggdrasilPeerSort) {
        P2PPreferences.prefs(context).edit().putString(PEER_SORT, sort.storedValue).apply()
    }

    fun sortedPublicPeers(
        peers: List<String>,
        disabledPeers: Set<String>,
        sort: YggdrasilPeerSort,
    ): List<String> = peers.sortedWith(peerComparator(sort) { uri ->
        disabledPeers.any { it.equals(uri, ignoreCase = true) }
    })

    fun sortedCustomPeers(
        peers: List<CustomYggdrasilPeer>,
        sort: YggdrasilPeerSort,
    ): List<CustomYggdrasilPeer> = peers.sortedWith(
        when (sort) {
            YggdrasilPeerSort.STATUS -> compareByDescending<CustomYggdrasilPeer> { it.enabled }
                .thenBy { it.name.lowercase() }
                .thenBy { it.uri.lowercase() }
            YggdrasilPeerSort.ADDRESS -> compareBy { peerAddressSortKey(it.uri) }
            YggdrasilPeerSort.PROTOCOL -> compareBy<CustomYggdrasilPeer> { peerProtocol(it.uri).orEmpty() }
                .thenBy { it.uri.lowercase() }
        },
    )

    internal fun effectivePeerUris(context: Context, publicCandidates: Collection<String>): List<String> {
        return selectEffectivePeerUris(
            publicCandidates = publicCandidates,
            publicEnabled = publicPeersEnabled(context),
            disabledPublicPeers = disabledPublicPeers(context),
            customPeers = customPeers(context),
        )
    }

    internal fun selectEffectivePeerUris(
        publicCandidates: Collection<String>,
        publicEnabled: Boolean,
        disabledPublicPeers: Set<String>,
        customPeers: List<CustomYggdrasilPeer>,
    ): List<String> {
        val public = if (publicEnabled) {
            publicCandidates.asSequence()
                .mapNotNull(::normalizedPeerUri)
                .filterNot { uri -> disabledPublicPeers.any { it.equals(uri, ignoreCase = true) } }
        } else {
            emptySequence()
        }
        return (public + customPeers.asSequence().filter { it.enabled }.map { it.uri })
            .distinctBy(String::lowercase)
            .toList()
    }

    fun resetDefaults(context: Context) {
        P2PPreferences.prefs(context).edit()
            .remove(PUBLIC_PEERS_ENABLED)
            .remove(DISABLED_PUBLIC_PEERS)
            .remove(CUSTOM_PEERS_JSON)
            .remove(PEER_SORT)
            .apply()
    }

    internal fun peerProtocol(uri: String): String? = try {
        URI(uri.trim()).scheme?.lowercase()?.takeIf { it in supportedProtocols }
    } catch (_: Exception) {
        null
    }

    internal fun normalizedPeerUri(value: String): String? {
        val clean = value.trim()
        if (clean.isEmpty() || clean.length > 512 || clean.any(Char::isISOControl)) return null
        return try {
            val uri = URI(clean)
            val protocol = uri.scheme?.lowercase()
            if (protocol !in supportedProtocols || uri.host.isNullOrBlank() || uri.port !in 1..65535 ||
                uri.userInfo != null || uri.fragment != null
            ) {
                null
            } else {
                clean
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun peerComparator(
        sort: YggdrasilPeerSort,
        isDisabled: (String) -> Boolean,
    ): Comparator<String> = when (sort) {
        YggdrasilPeerSort.STATUS -> compareBy<String> { isDisabled(it) }
            .thenBy { it.lowercase() }
        YggdrasilPeerSort.ADDRESS -> compareBy(::peerAddressSortKey)
        YggdrasilPeerSort.PROTOCOL -> compareBy<String> { peerProtocol(it).orEmpty() }
            .thenBy { it.lowercase() }
    }

    private fun validName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 60 && name.none(Char::isISOControl)

    private fun peerAddressSortKey(uri: String): String = try {
        URI(uri).let { parsed -> "${parsed.host.orEmpty().lowercase()}:${parsed.port}" }
    } catch (_: Exception) {
        uri.lowercase()
    }

    private fun saveCustomPeers(context: Context, peers: List<CustomYggdrasilPeer>) {
        val payload = JSONArray().apply {
            peers.take(MAX_CUSTOM_PEERS).forEach { peer ->
                put(JSONObject().apply {
                    put("id", peer.id)
                    put("name", peer.name)
                    put("uri", peer.uri)
                    put("enabled", peer.enabled)
                })
            }
        }
        P2PPreferences.prefs(context).edit().putString(CUSTOM_PEERS_JSON, payload.toString()).apply()
    }
}
