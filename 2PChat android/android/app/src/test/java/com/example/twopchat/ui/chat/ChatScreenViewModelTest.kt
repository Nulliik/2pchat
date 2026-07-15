package com.example.twopchat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenViewModelTest {
    @Test
    fun historyRefreshIncludesMessagePersistedWhileChatWasInactive() {
        val stale = Message("1", "old", false, "12:00")
        val receivedOnMainScreen = Message("2", "new", false, "12:01")

        val merged = mergeHistorySnapshot(
            persistedMessages = listOf(stale, receivedOnMainScreen),
            currentMessages = listOf(stale),
            defaultMessages = emptyList(),
            persistHistory = true,
        )

        assertEquals(listOf("1", "2"), merged.map { it.id })
    }

    @Test
    fun historyRefreshDeduplicatesLiveMessageAndUsesPersistedRow() {
        val persisted = Message("2", "stored", false, "12:01", status = "READ")
        val liveCopy = persisted.copy(text = "stale", status = "SENT")

        val merged = mergeHistorySnapshot(
            persistedMessages = listOf(persisted),
            currentMessages = listOf(liveCopy),
            defaultMessages = emptyList(),
            persistHistory = true,
        )

        assertEquals(listOf(persisted), merged)
    }
}
