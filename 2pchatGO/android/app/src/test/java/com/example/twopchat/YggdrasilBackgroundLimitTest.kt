package com.example.twopchat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.twopchat.config.P2PPreferences
import com.example.twopchat.yggdrasil.AppForegroundTracker
import com.example.twopchat.yggdrasil.PREF_KEY_ENABLED
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the "limit background Yggdrasil activity" behavior:
 *  - the mesh service is stopped when the app transitions to the background, and
 *  - it is restarted when the app returns to the foreground,
 * but only when the setting is enabled and Yggdrasil is enabled.
 */
class YggdrasilBackgroundLimitTest {

    private val prefMap = mutableMapOf<String, Any?>()
    private lateinit var fakePrefs: SharedPreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        prefMap.clear()
        fakePrefs = TestSharedPreferences(prefMap)
        P2PPreferences.setCachedPrefsForTesting(fakePrefs)
        context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
        }
        AppForegroundTracker.resetForTesting()
    }

    @After
    fun tearDown() {
        AppForegroundTracker.resetForTesting()
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    @Test
    fun shouldLimitBackground_requiresBothSettingAndEnabled() {
        // Neither set -> false
        assertFalse(AppForegroundTracker.shouldLimitBackground(context))

        // Only Yggdrasil enabled, limit-background off -> false
        prefMap[PREF_KEY_ENABLED] = true
        assertFalse(AppForegroundTracker.shouldLimitBackground(context))

        // Only limit-background on, Yggdrasil disabled -> false
        prefMap[PREF_KEY_ENABLED] = false
        P2PPreferences.setYggdrasilLimitBackgroundEnabled(context, true)
        assertFalse(AppForegroundTracker.shouldLimitBackground(context))

        // Both set -> true
        prefMap[PREF_KEY_ENABLED] = true
        assertTrue(AppForegroundTracker.shouldLimitBackground(context))
    }

    @Test
    fun firstActivityStart_triggersForegroundTransition() {
        val transitions = mutableListOf<Boolean>()
        AppForegroundTracker.backgroundLimitAction = { foreground -> transitions.add(foreground) }

        // First activity starts -> app entered foreground.
        AppForegroundTracker.onActivityStarted()
        assertEquals(listOf(true), transitions)

        // A second activity starts while already foreground -> no new transition.
        AppForegroundTracker.onActivityStarted()
        assertEquals(listOf(true), transitions)
    }

    @Test
    fun lastActivityStop_triggersBackgroundTransition() {
        val transitions = mutableListOf<Boolean>()
        AppForegroundTracker.backgroundLimitAction = { foreground -> transitions.add(foreground) }

        AppForegroundTracker.onActivityStarted()
        AppForegroundTracker.onActivityStarted()

        // One activity stops, one remains -> still foreground, no transition.
        AppForegroundTracker.onActivityStopped()
        assertEquals(listOf(true), transitions)

        // Last activity stops -> app entered background.
        AppForegroundTracker.onActivityStopped()
        assertEquals(listOf(true, false), transitions)
    }

    @Test
    fun backgroundThenForeground_emitsBothTransitions() {
        val transitions = mutableListOf<Boolean>()
        AppForegroundTracker.backgroundLimitAction = { foreground -> transitions.add(foreground) }

        AppForegroundTracker.onActivityStarted()   // foreground
        AppForegroundTracker.onActivityStopped()    // background
        AppForegroundTracker.onActivityStarted()    // foreground again

        assertEquals(listOf(true, false, true), transitions)
    }

    @Test
    fun activityStoppedWhenNoneActive_isIgnored() {
        val transitions = mutableListOf<Boolean>()
        AppForegroundTracker.backgroundLimitAction = { foreground -> transitions.add(foreground) }

        // No activities active; a stray stop must not underflow or emit a transition.
        AppForegroundTracker.onActivityStopped()
        AppForegroundTracker.onActivityStopped()
        assertTrue(transitions.isEmpty())
    }

    private class TestSharedPreferences(private val map: MutableMap<String, Any?>) : SharedPreferences, SharedPreferences.Editor {
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((map[key] as? Set<String>)?.toMutableSet()) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = this
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) map[key] = values?.toSet()
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) map.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
