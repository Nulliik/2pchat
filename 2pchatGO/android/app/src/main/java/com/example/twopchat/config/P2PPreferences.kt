package com.example.twopchat.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.twopchat.relay.*
import com.example.twopchat.security.*
import com.example.twopchat.tor.*
import java.util.UUID

import java.util.concurrent.ConcurrentHashMap

/** Canonical keys for relay state which is intentionally small key/value metadata. */
object P2PPreferences {
    const val FILE_NAME = "2pchat_prefs"
    private const val ENCRYPTED_FILE_NAME = "2pchat_secure_prefs"
    const val ACTIVE_CHATS = "active_chats"
    const val LISTENER_PORT = "listener_port"
    const val WIFI_DISCOVERY = "settings_wifi"
    const val STICKER_CACHE_LIMIT_MB = "settings_sticker_cache_limit_mb"
    const val UPNP_ENABLED = "settings_upnp"
    const val DEFAULT_LISTENER_PORT = 50001
    const val PROXY_ENABLED = "settings_proxy_enabled"
    const val TOR_ENABLED = "settings_tor_enabled"
    const val TOR_BRIDGES = "settings_tor_bridges"
    const val TOR_PUBLIC_BRIDGES_ENABLED = "settings_tor_public_bridges_enabled"
    const val TOR_TRANSPORT = "settings_tor_transport"
    const val TOR_ONION_HOSTNAME = "settings_tor_onion_hostname"
    const val PROXY_HOST = "settings_proxy_host"
    const val PROXY_PORT = "settings_proxy_port"
    const val DEFAULT_PROXY_HOST = "127.0.0.1"
    const val DEFAULT_PROXY_PORT = 9050
    const val HERO_WIDGET_COLLAPSED_DEFAULT = "settings_hero_widget_collapsed"

    const val SOCKS5_ENABLED = "settings_socks5_enabled"
    const val SOCKS5_HOST = "settings_socks5_host"
    const val SOCKS5_PORT = "settings_socks5_port"
    const val DEFAULT_SOCKS5_HOST = "127.0.0.1"
    const val DEFAULT_SOCKS5_PORT = 1080
    const val INCOGNITO_KEYBOARD = "settings_incognito_keyboard"
    const val USE_NATIVE_GO_CORE = "settings_use_native_go_core"

    enum class YggdrasilMode(val id: String) {
        PROXY("proxy"),
        VPN("vpn")
    }

    const val YGGDRASIL_MODE = "settings_yggdrasil_operation_mode"
    const val PREF_YGGDRASIL_PROXY_PORT = "settings_yggdrasil_proxy_port"
    const val DEFAULT_YGGDRASIL_PROXY_PORT = 9053
    const val DEFAULT_YGGDRASIL_PROXY_HOST = "127.0.0.1"

