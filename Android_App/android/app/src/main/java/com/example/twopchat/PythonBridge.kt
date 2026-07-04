package com.example.twopchat

import android.content.Context
import android.util.Log
import com.chaquo.python.Python

object PythonBridge {
    private const val TAG = "PythonBridge"
    private const val DEFAULT_VERBOSE_LOGGING = false
    private var initialized = false

    data class PySendResult(
        val ok: Boolean,
        val error: String?,
    )

    fun init(context: Context) {
        if (initialized) return
        try {
            val py = Python.getInstance()
            
            // Set config dir to private app storage dir
            val appDir = context.filesDir.absolutePath
            val os = py.getModule("os")
            os.get("environ")?.put("P2PCHAT_CONFIG_DIR", appDir)
            val bridge = py.getModule("discovery_bridge")
            bridge.callAttr("configure_logging", DEFAULT_VERBOSE_LOGGING)
            
            // Try loading core modules to verify
            val identity = py.getModule("messenger.core.identity")
            Log.i(TAG, "Python core modules loaded successfully. Config dir: $appDir")
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python core", e)
        }
    }

    fun getLocalFingerprint(): String {
        if (!initialized) return "Not Initialized"
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
        if (!initialized) return emptyList()
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

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress) {
                        val host = address.hostAddress ?: ""
                        if (host.indexOf(':') < 0) { // Check if IPv4
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
        }
        return "127.0.0.1"
    }

    fun announceSelf(nickname: String, fingerprint: String, port: Int): Boolean {
        if (!initialized) return false
        return try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            val localIp = getLocalIpAddress()
            val success = bridge.callAttr("announce_peer", nickname, fingerprint, localIp, port)
            success.toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Error announcing self", e)
            false
        }
    }

    fun setVerboseLogging(enabled: Boolean): Boolean {
        if (!initialized) return false
        return try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            val result = bridge.callAttr("configure_logging", enabled).toBoolean()
            Log.i(TAG, "Python verbose logging " + if (result) "enabled" else "disabled")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring Python verbose logging", e)
            false
        }
    }

    interface PyMessageListener {
        fun onMessageReceived(sender: String, text: String)
    }

    interface PySessionListener {
        fun onSessionEstablished(peerName: String, fingerprint: String)
        fun onSessionClosed(peerName: String)
    }

    interface PyStatusListener {
        fun onStatus(text: String)
    }

    fun registerMessageListener(listener: PyMessageListener) {
        if (!initialized) return
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            bridge.callAttr("register_message_listener", listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering message listener", e)
        }
    }

    fun registerSessionListener(listener: PySessionListener) {
        if (!initialized) return
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            bridge.callAttr("register_session_listener", listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering session listener", e)
        }
    }

    fun registerStatusListener(listener: PyStatusListener) {
        if (!initialized) return
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            bridge.callAttr("register_status_listener", listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering status listener", e)
        }
    }

    fun startP2pListener(port: Int = 50001) {
        if (!initialized) return
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            bridge.callAttr("start_p2p_listener", port)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting P2P listener", e)
        }
    }

    fun sendP2pMessage(peerName: String, endpoint: String, text: String): Boolean {
        return sendP2pMessageDetailed(peerName, endpoint, text).ok
    }

    fun sendP2pMessageDetailed(peerName: String, endpoint: String, text: String): PySendResult {
        if (!initialized) return PySendResult(ok = false, error = "Python bridge not initialized")
        return try {
            val py = Python.getInstance()
            val bridge = py.getModule("discovery_bridge")
            val result = bridge.callAttr("send_p2p_message_detailed", peerName, endpoint, text)
            val map = result.asMap()
            var ok = false
            var error: String? = null
            for ((keyObj, valueObj) in map.entries) {
                when (keyObj.toString()) {
                    "ok" -> ok = valueObj.toBoolean()
                    "error" -> error = valueObj.toString().takeIf { it.isNotBlank() }
                }
            }
            PySendResult(ok = ok, error = error)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending P2P message via Python", e)
            PySendResult(ok = false, error = e.message ?: e.javaClass.simpleName)
        }
    }
}
