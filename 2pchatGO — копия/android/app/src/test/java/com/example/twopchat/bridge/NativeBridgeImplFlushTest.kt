package com.example.twopchat.bridge

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeBridgeImplFlushTest {

    private val testPeerFP = "test_peer_fp_1234567890abcdef"

    @Test
    fun scenario1_retryThenSuccess_preservesOrderWithoutDuplicates() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { true }
        bridge.flushRetryDelayMs = 10L

        val delivered = mutableListOf<String>()
        val attemptCounts = mutableMapOf<String, AtomicInteger>()

        bridge.sendNow = { _, msg ->
            val count = attemptCounts.getOrPut(msg.payload) { AtomicInteger(0) }.incrementAndGet()
            if (msg.payload == "msg1" && count < 3) {
                // Fails on attempt 1 and 2, succeeds on 3rd attempt
                false
            } else {
                delivered.add(msg.payload)
                true
            }
        }

        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msg1"))
        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msg2"))
        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msg3"))

        bridge.flushPendingMessages(testPeerFP)
        advanceUntilIdle()

        assertEquals("All 3 messages must be delivered", listOf("msg1", "msg2", "msg3"), delivered)
        assertEquals("msg1 must be attempted 3 times", 3, attemptCounts["msg1"]?.get())
        assertEquals("msg2 must be attempted 1 time", 1, attemptCounts["msg2"]?.get())
        assertEquals("msg3 must be attempted 1 time", 1, attemptCounts["msg3"]?.get())
        assertEquals("Pending queue must be empty after full flush", 0, bridge.getPendingMessageCount(testPeerFP))

        bridge.shutdown()
    }

    @Test
    fun scenario2_allAttemptsFail_stopsAfterFiveAttemptsAndRetainsQueue() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { true }
        bridge.flushRetryDelayMs = 10L

        var totalAttempts = 0
        bridge.sendNow = { _, _ ->
            totalAttempts++
            false
        }

        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msg1"))
        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msg2"))
        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msg3"))

        bridge.flushPendingMessages(testPeerFP)
        advanceUntilIdle()

        assertEquals("Must stop after exactly MAX_FLUSH_ATTEMPTS (5) attempts", NativeBridgeImpl.MAX_FLUSH_ATTEMPTS, totalAttempts)
        assertEquals("Queue must retain all 3 messages on failure", 3, bridge.getPendingMessageCount(testPeerFP))

        bridge.shutdown()
    }

    @Test
    fun scenario3_consecutiveFlushCalls_executesOncePerMessage() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { true }
        bridge.flushRetryDelayMs = 10L

        val delivered = mutableListOf<String>()
        val sendCalls = AtomicInteger(0)

        bridge.sendNow = { _, msg ->
            sendCalls.incrementAndGet()
            delivered.add(msg.payload)
            true
        }

        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msgA"))
        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msgB"))

        // Trigger two consecutive flush calls
        bridge.flushPendingMessages(testPeerFP)
        bridge.flushPendingMessages(testPeerFP)
        advanceUntilIdle()

        assertEquals("sendNow must be called exactly 2 times (once per message)", 2, sendCalls.get())
        assertEquals("Delivered messages must match exactly", listOf("msgA", "msgB"), delivered)
        assertEquals("Queue must be empty", 0, bridge.getPendingMessageCount(testPeerFP))

        bridge.shutdown()
    }

    @Test
    fun scenario4_queueOverflow_dropsOldestWhenExceedingMaxLimit() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)

        for (i in 0 until NativeBridgeImpl.MAX_PENDING_PER_PEER + 5) {
            bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("msg_$i"))
        }

        assertEquals("Queue size must be capped at MAX_PENDING_PER_PEER (500)",
            NativeBridgeImpl.MAX_PENDING_PER_PEER, bridge.getPendingMessageCount(testPeerFP))

        bridge.shutdown()
    }

    @Test
    fun scenario5_expiredMessagesArePrunedDuringFlush() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { true }
        bridge.flushRetryDelayMs = 10L

        val delivered = mutableListOf<String>()
        bridge.sendNow = { _, msg ->
            delivered.add(msg.payload)
            true
        }

        val expiredTimestamp = System.currentTimeMillis() - (NativeBridgeImpl.MESSAGE_TTL_MS + 10_000L)
        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("expired_msg", timestampMs = expiredTimestamp))
        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("fresh_msg"))

        bridge.flushPendingMessages(testPeerFP)
        advanceUntilIdle()

        assertEquals("Only the fresh message should be sent", listOf("fresh_msg"), delivered)
        assertEquals("Queue should be empty after pruning expired and sending fresh", 0, bridge.getPendingMessageCount(testPeerFP))

        bridge.shutdown()
    }

    @Test
    fun scenario6_peerOffline_stopsRetriesImmediately() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.flushRetryDelayMs = 10L

        var attempts = 0
        bridge.isPeerOnlineForFlush = { false } // Peer went offline
        bridge.sendNow = { _, _ ->
            attempts++
            false
        }

        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("offline_msg1"))
        bridge.enqueuePending(testPeerFP, NativeBridgeImpl.PendingMessage("offline_msg2"))

        bridge.flushPendingMessages(testPeerFP)
        advanceUntilIdle()

        assertEquals("Must stop immediately after 1 attempt when peer is offline", 1, attempts)
        assertEquals("Queue must retain messages", 2, bridge.getPendingMessageCount(testPeerFP))

        bridge.shutdown()
    }
}
