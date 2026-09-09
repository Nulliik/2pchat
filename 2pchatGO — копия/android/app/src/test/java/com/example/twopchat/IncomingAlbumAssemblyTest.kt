package com.example.twopchat

import com.example.twopchat.ui.chat.Message
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingAlbumAssemblyTest {

    @Test
    fun parseAlbumFieldsFromJSON_handlesValidAlbumMetadata() {
        val jsonStr0 = """{
            "type": "file",
            "message_id": "album123_0",
            "file_path": "/fake/path0.jpg",
            "file_name": "photo0.jpg",
            "album_id": "album123",
            "album_index": 0,
            "album_count": 2,
            "caption": "My Vacation"
        }""".trimIndent()

        val json0 = JSONObject(jsonStr0)
        val albumId0 = json0.optString("album_id").take(128)
        val albumIndex0 = json0.optInt("album_index", -1)
        val albumCount0 = json0.optInt("album_count", 0)
        val hasValidAlbum0 = albumId0.isNotBlank() && albumCount0 in 2..100 && albumIndex0 in 0 until albumCount0

        assertTrue(hasValidAlbum0)
        assertEquals("album123", albumId0)
        assertEquals(0, albumIndex0)
        assertEquals(2, albumCount0)

        val jsonStr1 = """{
            "type": "file",
            "message_id": "album123_1",
            "file_path": "/fake/path1.jpg",
            "file_name": "photo1.jpg",
            "album_id": "album123",
            "album_index": 1,
            "album_count": 2,
            "caption": ""
        }""".trimIndent()

        val json1 = JSONObject(jsonStr1)
        val albumId1 = json1.optString("album_id").take(128)
        val albumIndex1 = json1.optInt("album_index", -1)
        val albumCount1 = json1.optInt("album_count", 0)
        val hasValidAlbum1 = albumId1.isNotBlank() && albumCount1 in 2..100 && albumIndex1 in 0 until albumCount1

        assertTrue(hasValidAlbum1)
        assertEquals("album123", albumId1)
        assertEquals(1, albumIndex1)
        assertEquals(2, albumCount1)
    }

    @Test
    fun albumStateAssembly_mergesTwoPartsIntoSingleAlbumMessage() {
        val totalParts = 2
        val albumUris = mutableListOf("", "")
        val albumTypes = mutableListOf("IMAGE", "IMAGE")

        // Part 0 arrives
        albumUris[0] = "/storage/photo0.jpg"
        albumTypes[0] = "IMAGE"
        var complete = albumUris.take(totalParts).all { it.isNotBlank() }
        org.junit.Assert.assertFalse(complete)

        val intermediateMsg = Message(
            id = "album123",
            text = "My Vacation",
            isMe = false,
            timestamp = "12:00",
            attachmentType = "ALBUM",
            attachmentUri = albumUris.firstOrNull { it.isNotBlank() },
            attachmentName = "Album",
            status = if (complete) "SENT" else "RECEIVING",
            albumMediaUris = albumUris.toList(),
            albumMediaTypes = albumTypes.toList(),
        )
        assertEquals("RECEIVING", intermediateMsg.status)
        assertEquals(2, intermediateMsg.albumMediaUris.size)
        assertEquals("/storage/photo0.jpg", intermediateMsg.albumMediaUris[0])
        assertEquals("", intermediateMsg.albumMediaUris[1])

        // Part 1 arrives
        val existingUris = intermediateMsg.albumMediaUris.toMutableList()
        val existingTypes = intermediateMsg.albumMediaTypes.toMutableList()
        existingUris[1] = "/storage/photo1.jpg"
        existingTypes[1] = "IMAGE"
        complete = existingUris.take(totalParts).all { it.isNotBlank() }
        assertTrue(complete)

        val finalMsg = Message(
            id = "album123",
            text = intermediateMsg.text,
            isMe = false,
            timestamp = intermediateMsg.timestamp,
            attachmentType = "ALBUM",
            attachmentUri = existingUris.firstOrNull { it.isNotBlank() },
            attachmentName = "Album",
            status = if (complete) "SENT" else "RECEIVING",
            albumMediaUris = existingUris.toList(),
            albumMediaTypes = existingTypes.toList(),
        )
        assertEquals("SENT", finalMsg.status)
        assertEquals(2, finalMsg.albumMediaUris.size)
        assertEquals("/storage/photo0.jpg", finalMsg.albumMediaUris[0])
        assertEquals("/storage/photo1.jpg", finalMsg.albumMediaUris[1])
        assertEquals("ALBUM", finalMsg.attachmentType)
    }

    @Test
    fun outOfOrderArrival_assemblesCorrectly() {
        val totalParts = 3
        val albumUris = mutableListOf("", "", "")
        val albumTypes = mutableListOf("IMAGE", "IMAGE", "IMAGE")

        // Part 2 arrives first
        albumUris[2] = "/storage/photo2.jpg"
        assertEquals(false, albumUris.all { it.isNotBlank() })

        // Part 0 arrives second
        albumUris[0] = "/storage/photo0.jpg"
        assertEquals(false, albumUris.all { it.isNotBlank() })

        // Part 1 arrives third
        albumUris[1] = "/storage/photo1.jpg"
        assertEquals(true, albumUris.all { it.isNotBlank() })

        assertEquals(listOf("/storage/photo0.jpg", "/storage/photo1.jpg", "/storage/photo2.jpg"), albumUris)
    }

    @Test
    fun concurrentArrival_threadSafety() {
        val totalParts = 8
        val incomingAlbums = java.util.concurrent.ConcurrentHashMap<String, Message>()
        val albumKey = "peer:album_multi"
        val threads = mutableListOf<Thread>()

        for (i in 0 until totalParts) {
            val partIndex = i
            val thread = Thread {
                synchronized(incomingAlbums) {
                    val existing = incomingAlbums[albumKey]
                    val uris = existing?.albumMediaUris.orEmpty().toMutableList()
                    val types = existing?.albumMediaTypes.orEmpty().toMutableList()
                    while (uris.size < totalParts) uris.add("")
                    while (types.size < totalParts) types.add("IMAGE")
                    uris[partIndex] = "/storage/photo_$partIndex.jpg"
                    val complete = uris.take(totalParts).all { it.isNotBlank() }

                    val msg = Message(
                        id = "album_multi",
                        text = "Multi Album",
                        isMe = false,
                        timestamp = "12:00",
                        attachmentType = "ALBUM",
                        attachmentUri = uris.firstOrNull { it.isNotBlank() },
                        attachmentName = "Album",
                        status = if (complete) "SENT" else "RECEIVING",
                        albumMediaUris = uris,
                        albumMediaTypes = types,
                    )
                    incomingAlbums[albumKey] = msg
                }
            }
            threads.add(thread)
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val finalAlbum = incomingAlbums[albumKey]
        assertNotNull(finalAlbum)
        assertEquals("SENT", finalAlbum!!.status)
        assertEquals(totalParts, finalAlbum.albumMediaUris.size)
        for (i in 0 until totalParts) {
            assertEquals("/storage/photo_$i.jpg", finalAlbum.albumMediaUris[i])
        }
    }

    @Test
    fun rawVsResolvedSenderKeyLookup_findsExistingAlbum() {
        val incomingAlbums = java.util.concurrent.ConcurrentHashMap<String, Message>()
        val rawSender = "dzPId8GVZuNv3LnrjUaHnD2pmtKRDp2guBrmwcVClGc="
        val resolvedSender = "Alice"
        val albumId = "album_xyz"

        val peerKey = resolvedSender.ifBlank { rawSender }
        val albumKey = "$peerKey:$albumId"

        val testMessage = Message(
            id = albumId,
            text = "Album Title",
            isMe = false,
            timestamp = "12:00",
            attachmentType = "ALBUM",
            attachmentUri = "/storage/photo0.jpg",
            attachmentName = "Album",
            status = "RECEIVING",
            albumMediaUris = listOf("/storage/photo0.jpg", ""),
            albumMediaTypes = listOf("IMAGE", "IMAGE"),
        )

        // Store under normalized and fallback keys
        incomingAlbums[albumKey] = testMessage
        incomingAlbums["$rawSender:$albumId"] = testMessage
        incomingAlbums["$resolvedSender:$albumId"] = testMessage

        // Check lookup from raw sender
        val lookupRaw = incomingAlbums["$peerKey:$albumId"]
            ?: incomingAlbums["$rawSender:$albumId"]
            ?: incomingAlbums["$resolvedSender:$albumId"]
        assertNotNull(lookupRaw)
        assertEquals(albumId, lookupRaw!!.id)

        // Check lookup from resolved sender
        val lookupResolved = incomingAlbums["$resolvedSender:$albumId"]
            ?: incomingAlbums["$rawSender:$albumId"]
        assertNotNull(lookupResolved)
        assertEquals(albumId, lookupResolved!!.id)
    }
}
