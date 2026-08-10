package com.example.twopchat

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** LAN discovery is an optimisation only; authenticated fingerprints remain authoritative. */
internal class LocalPeerDiscovery(
    context: Context,
    private val onPeerResolved: (name: String, fingerprint: String, endpoint: String) -> Unit,
) {
    private val manager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var localFingerprint = ""

    @Synchronized
    fun start(name: String, fingerprint: String, port: Int, hiddenMode: Boolean = false) {
        stop()
        if (fingerprint.isBlank()) return
        localFingerprint = fingerprint
        if (!hiddenMode && name.isNotBlank()) {
            val safeFingerprint = fingerprint.take(16)
            val service = NsdServiceInfo().apply {
                serviceName = "2PChat-$safeFingerprint"
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute(ATTRIBUTE_NAME, name.take(32))
                setAttribute(ATTRIBUTE_FINGERPRINT, fingerprint)
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD unregistration failed: $errorCode")
                }
            }.also { manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, it) }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE ||
                    !resolving.add(serviceInfo.serviceName)
                ) return
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving.remove(serviceInfo.serviceName)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving.remove(serviceInfo.serviceName)
                        val fingerprint = (serviceInfo.attributes[ATTRIBUTE_FINGERPRINT]
                            ?: serviceInfo.attributes["fingerprint"])
                            ?.toString(Charsets.UTF_8).orEmpty()
                        if (fingerprint.isBlank() || fingerprint == localFingerprint) return
                        val peerName = serviceInfo.attributes[ATTRIBUTE_NAME]
                            ?.toString(Charsets.UTF_8).orEmpty()
                        val host = serviceInfo.host?.hostAddress?.substringBefore('%').orEmpty()
                        if (peerName.isBlank() || host.isBlank() || serviceInfo.port !in 1..65535) return
                        val endpoint = if (host.contains(':')) "[$host]:${serviceInfo.port}" else "$host:${serviceInfo.port}"
                        onPeerResolved(peerName, fingerprint, endpoint)
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery stop failed: $errorCode")
            }
        }.also { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, it) }
    }

    @Synchronized
    fun onScreenStateChanged(isScreenOn: Boolean) {
        if (!isScreenOn) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        discoveryListener?.let {
            try { manager.stopServiceDiscovery(it) } catch (_: Exception) { }
        }
        registrationListener?.let {
            try { manager.unregisterService(it) } catch (_: Exception) { }
        }
        discoveryListener = null
        registrationListener = null
        resolving.clear()
    }

    private companion object {
        const val TAG = "LocalPeerDiscovery"
        const val SERVICE_TYPE = "_2pchat._tcp."
        const val ATTRIBUTE_NAME = "name"
        const val ATTRIBUTE_FINGERPRINT = "fp"
    }
}
