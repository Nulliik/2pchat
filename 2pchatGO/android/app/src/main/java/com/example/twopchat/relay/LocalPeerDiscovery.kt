package com.example.twopchat.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.twopchat.logging.SafeLog
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal object LocalDiscoveryToken {
    fun deriveToken(fingerprint: String, epochDay: Long = System.currentTimeMillis() / 86_400_000L): String {
        if (fingerprint.isBlank()) return ""
        val payload = "$fingerprint:2pchat-mdns-v1:$epochDay".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun matchesFingerprint(token: String, fingerprint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (token.isBlank() || fingerprint.isBlank()) return false
        if (token == fingerprint) return true // Backward compatibility with legacy raw fingerprints
        val currentDay = nowMs / 86_400_000L
        return deriveToken(fingerprint, currentDay) == token ||
               deriveToken(fingerprint, currentDay - 1) == token
    }
}

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
        if (!hiddenMode) {
            val token = LocalDiscoveryToken.deriveToken(fingerprint)
            val serviceToken = token.take(16)
            val service = NsdServiceInfo().apply {
                serviceName = "2PChat-$serviceToken"
                serviceType = SERVICE_TYPE
                setPort(port)
                // Privacy Invariant (§5): Plaintext nickname is omitted from mDNS TXT records.
                setAttribute(ATTRIBUTE_FINGERPRINT, token)
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    SafeLog.w(TAG, "NSD registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    SafeLog.w(TAG, "NSD unregistration failed: $errorCode")
                }
            }
            registrationListener = listener
            try {
                manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Throwable) {
                SafeLog.w(TAG, "Failed to register NSD service", e)
            }
        }

        val dListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE ||
                    !resolving.add(serviceInfo.serviceName)
                ) return
                try {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolving.remove(serviceInfo.serviceName)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            resolving.remove(serviceInfo.serviceName)
                            try {
                                val discoveryToken = (serviceInfo.attributes[ATTRIBUTE_FINGERPRINT]
                                    ?: serviceInfo.attributes["fingerprint"])
                                    ?.toString(Charsets.UTF_8).orEmpty()
                                if (discoveryToken.isBlank()) return
                                if (LocalDiscoveryToken.matchesFingerprint(discoveryToken, localFingerprint)) return
                                val host = serviceInfo.host?.hostAddress?.substringBefore('%').orEmpty()
                                if (host.isBlank() || serviceInfo.port !in 1..65535) return
                                val endpoint = if (host.contains(':')) "[$host]:${serviceInfo.port}" else "$host:${serviceInfo.port}"
                                // Plaintext nickname is not broadcast in mDNS (Privacy Invariant); caller resolves contact or uses candidate ID.
                                onPeerResolved("", discoveryToken, endpoint)
                            } catch (e: Throwable) {
                                SafeLog.w(TAG, "Error handling resolved NSD service", e)
                            }
                        }
                    })
                } catch (e: Throwable) {
                    resolving.remove(serviceInfo.serviceName)
                    SafeLog.w(TAG, "Failed to initiate NSD service resolution", e)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                SafeLog.w(TAG, "NSD discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                SafeLog.w(TAG, "NSD discovery stop failed: $errorCode")
            }
        }
        discoveryListener = dListener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dListener)
        } catch (e: Throwable) {
            SafeLog.w(TAG, "Failed to start NSD service discovery", e)
        }
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
        const val ATTRIBUTE_FINGERPRINT = "fp"
    }
}
