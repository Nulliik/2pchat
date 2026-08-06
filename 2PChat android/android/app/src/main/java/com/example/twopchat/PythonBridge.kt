package com.example.twopchat

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject

private fun numericIpv4Octets(endpointOrHost: String): List<Int>? {
    val host = endpointOrHost.substringBeforeLast(':', endpointOrHost).trim('[', ']')
    val octets = host.split('.').mapNotNull(String::toIntOrNull)
    return octets.takeIf { parts -> parts.size == 4 && parts.all { it in 0..255 } }
}

private fun isLocalIpv4(endpoint: String): Boolean {
    val octets = numericIpv4Octets(endpoint) ?: return false
    return octets[0] == 10 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 169 && octets[1] == 254) ||
        octets[0] == 127
}

private fun isUsableExternalIpv4(value: String): Boolean {
    val octets = numericIpv4Octets(value) ?: return false
    return !isLocalIpv4(value) && octets[0] != 0 && octets[0] < 224
}

/** Stable route order for QR probes: LAN IPv4, public IPv4, then IPv6. */
internal fun orderedDirectEndpoints(endpoints: List<String>): List<String> =
    endpoints.distinct().sortedBy { endpoint ->
        when {
            isLocalIpv4(endpoint) -> 0
            numericIpv4Octets(endpoint) != null -> 1
            endpoint.substringBeforeLast(':', endpoint).trim('[', ']').contains(':') -> 2
            else -> 3
        }
    }.take(12)

internal fun selectExternalIpv4(localIpv4: String, observedAddresses: List<String>): String =
    observedAddresses.firstOrNull { candidate ->
        candidate != localIpv4 && isUsableExternalIpv4(candidate)
    }.orEmpty()

object PythonBridge {
    @Volatile
    private var cachedDiscoveryBridge: PyObject? = null
    @Volatile
    private var cachedIdentityModule: PyObject? = null

    private fun getDiscoveryBridgeModule(): PyObject? {
        if (!isInitialized) return null
        return cachedDiscoveryBridge ?: synchronized(this) {
            cachedDiscoveryBridge ?: runCatching {
                Python.getInstance().getModule("discovery_bridge").also { cachedDiscoveryBridge = it }
            }.getOrNull()
        }
    }

    private fun getIdentityModule(): PyObject? {
        if (!isInitialized) return null
        return cachedIdentityModule ?: synchronized(this) {
            cachedIdentityModule ?: runCatching {
                Python.getInstance().getModule("messenger.core.identity").also { cachedIdentityModule = it }
            }.getOrNull()
        }
    }

    fun ensurePythonStarted(context: Context) {
        if (!Python.isStarted()) {
            synchronized(this) {
                if (!Python.isStarted()) {
                    Python.start(com.chaquo.python.android.AndroidPlatform(context.applicationContext))
                }
            }
        }
    }

    private const val MIN_ANNOUNCE_INTERVAL_MS = 60_000L
    private val announceLock = Any()
    private val lastAnnounceAt = mutableMapOf<String, Long>()
    private val lastAnnounceResult = mutableMapOf<String, Boolean>()
    private val announcesInFlight = mutableSetOf<String>()
    private const val TAG = "PythonBridge"
    private var appContext: Context? = null
    @Volatile
    var isInitialized = false
        private set

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                appContext = context.applicationContext
            
            // Clear runtime state to ensure we start clean and don't announce stale IPs from previous crashes.
            val sharedPrefs = P2PPreferences.prefs(context)
            sharedPrefs.edit()
                .putString("yggdrasil_runtime_ip", "")
                .putString("yggdrasil_runtime_state", "Disabled")
                .putInt("yggdrasil_runtime_peers", 0)
                .putInt("yggdrasil_runtime_routes", 0)
                .putInt("yggdrasil_runtime_tree_nodes", 0)
                .apply()

            val py = Python.getInstance()
            
