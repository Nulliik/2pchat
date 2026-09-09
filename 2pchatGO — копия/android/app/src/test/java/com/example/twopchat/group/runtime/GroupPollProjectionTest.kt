package com.example.twopchat.group.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPollProjectionTest {
    @Test
    fun latestVotePerMemberProducesStableCountsAndLocalSelection() {
        val poll = projectGroupPoll(
            pollId = "poll-1",
            question = "Куда идём?",
            options = listOf("Парк", "Музей", "Кино"),
            isAnonymous = true,
            latestVotesByMember = mapOf(
                "local" to 1,
                "alice" to 1,
                "bob" to 2,
            ),
            localDeviceId = "local",
        )

        assertEquals(3, poll.totalVotes)
        assertEquals(listOf(0, 2, 1), poll.options.map { it.voteCount })
        assertFalse(poll.options[0].isVotedByMe)
        assertTrue(poll.options[1].isVotedByMe)
        assertTrue(poll.isAnonymous)
    }
}
