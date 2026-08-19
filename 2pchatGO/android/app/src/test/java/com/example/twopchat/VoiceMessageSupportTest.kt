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
        assertEquals("IMAGE", VoiceMessageSupport.attachmentType("provider-generated-name", "image/jpeg"))
        assertEquals("FILE", VoiceMessageSupport.attachmentType("report.pdf", "application/pdf"))
        assertEquals("STICKER", VoiceMessageSupport.attachmentType("2psticker_moods_love.webp", "image/webp"))
        assertEquals("GIF", VoiceMessageSupport.attachmentType("animation.gif", "image/gif"))
        assertEquals(
            "STICKER_PACK",
            VoiceMessageSupport.attachmentType("2pstickerpack_moods.2psticker", "application/zip"),
        )
    }

    @Test
    fun addsMimeExtensionToExtensionlessProviderNames() {
        assertEquals(
            "provider-generated-name.jpg",
            VoiceMessageSupport.ensureMediaExtension("provider-generated-name", "image/jpeg"),
        )
        assertEquals(
            "photo.png",
            VoiceMessageSupport.ensureMediaExtension("../photo", "image/png"),
        )
        assertEquals(
            "already.webp",
            VoiceMessageSupport.ensureMediaExtension("already.webp", "image/jpeg"),
        )
    }

    @Test
    fun classifiesVideoByMimeOrExtension() {
        assertEquals("VIDEO", VoiceMessageSupport.attachmentType("movie.mp4", "video/mp4"))
        assertEquals("VIDEO", VoiceMessageSupport.attachmentType("clip.MOV", "application/octet-stream"))
    }

    @Test
    fun formatsDurationsForVoiceBubble() {
        assertEquals("0:00", VoiceMessageSupport.formatDuration(0))
        assertEquals("0:01", VoiceMessageSupport.formatDuration(1_501))
        assertEquals("1:05", VoiceMessageSupport.formatDuration(65_000))
    }
}
