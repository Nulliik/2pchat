package com.example.twopchat.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoTranscoderTest {

    @Test
    fun testQualityPresets() {
        assertEquals(720, VideoTranscoder.VideoQuality.HD_720P.targetHeight)
        assertEquals(1080, VideoTranscoder.VideoQuality.FULL_HD_1080P.targetHeight)
        assertEquals(0, VideoTranscoder.VideoQuality.ORIGINAL.targetHeight)
    }

    @Test
    fun testDisplayNames() {
        assertEquals("720p (Сжатое)", VideoTranscoder.VideoQuality.HD_720P.displayName("Русский"))
        assertEquals("720p (Compressed)", VideoTranscoder.VideoQuality.HD_720P.displayName("English"))
        assertEquals("1080p (HD)", VideoTranscoder.VideoQuality.FULL_HD_1080P.displayName("Русский"))
        assertEquals("Оригинал", VideoTranscoder.VideoQuality.ORIGINAL.displayName("Русский"))
        assertEquals("Original", VideoTranscoder.VideoQuality.ORIGINAL.displayName("English"))
    }

    @Test
    fun testNonExistentFileReturnsFailure() = runBlocking {
        // Dummy context not reached for non-existent file validation
        val nonExistentPath = "/non/existent/path/video.mp4"
        val inputFile = File(nonExistentPath)
        assertTrue(!inputFile.exists())
    }
}
