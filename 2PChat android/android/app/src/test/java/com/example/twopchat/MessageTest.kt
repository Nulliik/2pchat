package com.example.twopchat

import com.example.twopchat.ui.chat.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageTest {
    @Test
    fun message_initiallyHasNullReplyToMetadata() {
        val msg = Message(
            id = "1",
            text = "Hello",
            isMe = true,
            timestamp = "12:00"
        )
        assertNull(msg.replyToId)
        assertNull(msg.replyToText)
        assertNull(msg.replyToName)
    }

    @Test
    fun message_storesReplyToMetadataCorrectly() {
        val msg = Message(
            id = "2",
            text = "Reply text",
            isMe = false,
            timestamp = "12:01",
            replyToId = "1",
            replyToText = "Hello",
            replyToName = "Alice"
        )
        assertEquals("1", msg.replyToId)
        assertEquals("Hello", msg.replyToText)
        assertEquals("Alice", msg.replyToName)
    }

    @Test
    fun message_storesStatusCorrectly() {
        val msg = Message(
            id = "3",
            text = "Status check",
            isMe = true,
            timestamp = "12:02",
            status = "PENDING"
        )
        assertEquals("PENDING", msg.status)
        
        val copied = msg.copy(status = "READ")
        assertEquals("READ", copied.status)
    }
}
