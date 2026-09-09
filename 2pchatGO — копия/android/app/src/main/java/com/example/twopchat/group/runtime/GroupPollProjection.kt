package com.example.twopchat.group.runtime

import com.example.twopchat.group.ui.GroupPollOption
import com.example.twopchat.group.ui.GroupPollUi

internal fun projectGroupPoll(
    pollId: String,
    question: String,
    options: List<String>,
    isAnonymous: Boolean,
    latestVotesByMember: Map<String, Int>,
    localDeviceId: String,
): GroupPollUi = GroupPollUi(
    pollId = pollId,
    question = question,
    options = options.mapIndexed { optionId, text ->
        GroupPollOption(
            id = optionId,
            text = text,
            voteCount = latestVotesByMember.values.count { it == optionId },
            isVotedByMe = latestVotesByMember[localDeviceId] == optionId,
        )
    },
    totalVotes = latestVotesByMember.size,
    isAnonymous = isAnonymous,
)
