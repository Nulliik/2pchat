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

    @Test
    fun verificationEmojisAreSymmetricForBothPeers() {
        val jiji = "5rSGKjQS1DnWdn1SmfsTLd93XmZTl2DVXUlr/QlNfgY="
        val foxy = "V4fj+FK9wjtpR13J+aU6IgDxYAlWwatYrA5FSLU+CC8="

        assertEquals(
            getVerificationEmojis(jiji, foxy),
            getVerificationEmojis(foxy, jiji),
        )
    }
}
