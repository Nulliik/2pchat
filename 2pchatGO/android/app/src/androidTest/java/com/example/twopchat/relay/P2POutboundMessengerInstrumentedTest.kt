package com.example.twopchat.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.twopchat.bridge.IP2PBridge
import com.example.twopchat.data.ChatDatabaseHelper
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P2POutboundMessengerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val peerName = "persisted-control-test-${UUID.randomUUID()}"
    private val endpoint = "127.0.0.1:50001"
    private val controlId = "pin:${UUID.randomUUID()}"
    private val payload = JSONObject().apply {
        put("type", "pin_message")
        put("control_id", controlId)
        put("message_id", "message-1")
    }
    private val bridge = SuspendedBridge()
    private val results = CopyOnWriteArrayList<Boolean>()
    private val logs = CopyOnWriteArrayList<Triple<String, String, Throwable?>>()
    private val messenger = P2POutboundMessenger(
        peerEndpoints = mapOf(peerName to endpoint),
        log = { _, message, level, error -> logs.add(Triple(message, level, error)) },
        onMessageStatusChanged = { _, _, _ -> },
        bridgeProvider = { bridge },
    )
    private var sendJob: Job? = null

    @After
    fun cleanUp() = runBlocking {
        withTimeout(10_000) {
            sendJob?.cancelAndJoin()
        }
        drainMainQueue()
        ChatDatabaseHelper.getInstance(context).deletePendingControlsForPeer(peerName)
    }

    @Test
    fun cancellingSuspendedPersistedControlRetainsQueueWithoutFailureReporting() = runBlocking {
        withTimeout(10_000) {
            val attempt = startSend()
            val pending = ChatDatabaseHelper.getInstance(context).getPendingControlsForPeer(peerName)
            assertEquals(1, pending.size)
            assertEquals(controlId, pending.single().id)
            assertEquals("pin_message", pending.single().type)
            assertEquals(payload.toString(), pending.single().payload)

            attempt.continuation.context.job.cancelAndJoin()
            drainMainQueue()

            assertTrue(attempt.continuation.isCancelled)
            assertTrue(sendJob!!.isCancelled)
            assertTrue(results.isEmpty())
            assertTrue(logs.isEmpty())
            assertEquals(pending, ChatDatabaseHelper.getInstance(context).getPendingControlsForPeer(peerName))
        }
    }

    @Test
    fun ordinaryPersistedControlExceptionReportsFailureAndRetainsQueue() = runBlocking {
        withTimeout(10_000) {
            val attempt = startSend()
            val pending = ChatDatabaseHelper.getInstance(context).getPendingControlsForPeer(peerName)
            assertEquals(1, pending.size)
            assertEquals(controlId, pending.single().id)
            val failure = IOException("bridge send failed")

            attempt.continuation.resumeWithException(failure)
            sendJob!!.join()
            drainMainQueue()

            assertFalse(sendJob!!.isCancelled)
            assertEquals(listOf(false), results.toList())
            assertEquals(1, logs.size)
            assertEquals("Failed to queue/send pin_message control", logs.single().first)
            assertEquals("ERROR", logs.single().second)
            assertSame(failure, logs.single().third)
            assertEquals(pending, ChatDatabaseHelper.getInstance(context).getPendingControlsForPeer(peerName))
        }
    }

    private suspend fun startSend(): SendAttempt {
        messenger.sendPinnedState(context, peerName, payload) { results.add(it) }
        val attempt = bridge.entered.await()
        sendJob = attempt.continuation.context.job
        assertEquals(peerName, attempt.peerName)
        assertEquals(endpoint, attempt.endpoint)
        assertEquals(payload.toString(), attempt.payload)
        return attempt
    }

    private fun drainMainQueue() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {}
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private data class SendAttempt(
        val peerName: String,
        val endpoint: String,
        val payload: String,
        val continuation: CancellableContinuation<Boolean>,
    )

    private class SuspendedBridge : IP2PBridge by (Proxy.newProxyInstance(
        IP2PBridge::class.java.classLoader,
        arrayOf(IP2PBridge::class.java),
    ) { _, method, _ -> error("Unexpected bridge call: ${method.name}") } as IP2PBridge) {
        val entered = CompletableDeferred<SendAttempt>()

        override suspend fun sendP2pMessage(
            peerName: String,
            endpoint: String,
            payload: String,
            expectedFingerprint: String?,
        ): Boolean = suspendCancellableCoroutine { continuation ->
            entered.complete(SendAttempt(peerName, endpoint, payload, continuation))
        }
    }
}
