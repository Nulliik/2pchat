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
}
