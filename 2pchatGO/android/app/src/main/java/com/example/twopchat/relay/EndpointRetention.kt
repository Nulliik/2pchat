package com.example.twopchat.relay

import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

internal enum class EndpointKind { LAN, PUBLIC, YGGDRASIL, TOR }
internal enum class EndpointSource { DISCOVERY, AUTHENTICATED, MANUAL, MIGRATED }

internal data class EndpointRecord(
    val fingerprint: String,
    val endpoint: String,
    val source: EndpointSource,
    val firstSeen: Long,
    val lastSeen: Long,
    val lastSuccess: Long = 0,
    val successDays: Int = 0,
    val lastFailure: Long = 0,
    val failures: Int = 0,
    val retryAfter: Long = 0,
    val advertisedExpires: Long = 0,
    val savedContact: Boolean = false,
) {
    val kind: EndpointKind get() = EndpointRetention.kind(endpoint)
}

/** Address retention is independent of contact/identity retention. No policy
 * operation can remove a contact, its fingerprint or its trust decisions. */
internal object EndpointRetention {
    const val DAY = 86_400_000L
    const val MAX_PER_PEER = 16
    const val MAX_CACHE_RECORDS = 8192 // excludes protected friend routes

    fun normalize(value: String): String? {
        var endpoint = value.trim().lowercase(Locale.ROOT)
        if (endpoint.endsWith(".onion")) endpoint += ":50001"
        if (endpoint.startsWith('[') && endpoint.endsWith(']')) endpoint += ":50001"
        if (endpoint.count { it == ':' } > 1 && !endpoint.startsWith('[')) endpoint = "[$endpoint]:50001"
        if (endpoint.length > 512 || endpoint.contains(',') || !isValidPeerEndpointList(endpoint)) return null
        var host = endpoint.substringBeforeLast(':').removeSurrounding("[", "]")
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return null
        if (':' in host) {
            // Literal IPv6 only: never resolve a DNS hostname while storing routes.
            if (!host.all { it in "0123456789abcdef:" }) return null
            val ip = runCatching { InetAddress.getByName(host) as? Inet6Address }.getOrNull() ?: return null
            if (ip.isLoopbackAddress || ip.isAnyLocalAddress || ip.isLinkLocalAddress || ip.isMulticastAddress) return null
            host = ip.hostAddress ?: return null
            return "[$host]:$port"
        }
        if (host == "localhost" || host == "0.0.0.0" || host.startsWith("127.") || host.startsWith("169.254.")) return null
        return "$host:$port"
    }

