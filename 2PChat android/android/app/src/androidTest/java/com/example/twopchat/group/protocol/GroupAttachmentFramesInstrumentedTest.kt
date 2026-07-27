package com.example.twopchat.group.protocol

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupAttachmentFramesInstrumentedTest {
    @Test
    fun requestRoundTripsWithBoundedDistinctCids() {
        val request = GroupAttachmentRequest(
            requestId = "request-1",
            groupId = "group-1",
            eventId = "event-1",
            requesterDeviceId = "device-1",
            ciphertextCids = listOf("a".repeat(64), "b".repeat(64)),
        )

        assertEquals(
            request,
            GroupAttachmentFrames.parseRequest(
                JSONObject(GroupAttachmentFrames.requestToJson(request).toString()),
            ),
        )
    }

    @Test
    fun requestRejectsDuplicatesInvalidCidsAndOversizedLists() {
        val duplicate = JSONObject()
            .put("type", GroupWireProtocol.TYPE_ATTACHMENT_REQUEST)
            .put("version", GroupWireProtocol.VERSION)
            .put("request_id", "request-1")
            .put("group_id", "group-1")
            .put("requester_device_id", "device-1")
            .put("cids", JSONArray().put("a".repeat(64)).put("a".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) {
            GroupAttachmentFrames.parseRequest(duplicate)
        }

        duplicate.put("cids", JSONArray().put("not-a-cid"))
        assertThrows(IllegalArgumentException::class.java) {
            GroupAttachmentFrames.parseRequest(duplicate)
        }

        duplicate.put("cids", JSONArray().apply {
            repeat(GroupAttachmentFrames.MAX_CIDS_PER_REQUEST + 1) { index ->
                put(index.toString(16).padStart(64, '0'))
            }
        })
        assertThrows(IllegalArgumentException::class.java) {
            GroupAttachmentFrames.parseRequest(duplicate)
        }
    }

    @Test
    fun opaqueBlockRoundTripsNearProductionChunkSize() {
        val bytes = ByteArray(512 * 1024 + 16) { (it % 251).toByte() }
        val frame = GroupAttachmentBlockFrame(
            requestId = "request-1",
            groupId = "group-1",
            senderDeviceId = "device-2",
            ciphertextCid = "c".repeat(64),
            ciphertextBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
        val json = GroupAttachmentFrames.blockToJson(frame)

        GroupWireProtocol.requireBoundedWire(json.toString())
        assertEquals(frame, GroupAttachmentFrames.parseBlock(JSONObject(json.toString())))
    }

    @Test
    fun blockRejectsOversizedOrMalformedBase64() {
        val base = JSONObject()
            .put("type", GroupWireProtocol.TYPE_ATTACHMENT_BLOCK)
            .put("version", GroupWireProtocol.VERSION)
            .put("request_id", "request-1")
            .put("group_id", "group-1")
            .put("sender_device_id", "device-2")
            .put("cid", "d".repeat(64))

        assertThrows(IllegalArgumentException::class.java) {
            GroupAttachmentFrames.parseBlock(
                JSONObject(base.toString()).put("ciphertext", "%%%not-base64%%%"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupAttachmentFrames.parseBlock(
                JSONObject(base.toString()).put(
                    "ciphertext",
                    "A".repeat(GroupAttachmentFrames.MAX_BLOCK_BASE64_CHARS + 1),
                ),
            )
        }
    }
}
