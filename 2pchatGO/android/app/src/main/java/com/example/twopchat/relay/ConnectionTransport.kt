package com.example.twopchat.relay

enum class TransportType {
    DIRECT,      // LAN / Direct IPv4 / Direct IPv6 (High Speed)
    YGGDRASIL,   // Yggdrasil Mesh IPv6 (High Speed)
    ONION,       // Tor Onion Service v3 (High Privacy)
    DISCONNECTED // Offline / Connecting
}

internal enum class ConnectionTransportKind {
    DIRECT,
    YGGDRASIL,
    ONION,
    UNKNOWN,
}

internal fun connectionTransportKind(
    rawTransport: String?,
    endpoint: String? = null,
): ConnectionTransportKind {
    // The live session's transport label (written by the bridge on session
    // establishment) is authoritative. The endpoint argument is often the
    // comma-joined candidate list of every known peer address (IPv4,
    // Yggdrasil, onion) rather than the live route, so it is only a fallback
    // when no live label is available.
    val normalized = rawTransport.orEmpty().trim().lowercase()
    when {
        "onion" in normalized || "tor" in normalized -> return ConnectionTransportKind.ONION
        "ygg" in normalized || "overlay" in normalized -> return ConnectionTransportKind.YGGDRASIL
        "direct" in normalized || "ipv4" in normalized || "clearnet" in normalized ||
            "local" in normalized || "wifi" in normalized || "lan" in normalized ->
            return ConnectionTransportKind.DIRECT
    }

    return endpointTransportKind(endpoint)
}

private fun endpointTransportKind(endpoint: String?): ConnectionTransportKind {
    val endpointValue = endpoint.orEmpty().trim()
    if (endpointValue.isBlank() || endpointValue.contains("resolv", ignoreCase = true)) {
        return ConnectionTransportKind.UNKNOWN
    }
    if (endpointValue.contains(".onion", ignoreCase = true)) {
        return ConnectionTransportKind.ONION
    }
    val host = when {
        endpointValue.startsWith("[") -> endpointValue.substringAfter('[').substringBefore(']')
        endpointValue.count { it == ':' } > 1 -> endpointValue.substringBeforeLast(':')
        else -> endpointValue.substringBeforeLast(':', endpointValue)
    }
    return when {
        isYggdrasilIpv6(host) -> ConnectionTransportKind.YGGDRASIL
        host.isNotBlank() -> ConnectionTransportKind.DIRECT
        else -> ConnectionTransportKind.UNKNOWN
    }
}

/** Yggdrasil addresses are allocated from 0200::/7 (0x0200..0x03ff), not from all IPv6. */
private fun isYggdrasilIpv6(host: String): Boolean {
    val cleanHost = host.trim().trim('[', ']')
    if (':' !in cleanHost) return false
    val firstHextet = cleanHost.substringBefore(':').substringBefore('%').toIntOrNull(16) ?: return false
    return firstHextet in 0x0200..0x03ff
}

internal fun canonicalConnectionTransport(
    rawTransport: String?,
    endpoint: String? = null,
): String? = when (connectionTransportKind(rawTransport, endpoint)) {
    ConnectionTransportKind.ONION -> "Tor Onion"
    ConnectionTransportKind.DIRECT -> "Direct P2P"
    ConnectionTransportKind.YGGDRASIL -> "Yggdrasil"
    ConnectionTransportKind.UNKNOWN -> null
}

/**
 * The incoming side of a Tor onion service reaches the local listener through
 * Tor's loopback proxy. JNI reports the socket peer address, so it is
 * `127.0.0.1` or `[::1]`, not the remote onion hostname. Keep this aligned
 * with Go's session.Manager.handleIncomingConnection classification.
 */
internal fun incomingConnectionTransport(endpoint: String, localOnionServiceConfigured: Boolean): String {
    val normalized = endpoint.trim().lowercase()
    // The user-space Yggdrasil TCP shim connects to the native listener from
    // this dedicated loopback address. It is display provenance only, never a
    // remote endpoint that can be reused for dialing.
    if (normalized.startsWith("127.0.0.2:")) return "Yggdrasil"
    val isTorLoopback = normalized.startsWith("127.0.0.1:") || normalized.startsWith("[::1]:")
    if (localOnionServiceConfigured && isTorLoopback) return "Tor Onion"
    return canonicalConnectionTransport(null, endpoint) ?: "Direct P2P"
}

fun resolveTransportType(
    rawTransport: String?,
    endpoint: String? = null,
    isOnline: Boolean = true,
): TransportType {
    if (!isOnline) return TransportType.DISCONNECTED
    return when (connectionTransportKind(rawTransport, endpoint)) {
        ConnectionTransportKind.ONION -> TransportType.ONION
        ConnectionTransportKind.DIRECT -> TransportType.DIRECT
        ConnectionTransportKind.YGGDRASIL -> TransportType.YGGDRASIL
        ConnectionTransportKind.UNKNOWN -> TransportType.DIRECT
    }
}

internal fun connectionTransportLabel(
    rawTransport: String?,
    endpoint: String?,
    appLanguage: String,
): String = canonicalConnectionTransport(rawTransport, endpoint)
    ?: com.example.twopchat.data.Localizations.tr(
        appLanguage,
        ru = "маршрут определяется",
        en = "detecting route",
        de = "Route wird ermittelt",
        es = "detectando ruta",
        fr = "détection de l'itinéraire",
        pt = "detectando rota",
        tr = "rota belirleniyor"
    )
