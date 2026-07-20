package com.example.twopchat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenViewModelTest {
    @Test
    fun recentHistoryMessagesAreAppendedToRetainedConversation() {
        val oldMessages = listOf(
            Message("1", "old", false, "12:00", sentAtEpochMs = 1_000L),
            Message("2", "older", false, "12:01", sentAtEpochMs = 2_000L),
        )
        val recent = listOf(
            Message("3", "new", false, "12:02", sentAtEpochMs = 3_000L),
            Message("4", "newer", false, "12:03", sentAtEpochMs = 4_000L),
        )

        val merged = mergeRecentHistoryMessages(oldMessages, recent)

        assertEquals(listOf("1", "2", "3", "4"), merged.map { it.id })
    }

    @Test
    fun recentHistoryRefreshesExistingRowWithoutReordering() {
        val staleLatest = Message("2", "new", false, "12:01", status = "SENT")
        val authoritativeLatest = staleLatest.copy(status = "READ")

        val merged = mergeRecentHistoryMessages(
            currentMessages = listOf(Message("1", "old", false, "12:00"), staleLatest),
            recentPersistedMessages = listOf(authoritativeLatest),
        )

        assertEquals(listOf("1", "2"), merged.map { it.id })
        assertEquals("READ", merged.last().status)
    }

    @Test
    fun recentHistoryStaysBeforeLiveMessageReceivedDuringQuery() {
        val retained = Message("1", "old", false, "12:00", sentAtEpochMs = 1_000L)
        val live = Message("4", "live", false, "12:03", sentAtEpochMs = 4_000L)
        val recent = listOf(
            Message("2", "new", false, "12:01", sentAtEpochMs = 2_000L),
            Message("3", "newer", false, "12:02", sentAtEpochMs = 3_000L),
        )

        val merged = mergeRecentHistoryMessages(listOf(retained, live), recent)

        assertEquals(listOf("1", "2", "3", "4"), merged.map { it.id })
    }

    @Test
    fun fastHistoryLimitUsesUnreadCountAndIsBounded() {
        assertEquals(40, fastHistoryMessageLimit(0))
        assertEquals(40, fastHistoryMessageLimit(5))
        assertEquals(100, fastHistoryMessageLimit(500))
    }

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

    @Test
    fun historyRefreshDoesNotRevertVisibleMessageFromReadToSent() {
        val persisted = Message("2", "stored", false, "12:01", status = "SENT")
        val visibleReadCopy = persisted.copy(status = "READ")

        val merged = mergeHistorySnapshot(
            persistedMessages = listOf(persisted),
            currentMessages = listOf(visibleReadCopy),
            defaultMessages = emptyList(),
            persistHistory = true,
        )

        assertEquals("READ", merged.single().status)
    }
}
