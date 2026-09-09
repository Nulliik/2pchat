package com.example.twopchat.config

import android.content.Context
import com.example.twopchat.NativeBridge
import com.example.twopchat.tor.*

object ProxyConfig {
    fun isValidHost(host: String): Boolean {
        val trimmed = host.trim()
        return trimmed.isNotEmpty() && trimmed.length <= 256 && !trimmed.contains(' ')
    }

    fun isValidPort(port: Int): Boolean {
        return port in 1..65535
    }

    fun toJson(enabled: Boolean, host: String, port: Int): String {
        val safeHost = host.trim().replace("\"", "\\\"")
        return "{\"proxy_enabled\":$enabled,\"proxy_host\":\"$safeHost\",\"proxy_port\":$port}"
    }

    fun configJson(context: Context): String {
        val effective = getEffectiveProxyConfig(context)
        return toJson(
            enabled = effective.enabled,
            host = effective.host,
            port = effective.port
        )
    }

    fun resolveProxyConfig(
        isTorEnabled: Boolean,
        isTorRunning: Boolean,
        customSocks5Enabled: Boolean,
        customHost: String,
        customPort: Int
    ): ResolvedProxyConfig {
        return when {
            isTorEnabled && isTorRunning -> ResolvedProxyConfig(
                enabled = true,
                host = "127.0.0.1",
                port = TorManager.effectiveSocksPort
            )
            customSocks5Enabled -> ResolvedProxyConfig(
                enabled = true,
                host = customHost.trim().takeIf(::isValidHost)
                    ?: P2PPreferences.DEFAULT_SOCKS5_HOST,
                port = customPort.takeIf(::isValidPort)
                    ?: P2PPreferences.DEFAULT_SOCKS5_PORT
            )
            else -> ResolvedProxyConfig(
                enabled = false,
                host = "127.0.0.1",
                port = TorManager.effectiveSocksPort
            )
        }
    }

    fun getEffectiveProxyConfig(context: Context): ResolvedProxyConfig {
        val isTorRunning = TorManager.isTorRunning.value
        val isTorEnabled = P2PPreferences.isTorEnabled(context)
        val customSocks5Enabled = P2PPreferences.isCustomSocks5Enabled(context)
        val customHost = P2PPreferences.getCustomSocks5Host(context)
        val customPort = P2PPreferences.getCustomSocks5Port(context)

        return resolveProxyConfig(
            isTorEnabled = isTorEnabled,
            isTorRunning = isTorRunning,
            customSocks5Enabled = customSocks5Enabled,
            customHost = customHost,
            customPort = customPort
        )
    }

    fun updateNetworkProxy(context: Context): Boolean {
        val effective = getEffectiveProxyConfig(context)
        val proxyAddr = if (effective.enabled && effective.host.isNotBlank() && effective.port > 0) {
            "${effective.host}:${effective.port}"
        } else {
            ""
        }
        NativeBridge.setTorProxy(effective.enabled, proxyAddr)
        val policyFlags = if (effective.enabled && P2PPreferences.isTorStrictMode(context)) {
            8 // PolicyFlagAllowOnion = 1 << 3
        } else {
            31 // PolicySpeed (LAN | WAN | Yggdrasil | Onion | LocalDNS)
        }
        NativeBridge.applyPolicy(policyFlags)
        return true
    }
}

data class ResolvedProxyConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int
)

