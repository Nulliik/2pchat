package com.example.twopchat

import com.example.twopchat.media.AnimatedStickerConverter
import com.example.twopchat.media.SimpleGifDecoder
import com.example.twopchat.media.StickerSupport
import com.example.twopchat.media.WebPInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class AnimatedStickerConverterTest {

    @Test
    fun detectsMp4FilesCorrectly() {
        val mp4File = File.createTempFile("test_", ".mp4")
        val otherFile = File.createTempFile("test_", ".dat")
        try {
            // ISO BMFF with ftyp box
            val mp4Header = ByteArray(16).apply {
                this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 16 // size 16
                this[4] = 'f'.code.toByte()
                this[5] = 't'.code.toByte()
                this[6] = 'y'.code.toByte()
                this[7] = 'p'.code.toByte()
                this[8] = 'i'.code.toByte()
                this[9] = 's'.code.toByte()
                this[10] = 'o'.code.toByte()
                this[11] = 'm'.code.toByte()
            }
            mp4File.writeBytes(mp4Header)
            otherFile.writeBytes(ByteArray(16))

            assertTrue(AnimatedStickerConverter.isMp4(mp4File))
            assertFalse(AnimatedStickerConverter.isMp4(otherFile))
            assertTrue(StickerSupport.isMp4(mp4File))
            assertFalse(StickerSupport.isMp4(otherFile))
        } finally {
            mp4File.delete()
            otherFile.delete()
        }
    }

    @Test
    fun detectsGifFilesCorrectly() {
        val gifFile = File.createTempFile("test_", ".gif")
        val textFile = File.createTempFile("test_", ".txt")
        try {
            gifFile.writeBytes("GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(10))
            textFile.writeBytes("HELLO WORLD".toByteArray(Charsets.US_ASCII))

            assertTrue(AnimatedStickerConverter.isGif(gifFile))
            assertFalse(AnimatedStickerConverter.isGif(textFile))
            assertTrue(StickerSupport.isGif(gifFile))
            assertFalse(StickerSupport.isGif(textFile))
        } finally {
            gifFile.delete()
            textFile.delete()
        }
    }

    @Test
    fun detectsAnimatedWebPCorrectly() {
        val animFile = File.createTempFile("test_anim_", ".webp")
        val staticFile = File.createTempFile("test_static_", ".webp")
        try {
            val animHeader = ByteArray(30).apply {
                putAscii(0, "RIFF")
                putUInt32Le(4, 22)
                putAscii(8, "WEBP")
                putAscii(12, "VP8X")
                putUInt32Le(16, 10)
                this[20] = 0x02 // animated bit set
                putUInt24Le(24, 255)
                putUInt24Le(27, 255)
            }
            val staticHeader = ByteArray(30).apply {
                putAscii(0, "RIFF")
                putUInt32Le(4, 22)
                putAscii(8, "WEBP")
                putAscii(12, "VP8X")
                putUInt32Le(16, 10)
                this[20] = 0x00 // static
                putUInt24Le(24, 255)
                putUInt24Le(27, 255)
            }
            animFile.writeBytes(animHeader)
            staticFile.writeBytes(staticHeader)

            assertTrue(AnimatedStickerConverter.isAnimatedWebP(animFile))
            assertFalse(AnimatedStickerConverter.isAnimatedWebP(staticFile))
            assertTrue(AnimatedStickerConverter.isAnimatedSource(animFile))
        } finally {
            animFile.delete()
            staticFile.delete()
        }
    }

    @Test
    fun extractsFrameDataFromSingleWebP() {
        // Construct a single-frame lossless WebP: RIFF + VP8L chunk
        val vp8lPayload = ByteArray(10) { (it + 1).toByte() }
        val webpStream = ByteArrayOutputStream()
        webpStream.write("RIFF".toByteArray(Charsets.US_ASCII))
        val riffSize = 4 + 8 + vp8lPayload.size // "WEBP" + "VP8L" + size + payload
        webpStream.writeUInt32Le(riffSize.toLong())
        webpStream.write("WEBP".toByteArray(Charsets.US_ASCII))
        webpStream.write("VP8L".toByteArray(Charsets.US_ASCII))
        webpStream.writeUInt32Le(vp8lPayload.size.toLong())
        webpStream.write(vp8lPayload)

        val webpBytes = webpStream.toByteArray()
        val extracted = AnimatedStickerConverter.extractFrameData(webpBytes)
        assertNotNull(extracted)

        // Extracted data must start with "VP8L" and include payload
        assertEquals("VP8L", String(extracted!!, 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun muxFrameDataToAnimatedWebPProducesCompliantWebP() {
        // Construct dummy VP8L frame payload (16 bytes)
        val vp8lPayload = ByteArray(16) { 0x2f.toByte() }
        val frameChunk = ByteArrayOutputStream().apply {
            write("VP8L".toByteArray(Charsets.US_ASCII))
            writeUInt32Le(vp8lPayload.size.toLong())
            write(vp8lPayload)
        }.toByteArray()

        val frames = listOf(
            frameChunk to 100,
            frameChunk to 150,
            frameChunk to 200,
        )

        val animatedBytes = AnimatedStickerConverter.muxFrameDataToAnimatedWebP(
            canvasWidth = 256,
            canvasHeight = 256,
            hasAlpha = true,
            frames = frames,
        )

        val tempFile = File.createTempFile("mux_test_", ".webp")
        try {
            tempFile.writeBytes(animatedBytes)
            val info = StickerSupport.validateWebP(tempFile)
            assertNotNull("Muxed animated WebP must pass StickerSupport.validateWebP", info)
            assertEquals(256, info?.width)
            assertEquals(256, info?.height)
            assertTrue(info?.animated == true)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun simpleGifDecoderParsesValidGif89aStream() {
        val gifBytes = createMinimalTwoFrameGif(width = 4, height = 4)
        val rawFrames = SimpleGifDecoder.decodeRawFrames(
            ByteArrayInputStream(gifBytes),
            maxDurationMs = 3000L,
        )
        assertNotNull(rawFrames)
        assertEquals(2, rawFrames!!.size)
        assertEquals(4, rawFrames[0].width)
        assertEquals(4, rawFrames[0].height)
        assertEquals(100, rawFrames[0].delayMs)
        assertEquals(150, rawFrames[1].delayMs)

        // Also verify decodeStream handles headless JVM safely
        val decodedBitmaps = SimpleGifDecoder.decodeStream(
            ByteArrayInputStream(gifBytes),
            maxDimension = 512,
            maxDurationMs = 3000L,
        )
        // In headless mockable jar, Bitmap.createBitmap returns null so decodedBitmaps is null or empty,
        // but no NPE is thrown
        assertTrue(decodedBitmaps == null || decodedBitmaps.size <= 2)
    }

    private fun createMinimalTwoFrameGif(width: Int, height: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Screen width, height
        bos.write(width and 0xff); bos.write((width shr 8) and 0xff)
        bos.write(height and 0xff); bos.write((height shr 8) and 0xff)
        // GCT flag = 1, 2 colors (size 0 -> 2^(0+1) = 2)
        bos.write(0x80)
        bos.write(0) // bg index
        bos.write(0) // aspect ratio
        // GCT: 2 colors (Black, White)
        bos.write(byteArrayOf(0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()))

        // Frame 1
        // Graphic Control Extension (delay 10 = 100ms)
        bos.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00))
        // Image Descriptor
        bos.write(0x2C)
        bos.write(0); bos.write(0); bos.write(0); bos.write(0) // left, top
        bos.write(width and 0xff); bos.write((width shr 8) and 0xff)
        bos.write(height and 0xff); bos.write((height shr 8) and 0xff)
        bos.write(0x00) // no local color table
        // LZW min code size = 2
        bos.write(0x02)
        // Sub-block with clear code & EOI
        bos.write(byteArrayOf(0x02, 0x04, 0x01, 0x00))

        // Frame 2
        bos.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 0x0F, 0x00, 0x00, 0x00))
        bos.write(0x2C)
        bos.write(0); bos.write(0); bos.write(0); bos.write(0)
        bos.write(width and 0xff); bos.write((width shr 8) and 0xff)
        bos.write(height and 0xff); bos.write((height shr 8) and 0xff)
        bos.write(0x00)
        bos.write(0x02)
        bos.write(byteArrayOf(0x02, 0x04, 0x01, 0x00))

        // Trailer
        bos.write(0x3B)
        return bos.toByteArray()
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

    private fun ByteArrayOutputStream.writeUInt32Le(value: Long) {
        write((value and 0xffL).toInt())
        write(((value shr 8) and 0xffL).toInt())
        write(((value shr 16) and 0xffL).toInt())
        write(((value shr 24) and 0xffL).toInt())
    }
}
