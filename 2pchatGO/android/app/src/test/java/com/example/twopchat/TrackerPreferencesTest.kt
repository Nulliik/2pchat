package com.example.twopchat

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
    fun trackerProtocolRejectsUnsafeOrIncompleteUrls() {
        assertNull(TrackerPreferences.trackerProtocol("file:///etc/passwd"))
        assertNull(TrackerPreferences.trackerProtocol("https://user:pass@tracker.example/announce"))
        assertNull(TrackerPreferences.trackerProtocol("udp://tracker.example/announce"))
        assertNull(TrackerPreferences.trackerProtocol("not a url"))
    }
}
