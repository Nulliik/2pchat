package com.example.twopchat

import com.example.twopchat.data.cache.MessageCache
import com.example.twopchat.ui.chat.Message
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MessageCacheTest {

    @Before
    fun setUp() {
        MessageCache.clear()
    }

    @After
    fun tearDown() {
        MessageCache.clear()
    }

    private fun createDummyMessage(id: String, status: String = "SENT", isPinned: Boolean = false): Message {
        return Message(
            id = id,
            text = "Decrypted text for $id",
            isMe = true,
            timestamp = "12:00",
            attachmentType = null,
            attachmentUri = null,
            attachmentName = null,
            replyToId = null,
            replyToText = null,
            replyToName = null,
            status = status,
            reactions = emptyMap(),
            sentAtEpochMs = 1000L,
            isPinned = isPinned,
            albumMediaUris = emptyList(),
            albumMediaTypes = emptyList(),
        )
    }

    @Test
    fun testPutAndGet() {
        val msg = createDummyMessage("msg-1")
        MessageCache.put("msg-1", msg)

        val retrieved = MessageCache.get("msg-1")
        assertNotNull(retrieved)
        assertEquals(msg, retrieved)
        assertEquals("Decrypted text for msg-1", retrieved?.text)
    }

    @Test
    fun testInvalidateSingle() {
        val msg1 = createDummyMessage("msg-1")
        val msg2 = createDummyMessage("msg-2")
        MessageCache.put("msg-1", msg1)
        MessageCache.put("msg-2", msg2)

        MessageCache.invalidate("msg-1")

        assertNull(MessageCache.get("msg-1"))
        assertNotNull(MessageCache.get("msg-2"))
    }

    @Test
    fun testInvalidateAll() {
        val ids = (1..5).map { "msg-$it" }
        ids.forEach { MessageCache.put(it, createDummyMessage(it)) }
        assertEquals(5, MessageCache.size())

        MessageCache.invalidateAll(listOf("msg-1", "msg-3", "msg-5"))

        assertNull(MessageCache.get("msg-1"))
        assertNotNull(MessageCache.get("msg-2"))
        assertNull(MessageCache.get("msg-3"))
        assertNotNull(MessageCache.get("msg-4"))
        assertNull(MessageCache.get("msg-5"))
        assertEquals(2, MessageCache.size())
    }

    @Test
    fun testClear() {
        (1..10).forEach { MessageCache.put("msg-$it", createDummyMessage("msg-$it")) }
        assertEquals(10, MessageCache.size())

        MessageCache.clear()

        assertEquals(0, MessageCache.size())
        assertNull(MessageCache.get("msg-1"))
    }

    @Test
    fun testBoundedCapacityLruEviction() {
        val total = MessageCache.MAX_ENTRIES + 50
        for (i in 1..total) {
            MessageCache.put("msg-$i", createDummyMessage("msg-$i"))
        }

        assertTrue("Cache size must not exceed MAX_ENTRIES", MessageCache.size() <= MessageCache.MAX_ENTRIES)
        assertEquals(MessageCache.MAX_ENTRIES, MessageCache.size())

        // The first 50 oldest entries should have been evicted
        for (i in 1..50) {
            assertNull("Oldest entry msg-$i should be evicted", MessageCache.get("msg-$i"))
        }

        // Recent entries must still be present
        for (i in 51..total) {
            assertNotNull("Recent entry msg-$i must be retained", MessageCache.get("msg-$i"))
        }
    }

    @Test
    fun testConcurrentAccessThreadSafety() {
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)
        val errorCount = java.util.concurrent.atomic.AtomicInteger(0)

        for (threadIdx in 0 until 8) {
            executor.execute {
                try {
                    for (i in 0 until 200) {
                        val id = "msg-${threadIdx * 1000 + i}"
                        MessageCache.put(id, createDummyMessage(id))
                        val retrieved = MessageCache.get(id)
                        if (retrieved == null || retrieved.id != id) {
                            errorCount.incrementAndGet()
                        }
                        if (i % 5 == 0) {
                            MessageCache.invalidate(id)
                        }
                    }
                } catch (_: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(0, errorCount.get())
    }

    @Test
    fun testCopyOnStatusUpdatePreservesDecryptedContent() {
        val original = createDummyMessage("msg-1", status = "SENT", isPinned = false)
        MessageCache.put("msg-1", original)

        val cached = MessageCache.get("msg-1")
        assertNotNull(cached)

        // Status transition without re-decryption
        val updated = cached!!.copy(status = "DELIVERED", isPinned = true)
        MessageCache.put("msg-1", updated)

        val retrieved = MessageCache.get("msg-1")
        assertNotNull(retrieved)
        assertEquals("DELIVERED", retrieved?.status)
        assertTrue(retrieved?.isPinned == true)
        assertEquals(original.text, retrieved?.text)
        assertEquals(original.id, retrieved?.id)
    }
}
