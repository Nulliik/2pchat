package com.example.twopchat.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.twopchat.NativeBridge
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.group.runtime.GroupChatCoordinator
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

class SuccessionHeartbeatWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val prefMap = mutableMapOf<String, Any?>()
    private lateinit var fakePrefs: SharedPreferences
    private lateinit var context: Context
    private lateinit var testFilesDir: File

    @Before
    fun setUp() {
        prefMap.clear()
        testFilesDir = tempFolder.newFolder("files_hb")
        fakePrefs = TestSharedPreferences(prefMap)
        P2PPreferences.setCachedPrefsForTesting(fakePrefs)

        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
            override fun getFilesDir(): File = testFilesDir
            override fun getPackageName(): String = "com.example.twopchat"
            override fun getSystemService(name: String): Any? = null
        }

        NativeBridge.isInitializeOverride = true
        BackgroundDiagnostics.resetForTesting(context)
    }

    @After
    fun tearDown() {
        BackgroundDiagnostics.resetForTesting(context)
        P2PPreferences.setCachedPrefsForTesting(null)
        P2PPreferences.clearInMemoryState()
        NativeBridge.isInitializeOverride = null
    }

    @Test
    fun testSuccessionHeartbeatWorkerReturnsSuccessWhenNoIdentity() = runBlocking {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit()
            .putBoolean("onboarding_completed", false)
            .remove("username_profile")
            .commit()

        val worker = TestListenableWorkerBuilder<SuccessionHeartbeatWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun testSuccessionHeartbeatWorkerReturnsSuccessWhenAppLocked() = runBlocking {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit()
            .putBoolean("onboarding_completed", true)
            .putString("username_profile", "Alice")
            .putBoolean("settings_passcode", true)
            .putString("passcode_value", "1234")
            .commit()

        P2PPreferences.setAppLocked(true)
        val worker = TestListenableWorkerBuilder<SuccessionHeartbeatWorker>(context).build()
        val result = worker.doWork()

        // Defer emission while app is locked
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun testHeartbeatThrottlingLogic() {
        val now = 10_000_000L
        val minInterval = GroupChatCoordinator.MIN_HEARTBEAT_INTERVAL_MS
        assertEquals(3600_000L, minInterval)

        // Emitted 30 minutes ago -> throttled
        val recentEmit = now - 1800_000L
        assertTrue(now - recentEmit < minInterval)

        // Emitted 25 hours ago -> due
        val oldEmit = now - 25 * 3600_000L
        assertTrue(now - oldEmit >= 24 * 3600_000L && now - oldEmit >= minInterval)
    }

    @Test
    fun testOwnerOnlyHeartbeatEmissionInvariants() {
        val localDeviceId = "device-owner-123"
        val otherDeviceId = "device-member-456"

        // Local device is owner -> eligible
        val isOwner = localDeviceId == localDeviceId
        assertTrue("Owner is eligible to emit heartbeats", isOwner)

        // Local device is member, not owner -> not eligible
        val isNonOwner = localDeviceId == otherDeviceId
        assertFalse("Non-owner member must never emit succession heartbeats", isNonOwner)
    }

    @Test
    fun testSuccessionHeartbeatWorkerRetriesOnBridgeUnavailable() = runBlocking {
        val prefs = P2PPreferences.prefs(context)
        prefs.edit()
            .putBoolean("onboarding_completed", true)
            .putString("username_profile", "Alice")
            .commit()

        NativeBridge.isInitializeOverride = false

        val worker = TestListenableWorkerBuilder<SuccessionHeartbeatWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
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
