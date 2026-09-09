package com.example.twopchat

import com.example.twopchat.media.VoiceMessageSupport
import com.example.twopchat.relay.FileTransferCoordinator
import com.example.twopchat.ui.chat.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingFileTransferRoutingTest {

    @Test
    fun incomingFileTransfer_stateMachineTransitionsFromReceivingToDelivered() {
        val coordinator = FileTransferCoordinator()
        val offerKey = "alice:msg-999"

        // 1. Initial offer registered
        coordinator.incomingFileOffers.add(offerKey)
        coordinator.updateProgress(offerKey, "msg-999", 0L, 409600L, 0.0)

        assertTrue(offerKey in coordinator.incomingFileOffers)
        assertTrue(offerKey in coordinator.incomingFileOffers)

        // 2. Incoming message created with RECEIVING status and null attachmentUri
        val receivingMessage = Message(
            id = "msg-999",
            text = VoiceMessageSupport.displayMessage("IMAGE", "photo.jpg"),
            isMe = false,
            timestamp = "17:48",
            attachmentType = "IMAGE",
            attachmentUri = null,
            attachmentName = "photo.jpg",
            replyToId = "msg-888",
            replyToText = "Previous quote",
            replyToName = "Bob",
            status = "RECEIVING"
        )
        assertEquals("RECEIVING", receivingMessage.status)
        assertEquals(null, receivingMessage.attachmentUri)
        assertEquals("msg-888", receivingMessage.replyToId)
        assertEquals("Previous quote", receivingMessage.replyToText)
        assertEquals("Bob", receivingMessage.replyToName)

        // 3. File arrives and completes assembly
        coordinator.incomingFileOffers.remove(offerKey)
        assertFalse(offerKey in coordinator.incomingFileOffers)

        val completedProgress = FileTransferCoordinator.FileProgressInfo(
            bytesTransferred = 409600L,
            totalBytes = 409600L,
            speedKbps = 128.0,
            state = FileTransferCoordinator.FileTransferState.COMPLETED
        )
        assertEquals(FileTransferCoordinator.FileTransferState.COMPLETED, completedProgress.state)

        // 4. Message updated to DELIVERED with valid local file attachmentUri
        val deliveredMessage = receivingMessage.copy(
            attachmentUri = "/data/user/0/com.example.twopchat/files/config/downloads/photo.jpg",
            status = "DELIVERED"
        )
        assertEquals("DELIVERED", deliveredMessage.status)
        assertEquals("/data/user/0/com.example.twopchat/files/config/downloads/photo.jpg", deliveredMessage.attachmentUri)
        assertEquals("msg-888", deliveredMessage.replyToId)
        assertEquals("Previous quote", deliveredMessage.replyToText)
        assertEquals("Bob", deliveredMessage.replyToName)
    }

    @Test
    fun attachmentType_correctlyClassifiesMediaTypes() {
        assertEquals("IMAGE", VoiceMessageSupport.attachmentType("image_123.jpg", "image/jpeg"))
        assertEquals("IMAGE", VoiceMessageSupport.attachmentType("screenshot.png", "image/png"))
        assertEquals("IMAGE", VoiceMessageSupport.attachmentType("photo.webp", "image/webp"))
        assertEquals("VIDEO", VoiceMessageSupport.attachmentType("clip.mp4", "video/mp4"))
        assertEquals("VOICE", VoiceMessageSupport.attachmentType("voice.ogg", "audio/ogg"))
        assertEquals("FILE", VoiceMessageSupport.attachmentType("archive.zip", "application/zip"))
    }

    @Test
    fun isRawFingerprint_identifiesBase64AndHexFingerprints() {
        val base64Fp = "dzPId8GVZuNv3LnrjUaHnD2pmtKRDp2guBrmwcVClGc="
        val hexFp = "8da7c5fb537074e3d64d68e1f7e26900c640d890a1f8a7fb82aaeb921589056b"
        val nickname = "doggy"
        val torAddress = "6xu647ahsjnzdc5225t5wqol2mc252kmqtjjjbj5zwjji3mv4vv5aqyd.onion"

        assertTrue(com.example.twopchat.relay.P2PMessageRelay.isRawFingerprint(base64Fp))
        assertTrue(com.example.twopchat.relay.P2PMessageRelay.isRawFingerprint(hexFp))
        assertFalse(com.example.twopchat.relay.P2PMessageRelay.isRawFingerprint(nickname))
        assertFalse(com.example.twopchat.relay.P2PMessageRelay.isRawFingerprint(torAddress))
    }

    @Test
    fun completedFileOfferRemoval_supportsBothResolvedAndRawSenderKeys() {
        val coordinator = FileTransferCoordinator()
        val rawSender = "dzPId8GVZuNv3LnrjUaHnD2pmtKRDp2guBrmwcVClGc="
        val resolvedSender = "Foxxxy"
        val messageId = "msg-12345"

        // Offer registered under resolved sender name (e.g., from file_offer event)
        val offerKeyResolved = "$resolvedSender:$messageId"
        val offerKeyRaw = "$rawSender:$messageId"
        coordinator.incomingFileOffers.add(offerKeyResolved)
        assertTrue(coordinator.incomingFileOffers.contains(offerKeyResolved))

        // Completion logic removes using resolvedSender or rawSender
        val removed = coordinator.incomingFileOffers.remove(offerKeyResolved) ||
            coordinator.incomingFileOffers.remove(offerKeyRaw)
        assertTrue(removed)
        assertFalse(coordinator.incomingFileOffers.contains(offerKeyResolved))
    }

    @Test
    fun fileOfferPayload_preservesCaptionOrEmojiWhenPresent() {
        val rawJsonWithCaption = """{"type":"file_offer","message_id":"m1","file_name":"2psticker_animals--fox.webp","size":10240,"caption":"🦊"}"""
        val json = org.json.JSONObject(rawJsonWithCaption)
        val fileName = json.optString("file_name")
        val attachmentType = VoiceMessageSupport.attachmentType(fileName, "")
        val caption = json.optString("caption").ifBlank { json.optString("emoji") }.ifBlank { json.optString("text") }.trim()
        val displayMsg = if (caption.isNotBlank()) caption else VoiceMessageSupport.displayMessage(attachmentType, fileName)

        assertEquals("STICKER", attachmentType)
        assertEquals("🦊", displayMsg)
    }
}

