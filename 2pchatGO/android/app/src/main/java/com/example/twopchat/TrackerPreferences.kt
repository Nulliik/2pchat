package com.example.twopchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class BuiltInTracker(
    val name: String,
    val url: String,
    val protocol: String,
)

data class CustomTracker(
    val id: String,
    val name: String,
    val url: String,
    val protocol: String,
    val enabled: Boolean = true,
)

object TrackerPreferences {
    const val ANNOUNCE_ENABLED = "tracker_announce_enabled"
    const val DHT_ENABLED = "tracker_dht_enabled"
    const val CLEARNET_TRACKERS_ENABLED = "tracker_clearnet_enabled"
    const val YGG_TRACKERS_ENABLED = "tracker_ygg_enabled"
    const val IPV4_ANNOUNCE_MODE = "tracker_ipv4_announce_mode"
    const val ENABLED_PROTOCOLS = "tracker_enabled_protocols"
    const val DISABLED_BUILTINS = "tracker_disabled_builtins"
    const val CUSTOM_TRACKERS_JSON = "tracker_custom_json"
    const val MAX_CUSTOM_TRACKERS = 32

    val supportedProtocols = setOf("http", "https", "udp")

    val builtInTrackers = listOf(
        BuiltInTracker("Torrent.eu.org UDP", "udp://tracker.torrent.eu.org:451/announce", "udp"),
        BuiltInTracker("Open Stealth UDP", "udp://open.stealth.si:80/announce", "udp"),
        BuiltInTracker("Exodus UDP", "udp://exodus.desync.com:6969/announce", "udp"),
        BuiltInTracker("OpenTrackr UDP", "udp://tracker.opentrackr.org:1337/announce", "udp"),
        BuiltInTracker("Dler UDP", "udp://tracker2.dler.org:80/announce", "udp"),
        BuiltInTracker("BitSearch UDP", "udp://tracker.bitsearch.to:6969/announce", "udp"),
        BuiltInTracker("Qu.Ax UDP", "udp://tracker.qu.ax:6969/announce", "udp"),
        BuiltInTracker("OpenTrackr HTTPS", "https://tracker.opentrackr.org:443/announce", "https"),
        BuiltInTracker("Yemekyedim HTTPS", "https://tracker.yemekyedim.com:443/announce", "https"),
        BuiltInTracker("Nyacat HTTPS", "https://tr.nyacat.pw:443/announce", "https"),
        BuiltInTracker(
            "Yggdrasil-only HTTP",
            "http://[200:1e2f:e608:eb3a:2bf:1e62:87ba:e2f7]/announce",
            "http",
        ),
        BuiltInTracker(
            "Yggdrasil-only UDP",
            "udp://[202:68d0:f0d5:b88d:1d1a:555e:2f6b:3148]:6969/announce",
            "udp",
        ),
    )

    fun announceEnabled(context: Context): Boolean =
        P2PPreferences.prefs(context).getBoolean(ANNOUNCE_ENABLED, true)

    fun getActiveTrackerUrls(context: Context): List<String> {
        val disabled = disabledBuiltIns(context)
        val protocols = enabledProtocols(context)
        val clearnetEnabled = clearnetTrackersEnabled(context)
        val yggEnabled = yggTrackersEnabled(context)

        val builtins = builtInTrackers.filter { tracker ->
            tracker.name !in disabled &&
            tracker.protocol in protocols &&
            if (tracker.name.contains("Yggdrasil", ignoreCase = true)) yggEnabled else clearnetEnabled
        }.map { it.url }

        val customs = customTrackers(context).filter { tracker ->
            tracker.enabled && tracker.protocol in protocols
        }.map { it.url }

        val all = (builtins + customs).distinct()
        return if (all.isNotEmpty()) all else listOf(
            "udp://tracker.openbittorrent.com:6969",
            "udp://tracker.opentrackr.org:1337/announce",
            "https://tracker.tamersunion.org:443/announce",
        )
    }

    fun setAnnounceEnabled(context: Context, enabled: Boolean) {
        P2PPreferences.prefs(context).edit().putBoolean(ANNOUNCE_ENABLED, enabled).apply()
    }

    fun clearnetTrackersEnabled(context: Context): Boolean =
        P2PPreferences.prefs(context).getBoolean(CLEARNET_TRACKERS_ENABLED, true)

    fun setClearnetTrackersEnabled(context: Context, enabled: Boolean) {
        P2PPreferences.prefs(context).edit().putBoolean(CLEARNET_TRACKERS_ENABLED, enabled).apply()
    }

    fun yggTrackersEnabled(context: Context): Boolean =
        P2PPreferences.prefs(context).getBoolean(YGG_TRACKERS_ENABLED, true)

    fun setYggTrackersEnabled(context: Context, enabled: Boolean) {
        P2PPreferences.prefs(context).edit().putBoolean(YGG_TRACKERS_ENABLED, enabled).apply()
    }

    fun ipv4AnnounceMode(context: Context): String =
        P2PPreferences.prefs(context).getString(IPV4_ANNOUNCE_MODE, "always") ?: "always"

    fun setIpv4AnnounceMode(context: Context, mode: String) {
        P2PPreferences.prefs(context).edit().putString(IPV4_ANNOUNCE_MODE, mode).apply()
    }

    fun dhtEnabled(context: Context): Boolean =
        P2PPreferences.prefs(context).getBoolean(DHT_ENABLED, true)

    fun setDhtEnabled(context: Context, enabled: Boolean) {
        P2PPreferences.prefs(context).edit().putBoolean(DHT_ENABLED, enabled).apply()
    }

