package com.example.twopchat.group.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupNotificationServiceTest {
    @Test
    fun mentionUsesProfileNameAndRequiresAUsernameBoundary() {
        assertTrue(GroupNotificationService.isGroupMention("Привет, @Alice!", "Alice"))
        assertTrue(GroupNotificationService.isGroupMention("@алиса, ответь", "Алиса"))
        assertTrue(GroupNotificationService.isGroupMention("Срочно @all созвон", "Alice"))
        assertTrue(GroupNotificationService.isGroupMention("Внимание @everyone!", "Alice"))
        assertFalse(GroupNotificationService.isGroupMention("Привет, @Alice2", "Alice"))
        assertFalse(GroupNotificationService.isGroupMention("Привет всем", "Alice"))
        assertFalse(GroupNotificationService.isGroupMention("@Alice", ""))
    }
}
