package com.example.twopchat.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsScrollTest {
    @Test
    fun autoFollowOnlyWhenViewportIsNearLogTail() {
        assertTrue(isNearLogTail(scrollValue = 0, maxScrollValue = 0))
        assertTrue(isNearLogTail(scrollValue = 968, maxScrollValue = 1_000))
        assertFalse(isNearLogTail(scrollValue = 967, maxScrollValue = 1_000))
        assertFalse(isNearLogTail(scrollValue = 200, maxScrollValue = 1_000))
    }
}
