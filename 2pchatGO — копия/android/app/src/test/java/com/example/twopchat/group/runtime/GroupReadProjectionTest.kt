package com.example.twopchat.group.runtime

import com.example.twopchat.group.storage.StoredGroupMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupReadProjectionTest {
    @Test
    fun receiptForNewerMessageAlsoMarksEarlierMessagesRead() {
        val old = message("old", 10)
        val horizon = message("new", 20)
        val later = message("later", 30)
        val readHorizons = mapOf("alice" to horizon)

        assertEquals(setOf("alice"), membersWhoReadMessage(old, readHorizons))
        assertEquals(setOf("alice"), membersWhoReadMessage(horizon, readHorizons))
        assertEquals(emptySet<String>(), membersWhoReadMessage(later, readHorizons))
    }

    private fun message(id: String, time: Long) = StoredGroupMessage(
        groupId = "group",
        messageId = id,
        authorDeviceId = "author",
        authorSeq = time,
        hlcPhysicalMs = time,
        hlcLogical = 0,
        body = id,
        edited = false,
        deleted = false,
        unread = false,
        createdAtMs = time,
        updatedAtMs = time,
    )
}
