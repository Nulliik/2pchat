package com.example.twopchat.config

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackerPreferencesResetFlushTest {
    private lateinit var store: Bug18Prefs
    private val tracker = TrackerPreferences.builtInTrackers.first()
    private val diagnosticsKey = "tracker_diagnostics_json"

    @Before
    fun setUp() {
        store = Bug18Prefs()
        P2PPreferences.setCachedPrefsForTesting(store.prefs)
    }

    @After
    fun tearDown() {
        P2PPreferences.setCachedPrefsForTesting(null)
    }

    private fun seed() {
        val raw = JSONObject().put(tracker.url, JSONObject().put("success", true).put("peers", 3))
        store.prefs.edit().putString(diagnosticsKey, raw.toString()).apply()
    }

    private fun record() {
        TrackerPreferences.recordDiagnosticStatus(store.context, tracker.url, true, 5, 12L, "fresh\nresult")
    }

    @Test
    fun loadedIsNotPublishedBeforeDiskEntries() {
        seed()
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == diagnosticsKey) gate.pause() }
            val first = Bug18Call { TrackerPreferences.activeTrackerSuccessCount(store.context) }
            gate.awaitEntry()
            val second = Bug18Call { TrackerPreferences.activeTrackerSuccessCount(store.context) }
            try {
                second.awaitBlocked()
            } finally {
                gate.close()
            }
            assertEquals(1, first.result())
            assertEquals(1, second.result())
        }
    }

    @Test
    fun resetCannotRaceWithDiskLoadPublication() {
        seed()
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == diagnosticsKey) gate.pause() }
            val load = Bug18Call { TrackerPreferences.activeTrackerSuccessCount(store.context) }
            gate.awaitEntry()
            val reset = Bug18Call { TrackerPreferences.resetDefaults(store.context) }
            try {
                reset.awaitBlocked()
            } finally {
                gate.close()
            }
            assertEquals(1, load.result())
            reset.result()
            assertEquals(0, TrackerPreferences.activeTrackerSuccessCount(store.context))
            assertFalse(store.prefs.contains(diagnosticsKey))
        }
    }

    @Test
    fun resetWaitsForActualFlushPublication() {
        Bug18Gate().use { gate ->
            store.beforePublish = { if (diagnosticsKey in it) gate.pause() }
            record()
            gate.awaitEntry()
            val reset = Bug18Call { TrackerPreferences.resetDefaults(store.context) }
            try {
                reset.awaitBlocked()
            } finally {
                gate.close()
            }
            reset.result()
            assertFalse(store.prefs.contains(diagnosticsKey))
            assertEquals(0, TrackerPreferences.activeTrackerSuccessCount(store.context))
        }
    }

    @Test
    fun wipeWaitsForActualFlushAndFreshRecordStillPersists() {
        Bug18Gate().use { gate ->
            store.beforePublish = { if (diagnosticsKey in it) gate.pause() }
            record()
            gate.awaitEntry()
            val wipe = Bug18Call { P2PPreferences.clearInMemoryState() }
            try {
                wipe.awaitBlocked()
            } finally {
                gate.close()
            }
            wipe.result()
            assertFalse(store.prefs.contains(diagnosticsKey))
        }
        Bug18Gate().use { gate ->
            store.beforePublish = { if (diagnosticsKey in it) gate.pause() }
            TrackerPreferences.recordDiagnosticStatus(store.context, "https://fresh.example/announce", false, 0, 1, "new")
            gate.awaitEntry()
            gate.close()
            synchronized(P2PPreferences) {
                val raw = JSONObject(store.prefs.getString(diagnosticsKey, "{}")!!)
                assertTrue(raw.has("https://fresh.example/announce"))
                assertFalse(raw.has(tracker.url))
            }
        }
    }

    @Test
    fun recordingBeforeFirstReadPreservesOtherPersistedDiagnostics() {
        seed()
        val other = TrackerPreferences.builtInTrackers[1]
        Bug18Gate().use { gate ->
            store.beforePublish = { if (diagnosticsKey in it) gate.pause() }
            TrackerPreferences.recordDiagnosticStatus(store.context, other.url, false, -1, -2, "a\nb")
            gate.awaitEntry()
            gate.close()
            synchronized(P2PPreferences) {
                val raw = JSONObject(store.prefs.getString(diagnosticsKey, "{}")!!)
                assertTrue(raw.getJSONObject(tracker.url).getBoolean("success"))
                assertEquals("a b", raw.getJSONObject(other.url).getString("detail"))
                assertEquals(0, raw.getJSONObject(other.url).getInt("peers"))
                assertEquals(0L, raw.getJSONObject(other.url).getLong("elapsed_ms"))
            }
        }
    }

    @Test
    fun protocolUpdatesCannotLoseEachOther() {
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == TrackerPreferences.ENABLED_PROTOCOLS) gate.pause() }
            val first = Bug18Call { TrackerPreferences.setProtocolEnabled(store.context, "http", false) }
            gate.awaitEntry()
            val second = Bug18Call { TrackerPreferences.setProtocolEnabled(store.context, "udp", false) }
            try {
                second.awaitBlocked()
            } finally {
                gate.close()
            }
            first.result()
            second.result()
            assertEquals(setOf("https"), TrackerPreferences.enabledProtocols(store.context))
        }
    }

    @Test
    fun concurrentCustomAddsRetainBothEntries() {
        Bug18Gate().use { gate ->
            store.afterRead = { if (it == TrackerPreferences.CUSTOM_TRACKERS_JSON) gate.pause() }
            val first = Bug18Call { TrackerPreferences.addCustomTracker(store.context, "One", "https://one.example/announce") }
            gate.awaitEntry()
            val second = Bug18Call { TrackerPreferences.addCustomTracker(store.context, "Two", "https://two.example/announce") }
            try {
                second.awaitBlocked()
            } finally {
                gate.close()
            }
            assertEquals(null, first.result())
            assertEquals(null, second.result())
            assertEquals(setOf("One", "Two"), TrackerPreferences.customTrackers(store.context).map { it.name }.toSet())
        }
    }
}
