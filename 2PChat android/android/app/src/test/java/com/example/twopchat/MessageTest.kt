package com.example.twopchat

import com.example.twopchat.ui.chat.Message
import com.example.twopchat.ui.chat.getVerificationEmojis
import com.example.twopchat.ui.chat.MessageTimestampFormatter
import com.example.twopchat.ui.chat.MessageDeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

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

    @Test
    fun verificationEmojis_areStableForBothSidesOfIdentityPair() {
        val aliceView = getVerificationEmojis("alice-fingerprint", "bob-fingerprint")
        val bobView = getVerificationEmojis("bob-fingerprint", "alice-fingerprint")

        assertEquals(aliceView, bobView)
        assertNotEquals(aliceView, getVerificationEmojis("alice-fingerprint", "mallory-fingerprint"))
    }

    @Test
    fun timestampFormatter_distinguishesTodayYesterdayAndOlderDates() {
        val utc = TimeZone.getTimeZone("UTC")
        fun epoch(day: Int, hour: Int, minute: Int): Long = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.JULY, day, hour, minute)
        }.timeInMillis
        val now = epoch(13, 15, 30)
        fun messageAt(day: Int, hour: Int) = Message(
            id = "$day-$hour",
            text = "test",
            isMe = true,
            timestamp = "legacy",
            sentAtEpochMs = epoch(day, hour, 5),
        )

        assertEquals("14:05", MessageTimestampFormatter.format(messageAt(13, 14), "English", now, utc))
        assertEquals("14:05", MessageTimestampFormatter.format(messageAt(12, 14), "Русский", now, utc))
        assertEquals("14:05", MessageTimestampFormatter.format(messageAt(10, 14), "English", now, utc))
        assertEquals("Вчера", MessageTimestampFormatter.formatDateHeader(epoch(12, 14, 5), "Русский", now, utc))
    }

    @Test
    fun deliveryStatus_preservesEditedMarkerAcrossReadReceipt() {
        assertEquals("READ_edited", MessageDeliveryStatus.merge("SENT_edited", "READ"))
        assertEquals("READ", MessageDeliveryStatus.merge("SENT", "READ"))
    }
}
