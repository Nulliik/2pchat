package com.example.twopchat.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SensitiveMemoryRegistryTest {

    @Before
    @After
    fun cleanup() {
        SensitiveMemoryRegistry.clearHoldersForTesting()
    }

    @Test
    fun testRegisterAndUnregister() {
        val holder = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {}
        }

        assertEquals(0, SensitiveMemoryRegistry.getHoldersCount())
        SensitiveMemoryRegistry.register(holder)
        assertEquals(1, SensitiveMemoryRegistry.getHoldersCount())

        // Duplicate registration should not create duplicate entries (Set semantics)
        SensitiveMemoryRegistry.register(holder)
        assertEquals(1, SensitiveMemoryRegistry.getHoldersCount())

        SensitiveMemoryRegistry.unregister(holder)
        assertEquals(0, SensitiveMemoryRegistry.getHoldersCount())
    }

    @Test
    fun testClearAllInvokesAllHolders() {
        val holder1Cleared = AtomicBoolean(false)
        val holder2Cleared = AtomicBoolean(false)

        val holder1 = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                holder1Cleared.set(true)
            }
        }
        val holder2 = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                holder2Cleared.set(true)
            }
        }

        SensitiveMemoryRegistry.register(holder1)
        SensitiveMemoryRegistry.register(holder2)

        SensitiveMemoryRegistry.clearAll()

        assertTrue("Holder 1 must be cleared", holder1Cleared.get())
        assertTrue("Holder 2 must be cleared", holder2Cleared.get())
    }

    @Test
    fun testExceptionIsolation() {
        val normalHolder1Cleared = AtomicBoolean(false)
        val normalHolder2Cleared = AtomicBoolean(false)

        val failingHolder = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                throw IllegalStateException("Simulated crash in memory holder cleanup")
            }
        }
        val normalHolder1 = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                normalHolder1Cleared.set(true)
            }
        }
        val normalHolder2 = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                normalHolder2Cleared.set(true)
            }
        }

        SensitiveMemoryRegistry.register(normalHolder1)
        SensitiveMemoryRegistry.register(failingHolder)
        SensitiveMemoryRegistry.register(normalHolder2)

        // Should not throw exception and should clear both normal holders
        SensitiveMemoryRegistry.clearAll()

        assertTrue("Normal holder 1 must be cleared", normalHolder1Cleared.get())
        assertTrue("Normal holder 2 must be cleared", normalHolder2Cleared.get())
    }

    @Test
    fun testConcurrentClearAllProtection() {
        val clearCount = AtomicInteger(0)
        val enterLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)

        val slowHolder = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                clearCount.incrementAndGet()
                enterLatch.countDown()
                // Wait for concurrent thread attempt
                releaseLatch.await(2, TimeUnit.SECONDS)
            }
        }

        SensitiveMemoryRegistry.register(slowHolder)

        val t1 = Thread {
            SensitiveMemoryRegistry.clearAll()
        }
        t1.start()

        // Wait until t1 is inside clearSensitiveMemory
        assertTrue(enterLatch.await(2, TimeUnit.SECONDS))

        // t2 calls clearAll while t1 is actively holding the lock / isClearing flag
        val t2Executed = AtomicBoolean(false)
        val t2 = Thread {
            SensitiveMemoryRegistry.clearAll()
            t2Executed.set(true)
        }
        t2.start()
        t2.join(1000)

        // t2 should complete immediately because isClearing was true (fast path skip)
        assertTrue("Concurrent clearAll should return immediately without blocking", t2Executed.get())

        // Release t1
        releaseLatch.countDown()
        t1.join(2000)

        // slowHolder should have executed only once
        assertEquals(1, clearCount.get())
    }

    @Test
    fun testIdempotency() {
        val clearCounter = AtomicInteger(0)
        val holder = object : SensitiveMemoryHolder {
            override fun clearSensitiveMemory() {
                clearCounter.incrementAndGet()
            }
        }

        SensitiveMemoryRegistry.register(holder)

        SensitiveMemoryRegistry.clearAll()
        assertEquals(1, clearCounter.get())

        SensitiveMemoryRegistry.clearAll()
        assertEquals(2, clearCounter.get())
    }
}
