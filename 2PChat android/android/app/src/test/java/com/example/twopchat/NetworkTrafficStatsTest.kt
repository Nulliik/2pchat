package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTrafficStatsTest {
    @Test
    fun `protocol uses authenticated transport before endpoint`() {
        assertEquals(
            TrafficProtocol.YGGDRASIL,
            trafficProtocol("Yggdrasil", "192.168.1.20:50001"),
        )
        assertEquals(
            TrafficProtocol.DIRECT_P2P,
            trafficProtocol("Direct P2P", "[200:abcd::1]:50001"),
        )
    }

    @Test
    fun `protocol falls back to endpoint address family`() {
        assertEquals(
            TrafficProtocol.YGGDRASIL,
            trafficProtocol(null, "[200:abcd::1]:50001"),
        )
        assertEquals(
            TrafficProtocol.DIRECT_P2P,
            trafficProtocol(null, "10.0.2.2:50001"),
        )
        assertEquals(TrafficProtocol.UNKNOWN, trafficProtocol(null, null))
    }

    @Test
    fun `message and service payloads are separated`() {
        assertEquals(TrafficCategory.MESSAGES, trafficCategoryForMessage("hello"))
        assertEquals(
            TrafficCategory.MESSAGES,
            trafficCategoryForMessage("""{"message_id":"1","type":"text","text":"hello"}"""),
        )
        assertEquals(
            TrafficCategory.MESSAGES,
            trafficCategoryForMessage("""{"type" : "reply","text":"hello"}"""),
        )
        assertEquals(
            TrafficCategory.SERVICE,
            trafficCategoryForMessage("""{"type":"reaction","emoji":"+"}"""),
        )
    }

    @Test
    fun `local file bridge events are not counted as received network messages`() {
        assertEquals(
            false,
            shouldRecordIncomingTrafficPayload("""{"type":"file_progress","bytes_transferred":12}"""),
        )
        assertEquals(
            false,
            shouldRecordIncomingTrafficPayload("""{"type":"file","file_path":"/local/file"}"""),
        )
        assertEquals(
            true,
            shouldRecordIncomingTrafficPayload("""{"type":"reaction","emoji":"+"}"""),
        )
    }

    @Test
    fun `attachment types map to telegram style categories`() {
        assertEquals(TrafficCategory.PHOTOS, trafficCategoryForAttachment("IMAGE"))
        assertEquals(TrafficCategory.VIDEOS, trafficCategoryForAttachment("VIDEO"))
        assertEquals(TrafficCategory.GIFS, trafficCategoryForAttachment(GifStorageManager.ATTACHMENT_TYPE))
        assertEquals(
            TrafficCategory.STICKERS,
            trafficCategoryForAttachment(StickerSupport.ATTACHMENT_TYPE),
        )
        assertEquals(TrafficCategory.VOICE, trafficCategoryForAttachment("VOICE"))
        assertEquals(TrafficCategory.FILES, trafficCategoryForAttachment("FILE"))
    }

    @Test
    fun `counter aggregation keeps both directions and item counts`() {
        val first = TrafficCounter(
            receivedBytes = 10L,
            sentBytes = 20L,
            receivedItems = 1L,
            sentItems = 2L,
        )
        val second = TrafficCounter(
            receivedBytes = 30L,
            sentBytes = 40L,
            receivedItems = 3L,
            sentItems = 4L,
        )

        assertEquals(
            TrafficCounter(40L, 60L, 4L, 6L),
            first + second,
        )
    }
}
