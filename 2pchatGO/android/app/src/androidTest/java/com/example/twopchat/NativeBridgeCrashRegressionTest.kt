package com.example.twopchat

import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Process-level regressions for JNI/Go failures. A native abort terminates the
 * instrumentation process, so these tests fail instead of silently continuing.
 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeCrashRegressionTest {
    companion object {
        private const val UNKNOWN_FINGERPRINT =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        @JvmStatic
        @BeforeClass
        fun initializeNativeCore() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            NativeBridge.setStorageDir(context.filesDir.absolutePath)
            assertTrue("lib2pcore.so must load", NativeBridge.isLoaded)
            assertTrue("Go core must initialize", NativeBridge.initialize())
        }
    }

    @Test
    fun concurrentOnlinePollingAndNetworkChangesDoNotAbortProcess() {
        val workers = 8
        val iterations = 500
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)

        try {
            val futures = (0 until workers).map { worker ->
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(iterations) { iteration ->
                        assertFalse(NativeBridge.isPeerOnline(UNKNOWN_FINGERPRINT))
                        if ((worker + iteration) % 25 == 0) {
                            NativeBridge.onNetworkChanged()
                        }
                        if (iteration % 100 == 0) {
                            assertTrue(NativeBridge.echo("jni-$worker-$iteration").contains("jni-"))
                        }
                    }
                }
            }

            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun listenerRestartAndOfflineOperationsRemainStable() {
        NativeBridge.stopListener()

        repeat(20) {
            assertTrue("listener should start on an ephemeral port", NativeBridge.startListener(0))
            assertFalse(NativeBridge.isPeerOnline(UNKNOWN_FINGERPRINT))
            assertTrue("listener should stop cleanly", NativeBridge.stopListener())
            NativeBridge.onNetworkChanged()
        }
    }

    @Test
    fun identitySurvivesNativeReload() {
        val before = requireNotNull(NativeBridge.getLocalIdentity()).fingerprint
        assertTrue("native identity reload must succeed", NativeBridge.reloadIdentity())
        val after = requireNotNull(NativeBridge.getLocalIdentity()).fingerprint
        assertEquals("local fingerprint must be persistent", before, after)
    }

    @Test
    fun contextFreeRelayAccessKeepsTheProviderBridgeAndItsCallbacks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val providerBridge = com.example.twopchat.relay.P2PMessageRelay.getBridge(context)

        assertSame(
            "context-free maintenance must not replace process-wide JNI callbacks",
            providerBridge,
            com.example.twopchat.relay.P2PMessageRelay.getBridge(),
        )
    }

    @Test
    fun activityRecreationAndForegroundTransitionsDoNotCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(5) {
                scenario.moveToState(Lifecycle.State.STARTED)
                NativeBridge.onNetworkChanged()
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertFalse("activity must remain alive after recreation", activity.isFinishing)
                }
            }
        }
    }
}
