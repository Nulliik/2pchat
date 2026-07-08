package com.example.twopchat

import android.content.Context
import android.util.Log
import com.chaquo.python.Python

object PythonBridge {
    private const val TAG = "PythonBridge"
    var isInitialized = false
        private set

    fun init(context: Context) {
        if (isInitialized) return
        try {
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
            val identity = py.getModule("messenger.core.identity")
            Log.i(TAG, "Python core modules loaded successfully. Config dir: ${configDir.absolutePath}")
            isInitialized = true
            
            // Log network interfaces immediately to app.log for diagnostics
            logAllNetworkInterfaces(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python core", e)
        }
    }

    /**
     * Diagnostic tool to write all available network interfaces and addresses to the app.log file.
     */
    fun logAllNetworkInterfaces(context: Context) {
        try {
            val logDir = java.io.File(context.filesDir, "config")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = java.io.File(logDir, "app.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS", java.util.Locale.getDefault()).format(java.util.Date())
            val sb = java.lang.StringBuilder()
            sb.append("$timestamp [KOTLIN_INFO] Network Interfaces Diagnostics:\n")
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                sb.append("  Interface: ${networkInterface.name} (Up: ${networkInterface.isUp}, Loopback: ${networkInterface.isLoopback})\n")
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    val rawBytesHex = address.address.joinToString("") { "%02x".format(it) }
                    sb.append("    Address: ${address.hostAddress} (Type: ${address.javaClass.simpleName}, Loopback: ${address.isLoopbackAddress}, RawBytes: $rawBytesHex)\n")
                }
            }
            logFile.appendText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning network interfaces", e)
        }
    }

    fun getLocalFingerprint(): String {
        if (!isInitialized) return "Not Initialized"
        return try {
            val py = Python.getInstance()
            val identity = py.getModule("messenger.core.identity")
            // Call load_or_create_identity()
            val privKey = identity.callAttr("load_or_create_identity")
            val fp = identity.callAttr("fingerprint", privKey.get("public_key"))
            fp.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local fingerprint", e)
            "Error"
        }
    }

    fun searchPeers(query: String): List<Map<String, Any>> {
        if (!isInitialized) return emptyList()
        return try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            val pyResults = bridge.callAttr("resolve_peers", query, query)
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
        return result.distinct()
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

    /** Returns the Yggdrasil/global IPv6 address if present, else empty string. */
    fun getYggdrasilAddress(): String = getLocalAddresses().firstOrNull { it.contains(':') } ?: ""

    fun announceSelf(nickname: String, fingerprint: String, port: Int): Boolean {
        if (!isInitialized) return false
        return try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")

            val addresses = getLocalAddresses()
            Log.i(TAG, "Announcing self on tracker for endpoints: $addresses")
            for (addr in addresses) {
                if (addr.contains(':')) {
                    Log.i(TAG, "Announcing IPv6/Yggdrasil endpoint: $addr")
                    bridge.callAttr("announce_peer_ygg", nickname, fingerprint, addr, port)
                } else {
                    Log.i(TAG, "Announcing IPv4 endpoint: $addr")
                    bridge.callAttr("announce_peer", nickname, fingerprint, addr, port)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error announcing self", e)
            false
        }
    }

    interface PyMessageListener {
        fun onMessageReceived(sender: String, text: String)
    }

    interface PySessionListener {
        fun onSessionEstablished(peerName: String, fingerprint: String, endpoint: String)
        fun onSessionClosed(peerName: String)
    }

    fun registerMessageListener(listener: PyMessageListener) {
        if (!isInitialized) return
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            bridge.callAttr("register_message_listener", listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering message listener", e)
        }
    }

    fun registerSessionListener(listener: PySessionListener) {
        if (!isInitialized) return
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            bridge.callAttr("register_session_listener", listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering session listener", e)
        }
    }

    fun startP2pListener(port: Int = 50001) {
        if (!isInitialized) return
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            bridge.callAttr("start_p2p_listener", port)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting P2P listener", e)
        }
    }

    fun sendP2pMessage(peerName: String, endpoint: String, text: String): Boolean {
        if (!isInitialized) return false
        return try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            val success = bridge.callAttr("send_p2p_message", peerName, endpoint, text)
            success.toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending P2P message via Python", e)
            false
        }
    }

    fun sendP2pFile(peerName: String, endpoint: String, filePath: String): Boolean {
        if (!isInitialized) return false
        return try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            val success = bridge.callAttr("send_p2p_file", peerName, endpoint, filePath)
            success.toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending P2P file via Python", e)
            false
        }
    }

    fun getActivePeers(): List<String> {
        if (!isInitialized) return emptyList()
        return try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            val peersStr = bridge.callAttr("get_active_peers_list").toString()
            if (peersStr.isEmpty()) emptyList() else peersStr.split(",")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active peers", e)
            emptyList()
        }
    }
}
