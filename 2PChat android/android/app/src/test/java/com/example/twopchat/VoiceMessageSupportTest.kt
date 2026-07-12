package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceMessageSupportTest {
    @Test
    fun classifiesVoiceByMimeOrExtension() {
        assertEquals("VOICE", VoiceMessageSupport.attachmentType("recording.bin", "audio/mp4"))
        assertEquals("VOICE", VoiceMessageSupport.attachmentType("recording.M4A", "application/octet-stream"))
        assertEquals("VOICE", VoiceMessageSupport.attachmentType("voice.opus", ""))
    }

    @Test
    fun keepsImagesAndDocumentsDistinct() {
        assertEquals("IMAGE", VoiceMessageSupport.attachmentType("photo.jpg", "application/octet-stream"))
        assertEquals("FILE", VoiceMessageSupport.attachmentType("report.pdf", "application/pdf"))
    }

    @Test
    fun formatsDurationsForVoiceBubble() {
        assertEquals("0:00", VoiceMessageSupport.formatDuration(0))
        assertEquals("0:01", VoiceMessageSupport.formatDuration(1_501))
        assertEquals("1:05", VoiceMessageSupport.formatDuration(65_000))
    }
}
