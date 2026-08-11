package com.example.twopchat

import android.content.Context

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
        return toJson(
            enabled = P2PPreferences.isProxyEnabled(context),
            host = P2PPreferences.getProxyHost(context),
            port = P2PPreferences.getProxyPort(context)
        )
    }
}
