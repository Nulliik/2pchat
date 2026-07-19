package com.example.twopchat.ui.chat

import org.junit.Assert.assertFalse
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
    fun arrivalAnimationIsConsumedOnceAndExpires() {
        val tracker = MessageArrivalAnimationTracker(lifetimeMs = 1_000L)

        tracker.mark("fresh", nowEpochMs = 1_000L)
        assertTrue(tracker.consume("fresh", nowEpochMs = 1_500L))
        assertFalse(tracker.consume("fresh", nowEpochMs = 1_500L))

        tracker.mark("stale", nowEpochMs = 2_000L)
        assertFalse(tracker.consume("stale", nowEpochMs = 3_001L))
    }
}
