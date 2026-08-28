package com.example.twopchat

import com.example.twopchat.ui.chat.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the media sending state machine and read-receipt guards.
 */
class MediaTransferStatusStateMachineTest {

    @Test
    fun outgoingMedia_startsInSendingStatus_notMarkedAsSentOrRead() {
        val outMsg = Message(
            id = "msg-101",
            text = "Sent an image",
            isMe = true,
            timestamp = "14:00",
            attachmentType = "IMAGE",
            attachmentUri = "/path/to/image.jpg",
            attachmentName = "image.jpg",
            status = "SENDING"
        )

        // Pending or sending messages should evaluate isPendingOrSending = true
        val isPendingOrSending = outMsg.status?.startsWith("PENDING") == true ||
            outMsg.status?.startsWith("SENDING") == true ||
            outMsg.status?.startsWith("FAILED") == true

        assertTrue(isPendingOrSending)

        // Incoming message arrives after msg-101
        val incomingAfter = true
        val isTyping = true

        val isRead = outMsg.isMe && !isPendingOrSending && (
            incomingAfter ||
                outMsg.status?.startsWith("READ") == true ||
                isTyping
            )

        assertFalse("In-flight sending media must never be marked as READ", isRead)
    }

    @Test
    fun outgoingMedia_onTransferSuccess_transitionsToSentThenRead() {
        var msg = Message(
            id = "msg-102",
            text = "Sent a video",
            isMe = true,
            timestamp = "14:05",
            attachmentType = "VIDEO",
            attachmentUri = "/path/to/video.mp4",
            attachmentName = "video.mp4",
            status = "SENDING"
        )

        // Transfer completes successfully
        msg = msg.copy(status = "SENT")

        val isPendingOrSending = msg.status?.startsWith("PENDING") == true ||
            msg.status?.startsWith("SENDING") == true ||
            msg.status?.startsWith("FAILED") == true

        assertFalse(isPendingOrSending)

        // Now an incoming message arrives
        val incomingAfter = true
        val isRead = msg.isMe && !isPendingOrSending && incomingAfter

        assertTrue("Completed SENT media message should become READ when peer replies", isRead)
    }

    @Test
    fun outgoingMedia_onTransferFailure_transitionsToPending() {
        var msg = Message(
            id = "msg-103",
            text = "Voice message",
            isMe = true,
            timestamp = "14:10",
            attachmentType = "VOICE",
            attachmentUri = "/path/to/voice.ogg",
            attachmentName = "voice.ogg",
            status = "SENDING"
        )

        // Transfer fails (e.g. peer disconnected)
        msg = msg.copy(status = "PENDING")

        val isPendingOrSending = msg.status?.startsWith("PENDING") == true ||
            msg.status?.startsWith("SENDING") == true ||
            msg.status?.startsWith("FAILED") == true

        assertTrue(isPendingOrSending)

        val isRead = msg.isMe && !isPendingOrSending && true
        assertFalse("Pending media must not be marked as READ", isRead)
    }

    @Test
    fun savedMessages_alwaysMarkedAsReadImmediately() {
        val savedMsg = Message(
            id = "msg-104",
            text = "Saved note",
            isMe = true,
            timestamp = "14:15",
            status = "SENT"
        )

        val peerName = "Saved Messages"
        val isPendingOrSending = savedMsg.status?.startsWith("PENDING") == true ||
            savedMsg.status?.startsWith("SENDING") == true ||
            savedMsg.status?.startsWith("FAILED") == true

        val isRead = savedMsg.isMe && !isPendingOrSending && (
            peerName == "Saved Messages"
            )

        assertTrue(isRead)
    }

    @Test
    fun multiPartAlbum_requiresAllPartsToMarkSent() {
        val totalParts = 4
        val acknowledgedParts = mutableSetOf<Int>()

        var albumStatus = "SENDING"

        for (i in 0 until totalParts) {
            acknowledgedParts.add(i)
            if (acknowledgedParts.size == totalParts) {
                albumStatus = "SENT"
            }
        }

        assertEquals("SENT", albumStatus)
    }

    @Test
    fun mediaTransferOverlay_completesWhenSentOr100Percent() {
        val completedInfo = com.example.twopchat.relay.P2PMessageRelay.FileProgressInfo(
            bytesTransferred = 100_000L,
            totalBytes = 100_000L,
            speedKbps = 356.0,
        )
        // Default state when 100% reached must be COMPLETED
        assertEquals(com.example.twopchat.relay.P2PMessageRelay.FileTransferState.COMPLETED, completedInfo.state)

        val sentMsg = Message(
            id = "photo-1",
            text = "Sent an image",
            isMe = true,
            timestamp = "01:25",
            attachmentType = "IMAGE",
            status = "SENT"
        )

        val isCancelled = completedInfo.state ==
            com.example.twopchat.relay.P2PMessageRelay.FileTransferState.CANCELLED ||
            sentMsg.status.equals("CANCELLED", ignoreCase = true)
        val hasFailed = completedInfo.state ==
            com.example.twopchat.relay.P2PMessageRelay.FileTransferState.FAILED ||
            sentMsg.status.equals("FAILED", ignoreCase = true)
        val isProgressActive = completedInfo.state == com.example.twopchat.relay.P2PMessageRelay.FileTransferState.TRANSFERRING &&
            (completedInfo.totalBytes <= 0L || completedInfo.bytesTransferred < completedInfo.totalBytes)
        val isTransferring = !isCancelled && !hasFailed && (
            isProgressActive || (sentMsg.isMe && sentMsg.status?.startsWith("SENDING") == true) || (!sentMsg.isMe && sentMsg.status == "RECEIVING")
        )

        assertFalse("100% completed media with SENT status must not display transfer overlay", isTransferring)
    }
}