    fun kind(endpoint: String): EndpointKind {
        val host = endpoint.substringBeforeLast(':').removeSurrounding("[", "]").lowercase(Locale.ROOT)
        if (host.endsWith(".onion")) return EndpointKind.TOR
        if (':' in host) {
            val prefix = host.substringBefore(':').toIntOrNull(16)
            if (prefix != null && prefix in 0x200..0x3ff) return EndpointKind.YGGDRASIL
            if (host.startsWith("fc") || host.startsWith("fd")) return EndpointKind.LAN
        }
        val parts = host.split('.').map { it.toIntOrNull() }
        if (parts.size == 4 && (parts[0] == 10 ||
                (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1] in 16..31))) return EndpointKind.LAN
        return EndpointKind.PUBLIC
    }

    fun expiresAt(record: EndpointRecord): Long {
        if (record.lastSuccess > 0) {
            val days = when (record.kind) {
                EndpointKind.LAN -> if (record.successDays >= 3) 30 else 7
                EndpointKind.PUBLIC -> if (record.successDays >= 3) 90 else 30
                EndpointKind.TOR, EndpointKind.YGGDRASIL -> if (record.successDays >= 3) 365 else 180
            }
            return record.lastSuccess + days * DAY
        }
        val ttl = if (record.source == EndpointSource.MIGRATED) 7 * DAY else when (record.kind) {
            EndpointKind.LAN -> 30 * 60_000L
            EndpointKind.PUBLIC -> 2 * 60 * 60_000L
            EndpointKind.TOR, EndpointKind.YGGDRASIL -> 7 * DAY
        }
        val expiry = record.lastSeen + ttl
        return if (record.advertisedExpires > 0) minOf(expiry, record.advertisedExpires) else expiry
    }

    fun success(record: EndpointRecord, now: Long): EndpointRecord = record.copy(
        lastSuccess = maxOf(record.lastSuccess, now),
        successDays = (record.successDays + if (record.lastSuccess == 0L || now / DAY > record.lastSuccess / DAY) 1 else 0).coerceAtMost(365),
        failures = 0,
        retryAfter = 0,
    )

    fun failure(record: EndpointRecord, now: Long): EndpointRecord {
        val failures = (record.failures + 1).coerceAtMost(12)
        val delay = (30_000L * (1L shl (failures - 1))).coerceAtMost(6 * 60 * 60_000L)
        return record.copy(lastFailure = now, failures = failures, retryAfter = now + delay)
    }

    /** One last working Tor and Yggdrasil address per saved identity. Migration
     * and authenticated route announcements provide reserves until first use.
     * If no stable route exists, retain one last direct route instead. */
    fun protected(records: List<EndpointRecord>, active: Set<String> = emptySet()): Set<String> {
        val result = active.toMutableSet()
        val friends = records.filter { it.savedContact && (it.lastSuccess > 0 || it.source != EndpointSource.DISCOVERY) }
        val best = compareBy<EndpointRecord> { it.lastSuccess > 0 }.thenBy { it.lastSuccess }.thenBy { it.lastSeen }.thenBy { it.endpoint }
        for (kind in listOf(EndpointKind.TOR, EndpointKind.YGGDRASIL)) {
            friends.filter { it.kind == kind }.maxWithOrNull(best)?.let { result.add(it.endpoint) }
        }
        if (friends.none { it.kind == EndpointKind.TOR || it.kind == EndpointKind.YGGDRASIL }) {
            friends.maxWithOrNull(best)?.let { result.add(it.endpoint) }
        }
        return result
    }

    fun retain(records: List<EndpointRecord>, now: Long, active: Set<String> = emptySet()): List<EndpointRecord> {
        val protected = protected(records, active)
        return records.filter { it.endpoint in protected || now < expiresAt(it) }
            .sortedWith(compareByDescending<EndpointRecord> { it.endpoint in protected }
                .thenByDescending { it.lastSuccess > 0 }
                .thenBy { it.failures }
                .thenByDescending { maxOf(it.lastSuccess, it.lastSeen) }
                .thenBy { it.endpoint })
            .take(MAX_PER_PEER)
    }

    fun candidates(records: List<EndpointRecord>, now: Long, includeReserve: Boolean): List<String> {
        val protected = protected(records)
        return records.filter { record ->
            (now < expiresAt(record) || (includeReserve && record.endpoint in protected)) &&
                (includeReserve || now >= record.retryAfter)
        }.sortedWith(compareByDescending<EndpointRecord> { now < expiresAt(it) }
            .thenBy { it.failures }
            .thenByDescending { it.lastSuccess }
            .thenByDescending { it.lastSeen }
            .thenBy { it.endpoint })
            .take(MAX_PER_PEER).map { it.endpoint }
    }

    fun trimCache(records: List<EndpointRecord>, now: Long, active: Map<String, Set<String>> = emptyMap()): List<EndpointRecord> {
        val retained = records.groupBy { it.fingerprint }.flatMap { (fp, rows) -> retain(rows, now, active[fp].orEmpty()) }
        val protectedKeys = retained.groupBy { it.fingerprint }.flatMap { (fp, rows) ->
            protected(rows, active[fp].orEmpty()).map { fp to it }
        }.toSet()
        val (reserves, cache) = retained.partition { (it.fingerprint to it.endpoint) in protectedKeys }
        return reserves + cache.sortedWith(compareByDescending<EndpointRecord> { it.lastSuccess > 0 }
            .thenBy { it.failures }.thenByDescending { maxOf(it.lastSuccess, it.lastSeen) })
            .take(MAX_CACHE_RECORDS)
    }
}
