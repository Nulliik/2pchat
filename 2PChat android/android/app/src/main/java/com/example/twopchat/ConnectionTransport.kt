package com.example.twopchat

internal enum class ConnectionTransportKind {
    DIRECT,
    YGGDRASIL,
    UNKNOWN,
}

internal fun connectionTransportKind(
    rawTransport: String?,
    endpoint: String? = null,
): ConnectionTransportKind {
    val normalized = rawTransport.orEmpty().trim().lowercase()
    when {
        "ygg" in normalized || "overlay" in normalized -> return ConnectionTransportKind.YGGDRASIL
        "direct" in normalized || "ipv4" in normalized || "clearnet" in normalized ||
            "local" in normalized || "wifi" in normalized || "lan" in normalized ->
            return ConnectionTransportKind.DIRECT
    }

    val endpointValue = endpoint.orEmpty().trim()
    if (endpointValue.isBlank() || endpointValue.contains("resolv", ignoreCase = true)) {
        return ConnectionTransportKind.UNKNOWN
    }
    val host = when {
        endpointValue.startsWith("[") -> endpointValue.substringAfter('[').substringBefore(']')
        endpointValue.count { it == ':' } > 1 -> endpointValue.substringBeforeLast(':')
        else -> endpointValue.substringBeforeLast(':', endpointValue)
    }
    return when {
        ':' in host -> ConnectionTransportKind.YGGDRASIL
        host.isNotBlank() -> ConnectionTransportKind.DIRECT
        else -> ConnectionTransportKind.UNKNOWN
    }
}

internal fun canonicalConnectionTransport(
    rawTransport: String?,
    endpoint: String? = null,
): String? = when (connectionTransportKind(rawTransport, endpoint)) {
    ConnectionTransportKind.DIRECT -> "Direct P2P"
    ConnectionTransportKind.YGGDRASIL -> "Yggdrasil"
    ConnectionTransportKind.UNKNOWN -> null
}

internal fun connectionTransportLabel(
    rawTransport: String?,
    endpoint: String?,
    appLanguage: String,
): String = canonicalConnectionTransport(rawTransport, endpoint)
    ?: if (appLanguage == "Русский") "маршрут определяется" else "detecting route"
