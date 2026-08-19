package com.example.twopchat

import com.example.twopchat.bridge.NativeBridgeImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class NativeBridgePendingMessageTest {

    @Test
    fun testPendingMessageTtlConstant() {
        assertEquals("TTL must be 5 minutes (300,000 ms)", 5 * 60 * 1000L, NativeBridgeImpl.MESSAGE_TTL_MS)
    }

    @Test
    fun testPendingMessageExpiration() {
        val now = 1_000_000_000L
        val freshMessage = NativeBridgeImpl.PendingMessage(payload = "Hello Fresh", timestampMs = now)

        // Fresh message after 10 seconds is NOT expired
        assertFalse("Message after 10s should not be expired", freshMessage.isExpired(ttlMs = NativeBridgeImpl.MESSAGE_TTL_MS, now = now + 10_000L))

        // Fresh message after 4 minutes 59 seconds is NOT expired
        assertFalse("Message after 4m 59s should not be expired", freshMessage.isExpired(ttlMs = NativeBridgeImpl.MESSAGE_TTL_MS, now = now + 299_000L))

        // Message at exactly 5 minutes + 1 ms IS expired
        assertTrue("Message at 5m + 1ms should be expired", freshMessage.isExpired(ttlMs = NativeBridgeImpl.MESSAGE_TTL_MS, now = now + 300_001L))

        // Message after 1 hour IS expired
        assertTrue("Message after 1h should be expired", freshMessage.isExpired(ttlMs = NativeBridgeImpl.MESSAGE_TTL_MS, now = now + 3_600_000L))
    }

    @Test
    fun testQueuePruningExpiredMessages() {
        val now = 1_000_000_000L
        val queue = ConcurrentLinkedQueue<NativeBridgeImpl.PendingMessage>()

        // Add 2 stale messages (older than 5 minutes) and 2 fresh messages
        val stale1 = NativeBridgeImpl.PendingMessage(payload = "Stale 1", timestampMs = now - 400_000L) // ~6.6 min old
        val stale2 = NativeBridgeImpl.PendingMessage(payload = "Stale 2", timestampMs = now - 350_000L) // ~5.8 min old
        val fresh1 = NativeBridgeImpl.PendingMessage(payload = "Fresh 1", timestampMs = now - 60_000L)  // 1 min old
        val fresh2 = NativeBridgeImpl.PendingMessage(payload = "Fresh 2", timestampMs = now - 5_000L)   // 5 sec old

        queue.add(stale1)
        queue.add(stale2)
        queue.add(fresh1)
        queue.add(fresh2)

        assertEquals(4, queue.size)

        // Simulate TTL pruning logic
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.isExpired(ttlMs = NativeBridgeImpl.MESSAGE_TTL_MS, now = now)) {
                iterator.remove()
            }
        }

        // Only the 2 fresh messages should remain
        assertEquals(2, queue.size)
        val remaining = queue.toList()
        assertEquals("Fresh 1", remaining[0].payload)
        assertEquals("Fresh 2", remaining[1].payload)
    }
}
