package com.example.twopchat.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.security.KeystoreProvider
import com.example.twopchat.tor.TorManager
import com.example.twopchat.yggdrasil.AppForegroundTracker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OutboxDrainWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val prefMap = mutableMapOf<String, Any?>()
    private lateinit var fakePrefs: SharedPreferences
    private lateinit var context: Context
    private lateinit var testFilesDir: File

    @Before
    fun setUp() {
        prefMap.clear()
        testFilesDir = tempFolder.newFolder("files")
        fakePrefs = TestSharedPreferences(prefMap)
        P2PPreferences.setCachedPrefsForTesting(fakePrefs)

        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
            override fun getFilesDir(): File = testFilesDir
            override fun getPackageName(): String = "com.example.twopchat"
            override fun getSystemService(name: String): Any? = null
        }

        AppForegroundTracker.resetForTesting()
        TorManager.resetCountersForTesting()
        P2PRelayService.resetCountersForTesting()
        KeystoreProvider.resetForTesting()
        NativeBridge.isInitializeOverride = null
        BackgroundDiagnostics.resetForTesting(context)
    }

    @After
    fun tearDown() {
        BackgroundDiagnostics.resetForTesting(context)
        P2PPreferences.setCachedPrefsForTesting(null)
        P2PPreferences.clearInMemoryState()
        AppForegroundTracker.resetForTesting()
        TorManager.resetCountersForTesting()
        P2PRelayService.resetCountersForTesting()
        KeystoreProvider.resetForTesting()
        NativeBridge.isInitializeOverride = null
    }

    @Test
    fun testOutboxDrainWorkerReturnsSuccessWhenPeerOffline() = runBlocking {
        // Configure local identity
        val prefs = P2PPreferences.prefs(context)
        prefs.edit()
            .putBoolean("onboarding_completed", true)
            .putString("username_profile", "Alice")
            .commit()

        // Bridge available
        NativeBridge.isInitializeOverride = true

        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(context).build()
        val result = worker.doWork()

        // P0.1: Best-effort delivery MUST return Result.success() to prevent battery-draining retry loops
        assertTrue(
            "OutboxDrainWorker must return Result.success() for offline peers instead of retry storm",
            result is ListenableWorker.Result.Success
        )

        val stats = BackgroundDiagnostics.getStats(context)
        assertEquals(1, stats.drainOutcomes["success"])
        assertTrue(stats.lastDrainTimestamp > 0L)
    }

    @Test
    fun workerDoesNotStartTorOrForegroundService() = runBlocking {
        // Simulate headless background worker execution
        AppForegroundTracker.isForegroundOverride = false

        assertFalse("App should not be in foreground during headless worker run", AppForegroundTracker.isAppInForeground())

        // Attempting to init or start Tor while headless must be suppressed
        TorManager.init(context)
        TorManager.startTor(context, isUserInitiated = false)
        assertEquals("Tor invocation should be recorded", 1, TorManager.startTorInvocationCount)
        assertEquals("Tor daemon start must be suppressed in headless execution", 1, TorManager.startTorSuppressedCount)
        assertFalse("Tor daemon must not be running", TorManager.isTorRunning.value)

        // KeystoreProvider UI-prewarm must NOT have executed in headless context
        assertFalse("Keystore UI-prewarm must not run in headless worker execution", KeystoreProvider.isPrewarmed)

        // Running worker must not trigger foreground services or Tor
        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("P2PRelayService must not be created during headless worker run", 0, P2PRelayService.createCallCount)
        assertFalse("Tor must remain inactive after worker execution", TorManager.isTorRunning.value)
        assertFalse("Keystore prewarm must still remain false after worker run", KeystoreProvider.isPrewarmed)
    }

    @Test
    fun testDrainSkippedWhenAppLockedWithUserFactor() = runBlocking {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit()
            .putBoolean("onboarding_completed", true)
            .putString("username_profile", "Alice")
            .putBoolean("settings_passcode", true)
            .putString("passcode_value", "1234")
            .commit()

        P2PPreferences.setAppLocked(true)
        assertTrue(P2PPreferences.isPasscodeConfigured(context))
        assertTrue(P2PPreferences.isAppLocked())

        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(context).build()
        val result = worker.doWork()

        // P0.3: When app is locked with user passcode, drainage is deferred without retry storm
        assertTrue(
            "Locked app must return Result.success() to defer drain until user unlocks",
            result is ListenableWorker.Result.Success
        )

        val stats = BackgroundDiagnostics.getStats(context)
        assertEquals(1, stats.drainOutcomes["skipped_locked"])
    }

    @Test
    fun testDrainSkippedWhenNoIdentity() = runBlocking {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit()
            .putBoolean("onboarding_completed", false)
            .remove("username_profile")
            .commit()

        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(context).build()
        val result = worker.doWork()

        assertTrue("Unprovisioned device must return Result.success()", result is ListenableWorker.Result.Success)

        val stats = BackgroundDiagnostics.getStats(context)
        assertEquals(1, stats.drainOutcomes["skipped_no_identity"])
    }

    @Test
    fun testWorkerRetriesOnBridgeUnavailable() = runBlocking {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit()
            .putBoolean("onboarding_completed", true)
            .putString("username_profile", "Alice")
            .commit()

        NativeBridge.isInitializeOverride = false

        val worker = TestListenableWorkerBuilder<OutboxDrainWorker>(context).build()
        val result = worker.doWork()

        assertTrue("Should retry on transient native bridge unavailability", result is ListenableWorker.Result.Retry)

        val stats = BackgroundDiagnostics.getStats(context)
        assertEquals(1, stats.drainOutcomes["retry_bridge"])
    }

    @Test
    fun testUniqueWorkPolicies() {
        assertEquals("twopchat-outbox-drain-periodic", OutboxWorkScheduler.WORK_DRAIN_PERIODIC)
        assertEquals("twopchat-outbox-drain-now", OutboxWorkScheduler.WORK_DRAIN_IMMEDIATE)
        assertEquals("twopchat-succession-heartbeat", OutboxWorkScheduler.WORK_HEARTBEAT_PERIODIC)
        assertEquals("twopchat-group-anti-entropy", OutboxWorkScheduler.WORK_GROUP_SYNC)

        // Verify scheduler calls are safe and idempotent
        OutboxWorkScheduler.schedulePeriodicDrain(context)
        OutboxWorkScheduler.schedulePeriodicHeartbeats(context)
        OutboxWorkScheduler.triggerImmediateDrain(context, expedited = false)
        OutboxWorkScheduler.triggerImmediateDrain(context, expedited = true)

        val stats = BackgroundDiagnostics.getStats(context)
        assertEquals("Expedited drain count should be recorded", 1, stats.expeditedCount)

        OutboxWorkScheduler.cancelAll(context)
    }

    private class TestSharedPreferences(private val map: MutableMap<String, Any?>) : SharedPreferences, SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun getAll(): Map<String, *> = synchronized(map) { map.toMap() }
        override fun getString(key: String?, defValue: String?): String? = synchronized(map) { (map[key] as? String) ?: defValue }
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = synchronized(map) {
            @Suppress("UNCHECKED_CAST")
            (map[key] as? Set<String>) ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = synchronized(map) { (map[key] as? Int) ?: defValue }
        override fun getLong(key: String?, defValue: Long): Long = synchronized(map) { (map[key] as? Long) ?: defValue }
        override fun getFloat(key: String?, defValue: Float): Float = synchronized(map) { (map[key] as? Float) ?: defValue }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(map) { (map[key] as? Boolean) ?: defValue }
        override fun contains(key: String?): Boolean = synchronized(map) { map.containsKey(key) }
        override fun edit(): SharedPreferences.Editor = this

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value != null) {
                    pending[key] = value
                    removals.remove(key)
                } else {
                    removals.add(key)
                    pending.remove(key)
                }
            }
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (key != null) {
                if (values != null) {
                    pending[key] = values
                    removals.remove(key)
                } else {
                    removals.add(key)
                    pending.remove(key)
                }
            }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
                removals.remove(key)
            }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) {
                removals.add(key)
                pending.remove(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            synchronized(map) {
                removals.addAll(map.keys)
                pending.clear()
            }
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            synchronized(map) {
                removals.forEach { map.remove(it) }
                map.putAll(pending)
                removals.clear()
                pending.clear()
            }
        }
    }
}
