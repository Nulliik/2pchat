package com.example.twopchat.security

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SensitiveMemoryLifecycleMonitorTest {

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    @Before
    @After
    fun cleanup() {
        SensitiveMemoryLifecycleMonitor.cancelBackgroundJobForTesting()
        SensitiveMemoryLifecycleMonitor.customTimeoutMs = null
        SensitiveMemoryRegistry.clearHoldersForTesting()
    }

    @Test
    fun testBackgroundSchedulesDelayedJobAndForegroundCancelsIt() {
        val owner = TestLifecycleOwner()
        SensitiveMemoryLifecycleMonitor.customTimeoutMs = 60_000L

        assertFalse(SensitiveMemoryLifecycleMonitor.hasPendingBackgroundJob())

        // App goes to background (onStop)
        SensitiveMemoryLifecycleMonitor.onStop(owner)
        assertTrue(SensitiveMemoryLifecycleMonitor.hasPendingBackgroundJob())

        // App returns to foreground before timeout (onStart)
        SensitiveMemoryLifecycleMonitor.onStart(owner)
        assertFalse(SensitiveMemoryLifecycleMonitor.hasPendingBackgroundJob())
    }

    @Test
    fun testImmediateBackgroundPurgeWhenTimeoutZero() {
        val owner = TestLifecycleOwner()
        SensitiveMemoryLifecycleMonitor.customTimeoutMs = 0L

        val clearCount = AtomicInteger(0)
        val holder = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                clearCount.incrementAndGet()
            }
        }
        SensitiveMemoryRegistry.register(holder)

        SensitiveMemoryLifecycleMonitor.onStop(owner)

        assertFalse("Job should not be pending when timeout is 0 (immediate execution)",
            SensitiveMemoryLifecycleMonitor.hasPendingBackgroundJob())
        assertEquals(1, clearCount.get())
    }

    @Test
    fun testDelayedPurgeExecutesAfterTimeout() {
        val owner = TestLifecycleOwner()
        // 50ms test timeout
        SensitiveMemoryLifecycleMonitor.customTimeoutMs = 50L

        val clearedLatch = CountDownLatch(1)
        val holder = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                clearedLatch.countDown()
            }
        }
        SensitiveMemoryRegistry.register(holder)

        SensitiveMemoryLifecycleMonitor.onStop(owner)
        assertTrue(SensitiveMemoryLifecycleMonitor.hasPendingBackgroundJob())

        assertTrue("Delayed purge should execute upon timeout", clearedLatch.await(1, TimeUnit.SECONDS))
    }
}
