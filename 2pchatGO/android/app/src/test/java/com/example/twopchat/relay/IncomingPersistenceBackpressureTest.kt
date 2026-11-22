package com.example.twopchat.relay

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class IncomingPersistenceBackpressureTest {
    @Test
    fun fullQueueBlocksProducerWithoutDroppingTasksAndPreservesFifo() {
        val queue = Channel<Int>(capacity = 1)
        assertTrue(queue.trySend(1).isSuccess)

        val started = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val producer = Thread {
            started.countDown()
            sendWithBackpressure(queue, 2)
            finished.set(true)
        }.apply { start() }

        assertTrue("producer did not start", started.await(1, TimeUnit.SECONDS))
        // The only slot is occupied: admission must suspend rather than drop.
        Thread.sleep(50)
        assertFalse("producer escaped a full queue", finished.get())

        assertEquals(1, queue.tryReceive().getOrThrow())
        assertTrue("producer did not resume after capacity became available",
            waitUntil(1_000) { finished.get() })
        assertEquals(2, queue.tryReceive().getOrThrow())
        producer.join(1_000)
        assertFalse("producer thread leaked", producer.isAlive)
        queue.close()
    }

    @Test
    fun closingQueueUnblocksSenderAndWipeDrainCanDiscardAcceptedTask() {
        val queue = Channel<Int>(capacity = 1)
        assertTrue(queue.trySend(1).isSuccess)
        val started = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val rejected = AtomicBoolean(false)
        val producer = Thread {
            started.countDown()
            try {
                sendWithBackpressure(queue, 2)
            } catch (_: Exception) {
                rejected.set(true)
            } finally {
                finished.set(true)
            }
        }.apply { start() }

        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertFalse("producer should be waiting on full queue", finished.get())
        queue.close()
        while (queue.tryReceive().isSuccess) { /* drain as account wipe does */ }
        assertTrue("closing and draining the queue must release blocked sender",
            waitUntil(1_000) { finished.get() })
        assertFalse(rejected.get())
        assertTrue(queue.tryReceive().isFailure)
        producer.join(1_000)
        assertFalse("producer thread leaked", producer.isAlive)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        return condition()
    }
}
