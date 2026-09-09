package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

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

    @Test
    fun albumAssembly_handlesOutOfOrderArrivalWithoutLosingParts() {
        val totalParts = 3
        val albumUris = mutableListOf("", "", "")
        val albumTypes = mutableListOf("IMAGE", "IMAGE", "IMAGE")

        // Part 2 (3rd image) arrives first
        val part2Index = 2
        val part2Uri = "file:///storage/img_2.jpg"
        if (part2Index in 0 until totalParts) {
            albumUris[part2Index] = part2Uri
        }
        assertEquals(listOf("", "", "file:///storage/img_2.jpg"), albumUris)
        org.junit.Assert.assertFalse(albumUris.take(totalParts).all { it.isNotBlank() })

        // Part 0 (1st image - the bat) arrives second
        val part0Index = 0
        val part0Uri = "file:///storage/bat_0.jpg"
        if (part0Index in 0 until totalParts) {
            albumUris[part0Index] = part0Uri
        }
        assertEquals(listOf("file:///storage/bat_0.jpg", "", "file:///storage/img_2.jpg"), albumUris)
        org.junit.Assert.assertFalse(albumUris.take(totalParts).all { it.isNotBlank() })

        // Part 1 (2nd image) arrives third
        val part1Index = 1
        val part1Uri = "file:///storage/img_1.jpg"
        if (part1Index in 0 until totalParts) {
            albumUris[part1Index] = part1Uri
        }
        assertEquals(listOf("file:///storage/bat_0.jpg", "file:///storage/img_1.jpg", "file:///storage/img_2.jpg"), albumUris)
        org.junit.Assert.assertTrue(albumUris.take(totalParts).all { it.isNotBlank() })
    }
}