    fun findAvailablePort(preferredPort: Int = DEFAULT_YGGDRASIL_PROXY_PORT, host: String = DEFAULT_YGGDRASIL_PROXY_HOST): Int {
        if (preferredPort > 0) {
            try {
                java.net.ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName(host), preferredPort))
                    return socket.localPort
                }
            } catch (_: Throwable) {}
        }
        // Ask operating system for an available ephemeral port
        try {
            java.net.ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName(host), 0))
                return socket.localPort
            }
        } catch (_: Throwable) {}
        return if (preferredPort > 0) preferredPort else DEFAULT_YGGDRASIL_PROXY_PORT
    }

    fun getYggdrasilProxyPort(context: Context): Int =
        prefs(context).getInt(PREF_YGGDRASIL_PROXY_PORT, DEFAULT_YGGDRASIL_PROXY_PORT)

    fun setYggdrasilProxyPort(context: Context, port: Int) {
        prefs(context).edit().putInt(PREF_YGGDRASIL_PROXY_PORT, port).apply()
    }

    fun getYggdrasilProxyAddr(context: Context): String {
        val port = getYggdrasilProxyPort(context)
        return "$DEFAULT_YGGDRASIL_PROXY_HOST:$port"
    }

    fun getYggdrasilMode(context: Context): YggdrasilMode {
        val raw = prefs(context).getString(YGGDRASIL_MODE, YggdrasilMode.PROXY.id)
        return YggdrasilMode.entries.find { it.id == raw } ?: YggdrasilMode.PROXY
    }

    fun setYggdrasilMode(context: Context, mode: YggdrasilMode) {
        prefs(context).edit().putString(YGGDRASIL_MODE, mode.id).apply()
    }

    fun isUseNativeGoCore(context: Context): Boolean = true

    fun setUseNativeGoCore(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(USE_NATIVE_GO_CORE, true).apply()
    }

    fun username(context: Context): String =
        prefs(context).getString("username_profile", "")?.takeIf { it != "User Identity" }.orEmpty()

    fun aboutMe(context: Context): String {
        val fromPrefs = prefs(context).getString("about_me_profile", null)?.trim()?.takeIf { it.isNotBlank() }
        if (fromPrefs != null) return fromPrefs

        val fromFile = runCatching {
            val file = java.io.File(context.filesDir, "profile_about_me.txt")
            if (file.exists()) file.readText().trim().takeIf { it.isNotBlank() } else null
        }.getOrNull()
        if (fromFile != null) {
            prefs(context).edit().putString("about_me_profile", fromFile).apply()
            return fromFile
        }

        val fromDb = runCatching {
            com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).getPeerAboutMe("my_profile_about_me")?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (fromDb != null) {
            prefs(context).edit().putString("about_me_profile", fromDb).apply()
            return fromDb
        }

        return ""
    }

    fun setAboutMe(context: Context, text: String) {
        val clean = text.trim()
        prefs(context).edit().putString("about_me_profile", clean).apply()
        runCatching {
            val file = java.io.File(context.filesDir, "profile_about_me.txt")
            file.writeText(clean)
        }
        runCatching {
            com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).savePeerAboutMe("my_profile_about_me", clean)
            val username = username(context)
            if (username.isNotBlank()) {
                com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).savePeerAboutMe(username, clean)
            }
        }
    }

    fun getRendezvousCode(context: Context): String {
        val sp = prefs(context)
        var code = sp.getString("local_discovery_code", null)
        if (code.isNullOrBlank() || code == "dce7d654") {
            val fp = P2PMessageRelay.getBridge(context).getLocalFingerprint().takeIf { it.isNotBlank() }
                ?: sp.getString("local_fingerprint", null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(fp.toByteArray(Charsets.UTF_8))
            code = digest.take(4).joinToString("") { "%02x".format(it) }
            sp.edit().putString("local_discovery_code", code).apply()
        }
        return code
    }

    fun getOrCreateDiscoveryCode(context: Context): String = getRendezvousCode(context)

    fun isIncognitoKeyboardEnabled(context: Context): Boolean =
        prefs(context).getBoolean(INCOGNITO_KEYBOARD, true)

    fun setIncognitoKeyboardEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(INCOGNITO_KEYBOARD, enabled).apply()
    }

    fun isHeroWidgetCollapsedByDefault(context: Context): Boolean =
        prefs(context).getBoolean(HERO_WIDGET_COLLAPSED_DEFAULT, false)

    fun isCustomSocks5Enabled(context: Context): Boolean =
        prefs(context).getBoolean(SOCKS5_ENABLED, false)

    fun getCustomSocks5Host(context: Context): String =
        prefs(context).getString(SOCKS5_HOST, DEFAULT_SOCKS5_HOST)?.takeIf { it.isNotBlank() } ?: DEFAULT_SOCKS5_HOST

    fun getCustomSocks5Port(context: Context): Int =
        prefs(context).getInt(SOCKS5_PORT, DEFAULT_SOCKS5_PORT)
            .takeIf { it in 1..65535 }
            ?: DEFAULT_SOCKS5_PORT

    fun isWifiDiscoveryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(WIFI_DISCOVERY, true)

    fun isUpnpEnabled(context: Context): Boolean =
        prefs(context).getBoolean(UPNP_ENABLED, false)

    fun isProxyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PROXY_ENABLED, false)

    fun isTorEnabled(context: Context): Boolean {
        val preferences = prefs(context)
        if (preferences.contains(TOR_ENABLED)) {
            return preferences.getBoolean(TOR_ENABLED, false)
        }
        return isLegacyTorConfiguration(
            proxyEnabled = preferences.getBoolean(PROXY_ENABLED, false),
            proxyHost = preferences.getString(PROXY_HOST, DEFAULT_PROXY_HOST),
            proxyPort = preferences.getInt(PROXY_PORT, DEFAULT_PROXY_PORT),
        )
    }

    internal fun isLegacyTorConfiguration(
        proxyEnabled: Boolean,
        proxyHost: String?,
        proxyPort: Int,
    ): Boolean = proxyEnabled &&
        proxyPort == DEFAULT_PROXY_PORT &&
        proxyHost?.trim()?.lowercase() in setOf("127.0.0.1", "localhost", "::1")

    fun getProxyHost(context: Context): String =
        prefs(context).getString(PROXY_HOST, DEFAULT_PROXY_HOST) ?: DEFAULT_PROXY_HOST

    fun getProxyPort(context: Context): Int =
        prefs(context).getInt(PROXY_PORT, DEFAULT_PROXY_PORT)

    fun getTorBridgeLines(context: Context): List<String> =
        prefs(context).getString(TOR_BRIDGES, "")
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

    fun setTorBridgeLines(context: Context, bridges: List<String>): Boolean {
        prefs(context).edit()
            .putString(TOR_BRIDGES, bridges.joinToString("\n") { it.trim() })
            .apply()
        return true
    }

    fun publicTorBridgesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(TOR_PUBLIC_BRIDGES_ENABLED, true)

    fun setPublicTorBridgesEnabled(context: Context, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean(TOR_PUBLIC_BRIDGES_ENABLED, enabled).apply()
        return true
    }

    fun torTransport(context: Context): TorTransport = TorTransport.fromStored(
        prefs(context).getString(TOR_TRANSPORT, null),
    )

    fun setTorTransport(context: Context, transport: TorTransport): Boolean {
        prefs(context).edit().putString(TOR_TRANSPORT, transport.storedValue).apply()
        return true
    }

    fun getTorOnionHostname(context: Context): String? =
        prefs(context).getString(TOR_ONION_HOSTNAME, null)?.takeIf { it.isNotBlank() }

    fun setTorOnionHostname(context: Context, hostname: String?): Boolean {
        prefs(context).edit().putString(TOR_ONION_HOSTNAME, hostname).apply()
        return true
    }

    private const val KEY_TOR_HIDDEN_SERVICE_ENABLED = "tor_hidden_service_enabled"

    fun isTorHiddenServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TOR_HIDDEN_SERVICE_ENABLED, true)

    fun setTorHiddenServiceEnabled(context: Context, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean(KEY_TOR_HIDDEN_SERVICE_ENABLED, enabled).apply()
        return true
    }

    fun getEffectiveTorBridgeLines(context: Context): List<String> =
        TorBridgeCatalog.select(
            customBridges = getTorBridgeLines(context),
            publicBridgesEnabled = publicTorBridgesEnabled(context),
            transport = torTransport(context),
        )

    const val DEFAULT_STICKER_CACHE_LIMIT_MB = 100
    val STICKER_CACHE_LIMIT_OPTIONS_MB = listOf(50, 100, 250, 500)
    const val MIN_LISTENER_PORT = 1024
    const val MAX_LISTENER_PORT = 65535

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Volatile
    private var isAppLockedState = false

    fun isAppLocked(): Boolean = isAppLockedState

    fun setAppLocked(locked: Boolean) {
        isAppLockedState = locked
    }

    /** Drops account-derived caches after the backing preferences are erased. */
    fun clearInMemoryState() {
        synchronized(this) {
            cachedPrefs?.let { p ->
                runCatching { p.edit().clear().commit() }
            }
            cachedPrefs = null
            fingerprintToPeerNameCache.clear()
            fingerprintCacheInitialized = false
            isAppLockedState = false
        }
    }

    private val fingerprintToPeerNameCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var fingerprintCacheInitialized = false

    fun findPeerNameByFingerprint(context: Context, fingerprint: String): String? {
        if (fingerprint.isBlank()) return null
        if (!fingerprintCacheInitialized) {
            synchronized(this) {
                if (!fingerprintCacheInitialized) {
                    val allEntries = prefs(context).all
                    for ((key, value) in allEntries) {
                        if (key.startsWith("peer_fingerprint_") && value is String && value.isNotBlank()) {
                            val peerName = key.removePrefix("peer_fingerprint_")
                            fingerprintToPeerNameCache[value] = peerName
                        }
                    }
                    fingerprintCacheInitialized = true
                }
            }
        }
        val cached = fingerprintToPeerNameCache[fingerprint]
        if (!cached.isNullOrBlank()) return cached
        val fromDb = try {
            com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).getPeerNameByFingerprint(fingerprint)
        } catch (_: Throwable) {
            null
        }
        if (!fromDb.isNullOrBlank()) {
            fingerprintToPeerNameCache[fingerprint] = fromDb
            return fromDb
        }
        return null
    }

    fun findPeerByDiscoveryToken(context: Context, discoveryToken: String): Pair<String, String>? {
        if (discoveryToken.isBlank()) return null
        if (!fingerprintCacheInitialized) {
            findPeerNameByFingerprint(context, "dummy")
        }
        for ((fingerprint, peerName) in fingerprintToPeerNameCache) {
            if (LocalDiscoveryToken.matchesFingerprint(discoveryToken, fingerprint)) {
                return Pair(peerName, fingerprint)
            }
        }
        return null
    }

    fun findPeerNameByEndpoint(context: Context, targetEndpoint: String): String? {
        if (targetEndpoint.isBlank()) return null
        val cleanTarget = targetEndpoint.trim().lowercase(java.util.Locale.US).removePrefix("[").removeSuffix("]")
        val targetHost = if (cleanTarget.contains(":")) cleanTarget.substringBefore(":") else cleanTarget
        if (targetHost.isBlank()) return null
        val allEntries = prefs(context).all
        
        // Pass 1: exact endpoint match (host:port)
        for ((key, value) in allEntries) {
            if ((key.startsWith("last_endpoint_") || key.startsWith("pending_peer_endpoint_") || key.startsWith("peer_onion_address_")) && value is String) {
                val endpoints = value.split(",")
                for (ep in endpoints) {
                    val cleanEp = ep.trim().lowercase(java.util.Locale.US).removePrefix("[").removeSuffix("]")
                    if (cleanEp == cleanTarget && cleanEp.isNotBlank()) {
                        val peerName = when {
                            key.startsWith("last_endpoint_") -> key.removePrefix("last_endpoint_")
                            key.startsWith("pending_peer_endpoint_") -> key.removePrefix("pending_peer_endpoint_")
                            else -> key.removePrefix("peer_onion_address_")
                        }
                        if (peerName.isNotBlank() && !peerName.startsWith("Peer (") && !peerName.startsWith("Tor Peer (")) {
                            return peerName
                        }
                    }
                }
            }
        }
        
        // Pass 2: host match (for non-loopback hosts)
        if (targetHost != "127.0.0.1" && targetHost != "localhost") {
            for ((key, value) in allEntries) {
                if ((key.startsWith("last_endpoint_") || key.startsWith("pending_peer_endpoint_") || key.startsWith("peer_onion_address_")) && value is String) {
                    val endpoints = value.split(",")
                    for (ep in endpoints) {
                        val cleanEp = ep.trim().lowercase(java.util.Locale.US).removePrefix("[").removeSuffix("]")
                        val host = if (cleanEp.contains(":")) cleanEp.substringBefore(":") else cleanEp
                        if (host == targetHost && host.isNotBlank()) {
                            val peerName = when {
                                key.startsWith("last_endpoint_") -> key.removePrefix("last_endpoint_")
                                key.startsWith("pending_peer_endpoint_") -> key.removePrefix("pending_peer_endpoint_")
                                else -> key.removePrefix("peer_onion_address_")
                            }
                            if (peerName.isNotBlank() && !peerName.startsWith("Peer (") && !peerName.startsWith("Tor Peer (")) {
                                return peerName
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    fun updateFingerprintCache(fingerprint: String, peerName: String) {
        if (fingerprint.isNotBlank() && peerName.isNotBlank()) {
            fingerprintToPeerNameCache[fingerprint] = peerName
        }
    }

    @Suppress("DEPRECATION")
    fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs?.let { return it }
            val appContext = context.applicationContext
            val preferences = try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    ENCRYPTED_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (e: Exception) {
                android.util.Log.e("P2PPreferences", "Failed to initialize EncryptedSharedPreferences", e)
                throw IllegalStateException("EncryptedSharedPreferences initialization failed: Keystore unavailable", e)
            }
            migrateLegacyPreferences(appContext, preferences)
            cachedPrefs = preferences
            preferences
        }
    }

    private fun migrateLegacyPreferences(context: Context, target: SharedPreferences) {
        val legacy = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val editor = target.edit()
        for ((key, value) in legacy.all) {
            if (target.contains(key)) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        check(editor.commit()) { "Unable to migrate preferences into encrypted storage" }
        check(legacy.edit().clear().commit()) { "Unable to remove legacy plaintext preferences" }
    }

    fun listenerPort(context: Context): Int =
        prefs(context).getInt(LISTENER_PORT, DEFAULT_LISTENER_PORT)
            .coerceIn(MIN_LISTENER_PORT, MAX_LISTENER_PORT)

    fun stickerCacheLimitMb(context: Context): Int {
        val stored = prefs(context).getInt(
            STICKER_CACHE_LIMIT_MB,
            DEFAULT_STICKER_CACHE_LIMIT_MB,
        )
        return stored.takeIf { it in STICKER_CACHE_LIMIT_OPTIONS_MB }
            ?: DEFAULT_STICKER_CACHE_LIMIT_MB
    }

    fun setStickerCacheLimitMb(context: Context, limitMb: Int) {
        require(limitMb in STICKER_CACHE_LIMIT_OPTIONS_MB) {
            "Unsupported sticker cache limit: $limitMb MB"
        }
        prefs(context).edit().putInt(STICKER_CACHE_LIMIT_MB, limitMb).apply()
    }

    fun peerFingerprint(peerName: String) = "peer_fingerprint_$peerName"
    fun peerOnionAddress(peerName: String) = "peer_onion_$peerName"
    fun fingerprintMismatch(peerName: String) = "fingerprint_mismatch_$peerName"
    fun pendingPeerFingerprint(peerName: String) = "pending_peer_fingerprint_$peerName"
    fun pendingPeerEndpoint(peerName: String) = "pending_peer_endpoint_$peerName"
    fun lastEndpoint(peerName: String) = "last_endpoint_$peerName"
    fun lastMessage(peerName: String) = "last_msg_$peerName"
    fun unreadCount(peerName: String) = "unread_count_$peerName"
    fun draftMessage(peerName: String) = "draft_msg_$peerName"
    fun transport(peerName: String) = "transport_$peerName"
    fun peerTransportPref(peerName: String) = "peer_transport_pref_$peerName"
    fun verifiedPeer(peerName: String) = "verified_peer_$peerName"
    fun blockedPeer(peerName: String) = "blocked_peer_$peerName"
    fun muteNotifications(peerName: String) = "mute_notifications_$peerName"
    fun pinnedMessageId(peerName: String) = "pinned_msg_id_$peerName"
    fun pinnedMessageText(peerName: String) = "pinned_msg_text_$peerName"
    fun pinnedMessageSender(peerName: String) = "pinned_msg_sender_$peerName"
    fun pinnedBy(peerName: String) = "pinned_by_$peerName"
    fun pinnedStateVersion(peerName: String) = "pinned_state_version_$peerName"
    fun pinnedStateActor(peerName: String) = "pinned_state_actor_$peerName"

    fun getPeerFingerprint(context: Context, peerName: String): String? {
        val clean = peerName.trim()
        if (clean.isBlank()) return null
        val fromPrefs = prefs(context).getString(peerFingerprint(clean), null)?.takeIf { it.isNotBlank() }
        if (fromPrefs != null) return fromPrefs
        if (com.example.twopchat.relay.P2PMessageRelay.isRawFingerprint(clean)) return clean
        return runCatching {
            com.example.twopchat.data.ChatDatabaseHelper.getInstance(context).getPeerFingerprint(clean)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    enum class PeerTransportPreference(val key: String) {
        AUTO("auto"),
        TOR_ONLY("tor"),
        YGGDRASIL_ONLY("yggdrasil"),
        DIRECT_ONLY("direct");

        companion object {
            fun fromKey(key: String?): PeerTransportPreference {
                return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: AUTO
            }
        }
    }

    fun getPeerTransportPreference(context: Context, peerName: String): PeerTransportPreference {
        val sp = prefs(context)
        val raw = sp.getString(peerTransportPref(peerName), null)
        if (raw != null) return PeerTransportPreference.fromKey(raw)
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            val fpRaw = sp.getString(peerTransportPref(fp), null)
            if (fpRaw != null) return PeerTransportPreference.fromKey(fpRaw)
        }
        return PeerTransportPreference.AUTO
    }

    fun setPeerTransportPreference(context: Context, peerName: String, pref: PeerTransportPreference) {
        val editor = prefs(context).edit().putString(peerTransportPref(peerName), pref.key)
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            editor.putString(peerTransportPref(fp), pref.key)
        }
        editor.apply()
    }

    fun getEffectiveEndpointsForPeer(context: Context, peerName: String, rawEndpoints: String? = null): String {
        val lastEp = prefs(context).getString(lastEndpoint(peerName), "").orEmpty()
        val savedOnion = getPeerOnionAddress(context, peerName)

        val combined = mutableListOf<String>()
        if (!rawEndpoints.isNullOrBlank()) {
            combined.addAll(rawEndpoints.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (lastEp.isNotBlank()) {
            for (ep in lastEp.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                if (ep !in combined) combined.add(ep)
            }
        }
        if (!savedOnion.isNullOrBlank() && savedOnion !in combined) {
            combined.add(savedOnion)
        }

        if (combined.isEmpty()) return ""
        val pref = getPeerTransportPreference(context, peerName)
        val filtered = filterEndpointsByPreference(combined, pref)
        return if (filtered.isNotEmpty()) {
            filtered.joinToString(",")
        } else if (pref == PeerTransportPreference.TOR_ONLY && !savedOnion.isNullOrBlank()) {
            savedOnion
        } else if (pref == PeerTransportPreference.AUTO) {
            combined.joinToString(",")
        } else {
            // Strict preference mode with no matching endpoints: return empty so it doesn't leak/dial wrong transport
            ""
        }
    }

    fun isYggdrasilEndpoint(endpoint: String): Boolean {
        val clean = endpoint.trim().trim('[', ']').lowercase()
        return clean.startsWith("200:") || clean.startsWith("300:") || clean.startsWith("0200:") || clean.startsWith("0300:")
    }

    fun filterEndpointsByPreference(endpoints: List<String>, pref: PeerTransportPreference): List<String> {
        return when (pref) {
            PeerTransportPreference.AUTO -> endpoints
            PeerTransportPreference.TOR_ONLY -> endpoints.filter { it.contains(".onion", ignoreCase = true) }
            PeerTransportPreference.YGGDRASIL_ONLY -> endpoints.filter { isYggdrasilEndpoint(it) }
            PeerTransportPreference.DIRECT_ONLY -> endpoints.filter { !it.contains(".onion", ignoreCase = true) && !isYggdrasilEndpoint(it) }
        }
    }

    fun setPeerOnionAddress(context: Context, peerName: String, onionAddress: String) {
        val editor = prefs(context).edit().putString(peerOnionAddress(peerName), onionAddress)
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            editor.putString(peerOnionAddress(fp), onionAddress)
        }
        editor.apply()
    }

    fun getPeerOnionAddress(context: Context, peerName: String): String? {
        val sp = prefs(context)
        val prefVal = sp.getString(peerOnionAddress(peerName), null)?.takeIf { it.isNotBlank() }
        if (prefVal != null) return prefVal
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            val fpVal = sp.getString(peerOnionAddress(fp), null)?.takeIf { it.isNotBlank() }
            if (fpVal != null) return fpVal
        }
        return runCatching {
            val db = com.example.twopchat.data.ChatDatabaseHelper.getInstance(context)
            db.getPeerOnionAddress(peerName)
                ?: (if (!fp.isNullOrBlank()) db.getPeerOnionAddress(fp) else null)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private const val PINNED_STATE_LOCAL_ACTOR = "pinned_state_local_actor"

    @Synchronized
    internal fun nextLocalPinnedStateVersion(
        context: Context,
        peerName: String,
    ): PinnedMessageStateVersion {
        val prefs = prefs(context)
        val actor = prefs.getString(PINNED_STATE_LOCAL_ACTOR, null)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val current = currentPinnedStateVersion(prefs, peerName)
        val next = nextPinnedMessageStateVersion(current, actor)
        check(
            prefs.edit()
                .putString(PINNED_STATE_LOCAL_ACTOR, actor)
                .putLong(pinnedStateVersion(peerName), next.counter)
                .putString(pinnedStateActor(peerName), next.actor)
                .commit()
        ) { "Unable to persist pinned-message version" }
        return next
    }

    internal fun currentPinnedStateVersion(
        prefs: SharedPreferences,
        peerName: String,
    ): PinnedMessageStateVersion = PinnedMessageStateVersion(
        counter = prefs.getLong(pinnedStateVersion(peerName), 0L),
        actor = prefs.getString(pinnedStateActor(peerName), "").orEmpty(),
    )

    fun isPeerVerified(context: Context, peerName: String): Boolean {
        val sp = prefs(context)
        if (sp.getBoolean(verifiedPeer(peerName), false)) return true
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            if (sp.getBoolean(verifiedPeer(fp), false)) return true
        }
        return false
    }

    fun setPeerVerified(context: Context, peerName: String, verified: Boolean) {
        val editor = prefs(context).edit().putBoolean(verifiedPeer(peerName), verified)
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            editor.putBoolean(verifiedPeer(fp), verified)
        }
        editor.apply()
    }

    fun isPeerBlocked(context: Context, peerName: String): Boolean {
        val sp = prefs(context)
        if (sp.getBoolean(blockedPeer(peerName), false)) return true
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            if (sp.getBoolean(blockedPeer(fp), false)) return true
        }
        return false
    }

    fun setPeerBlocked(context: Context, peerName: String, blocked: Boolean) {
        val editor = prefs(context).edit().putBoolean(blockedPeer(peerName), blocked)
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            editor.putBoolean(blockedPeer(fp), blocked)
        }
        editor.apply()
    }

    fun isPeerMuted(context: Context, peerName: String): Boolean {
        val sp = prefs(context)
        if (sp.getBoolean(muteNotifications(peerName), false)) return true
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            if (sp.getBoolean(muteNotifications(fp), false)) return true
        }
        return false
    }

    fun setPeerMuted(context: Context, peerName: String, muted: Boolean) {
        val editor = prefs(context).edit().putBoolean(muteNotifications(peerName), muted)
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            editor.putBoolean(muteNotifications(fp), muted)
        }
        editor.apply()
    }

    fun getDraftMessage(context: Context, peerName: String): String {
        val sp = prefs(context)
        val raw = sp.getString(draftMessage(peerName), null)?.takeIf { it.isNotBlank() }
        if (raw != null) return raw
        val fp = getPeerFingerprint(context, peerName)
        if (!fp.isNullOrBlank() && fp != peerName) {
            val fpRaw = sp.getString(draftMessage(fp), null)?.takeIf { it.isNotBlank() }
            if (fpRaw != null) return fpRaw
        }
        return ""
    }

    fun setDraftMessage(context: Context, peerName: String, draft: String) {
        val editor = prefs(context).edit()
        val clean = draft.trim()
        val fp = getPeerFingerprint(context, peerName)
        if (clean.isNotBlank()) {
            editor.putString(draftMessage(peerName), clean)
            if (!fp.isNullOrBlank() && fp != peerName) {
                editor.putString(draftMessage(fp), clean)
            }
        } else {
            editor.remove(draftMessage(peerName))
            if (!fp.isNullOrBlank() && fp != peerName) {
                editor.remove(draftMessage(fp))
            }
        }
        editor.apply()
    }

    fun isPeerIdentityChangePending(context: Context, peerName: String): Boolean {
        val prefs = prefs(context)
        val current = prefs.getString(peerFingerprint(peerName), null).orEmpty()
        val pending = prefs.getString(pendingPeerFingerprint(peerName), null).orEmpty()
        if (pending.isNotBlank() && (current.isBlank() || current.equals(peerName, ignoreCase = true) || current == pending)) {
            prefs.edit()
                .putString(peerFingerprint(peerName), pending)
                .putBoolean(fingerprintMismatch(peerName), false)
                .remove(pendingPeerFingerprint(peerName))
                .remove(pendingPeerEndpoint(peerName))
                .apply()
            return false
        }
        return shouldBlockPeerTraffic(
            mismatch = prefs.getBoolean(fingerprintMismatch(peerName), false),
            pendingFingerprint = pending.ifBlank { null },
        )
    }

    /**
     * Pins the first unexpected identity until the user makes an explicit choice.
     * A later connection cannot silently replace the fingerprint shown in the warning.
     */
    fun recordPendingPeerIdentity(
        context: Context,
        peerName: String,
        fingerprint: String,
        endpoint: String,
    ) {
        if (peerName.isBlank() || fingerprint.isBlank()) return
        val prefs = prefs(context)
        val pendingKey = pendingPeerFingerprint(peerName)
        val existingPending = prefs.getString(pendingKey, null)
        val editor = prefs.edit().putBoolean(fingerprintMismatch(peerName), true)
        if (existingPending.isNullOrBlank()) {
            editor.putString(pendingKey, fingerprint)
        }
        if (endpoint.isNotBlank() && (existingPending.isNullOrBlank() || existingPending == fingerprint)) {
            editor.putString(pendingPeerEndpoint(peerName), endpoint)
        }
        // The session callback is synchronous. Persist the security boundary before
        // the core can process any application frames or another sender can race it.
        // commit() is intentional here: we must ensure the block is written before returning.
        editor.commit()
    }

    internal fun acceptPendingPeerIdentity(context: Context, peerName: String): AcceptedPeerIdentity? {
        val prefs = prefs(context)
        val current = prefs.getString(peerFingerprint(peerName), null).orEmpty()
        val pending = prefs.getString(pendingPeerFingerprint(peerName), null).orEmpty()
        if (pending.isBlank() && current.isBlank()) return null
        val targetFingerprint = if (pending.isNotBlank()) pending else current
        if (!canAcceptPendingPeerFingerprint(current, targetFingerprint) && current != targetFingerprint) return null
        val endpoint = prefs.getString(pendingPeerEndpoint(peerName), null).orEmpty()
        val editor = prefs.edit()
            .putString(peerFingerprint(peerName), targetFingerprint)
            .putBoolean(fingerprintMismatch(peerName), false)
            .putBoolean(verifiedPeer(peerName), false)
            .remove(pendingPeerFingerprint(peerName))
            .remove(pendingPeerEndpoint(peerName))
        if (endpoint.isNotBlank()) editor.putString(lastEndpoint(peerName), endpoint)
        if (!editor.commit()) return null
        return AcceptedPeerIdentity(current, targetFingerprint, endpoint)
    }

    fun saveDraft(context: Context, chatId: String, text: String) {
        if (chatId.isBlank()) return
        val trimmed = text.trim()
        val prefs = prefs(context)
        if (trimmed.isEmpty()) {
            prefs.edit().remove("draft_$chatId").apply()
        } else {
            val encrypted = SecureStorage.encrypt(text)
            prefs.edit().putString("draft_$chatId", encrypted).apply()
        }
    }

    fun getDraft(context: Context, chatId: String): String? {
        if (chatId.isBlank()) return null
        val stored = prefs(context).getString("draft_$chatId", null) ?: return null
        return SecureStorage.decrypt(stored) ?: stored
    }

    fun rejectPendingPeerIdentity(context: Context, peerName: String): Boolean {
        prefs(context).edit()
            .putBoolean(fingerprintMismatch(peerName), false)
            .remove(pendingPeerFingerprint(peerName))
            .remove(pendingPeerEndpoint(peerName))
            .apply()
        return true
    }
}

internal data class AcceptedPeerIdentity(
    val previousFingerprint: String,
    val acceptedFingerprint: String,
    val endpoint: String,
)

internal fun shouldBlockPeerTraffic(mismatch: Boolean, pendingFingerprint: String?): Boolean =
    mismatch || !pendingFingerprint.isNullOrBlank()

internal fun canAcceptPendingPeerFingerprint(current: String?, pending: String?): Boolean =
    !current.isNullOrBlank() && !pending.isNullOrBlank() && current != pending
