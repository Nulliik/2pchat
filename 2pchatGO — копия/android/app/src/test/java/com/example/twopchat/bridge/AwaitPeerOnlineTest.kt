package com.example.twopchat.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitPeerOnlineTest {

    private val testPeerFP = "peer_fp_deadbeef12345678"

    @Test
    fun peerOnlineInitially_returnsTrueImmediatelyWithoutDelay() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { true }

        val isOnline = bridge.awaitPeerOnline(testPeerFP, 800L)

        assertTrue("Should return true immediately when peer is already online", isOnline)
        bridge.shutdown()
    }

    @Test
    fun peerConnectsAfter300ms_returnsTrueBeforeTimeout() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { false }

        val waitJob = async {
            bridge.awaitPeerOnline(testPeerFP, 800L)
        }

        launch {
            delay(300L)
            bridge.signalPeerConnected(testPeerFP)
        }

        advanceTimeBy(301L)
        assertTrue("awaitPeerOnline should complete after signal at 300ms", waitJob.isCompleted)
        assertTrue("Result should be true after peer connected signal", waitJob.await())

        bridge.shutdown()
    }

    @Test
    fun noEventReceived_returnsFalseAfterTimeout() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { false }

        val waitJob = async {
            bridge.awaitPeerOnline(testPeerFP, 800L)
        }

        advanceTimeBy(799L)
        assertFalse("Should still be waiting before timeout", waitJob.isCompleted)

        advanceTimeBy(100L) // virtual time reaches 899ms (> 800ms)
        assertTrue("Should be completed after timeout", waitJob.isCompleted)
        assertFalse("Should return false when timeout expires without connection", waitJob.await())

        bridge.shutdown()
    }

    @Test
    fun multipleWaiters_allResumeWhenPeerConnects() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { false }

        val waitJob1 = async { bridge.awaitPeerOnline(testPeerFP, 800L) }
        val waitJob2 = async { bridge.awaitPeerOnline(testPeerFP, 800L) }

        launch {
            delay(200L)
            bridge.signalPeerConnected(testPeerFP)
        }

        advanceUntilIdle()

        assertTrue("Waiter 1 should complete with true", waitJob1.await())
        assertTrue("Waiter 2 should complete with true", waitJob2.await())

        bridge.shutdown()
    }

    @Test
    fun shutdownCancelsWaiters_safelyReturnsFalse() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val bridge = NativeBridgeImpl(coroutineContext = testDispatcher)
        bridge.isPeerOnlineForFlush = { false }

        val waitJob = async { bridge.awaitPeerOnline(testPeerFP, 5000L) }

        launch {
            delay(100L)
            bridge.shutdownAllSessions()
        }

        advanceUntilIdle()

        assertFalse("Cancelled waiter should return false when sessions shutdown", waitJob.await())
        bridge.shutdown()
    }
}
