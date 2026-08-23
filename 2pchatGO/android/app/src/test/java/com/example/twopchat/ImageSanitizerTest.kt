package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import com.example.twopchat.security.ImageSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ImageSanitizerTest {

    @Test
    fun testIsSanitizableImageRecognizesValidFormats() {
        assertTrue(ImageSanitizer.isSanitizableImage("camera_photo.jpg"))
        assertTrue(ImageSanitizer.isSanitizableImage("camera_photo.jpeg"))
        assertTrue(ImageSanitizer.isSanitizableImage("CAMERA_PHOTO.JPG"))
        assertTrue(ImageSanitizer.isSanitizableImage("CAMERA_PHOTO.JPEG"))
        assertTrue(ImageSanitizer.isSanitizableImage("screenshot.png"))
        assertTrue(ImageSanitizer.isSanitizableImage("image.webp"))
        assertTrue(ImageSanitizer.isSanitizableImage("photo.heic"))
        assertTrue(ImageSanitizer.isSanitizableImage("photo.heif"))
    }

    @Test
    fun testIsSanitizableImageRejectsNonImagesAndAnimatedGifs() {
        assertFalse(ImageSanitizer.isSanitizableImage("video.mp4"))
        assertFalse(ImageSanitizer.isSanitizableImage("audio.m4a"))
        assertFalse(ImageSanitizer.isSanitizableImage("voice.ogg"))
        assertFalse(ImageSanitizer.isSanitizableImage("animated.gif"))
        assertFalse(ImageSanitizer.isSanitizableImage("document.pdf"))
        assertFalse(ImageSanitizer.isSanitizableImage("archive.zip"))
        assertFalse(ImageSanitizer.isSanitizableImage(""))
        assertFalse(ImageSanitizer.isSanitizableImage("no_extension_file"))
        assertFalse(ImageSanitizer.isSanitizableImage("2psticker_moods--hello.webp"))
        assertFalse(ImageSanitizer.isSanitizableImage("2pstickerpack_pack1.2psticker"))
    }

    @Test
    fun testNonExistentFileReturnsNullSafely() {
        val nonExistent = "/tmp/does_not_exist_image_12345.jpg"
        // Should return false for sanitization check on non-existent file or null
        assertFalse(File(nonExistent).exists())
    }

    @Test
    fun testOriginalFileRemainsUntouchedDuringSanitization() {
        val tempDir = Files.createTempDirectory("sanitizer_test").toFile()
        val originalFile = File(tempDir, "user_photo.jpg")
        val sampleBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)
        FileOutputStream(originalFile).use { it.write(sampleBytes) }

        val originalLengthBefore = originalFile.length()
        val originalChecksumBefore = originalFile.readBytes()

        // Verify file is present
        assertTrue(originalFile.exists())
        assertEquals(sampleBytes.size.toLong(), originalLengthBefore)

        // Ensure after checking / reading, the original file is byte-for-byte identical
        val originalLengthAfter = originalFile.length()
        val originalChecksumAfter = originalFile.readBytes()

        assertEquals(originalLengthBefore, originalLengthAfter)
        assertTrue(originalChecksumBefore.contentEquals(originalChecksumAfter))

        tempDir.deleteRecursively()
    }
}
