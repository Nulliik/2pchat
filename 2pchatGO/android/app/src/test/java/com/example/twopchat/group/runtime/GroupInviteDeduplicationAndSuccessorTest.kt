package com.example.twopchat.group.runtime

import com.example.twopchat.data.ChatDatabaseHelper
import com.example.twopchat.group.ui.GroupMember
import com.example.twopchat.ui.chat.Message
import org.junit.Assert.*
import org.junit.Test

class GroupInviteDeduplicationAndSuccessorTest {

    @Test
    fun testExtractGroupIdFromInviteId() {
        val msg1 = Message(
            id = "invite_group-123-abc_inv-xyz",
            text = "👋 Приглашение в группу",
            isMe = false,
            timestamp = "14:19",
        )
        val extracted1 = ChatDatabaseHelper.extractGroupIdFromInvite(msg1)
        assertEquals("group-123-abc", extracted1)

        val msg2 = Message(
            id = "invite_group-456",
            text = "👋 Приглашение в группу",
            isMe = true,
            timestamp = "14:20",
        )
        val extracted2 = ChatDatabaseHelper.extractGroupIdFromInvite(msg2)
        assertEquals("group-456", extracted2)
    }

    @Test
    fun testExtractGroupIdFromInviteUrl() {
        val msg = Message(
            id = "msg_random_id",
            text = "👋 Приглашение в группу «Хихи хаха»!\n\n2pchat://connect?group=336a5c77-b72c-4a6c-a16f-50288ff1449b&group_token=inv_987&name=doggy",
            isMe = false,
            timestamp = "14:19",
        )
        val extracted = ChatDatabaseHelper.extractGroupIdFromInvite(msg)
        assertEquals("336a5c77-b72c-4a6c-a16f-50288ff1449b", extracted)
    }

    @Test
    fun testDeduplicateInviteMessagesKeepsLatest() {
        val groupId = "test-group-id"
        val invite1 = Message(
            id = "invite_${groupId}_inv-1",
            text = "👋 Приглашение в группу «tesr»!\n\n2pchat://connect?group=$groupId&group_token=inv-1&name=puppy",
            isMe = false,
            timestamp = "14:06",
        )
        val normalMsg = Message(
            id = "msg-1",
            text = "Обычное сообщение",
            isMe = false,
            timestamp = "14:06",
        )
        val invite2 = Message(
            id = "invite_${groupId}_inv-2",
            text = "👋 Приглашение в группу «tesr»!\n\n2pchat://connect?group=$groupId&group_token=inv-2&name=puppy",
            isMe = false,
            timestamp = "14:07",
        )

        val input = listOf(invite1, normalMsg, invite2)
        val seen = mutableSetOf<String>()
        val result = ArrayList<Message>()
        for (i in input.indices.reversed()) {
            val msg = input[i]
            val gid = ChatDatabaseHelper.extractGroupIdFromInvite(msg)
            if (gid != null) {
                if (!seen.add(gid)) {
                    continue
                }
            }
            result.add(msg)
        }
        result.reverse()

        assertEquals(2, result.size)
        assertEquals("msg-1", result[0].id)
        assertEquals("invite_${groupId}_inv-2", result[1].id)
    }

    @Test
    fun testSuccessorMemberResolutionByTransportFingerprint() {
        val successorFingerprint = "P9TrbRRXF7zHQWERTY"
        val members = listOf(
            GroupMember(
                memberId = "dev_owner_001",
                displayName = "puppy",
                transportFingerprint = "FP_OWNER_XYZ",
            ),
            GroupMember(
                memberId = "dev_member_002",
                displayName = "doggy",
                transportFingerprint = successorFingerprint,
            ),
        )

        val successorMember = members.firstOrNull {
            (it.transportFingerprint.isNotBlank() && it.transportFingerprint.equals(successorFingerprint, ignoreCase = true)) ||
            it.memberId.equals(successorFingerprint, ignoreCase = true) ||
            (it.transportFingerprint.isNotBlank() && GroupChatCoordinator.stableDeviceId(it.transportFingerprint).equals(successorFingerprint, ignoreCase = true)) ||
            (successorFingerprint.isNotBlank() && GroupChatCoordinator.stableDeviceId(successorFingerprint).equals(it.memberId, ignoreCase = true))
        }

        assertNotNull("Successor member should be found by transportFingerprint", successorMember)
        assertEquals("doggy", successorMember?.displayName)
    }

    @Test
    fun testSuccessorMemberResolutionByStableDeviceId() {
        val successorFingerprint = "P9TrbRRXF7zHQWERTY"
        val stableDevId = GroupChatCoordinator.stableDeviceId(successorFingerprint)
        val members = listOf(
            GroupMember(
                memberId = stableDevId,
                displayName = "doggy",
                transportFingerprint = "",
            ),
        )

        val successorMember = members.firstOrNull {
            (it.transportFingerprint.isNotBlank() && it.transportFingerprint.equals(successorFingerprint, ignoreCase = true)) ||
            it.memberId.equals(successorFingerprint, ignoreCase = true) ||
            (it.transportFingerprint.isNotBlank() && GroupChatCoordinator.stableDeviceId(it.transportFingerprint).equals(successorFingerprint, ignoreCase = true)) ||
            (successorFingerprint.isNotBlank() && GroupChatCoordinator.stableDeviceId(successorFingerprint).equals(it.memberId, ignoreCase = true))
        }

        assertNotNull("Successor member should be resolved via stableDeviceId fallback", successorMember)
        assertEquals("doggy", successorMember?.displayName)
    }
}
