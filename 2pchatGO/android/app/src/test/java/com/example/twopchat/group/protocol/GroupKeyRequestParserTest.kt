package com.example.twopchat.group.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GroupKeyRequestParserTest {
    @Test
    fun acceptsMaximumRequestedEpochCount() {
        val epochs = (1L..GroupControlFrames.MAX_REQUESTED_EPOCHS.toLong()).toList()

        val parsed = GroupControlFrames.parseKeyRequest(frame(epochs))

        assertEquals(epochs, parsed.requestedEpochs)
    }

    @Test
    fun rejectsRequestedEpochCountAboveMaximum() {
        val epochs = (1L..(GroupControlFrames.MAX_REQUESTED_EPOCHS + 1L)).toList()

        assertThrows(IllegalArgumentException::class.java) {
            GroupControlFrames.parseKeyRequest(frame(epochs))
        }
    }

    @Test
    fun rejectsNonPositiveAndDuplicateRequestedEpochs() {
        assertThrows(IllegalArgumentException::class.java) {
            GroupControlFrames.parseKeyRequest(frame(listOf(0L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupControlFrames.parseKeyRequest(frame(listOf(1L, 1L)))
        }
    }

    @Test
    fun parsingAndEncodingPreserveSignedCanonicalEpochSequence() {
        val epochs = listOf(9L, 2L, 5L)
        val parsed = GroupControlFrames.parseKeyRequest(frame(epochs))

        assertEquals(epochs, parsed.requestedEpochs)
        assertEquals("2,5,9", parsed.canonicalForSignature().lines()[5])
        assertEquals(epochs.sorted(), GroupControlFrames.keyRequestToJson(parsed)
            .getJSONArray("requested_epochs").toLongList())
    }

    private fun frame(epochs: List<Long>) = JSONObject().apply {
        put("type", GroupWireProtocol.TYPE_KEY_REQUEST)
        put("version", GroupWireProtocol.VERSION)
        put("request_id", "request-1")
        put("group_id", "group-1")
        put("requester_device_id", "device-1")
        put("requested_epochs", JSONArray().apply { epochs.forEach(::put) })
        put("created_at_ms", 1L)
        put("signature", "signature")
    }

    private fun JSONArray.toLongList(): List<Long> =
        (0 until length()).map { getLong(it) }
}
