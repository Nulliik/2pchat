package com.example.twopchat.media

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class StickerThumbnailCacheTest {

    private fun createDummyBitmap(): Bitmap {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    @Before
    fun setUp() {
        StickerThumbnailCache.clear()
    }

    @Test
    fun testGetReturnsNullWhenEmpty() {
        assertNull(StickerThumbnailCache.get("/some/uncached/path.webp"))
    }

    @Test
    fun testPutAndGetBitmap() {
        val bitmap = createDummyBitmap()
        val path = "/fake/sticker.webp"

        StickerThumbnailCache.put(path, bitmap)
        val cached = StickerThumbnailCache.get(path)

        assertNotNull(cached)
        assertSame(bitmap, cached)
    }

    @Test
    fun testClearEvictsAll() {
        val bitmap = createDummyBitmap()
        val path = "/fake/sticker2.webp"

        StickerThumbnailCache.put(path, bitmap)
        assertNotNull(StickerThumbnailCache.get(path))

        StickerThumbnailCache.clear()
        assertNull(StickerThumbnailCache.get(path))
    }

    @Test
    fun testLoadFirstFrameBlankPathReturnsNull() {
        assertNull(StickerThumbnailCache.loadFirstFrame(""))
        assertNull(StickerThumbnailCache.loadFirstFrame("   "))
    }

    @Test
    fun testLoadFirstFrameNonExistentFileReturnsNull() {
        assertNull(StickerThumbnailCache.loadFirstFrame("/non/existent/sticker.webp"))
    }
}
