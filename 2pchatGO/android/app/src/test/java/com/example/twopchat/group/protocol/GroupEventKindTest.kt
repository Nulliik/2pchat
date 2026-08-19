package com.example.twopchat.group.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupEventKindTest {
    @Test
    fun pollKindsHaveDedicatedWireNames() {
        assertEquals(GroupEventKind.POLL, GroupEventKind.fromWire("poll"))
        assertEquals(GroupEventKind.POLL_VOTE, GroupEventKind.fromWire("poll_vote"))
    }
}
