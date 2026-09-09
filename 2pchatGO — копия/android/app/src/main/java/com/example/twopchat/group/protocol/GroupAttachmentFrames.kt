package com.example.twopchat.group.protocol

import org.json.JSONArray
import org.json.JSONObject

data class GroupAttachmentRequest(
    val requestId: String,
    val groupId: String,
    val eventId: String,
    val requesterDeviceId: String,
    val ciphertextCids: List<String>,
)

data class GroupAttachmentBlockFrame(
    val requestId: String,
    val groupId: String,
    val senderDeviceId: String,
    val ciphertextCid: String,
    val ciphertextBase64: String,
)

/**
 * Pairwise-authenticated exchange of opaque, content-addressed attachment
 * blocks. The manifest and content key remain inside the encrypted group event.
 */
object GroupAttachmentFrames {
    // Bound authenticated amplification to about 2 MiB per request.
    const val MAX_CIDS_PER_REQUEST = 4
    const val MAX_BLOCK_BASE64_CHARS = 720 * 1024

    fun requestToJson(request: GroupAttachmentRequest): JSONObject = JSONObject().apply {
        require(request.ciphertextCids.size in 1..MAX_CIDS_PER_REQUEST)
        require(request.ciphertextCids.distinct().size == request.ciphertextCids.size)
        put("type", GroupWireProtocol.TYPE_ATTACHMENT_REQUEST)
        put("version", GroupWireProtocol.VERSION)
        put("request_id", request.requestId.attachmentToken(128))
        put("group_id", request.groupId.attachmentToken(128))
        put("event_id", request.eventId.attachmentToken(128))
        put("requester_device_id", request.requesterDeviceId.attachmentToken(128))
        put("cids", JSONArray().apply {
            request.ciphertextCids.forEach { put(it.attachmentCid()) }
        })
    }.also { GroupWireProtocol.requireBoundedWire(it.toString()) }

    fun parseRequest(json: JSONObject): GroupAttachmentRequest {
        requireFrame(json, GroupWireProtocol.TYPE_ATTACHMENT_REQUEST)
        val array = json.optJSONArray("cids")
            ?: throw IllegalArgumentException("missing attachment CIDs")
        require(array.length() in 1..MAX_CIDS_PER_REQUEST)
        val cids = buildList {
            for (index in 0 until array.length()) {
                add(array.getString(index).attachmentCid())
            }
        }
        require(cids.distinct().size == cids.size)
        return GroupAttachmentRequest(
            requestId = json.getString("request_id").attachmentToken(128),
            groupId = json.getString("group_id").attachmentToken(128),
            eventId = json.getString("event_id").attachmentToken(128),
            requesterDeviceId = json.getString("requester_device_id").attachmentToken(128),
            ciphertextCids = cids,
        )
    }

    fun blockToJson(block: GroupAttachmentBlockFrame): JSONObject = JSONObject().apply {
        require(block.ciphertextBase64.length in 1..MAX_BLOCK_BASE64_CHARS)
        put("type", GroupWireProtocol.TYPE_ATTACHMENT_BLOCK)
        put("version", GroupWireProtocol.VERSION)
        put("request_id", block.requestId.attachmentToken(128))
        put("group_id", block.groupId.attachmentToken(128))
        put("sender_device_id", block.senderDeviceId.attachmentToken(128))
        put("cid", block.ciphertextCid.attachmentCid())
        put("ciphertext", block.ciphertextBase64)
    }.also { GroupWireProtocol.requireBoundedWire(it.toString()) }

    fun parseBlock(json: JSONObject): GroupAttachmentBlockFrame {
        GroupWireProtocol.requireBoundedWire(json.toString())
        requireFrame(json, GroupWireProtocol.TYPE_ATTACHMENT_BLOCK)
        val ciphertext = json.optString("ciphertext")
        require(ciphertext.length in 1..MAX_BLOCK_BASE64_CHARS)
        require(ciphertext.all {
            it.isLetterOrDigit() || it == '+' || it == '/' || it == '='
        })
        return GroupAttachmentBlockFrame(
            requestId = json.getString("request_id").attachmentToken(128),
            groupId = json.getString("group_id").attachmentToken(128),
            senderDeviceId = json.getString("sender_device_id").attachmentToken(128),
            ciphertextCid = json.getString("cid").attachmentCid(),
            ciphertextBase64 = ciphertext,
        )
    }

    private fun requireFrame(json: JSONObject, type: String) {
        GroupWireProtocol.requireBoundedWire(json.toString())
        require(json.optString("type") == type)
        require(json.optInt("version", -1) == GroupWireProtocol.VERSION)
    }
}

private fun String.attachmentCid(): String = lowercase().also {
    require(it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' })
}

private fun String.attachmentToken(maxLength: Int): String = also {
    require(it.isNotBlank() && it.length <= maxLength)
    require(it.all { char -> char.isLetterOrDigit() || char in "-_.:=" })
}
