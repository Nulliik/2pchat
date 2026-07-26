package com.example.twopchat

import android.content.Context
import java.io.File

private val MESSAGE_TYPE_PATTERN =
    Regex("\"type\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
private val LOCAL_FILE_BRIDGE_EVENTS = setOf(
    "file",
    "file_offer",
    "file_progress",
    "file_cancelled",
    "file_failed",
)

internal enum class TrafficProtocol {
    DIRECT_P2P,
    YGGDRASIL,
    UNKNOWN,
}

internal enum class TrafficCategory {
    MESSAGES,
    PHOTOS,
    VIDEOS,
    GIFS,
    STICKERS,
    VOICE,
    FILES,
    SERVICE,
}

internal enum class TrafficDirection {
    RECEIVED,
    SENT,
}

internal data class TrafficCounter(
    val receivedBytes: Long = 0L,
    val sentBytes: Long = 0L,
    val receivedItems: Long = 0L,
    val sentItems: Long = 0L,
) {
    val totalBytes: Long get() = receivedBytes + sentBytes
    val totalItems: Long get() = receivedItems + sentItems

    operator fun plus(other: TrafficCounter) = TrafficCounter(
        receivedBytes = receivedBytes.saturatedAdd(other.receivedBytes),
        sentBytes = sentBytes.saturatedAdd(other.sentBytes),
        receivedItems = receivedItems.saturatedAdd(other.receivedItems),
        sentItems = sentItems.saturatedAdd(other.sentItems),
    )
}

internal data class NetworkTrafficSnapshot(
    val startedAtMs: Long,
    val byProtocol: Map<TrafficProtocol, TrafficCounter>,
    val byCategory: Map<TrafficCategory, TrafficCounter>,
    val details: Map<TrafficProtocol, Map<TrafficCategory, TrafficCounter>>,
) {
    val total: TrafficCounter
        get() = byProtocol.values.fold(TrafficCounter(), TrafficCounter::plus)
}

/**
 * Persistent application-payload counters.
 *
 * Android cannot attribute kernel traffic to a specific route when Direct P2P and
 * Yggdrasil share the same process. The counters therefore live at the authenticated
 * P2P boundary: message envelopes and completed file payloads are attributed to the
 * route which carried them. Retries inside one transport call are counted once.
 */
internal object NetworkTrafficStats {
    private const val PREFS_NAME = "2pchat_network_traffic"
    private const val STARTED_AT = "started_at_ms"

    @Synchronized
    fun record(
        context: Context,
        protocol: TrafficProtocol,
        category: TrafficCategory,
        direction: TrafficDirection,
        bytes: Long,
        items: Long = 1L,
    ) {
        if (bytes < 0L || items < 0L || (bytes == 0L && items == 0L)) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (!prefs.contains(STARTED_AT)) editor.putLong(STARTED_AT, System.currentTimeMillis())
        val byteKey = key(protocol, category, direction, "bytes")
        val itemKey = key(protocol, category, direction, "items")
        editor
            .putLong(byteKey, prefs.getLong(byteKey, 0L).saturatedAdd(bytes))
            .putLong(itemKey, prefs.getLong(itemKey, 0L).saturatedAdd(items))
            .apply()
    }

    @Synchronized
    fun snapshot(context: Context): NetworkTrafficSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startedAt = prefs.getLong(STARTED_AT, 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis().also { prefs.edit().putLong(STARTED_AT, it).apply() }
        val details = TrafficProtocol.entries.associateWith { protocol ->
            TrafficCategory.entries.associateWith { category ->
                counter(prefs, protocol, category)
            }
        }
        val protocolCounters = details.mapValues { (_, categories) ->
            categories.values.fold(TrafficCounter(), TrafficCounter::plus)
        }
        val categoryCounters = TrafficCategory.entries.associateWith { category ->
            details.values.fold(TrafficCounter()) { total, categories ->
                total + categories.getValue(category)
            }
        }
        return NetworkTrafficSnapshot(startedAt, protocolCounters, categoryCounters, details)
    }

    @Synchronized
    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putLong(STARTED_AT, System.currentTimeMillis())
            .commit()
    }

    @Synchronized
    fun clear(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

    fun protocol(
        context: Context,
        peerName: String,
        endpoint: String?,
    ): TrafficProtocol {
        val prefs = P2PPreferences.prefs(context)
        val rawTransport = prefs.getString(P2PPreferences.transport(peerName), null)
        return trafficProtocol(rawTransport, endpoint)
    }

    fun recordMessage(
        context: Context,
        peerName: String,
        endpoint: String?,
        payload: String,
        direction: TrafficDirection,
    ) {
        record(
            context = context,
            protocol = protocol(context, peerName, endpoint),
            category = trafficCategoryForMessage(payload),
            direction = direction,
            bytes = payload.toByteArray(Charsets.UTF_8).size.toLong(),
        )
    }

    fun recordFile(
        context: Context,
        peerName: String,
        endpoint: String?,
        file: File,
        attachmentType: String = VoiceMessageSupport.attachmentType(file.name, ""),
        direction: TrafficDirection,
    ) {
        record(
            context = context,
            protocol = protocol(context, peerName, endpoint),
            category = trafficCategoryForAttachment(attachmentType),
            direction = direction,
            bytes = file.length().coerceAtLeast(0L),
        )
    }

    private fun counter(
        prefs: android.content.SharedPreferences,
        protocol: TrafficProtocol,
        category: TrafficCategory,
    ) = TrafficCounter(
        receivedBytes = prefs.getLong(
            key(protocol, category, TrafficDirection.RECEIVED, "bytes"),
            0L,
        ),
        sentBytes = prefs.getLong(
            key(protocol, category, TrafficDirection.SENT, "bytes"),
            0L,
        ),
        receivedItems = prefs.getLong(
            key(protocol, category, TrafficDirection.RECEIVED, "items"),
            0L,
        ),
        sentItems = prefs.getLong(
            key(protocol, category, TrafficDirection.SENT, "items"),
            0L,
        ),
    )

    private fun key(
        protocol: TrafficProtocol,
        category: TrafficCategory,
        direction: TrafficDirection,
        unit: String,
    ) = "${protocol.name.lowercase()}_${category.name.lowercase()}_${direction.name.lowercase()}_$unit"
}

internal fun trafficProtocol(rawTransport: String?, endpoint: String?): TrafficProtocol =
    when (connectionTransportKind(rawTransport, endpoint)) {
        ConnectionTransportKind.DIRECT -> TrafficProtocol.DIRECT_P2P
        ConnectionTransportKind.YGGDRASIL -> TrafficProtocol.YGGDRASIL
        ConnectionTransportKind.UNKNOWN -> TrafficProtocol.UNKNOWN
    }

internal fun trafficCategoryForAttachment(attachmentType: String?): TrafficCategory =
    when (attachmentType?.uppercase()) {
        "IMAGE" -> TrafficCategory.PHOTOS
        "VIDEO", "ALBUM" -> TrafficCategory.VIDEOS
        GifStorageManager.ATTACHMENT_TYPE -> TrafficCategory.GIFS
        StickerSupport.ATTACHMENT_TYPE, StickerSupport.PACK_ATTACHMENT_TYPE ->
            TrafficCategory.STICKERS
        "VOICE" -> TrafficCategory.VOICE
        else -> TrafficCategory.FILES
    }

internal fun trafficCategoryForMessage(payload: String): TrafficCategory {
    val type = messagePayloadType(payload)
    return if (type.isBlank() || type == "text" || type == "reply") {
        TrafficCategory.MESSAGES
    } else {
        TrafficCategory.SERVICE
    }
}

internal fun shouldRecordIncomingTrafficPayload(payload: String): Boolean =
    messagePayloadType(payload) !in LOCAL_FILE_BRIDGE_EVENTS

private fun messagePayloadType(payload: String): String {
    val trimmed = payload.trim()
    if (!trimmed.startsWith("{")) return ""
    return MESSAGE_TYPE_PATTERN.find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
        .lowercase()
}

private fun Long.saturatedAdd(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
