package com.example.twopchat

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
}
