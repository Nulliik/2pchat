package com.example.twopchat

import com.example.twopchat.relay.*
import com.example.twopchat.config.*
import com.example.twopchat.security.*
import com.example.twopchat.service.*
import com.example.twopchat.media.*
import com.example.twopchat.tor.*

import com.example.twopchat.config.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerPreferencesTest {
    @Test
    fun trackerProtocolAcceptsSupportedTrackerUrls() {
        assertEquals("http", TrackerPreferences.trackerProtocol("http://tracker.example/announce"))
        assertEquals("https", TrackerPreferences.trackerProtocol("https://tracker.example/announce"))
        assertEquals("udp", TrackerPreferences.trackerProtocol("udp://tracker.example:6969/announce"))
    }

    @Test
    fun builtInYggdrasilTrackersAreValidAndUseYggdrasilAddresses() {
        val trackers = TrackerPreferences.builtInTrackers
            .filter { it.name.contains("Yggdrasil", ignoreCase = true) }
        assertEquals(6, trackers.size)
        trackers.forEach { tracker ->
            assertEquals("invalid tracker: ${tracker.name}", tracker.protocol, TrackerPreferences.trackerProtocol(tracker.url))
            val host = java.net.URI(tracker.url).host.orEmpty().trim('[', ']')
            assertEquals("non-Yggdrasil host: ${tracker.name}", true, host.startsWith("2") || host.startsWith("3"))
        }
    }

    @Test
    fun trackerProtocolRejectsUnsafeOrIncompleteUrls() {
        assertNull(TrackerPreferences.trackerProtocol("file:///etc/passwd"))
        assertNull(TrackerPreferences.trackerProtocol("https://user:pass@tracker.example/announce"))
        assertNull(TrackerPreferences.trackerProtocol("udp://tracker.example/announce"))
        assertNull(TrackerPreferences.trackerProtocol("not a url"))
    }
}
