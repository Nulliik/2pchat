package com.example.twopchat

/** Mirrors the endpoint grammar accepted by discovery_bridge._dial_endpoint. */
internal fun isValidPeerEndpointList(value: String): Boolean {
    if (value.isBlank() || value.length > 4_096) return false
    val endpoints = value.split(',').map(String::trim).filter(String::isNotEmpty)
    if (endpoints.isEmpty() || endpoints.size > 12) return false
    return endpoints.all(::isValidPeerEndpoint)
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
): String? {
    if (peerName.isBlank()) return null
    return sequenceOf(liveEndpoint, persistedEndpoint)
        .mapNotNull { it?.trim()?.takeIf(::isValidPeerEndpointList) }
        .firstOrNull()
}
