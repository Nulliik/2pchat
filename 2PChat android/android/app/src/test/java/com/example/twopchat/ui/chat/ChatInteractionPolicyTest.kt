package com.example.twopchat.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInteractionPolicyTest {
    @Test
    fun incomingMessageDoesNotInterruptReadingOlderHistory() {
        assertFalse(
            shouldAutoScrollAfterAppend(
                previousItemCount = 40,
                lastVisibleItemIndex = 12,
                newestMessageIsMine = false,
            )
        )
    }

    @Test
    fun incomingMessageScrollsWhenUserIsAlreadyAtBottom() {
        assertTrue(
            shouldAutoScrollAfterAppend(
                previousItemCount = 40,
                lastVisibleItemIndex = 39,
                newestMessageIsMine = false,
            )
        )
    }

    @Test
    fun ownMessageScrollsEvenWhenHistoryIsVisible() {
        assertTrue(
            shouldAutoScrollAfterAppend(
                previousItemCount = 40,
                lastVisibleItemIndex = 12,
                newestMessageIsMine = true,
            )
        )
    }

    @Test
    fun incomingMessageIsCountedWhileReadingOlderHistory() {
        assertTrue(
            shouldCountIncomingMessage(
                previousItemCount = 40,
                lastVisibleItemIndex = 12,
            )
        )
    }

    @Test
    fun incomingMessageIsNotCountedAtBottomOrBeforeLayout() {
        assertFalse(
            shouldCountIncomingMessage(
                previousItemCount = 40,
                lastVisibleItemIndex = 39,
            )
        )
        assertFalse(
            shouldCountIncomingMessage(
                previousItemCount = 40,
                lastVisibleItemIndex = -1,
            )
        )
    }

    @Test
    fun historyPrependedAboveViewportIsNotTreatedAsNewTailMessage() {
        assertFalse(
            didAppendNewestMessage(
                previousMessageCount = 20,
                currentMessageCount = 120,
                previousNewestMessageId = "latest",
                currentNewestMessageId = "latest",
            )
        )
        assertTrue(
            didAppendNewestMessage(
                previousMessageCount = 20,
                currentMessageCount = 21,
                previousNewestMessageId = "latest",
                currentNewestMessageId = "new-latest",
            )
        )
    }

    @Test
    fun bottomDetectionRequiresNewestItemToBeVisible() {
        assertTrue(isMessageListAtBottom(totalItemCount = 0, lastVisibleItemIndex = -1))
        assertTrue(isMessageListAtBottom(totalItemCount = 40, lastVisibleItemIndex = 39))
        assertFalse(isMessageListAtBottom(totalItemCount = 40, lastVisibleItemIndex = 38))
    }

    @Test
    fun fullHistoryReplacementKeepsBottomPinned() {
        val messages = (1..100).map { index ->
            Message(index.toString(), "message $index", false, "12:00")
        }

        assertEquals(
            99,
            historyReplacementScrollIndex(
                messages = messages,
                anchorMessageId = "40",
                wasAtBottom = true,
            ),
        )
    }

    @Test
    fun fullHistoryReplacementKeepsVisibleMessageAnchored() {
        val messages = (1..100).map { index ->
            Message(index.toString(), "message $index", false, "12:00")
        }

        assertEquals(
            39,
            historyReplacementScrollIndex(
                messages = messages,
                anchorMessageId = "40",
                wasAtBottom = false,
            ),
        )
        assertEquals(
            -1,
            historyReplacementScrollIndex(
                messages = emptyList(),
                anchorMessageId = null,
                wasAtBottom = true,
            ),
        )
    }

    @Test
    fun arrivalAnimationIsConsumedOnceAndExpires() {
        val tracker = MessageArrivalAnimationTracker(lifetimeMs = 1_000L)

        tracker.mark("fresh", nowEpochMs = 1_000L)
        assertTrue(tracker.consume("fresh", nowEpochMs = 1_500L))
        assertFalse(tracker.consume("fresh", nowEpochMs = 1_500L))

        tracker.mark("stale", nowEpochMs = 2_000L)
        assertFalse(tracker.consume("stale", nowEpochMs = 3_001L))
    }
}
