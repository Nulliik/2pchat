package com.example.twopchat

/** Lamport-style version used to make concurrent pin/unpin operations converge. */
internal data class PinnedMessageStateVersion(
    val counter: Long,
    val actor: String,
)

internal fun shouldApplyPinnedMessageState(
    current: PinnedMessageStateVersion,
    incoming: PinnedMessageStateVersion,
): Boolean = incoming.counter > current.counter ||
    (incoming.counter == current.counter && incoming.actor > current.actor)

internal fun nextPinnedMessageStateVersion(
    current: PinnedMessageStateVersion,
    actor: String,
): PinnedMessageStateVersion = PinnedMessageStateVersion(
    counter = current.counter.coerceAtLeast(0L) + 1L,
    actor = actor,
)
