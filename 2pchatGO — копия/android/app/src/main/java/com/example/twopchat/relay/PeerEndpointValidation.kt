package com.example.twopchat.relay

/** Mirrors the endpoint grammar accepted by discovery_bridge._dial_endpoint. */
internal fun isValidPeerEndpointList(value: String): Boolean {
    if (value.isBlank() || value.length > 4_096) return false
    val endpoints = value.split(',').map(String::trim).filter(String::isNotEmpty)
    if (endpoints.isEmpty()) return false
    return endpoints.take(16).all(::isValidPeerEndpoint)
}

private fun isValidPeerEndpoint(endpoint: String): Boolean {
    if (endpoint.length > 512 || endpoint.any(Char::isISOControl)) return false
    val (host, portText) = if (endpoint.startsWith("[")) {
        val closingBracket = endpoint.indexOf(']')
        if (closingBracket <= 1 || endpoint.getOrNull(closingBracket + 1) != ':') return false
        endpoint.substring(1, closingBracket) to endpoint.substring(closingBracket + 2)
    } else {
        if (endpoint.count { it == ':' } != 1) return false
        endpoint.substringBeforeLast(':') to endpoint.substringAfterLast(':')
    }
    if (host.isBlank() || host.any { it.isWhitespace() || it == '<' || it == '>' }) return false
    if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return false
    return portText.toIntOrNull() in 1..65_535
}

internal fun resolvePeerEndpoint(
    peerName: String,
    liveEndpoint: String?,
    persistedEndpoint: String?,
    onionEndpoint: String? = null,
): String? {
    if (peerName.isBlank()) return null
    val validEndpoints = sequenceOf(onionEndpoint, liveEndpoint, persistedEndpoint)
        .mapNotNull { it?.trim() }
        .flatMap { it.split(',').asSequence().map(String::trim).filter(String::isNotEmpty) }
        .map { ep ->
            val clean = ep.trim()
            val isIpv4WithoutPort = !clean.contains(':') && clean.split('.').let { parts ->
                parts.size == 4 && parts.all { p -> p.toIntOrNull() in 0..255 }
            }
            when {
                clean.endsWith(".onion", ignoreCase = true) && !clean.contains(':') -> "$clean:50001"
                clean.startsWith("[") && clean.endsWith("]") -> "$clean:50001"
                clean.count { it == ':' } > 1 && !clean.startsWith("[") -> "[$clean]:50001"
                isIpv4WithoutPort -> "$clean:50001"
                else -> clean
            }
        }
        .filter(::isValidPeerEndpoint)
        .distinct()
        .toList()
    return if (validEndpoints.isNotEmpty()) validEndpoints.joinToString(",") else null
}

fun isValidEndpoint(value: String): Boolean = isValidPeerEndpointList(value)

fun orderedDirectEndpoints(endpoints: List<String>): List<String> {
    return endpoints.distinct().sortedWith(
        compareBy { endpoint ->
            val host = endpoint.substringBeforeLast(':').removeSurrounding("[", "]")
            when {
                host.startsWith("192.168.") || host.startsWith("10.") || (host.startsWith("172.") && host.split(".").getOrNull(1)?.toIntOrNull() in 16..31) -> 0
                !host.contains(':') -> 1
                else -> 2
            }
        }
    )
}

fun selectExternalIpv4(localIpv4: String, candidateIpv4s: List<String>): String {
    return candidateIpv4s.firstOrNull { candidate ->
        candidate.isNotBlank() &&
            !candidate.contains(':') &&
            candidate != localIpv4 &&
            !candidate.startsWith("127.") &&
            !candidate.startsWith("10.") &&
            !candidate.startsWith("192.168.")
    }.orEmpty()
}
