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
            
            // Set config dir to private app storage dir via bootstrap
            val appDir = context.filesDir.absolutePath
            val bootstrap = py.getModule("bootstrap")
            bootstrap.callAttr("set_config_dir", appDir)
            
            // Try loading core modules to verify (they will now read the environment variable correctly during import)
            val identity = py.getModule("messenger.core.identity")
            Log.i(TAG, "Python core modules loaded successfully. Config dir: $appDir")
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Python core", e)
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
        if (!isInitialized) return false
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

    interface PyMessageListener {
        fun onMessageReceived(sender: String, text: String)
    }

    interface PySessionListener {
        fun onSessionEstablished(peerName: String, fingerprint: String)
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
}
