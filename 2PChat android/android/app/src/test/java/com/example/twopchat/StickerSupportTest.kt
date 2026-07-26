package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StickerSupportTest {
    @Test
    fun recognizesReservedStickerFileNames() {
        assertTrue(StickerSupport.isStickerFileName("2psticker_moods_love.webp"))
        assertTrue(StickerSupport.isStickerFileName("2PSTICKER_pack_item.WEBP"))
        assertFalse(StickerSupport.isStickerFileName("../photo.webp"))
        assertFalse(StickerSupport.isStickerFileName("2psticker_fake.png"))
        assertEquals(
            "2psticker_my_pack--hi_there.webp",
            StickerSupport.fileName(BuiltinSticker("My Pack", "Hi there", "👋", 0L)),
        )
        assertEquals(
            "my_pack",
            StickerSupport.packIdFromStickerFileName("2psticker_my_pack--hi_there.webp"),
        )
        assertEquals(
            "my_pack",
            StickerSupport.packIdFromArchiveFileName("2pstickerpack_my_pack.2psticker"),
        )
        assertTrue(StickerSupport.isStickerPackFileName("2pstickerpack_my_pack.2psticker"))
    }

    @Test
    fun parsesExtendedWebPDimensionsAndAnimationFlag() {
        val header = extendedWebP(width = 512, height = 321, animated = true)

        assertEquals(WebPInfo(512, 321, animated = true), StickerSupport.inspectWebP(header))
    }

    @Test
    fun rejectsOversizedOrForgedWebPHeaders() {
        assertNull(StickerSupport.inspectWebP(extendedWebP(width = 513, height = 512)))
        assertNull(StickerSupport.inspectWebP(ByteArray(30)))
    }

    @Test
    fun limitsStickerBurstsToThreePerRollingSecond() {
        val limiter = StickerSendRateLimiter()

        assertTrue(limiter.tryAcquire(1_000L))
        assertTrue(limiter.tryAcquire(1_100L))
        assertTrue(limiter.tryAcquire(1_200L))
        assertFalse(limiter.tryAcquire(1_999L))
        assertTrue(limiter.tryAcquire(2_000L))
    }

    @Test
    fun validatesAnimatedTimelineAndRejectsOverThreeSeconds() {
        val safeFile = File.createTempFile("2psticker_safe_", ".webp")
        val longFile = File.createTempFile("2psticker_long_", ".webp")
        try {
            safeFile.writeBytes(animatedWebP(durationMs = 3_000))
            longFile.writeBytes(animatedWebP(durationMs = 3_001))

            assertEquals(
                WebPInfo(512, 512, animated = true),
                StickerSupport.validateWebP(safeFile),
            )
            assertNull(StickerSupport.validateWebP(longFile))
        } finally {
            safeFile.delete()
            longFile.delete()
        }
    }

    @Test
    fun validatesStaticWebPUsingDeclaredRiffSize() {
        val valid = File.createTempFile("2psticker_static_", ".webp")
        val forged = File.createTempFile("2psticker_forged_", ".webp")
        try {
            val bytes = ByteArray(4_096).apply {
                extendedWebP(width = 512, height = 384).copyInto(this)
                putUInt32Le(4, size - 8)
            }
            valid.writeBytes(bytes)
            forged.writeBytes(bytes.copyOf().apply { putUInt32Le(4, size - 9) })

            assertEquals(
                WebPInfo(512, 384, animated = false),
                StickerSupport.validateWebP(valid),
            )
            assertNull(StickerSupport.validateWebP(forged))
        } finally {
            valid.delete()
            forged.delete()
        }
    }

    private fun extendedWebP(width: Int, height: Int, animated: Boolean = false): ByteArray =
        ByteArray(30).apply {
            putAscii(0, "RIFF")
            putAscii(8, "WEBP")
            putAscii(12, "VP8X")
            this[20] = if (animated) 0x02 else 0x00
            putUInt24Le(24, width - 1)
            putUInt24Le(27, height - 1)
        }

    private fun animatedWebP(durationMs: Int): ByteArray = ByteArray(68).apply {
        putAscii(0, "RIFF")
        putUInt32Le(4, size - 8)
        putAscii(8, "WEBP")
        putAscii(12, "VP8X")
        putUInt32Le(16, 10)
        this[20] = 0x02
        putUInt24Le(24, 511)
        putUInt24Le(27, 511)
        putAscii(30, "ANIM")
        putUInt32Le(34, 6)
        putAscii(44, "ANMF")
        putUInt32Le(48, 16)
        putUInt24Le(64, durationMs)
    }

    private fun ByteArray.putAscii(offset: Int, value: String) {
        value.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
    }

    private fun ByteArray.putUInt24Le(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
        this[offset + 2] = (value shr 16).toByte()
    }

    private fun ByteArray.putUInt32Le(offset: Int, value: Int) {
        putUInt24Le(offset, value)
        this[offset + 3] = (value shr 24).toByte()
    }
}
