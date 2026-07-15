package com.example.twopchat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatMediaTest {
    @Test
    fun sampledImageCacheSeparatesThumbnailAndFullscreenQuality() {
        val thumbnail = sampledImageCacheKey("image.jpg", 200, 200)
        val fullscreen = sampledImageCacheKey("image.jpg", 2048, 2048)

        assertNotEquals(thumbnail, fullscreen)
        assertEquals(thumbnail, sampledImageCacheKey("image.jpg", 200, 200))
    }
}
