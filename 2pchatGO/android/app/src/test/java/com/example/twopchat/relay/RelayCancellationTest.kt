package com.example.twopchat.relay

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RelayCancellationTest {
    @Test
    fun discoveryCallbackCancellationCancelsItsJob() {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent)
        val presence = PeerPresenceManager()
        val files = FileTransferCoordinator()
        val dispatcher = BridgeEventDispatcher(
            scope,
            presence,
            files,
            DiscoveryCandidateRegistry(),
            IncomingMessageRouter(presence, files, AvatarManager(PeerAvatarCache()), PinnedMessageManager()),
        )
        val cancelled = CancellationException("cancelled callback")
        val completed = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()
        val child = AtomicReference<Job>()
        dispatcher.onPeerDiscoveredHook = { _, _, _ ->
            val job = parent.children.single()
            child.set(job)
            job.invokeOnCompletion {
                result.set(it)
                completed.countDown()
            }
            throw cancelled
        }
        try {
            dispatcher.onPeerDiscovered("", "", "test")
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertSame(cancelled, result.get())
            assertTrue(child.get().isCancelled)
            assertTrue(parent.isActive)
        } finally {
            scope.cancel()
        }
    }
}