    fun enabledProtocols(context: Context): Set<String> =
        P2PPreferences.prefs(context)
            .getStringSet(ENABLED_PROTOCOLS, supportedProtocols)
            .orEmpty()
            .filterTo(mutableSetOf()) { it in supportedProtocols }

    fun setProtocolEnabled(context: Context, protocol: String, enabled: Boolean) {
        require(protocol in supportedProtocols)
        val updated = enabledProtocols(context).toMutableSet().apply {
            if (enabled) add(protocol) else remove(protocol)
        }
        P2PPreferences.prefs(context).edit().putStringSet(ENABLED_PROTOCOLS, updated).apply()
    }

    fun disabledBuiltIns(context: Context): Set<String> =
        P2PPreferences.prefs(context)
            .getStringSet(DISABLED_BUILTINS, emptySet())
            .orEmpty()
            .filterTo(mutableSetOf()) { name -> builtInTrackers.any { it.name == name } }

    fun setBuiltInEnabled(context: Context, name: String, enabled: Boolean) {
        require(builtInTrackers.any { it.name == name })
        val disabled = disabledBuiltIns(context).toMutableSet().apply {
            if (enabled) remove(name) else add(name)
        }
        P2PPreferences.prefs(context).edit().putStringSet(DISABLED_BUILTINS, disabled).apply()
    }

    fun customTrackers(context: Context): List<CustomTracker> {
        val raw = P2PPreferences.prefs(context).getString(CUSTOM_TRACKERS_JSON, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_CUSTOM_TRACKERS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    val protocol = trackerProtocol(url) ?: continue
                    if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}")) ||
                        name.isBlank() || name.length > 60 || name.any { it.code < 32 }
                    ) continue
                    add(CustomTracker(id, name, url, protocol, item.optBoolean("enabled", true)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCustomTracker(context: Context, name: String, url: String): String? {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || cleanName.length > 60 || cleanName.any { it.code < 32 }) {
            return "Tracker name must contain 1 to 60 characters"
        }
        val protocol = trackerProtocol(cleanUrl)
            ?: return "URL must use http, https, or udp; UDP requires an explicit port"
        val trackers = customTrackers(context).toMutableList()
        if (trackers.size >= MAX_CUSTOM_TRACKERS) return "No more than $MAX_CUSTOM_TRACKERS custom trackers"
        if (trackers.any { it.url.equals(cleanUrl, ignoreCase = true) } ||
            builtInTrackers.any { it.url.equals(cleanUrl, ignoreCase = true) }
        ) {
            return "This tracker already exists"
        }
        trackers += CustomTracker(UUID.randomUUID().toString(), cleanName, cleanUrl, protocol)
        saveCustomTrackers(context, trackers)
        return null
    }

    fun setCustomTrackerEnabled(context: Context, id: String, enabled: Boolean) {
        saveCustomTrackers(
            context,
            customTrackers(context).map { if (it.id == id) it.copy(enabled = enabled) else it },
        )
    }

    fun deleteCustomTracker(context: Context, id: String) {
        saveCustomTrackers(context, customTrackers(context).filterNot { it.id == id })
    }

    fun resetDefaults(context: Context) {
        P2PPreferences.prefs(context).edit()
            .remove(ANNOUNCE_ENABLED)
            .remove(DHT_ENABLED)
            .remove(CLEARNET_TRACKERS_ENABLED)
            .remove(YGG_TRACKERS_ENABLED)
            .remove(IPV4_ANNOUNCE_MODE)
            .remove(ENABLED_PROTOCOLS)
            .remove(DISABLED_BUILTINS)
            .remove(CUSTOM_TRACKERS_JSON)
            .apply()
    }

    fun configJson(context: Context): String = JSONObject().apply {
        put("announce_enabled", announceEnabled(context))
        put("dht_enabled", dhtEnabled(context))
        put("clearnet_trackers_enabled", clearnetTrackersEnabled(context))
        put("ygg_trackers_enabled", yggTrackersEnabled(context))
        put("ipv4_announce_mode", ipv4AnnounceMode(context))
        put("enabled_protocols", JSONArray(enabledProtocols(context).sorted()))
        put("disabled_builtin_trackers", JSONArray(disabledBuiltIns(context).sorted()))
        put("custom_trackers", JSONArray().apply {
            customTrackers(context).forEach { tracker ->
                put(JSONObject().apply {
                    put("id", tracker.id)
                    put("name", tracker.name)
                    put("url", tracker.url)
                    put("enabled", tracker.enabled)
                })
            }
        })
    }.toString()

    internal fun trackerProtocol(url: String): String? {
        return try {
            val uri = URI(url.trim())
            val protocol = uri.scheme?.lowercase() ?: return null
            if (protocol !in supportedProtocols || uri.host.isNullOrBlank() || uri.userInfo != null) {
                return null
            }
            if (protocol == "udp" && uri.port !in 1..65535) return null
            if (uri.port > 65535) return null
            protocol
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCustomTrackers(context: Context, trackers: List<CustomTracker>) {
        val payload = JSONArray().apply {
            trackers.take(MAX_CUSTOM_TRACKERS).forEach { tracker ->
                put(JSONObject().apply {
                    put("id", tracker.id)
                    put("name", tracker.name)
                    put("url", tracker.url)
                    put("enabled", tracker.enabled)
                })
            }
        }
        P2PPreferences.prefs(context).edit().putString(CUSTOM_TRACKERS_JSON, payload.toString()).apply()
    }
}
