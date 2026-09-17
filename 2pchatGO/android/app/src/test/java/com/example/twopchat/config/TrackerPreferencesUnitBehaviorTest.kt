package com.example.twopchat.config

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class TrackerPreferencesUnitBehaviorTest {
    @Test
    fun trackerSettingsRoundTripAndReset() {
        val store = Bug18Prefs()
        P2PPreferences.setCachedPrefsForTesting(store.prefs)
        try {
            assertEquals(17, TrackerPreferences.builtInTrackers.size)
            assertEquals(setOf("http", "https", "udp"), TrackerPreferences.enabledProtocols(store.context))
            TrackerPreferences.setProtocolEnabled(store.context, "udp", false)
            TrackerPreferences.setBuiltInEnabled(store.context, "Nyacat HTTPS", false)
            assertEquals(null, TrackerPreferences.addCustomTracker(store.context, "Local", "https://tracker.example/announce"))
            val tracker = TrackerPreferences.customTrackers(store.context).single()
            TrackerPreferences.setCustomTrackerEnabled(store.context, tracker.id, false)
            assertTrue(!TrackerPreferences.customTrackers(store.context).single().enabled)
            TrackerPreferences.deleteCustomTracker(store.context, tracker.id)
            assertTrue(TrackerPreferences.customTrackers(store.context).isEmpty())
            TrackerPreferences.resetDefaults(store.context)
            assertEquals(TrackerPreferences.supportedProtocols, TrackerPreferences.enabledProtocols(store.context))
            assertTrue(TrackerPreferences.disabledBuiltIns(store.context).isEmpty())
            assertTrue(TrackerPreferences.announceEnabled(store.context))
        } finally {
            P2PPreferences.setCachedPrefsForTesting(null)
        }
    }
}

internal class Bug18Prefs {
    private val values = mutableMapOf<String, Any?>()
    @Volatile var afterRead: (String) -> Unit = {}
    @Volatile var beforePublish: (Map<String, Any?>) -> Unit = {}
    @Volatile var commitResult = true
    var commits = 0
        private set

    val prefs = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "edit" -> editor()
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args[0]
            "toString" -> "Bug18Prefs"
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            else -> {
                val key = args?.getOrNull(0) as? String ?: "all"
                val result = synchronized(values) {
                    when (method.name) {
                        "getAll" -> values.toMap()
                        "contains" -> values.containsKey(key)
                        else -> values[key] ?: args?.getOrNull(1)
                    }
                }
                afterRead(key)
                result
            }
        }
    } as SharedPreferences

    val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun editor(): SharedPreferences.Editor {
        val pending = mutableMapOf<String, Any?>()
        var clear = false
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { editor, method, args ->
            when (method.name) {
                "apply", "commit" -> {
                    beforePublish(pending.toMap())
                    synchronized(values) {
                        if (clear) values.clear()
                        pending.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        if (method.name == "commit") commits++
                    }
                    if (method.name == "commit") commitResult else null
                }
                "clear" -> { clear = true; editor }
                "remove" -> { pending[args[0] as String] = null; editor }
                else -> {
                    val value = args[1]
                    pending[args[0] as String] = if (value is Set<*>) value.toSet() else value
                    editor
                }
            }
        } as SharedPreferences.Editor
    }
}

internal class Bug18Gate : AutoCloseable {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    fun pause() {
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "Gate timed out" }
    }
    fun awaitEntry() = assertTrue(entered.await(10, TimeUnit.SECONDS))
    override fun close() = release.countDown()
}

internal class Bug18Call<T>(action: () -> T) {
    private val task = FutureTask(Callable(action))
    val thread = Thread(task).apply { isDaemon = true; start() }
    fun result(): T = task.get(10, TimeUnit.SECONDS)
    fun awaitBlocked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && !task.isDone && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }
}
