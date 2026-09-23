package com.example.twopchat

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The NativeEvent channel(s) are the only path between Go JNI callbacks
 * and the Kotlin domain layer. A misbehaving listener must not take the
 * pipeline down: both ordinary exceptions and CancellationExceptions thrown
 * by handlers are isolated, and the next event is still dispatched.
 * (This is the contract RelayCancellationTest used to pin down for the old
 * per-event dispatcher.)
 */
class NativeEventDispatchTest {

    @Test
    fun handlerExceptionDoesNotStopPipeline() {
        assertPipelineSurvives { throw IllegalStateException("boom") }
    }

    @Test
    fun handlerCancellationDoesNotStopPipeline() {
        assertPipelineSurvives { throw CancellationException("cancelled callback") }
    }

    @Test
    fun reliableLaneBackpressuresWithoutDroppingEvents() {
        val enteredHandler = CountDownLatch(1)
        val releaseHandler = CountDownLatch(1)
        val producerDone = CountDownLatch(1)
        val delivered = AtomicInteger()
        val old = NativeBridge.onPeerDiscoveredListener
        NativeBridge.onPeerDiscoveredListener = { hash, _, _ ->
            if (hash.startsWith("backpressure-")) {
                if (hash == "backpressure-0") {
                    enteredHandler.countDown()
                    assertTrue("test must release the stalled consumer", releaseHandler.await(5, TimeUnit.SECONDS))
                }
                delivered.incrementAndGet()
            }
        }
        try {
            val producer = Thread {
                // merge() adds a small internal buffer beyond the reliable lane.
                repeat(400) { NativeBridge.onPeerDiscovered("backpressure-$it", "", "test") }
                producerDone.countDown()
            }
            producer.start()
            assertTrue("first reliable event must reach the consumer", enteredHandler.await(5, TimeUnit.SECONDS))
            assertFalse(
                "producer must be back-pressured once the reliable lane is full",
                producerDone.await(250, TimeUnit.MILLISECONDS),
            )
            releaseHandler.countDown()
            assertTrue("all reliable events must be accepted after drain", producerDone.await(5, TimeUnit.SECONDS))
            assertTrue("reliable events must not be dropped", waitForDelivery(delivered, 400))
        } finally {
            releaseHandler.countDown()
            NativeBridge.onPeerDiscoveredListener = old
        }
    }

    private fun assertPipelineSurvives(failure: () -> Nothing) {
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val old = NativeBridge.onPeerDiscoveredListener
        NativeBridge.onPeerDiscoveredListener = { hash, _, _ ->
            if (hash == "boom") {
                first.countDown()
                failure()
            }
            second.countDown()
        }
        try {
            NativeBridge.onPeerDiscovered("boom", "", "test")
            assertTrue("failing event must be dispatched", first.await(5, TimeUnit.SECONDS))
            NativeBridge.onPeerDiscovered("after", "", "test")
            assertTrue("event after a handler failure must still be dispatched",
                second.await(5, TimeUnit.SECONDS))
        } finally {
            NativeBridge.onPeerDiscoveredListener = old
        }
    }

    private fun waitForDelivery(delivered: AtomicInteger, expected: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (delivered.get() == expected) return true
            Thread.sleep(10)
        }
        return delivered.get() == expected
    }
}