            // Set config dir to appDir/config (matching Kotlin's expected path for logs and settings)
            val appDir = context.filesDir.absolutePath
            val configDir = java.io.File(appDir, "config")
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            val bootstrap = py.getModule("bootstrap")
            bootstrap.callAttr("set_config_dir", configDir.absolutePath)
            
            // Try loading core modules to verify (they will now read the environment variable correctly during import)
            val identity = py.getModule("messenger.core.identity").also { cachedIdentityModule = it }
            val discoveryBridge = py.getModule("discovery_bridge").also { cachedDiscoveryBridge = it }
            check(
                discoveryBridge.callAttr(
                    "configure_trackers",
                    TrackerPreferences.configJson(context),
                ).toBoolean()
            ) { "Stored tracker configuration was rejected" }
            Log.i(TAG, "Python core modules loaded successfully. Config dir: ${configDir.absolutePath}")
                isInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Python core", e)
            }
        }
    }

    /**
     * Diagnostic tool to write all available network interfaces and addresses to the app.log file.
     */
    fun logAllNetworkInterfaces(context: Context) {
        try {
            val logDir = java.io.File(context.filesDir, "config")
            if (!logDir.exists()) logDir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", java.util.Locale.getDefault()).format(java.util.Date())
            val sb = java.lang.StringBuilder()
            sb.append("$timestamp [KOTLIN_INFO] Network Interfaces Summary:\n")
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                sb.append("  Interface: ${networkInterface.name} (Up: ${networkInterface.isUp}, Loopback: ${networkInterface.isLoopback})\n")
            }
            AppLog.append(context, sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning network interfaces", e)
        }
    }

    fun getLocalFingerprint(): String {
        if (!isInitialized) return "Not Initialized"
        return try {
            val identity = getIdentityModule() ?: return "Error"
            // Call load_or_create_identity()
            val privKey = identity.callAttr("load_or_create_identity")
            val fp = identity.callAttr("fingerprint", privKey.get("public_key"))
            fp.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local fingerprint", e)
            "Error"
        }
    }

    fun searchPeers(
        query: String,
        expectedLiveName: String = query,
        expectedFingerprint: String? = null,
        sharedCode: String = query,
    ): List<Map<String, Any>> {
        // Search can be opened immediately after process recreation, before the
        // activity's normal bootstrap has finished.  Treat that as a recoverable
        // startup race instead of reporting a misleading empty search result.
        if (!isInitialized) {
            val context = appContext ?: return emptyList()
            ensurePythonStarted(context)
            init(context)
        }
        if (!isInitialized) return emptyList()
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return emptyList()
            if (!applyTrackerConfiguration(bridge)) return emptyList()
            val directEndpoints = orderedDirectEndpoints(
                P2PMessageRelay.localDiscoveryEndpoints(expectedLiveName) +
                    connectedYggdrasilPeerEndpoints()
            )
            val directResults = if (directEndpoints.isNotEmpty()) {
                bridge.callAttr(
                    "verify_live_endpoints",
                    JSONArray(directEndpoints).toString(),
                    expectedLiveName,
                    expectedFingerprint,
                )
            } else {
                null
            }
            val pyResults = if (directResults != null && directResults.asList().isNotEmpty()) {
                Log.i(TAG, "Resolved requested peer through a direct discovery candidate")
                directResults
            } else {
                bridge.callAttr(
                    "resolve_peers", query, sharedCode, "Yemekyedim HTTPS",
                    expectedLiveName, expectedFingerprint
                )
            }
            val results = mutableListOf<Map<String, Any>>()
            val list = pyResults.asList()
            for (item in list) {
                val map = item.asMap()
                val kotlinMap = mutableMapOf<String, Any>()
                for (entry in map.entries) {
                    val key = entry.key.toString()
                    if (key == "endpoints") {
                        val endpointsList = mutableListOf<String>()
                        for (ep in entry.value.asList()) {
                            endpointsList.add(ep.toString())
                        }
                        kotlinMap[key] = endpointsList
                    } else {
                        kotlinMap[key] = entry.value.toString()
                    }
                }
                results.add(kotlinMap)
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving peers", e)
            emptyList()
        }
    }

    /** Stable, human-shareable discovery code. It is never the user's fingerprint. */
    @Synchronized
    fun getOrCreateDiscoveryCode(): String {
        val context = appContext ?: return ""
        val prefs = P2PPreferences.prefs(context)
        prefs.getString("discovery_code_v1", null)?.takeIf { it.isNotBlank() }?.let { return it }

        val alphabet = "23456789bcdfghjkmnpqrstvwxyz"
        val random = java.security.SecureRandom()
        val code = (1..3).joinToString("-") {
            (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
        prefs.edit().putString("discovery_code_v1", code).apply()
        return code
    }

    /**
     * Returns a list of all active non-loopback IP addresses (both IPv4 and IPv6/Yggdrasil).
     * This includes VPN interfaces (e.g. tun0, 10.10.14.x) so peers can connect via VPN tunnels.
     */
    fun getLocalAddresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress) continue

                    val host = address.hostAddress ?: continue
                    val cleanHost = if (host.contains('%')) host.substringBefore('%') else host

                    if (address is java.net.Inet6Address) {
                        // Skip link-local, site-local, and multicast IPv6 addresses
                        if (!address.isLinkLocalAddress && 
                            !address.isSiteLocalAddress && 
                            !address.isMulticastAddress) {
                            result.add(cleanHost)
                        }
                    } else if (host.indexOf(':') < 0) {
                        // Keep all non-loopback IPv4 addresses (Wi-Fi, Mobile Data, VPNs)
                        result.add(cleanHost)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP addresses", e)
        }
        try {
            val context = appContext
            if (context != null) {
                val sharedPrefs = P2PPreferences.prefs(context)
                val yggIp = sharedPrefs.getString("yggdrasil_runtime_ip", "")?.trim().orEmpty()
                if (yggIp.isNotEmpty()) {
                    result.add(yggIp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Yggdrasil runtime IP from prefs", e)
        }
        return result.distinct()
    }

    fun setIpv4Enabled(enabled: Boolean) {
        if (!isInitialized) return
        try {
            Python.getInstance().getModule("discovery_bridge")
                .callAttr("set_ipv4_enabled", enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying IPv4 transport setting", e)
        }
    }

    /**
     * Returns the first non-loopback IPv4 address, or Yggdrasil IPv6 address if requested.
     */
    fun getLocalIpAddress(preferYggdrasil: Boolean = false): String {
        val addrs = getLocalAddresses()
        if (preferYggdrasil) {
            return addrs.firstOrNull { it.contains(':') } ?: "127.0.0.1"
        }
        return addrs.firstOrNull { !it.contains(':') } ?: "127.0.0.1"
    }

    /**
     * Return the address reported by the embedded Yggdrasil node.
     *
     * Do not infer this from network interfaces: Android may expose ordinary
     * IPv6 addresses from Wi-Fi, mobile data, or another VPN there.
     */
    fun getYggdrasilAddress(): String = try {
        val context = appContext ?: return ""
        P2PPreferences.prefs(context)
            .getString("yggdrasil_runtime_ip", "")
            ?.trim()
            .orEmpty()
    } catch (e: Exception) {
        Log.e(TAG, "Error getting Yggdrasil runtime IP from prefs", e)
        ""
    }

    /** Snapshot reported by the embedded node, not inferred from ordinary IPv6. */
    fun getYggdrasilNetworkDiagnostics(): Map<String, String> = try {
        val context = appContext ?: return emptyMap()
        val prefs = P2PPreferences.prefs(context)
        linkedMapOf(
            "state" to prefs.getString("yggdrasil_runtime_state", "disabled").orEmpty(),
            "peers" to prefs.getInt("yggdrasil_runtime_peers", 0).toString(),
            "routes" to prefs.getInt("yggdrasil_runtime_routes", 0).toString(),
            "tree_nodes" to prefs.getInt("yggdrasil_runtime_tree_nodes", 0).toString(),
        )
    } catch (e: Exception) {
        Log.e(TAG, "Error reading Yggdrasil diagnostics", e)
        emptyMap()
    }

    fun announceSelf(
        nickname: String,
        fingerprint: String,
        port: Int,
        force: Boolean = false,
        rendezvousCode: String? = null,
    ): Boolean {
        if (!isInitialized) return false
        val canonicalName = validatedSearchNickname(nickname) ?: return false
        val context = appContext ?: return false
        val trackerConfig = TrackerPreferences.configJson(context)
        val discoveryCode = rendezvousCode?.trim()?.takeIf { it.isNotEmpty() }
            ?: getOrCreateDiscoveryCode()
        val prefs = appContext?.let(P2PPreferences::prefs)
        val ipv4Enabled = prefs
                ?.getBoolean("settings_ipv4", true) ?: true
        val ipv4Addresses = if (ipv4Enabled) {
            getLocalAddresses().filter { !it.contains(':') }
        } else {
            emptyList()
        }
        val yggEnabled = prefs?.getBoolean("settings_yggdrasil", true) ?: false
        val yggState = prefs?.getString("yggdrasil_runtime_state", "disabled").orEmpty()
        val yggRoutes = prefs?.getInt("yggdrasil_runtime_routes", 0) ?: 0
        // An address exists before the overlay is actually routable. Do not
        // publish it until the node reports a connected state and routes.
        val yggdrasilAddress = if (
            yggEnabled &&
            yggState.equals("CONNECTED", ignoreCase = true) &&
            yggRoutes > 0
        ) {
            getYggdrasilAddress()
        } else {
            ""
        }
        val addresses = buildList {
            addAll(ipv4Addresses)
            if (yggdrasilAddress.isNotEmpty()) add(yggdrasilAddress)
        }.distinct().sorted()
        // Endpoint changes are meaningful announces. In particular, do not
        // let an early IPv4/empty announce suppress a later Yggdrasil one.
        val endpointKey = addresses.joinToString(",")
        val announceKey = "$canonicalName\u0000$fingerprint\u0000$discoveryCode\u0000$port\u0000$endpointKey\u0000$trackerConfig"
        synchronized(announceLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            val lastAt = lastAnnounceAt[announceKey]
            if (announceKey in announcesInFlight || (!force && lastAt != null && now - lastAt < MIN_ANNOUNCE_INTERVAL_MS)) {
                Log.i(TAG, "Skipping duplicate tracker announce")
                return lastAnnounceResult[announceKey] ?: false
            }
            announcesInFlight.add(announceKey)
        }
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            if (!applyTrackerConfiguration(bridge, trackerConfig)) return false

            Log.i(
                TAG,
                "Announcing self on trackers. IPv4=$ipv4Addresses Yggdrasil=$yggdrasilAddress port=$port"
            )
            val endpointsJson = JSONArray(addresses).toString()
            val success = bridge.callAttr(
                "announce_peer_endpoints", canonicalName, fingerprint, endpointsJson, port, discoveryCode
            )
            success.toBoolean().also { result ->
                synchronized(announceLock) {
                    if (result) {
                        lastAnnounceAt[announceKey] = android.os.SystemClock.elapsedRealtime()
                        lastAnnounceResult[announceKey] = true
                    } else {
                        lastAnnounceAt.remove(announceKey)
                        lastAnnounceResult.remove(announceKey)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error announcing self", e)
            false
        } finally {
            synchronized(announceLock) {
                announcesInFlight.remove(announceKey)
            }
        }
    }

    fun getTrackerDiagnostics(): Map<String, String> {
        if (!isInitialized) return emptyMap()
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return emptyMap()
            if (!applyTrackerConfiguration(bridge)) return emptyMap()
            val rawJson = bridge.callAttr("get_tracker_diagnostics_json").toString()
            val json = JSONObject(rawJson)
            val result = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val trackerName = keys.next()
                val trackerJson = json.optJSONObject(trackerName) ?: continue
                val announce = trackerJson.optString("announce", "n/a")
                val resolve = trackerJson.optString("resolve", "n/a")
                val announceRtt = trackerJson.optString("announce_rtt_ms", "n/a")
                val resolveRtt = trackerJson.optString("resolve_rtt_ms", "n/a")
                result[trackerName] = "announce=$announce, resolve=$resolve, announce_rtt=${announceRtt}ms, resolve_rtt=${resolveRtt}ms"
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting tracker diagnostics", e)
            emptyMap()
        }
    }

    /** Applies encrypted user tracker preferences to the embedded discovery runtime. */
    fun applyTrackerConfiguration(): Boolean {
        if (!isInitialized) return false
        return try {
            val bridge = Python.getInstance().getModule("discovery_bridge")
            applyTrackerConfiguration(bridge).also { applied ->
                if (applied) clearAnnounceCache()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying tracker configuration", e)
            false
        }
    }

    private fun applyTrackerConfiguration(
        bridge: com.chaquo.python.PyObject,
        configJson: String? = null,
    ): Boolean {
        val context = appContext ?: return false
        val payload = configJson ?: TrackerPreferences.configJson(context)
        return bridge.callAttr("configure_trackers", payload).toBoolean()
    }

    private fun clearAnnounceCache() {
        synchronized(announceLock) {
            lastAnnounceAt.clear()
            lastAnnounceResult.clear()
        }
    }

    fun getObservedPublicAddresses(): List<String> {
        if (!isInitialized) return emptyList()
        return try {
            val raw = Python.getInstance().getModule("discovery_bridge")
                .callAttr("get_public_addresses_json").toString()
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading observed public addresses", e)
            emptyList()
        }
    }

    fun discoverPublicIpv4Address(): String {
        if (!isInitialized) return ""
        return try {
            Python.getInstance().getModule("discovery_bridge")
                .callAttr("discover_public_ipv4").toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering public IPv4 address", e)
            ""
        }
    }

    interface PyMessageListener {
        fun onMessageReceived(sender: String, text: String)
    }

    interface PySessionListener {
        fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String, transport: String, aboutMe: String): Boolean
        fun onSessionClosed(peerName: String, fingerprint: String)
    }

    fun configureLocalIdentity(nickname: String, fingerprint: String, aboutMe: String = ""): Boolean {
        val canonicalName = validatedSearchNickname(nickname) ?: return false
        if (!isInitialized || fingerprint.isBlank()) return false
        return try {
            Python.getInstance().getModule("discovery_bridge")
                .callAttr("configure_local_identity", canonicalName, fingerprint, aboutMe)
                .toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring local P2P identity", e)
            false
        }
    }

    fun getLocalSigningPublicKey(): String {
        if (!isInitialized) return ""
        return try {
            Python.getInstance().getModule("discovery_bridge")
                .callAttr("get_local_signing_public_key")
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading local group signing public key", e)
            ""
        }
    }

    fun signGroupPayload(canonicalPayload: String): String {
        if (!isInitialized || canonicalPayload.isBlank()) return ""
        return try {
            Python.getInstance().getModule("discovery_bridge")
                .callAttr("sign_group_payload", canonicalPayload)
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error signing group payload", e)
            ""
        }
    }

    fun verifyGroupPayload(
        verificationKeyBase64: String,
        canonicalPayload: String,
        signatureBase64: String,
    ): Boolean {
        if (!isInitialized ||
            verificationKeyBase64.isBlank() ||
            canonicalPayload.isBlank() ||
            signatureBase64.isBlank()
        ) {
            return false
        }
        return try {
            Python.getInstance().getModule("discovery_bridge")
                .callAttr(
                    "verify_group_payload",
                    verificationKeyBase64,
                    canonicalPayload,
                    signatureBase64,
                )
                .toBoolean()
        } catch (e: Exception) {
            Log.w(TAG, "Rejected invalid group signature", e)
            false
        }
    }

    fun shutdownAllSessions(timeoutSeconds: Double = 5.0): Boolean {
        if (!isInitialized) return true
        return try {
            Python.getInstance().getModule("discovery_bridge")
                .callAttr("shutdown_all_sessions", timeoutSeconds)
                .toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down P2P runtime", e)
            false
        }
    }

    fun clearRejectedFingerprint(peerName: String, fingerprint: String = "") {
        if (!isInitialized) return
        try {
            Python.getInstance().getModule("discovery_bridge")
                .callAttr("clear_rejected_fingerprint", peerName, fingerprint)
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing rejected fingerprint", e)
        }
    }

    fun clearAccountCaches() {
        synchronized(announceLock) {
            lastAnnounceAt.clear()
            lastAnnounceResult.clear()
            announcesInFlight.clear()
        }
    }

    fun registerMessageListener(listener: PyMessageListener) {
        if (!isInitialized) return
        try {
            val bridge = getDiscoveryBridgeModule() ?: return
            bridge.callAttr("register_message_listener", listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering message listener", e)
        }
    }

    fun registerSessionListener(listener: PySessionListener) {
        if (!isInitialized) return
        try {
            val bridge = getDiscoveryBridgeModule() ?: return
            bridge.callAttr("register_session_listener", listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering session listener", e)
        }
    }

    fun startP2pListener(port: Int = 50001, enableUpnp: Boolean = false) {
        if (!isInitialized) return
        try {
            val bridge = getDiscoveryBridgeModule() ?: return
            bridge.callAttr("start_p2p_listener", port, enableUpnp)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting P2P listener", e)
        }
    }

    fun isPeerOnline(peerName: String, expectedFingerprint: String? = null): Boolean {
        if (!isInitialized) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            val isOnline = bridge.callAttr("is_peer_online", peerName, expectedFingerprint)
            isOnline.toBoolean()
        } catch (_: Exception) {
            false
        }
    }

    fun sendP2pMessage(peerName: String, endpoint: String, text: String, expectedFingerprint: String? = null): Boolean {
        if (!isInitialized) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            val success = bridge.callAttr("send_p2p_message", peerName, endpoint, text, expectedFingerprint)
            success.toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending P2P message via Python", e)
            false
        }
    }

    fun sendP2pFile(
        peerName: String,
        endpoint: String,
        filePath: String,
        expectedFingerprint: String? = null,
        messageId: String = "",
        caption: String = "",
        previewBase64: String = "",
        albumId: String = "",
        albumIndex: Int = -1,
        albumCount: Int = 0,
    ): Boolean {
        if (!isInitialized) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            val success = bridge.callAttr(
                "send_p2p_file",
                peerName,
                endpoint,
                filePath,
                expectedFingerprint,
                messageId,
                caption,
                previewBase64,
                albumId,
                albumIndex,
                albumCount,
            )
            success.toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending P2P file via Python", e)
            false
        }
    }

    fun cancelP2pFile(peerName: String, messageId: String, expectedFingerprint: String? = null): Boolean {
        if (!isInitialized || messageId.isBlank()) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            bridge.callAttr("cancel_p2p_file", peerName, messageId, expectedFingerprint).toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling P2P file via Python", e)
            false
        }
    }

    fun getActivePeers(): List<String> {
        if (!isInitialized) return emptyList()
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return emptyList()
            val peersStr = bridge.callAttr("get_active_peers_list").toString()
            if (peersStr.isEmpty()) emptyList() else peersStr.split(",")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active peers", e)
            emptyList()
        }
    }

    private fun connectedYggdrasilPeerEndpoints(): List<String> {
        val context = appContext ?: return emptyList()
        val prefs = P2PPreferences.prefs(context)
        if (!prefs.getBoolean("settings_yggdrasil", true) ||
            (!prefs.getString("yggdrasil_runtime_state", "disabled").equals("CONNECTED", ignoreCase = true) &&
             !prefs.getString("yggdrasil_runtime_state", "disabled").equals("ENABLED", ignoreCase = true))
        ) {
            return emptyList()
        }
        val ownIp = prefs.getString("yggdrasil_runtime_ip", "").orEmpty()
        val peersJson = prefs.getString("yggdrasil_runtime_peers_json", "").orEmpty()
        if (peersJson.isBlank()) return emptyList()
        return try {
            val peers = JSONArray(peersJson)
            buildList {
                for (index in 0 until peers.length()) {
                    val peer = peers.optJSONObject(index) ?: continue
                    if (!peer.optBoolean("Up", false)) continue
                    val ip = peer.optString("IP").substringBefore('%').trim()
                    if (ip.isBlank() || ip == ownIp || !ip.contains(':')) continue
                    // Direct-neighbour diagnostics do not expose the remote app port;
                    // use the legacy default only as a probe. Tracker/NSD results carry
                    // the peer's actual configurable port and are preferred.
                    add("[$ip]:${P2PPreferences.DEFAULT_LISTENER_PORT}")
                }
            }.distinct().take(12)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed Yggdrasil peer diagnostics", e)
            emptyList()
        }
    }

    fun getActivePeerFingerprints(): List<String> {
        if (!isInitialized) return emptyList()
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return emptyList()
            val fingerprints = bridge.callAttr("probe_active_peer_fingerprints_list").toString()
            if (fingerprints.isEmpty()) emptyList() else fingerprints.split(",")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active peer fingerprints", e)
            emptyList()
        }
    }

    fun rememberPeerName(fingerprint: String, peerName: String): Boolean {
        if (!isInitialized || fingerprint.isBlank() || peerName.isBlank()) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            bridge.callAttr("remember_peer_name", fingerprint, peerName).toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error remembering authenticated peer name", e)
            false
        }
    }

    fun closePeerSession(peerName: String, expectedFingerprint: String? = null): Boolean {
        if (!isInitialized) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            val success = bridge.callAttr("close_peer_session", peerName, expectedFingerprint)
            success.toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing P2P session via Python", e)
            false
        }
    }

    fun isLoopRunning(): Boolean {
        if (!isInitialized) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            val loopObj = bridge.get("loop")
            loopObj != null && loopObj.toString() != "None"
        } catch (_: Exception) {
            false
        }
    }

    fun reconnectPeerSession(peerName: String, endpoint: String, expectedFingerprint: String? = null): Boolean {
        if (!isInitialized || !isLoopRunning()) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            val success = bridge.callAttr("reconnect_peer_session", peerName, endpoint, expectedFingerprint)
            success.toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error reconnecting peer session via Python", e)
            false
        }
    }

    fun isUpnpMapped(): Boolean {
        if (!isInitialized) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            bridge.callAttr("is_upnp_mapped").toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking UPnP mapping status", e)
            false
        }
    }

    fun getUpnpDetails(): Map<String, String> {
        if (!isInitialized) return emptyMap()
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return emptyMap()
            val jsonStr = bridge.callAttr("get_upnp_details_json").toString()
            val json = JSONObject(jsonStr)
            val result = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = json.getString(key)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting UPnP details", e)
            emptyMap()
        }
    }

    fun triggerUpnpReopen(): Boolean {
        if (!isInitialized) return false
        return try {
            val bridge = getDiscoveryBridgeModule() ?: return false
            bridge.callAttr("trigger_upnp_reopen").toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering UPnP reopen", e)
            false
        }
    }
}

