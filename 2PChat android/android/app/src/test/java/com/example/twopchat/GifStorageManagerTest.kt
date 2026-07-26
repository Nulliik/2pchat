package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Base64

class GifStorageManagerTest {
    @Test
    fun acceptsGif87aAndGif89aHeaders() {
        assertEquals(GifInfo(320, 240), GifStorageManager.inspectGifHeader(gifHeader("GIF87a", 320, 240)))
        assertEquals(GifInfo(1, 1), GifStorageManager.inspectGifHeader(gifHeader("GIF89a", 1, 1)))
    }

    @Test
    fun rejectsForgedAndOversizedGifHeaders() {
        assertNull(GifStorageManager.inspectGifHeader(gifHeader("NOTGIF", 320, 240)))
        assertNull(
            GifStorageManager.inspectGifHeader(
                gifHeader("GIF89a", GifStorageManager.MAX_DIMENSION + 1, 10),
            ),
        )
    }

    @Test
    fun validatesACompleteGifAndRejectsMissingTrailer() {
        val valid = File.createTempFile("twopchat_", ".gif")
        val truncated = File.createTempFile("twopchat_truncated_", ".gif")
        try {
            val bytes = Base64.getDecoder().decode(
                "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==",
            )
            valid.writeBytes(bytes)
            truncated.writeBytes(bytes.copyOf(bytes.lastIndex))

            assertEquals(GifInfo(1, 1), GifStorageManager.validateGif(valid))
            assertNull(GifStorageManager.validateGif(truncated))
        } finally {
            valid.delete()
            truncated.delete()
        }
    }

    private fun gifHeader(signature: String, width: Int, height: Int): ByteArray =
        ByteArray(10).apply {
            signature.forEachIndexed { index, char -> this[index] = char.code.toByte() }
            this[6] = width.toByte()
            this[7] = (width shr 8).toByte()
            this[8] = height.toByte()
            this[9] = (height shr 8).toByte()
        }
}
