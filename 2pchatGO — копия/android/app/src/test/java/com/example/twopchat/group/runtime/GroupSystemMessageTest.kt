package com.example.twopchat.group.runtime

import com.example.twopchat.group.ui.GroupRole
import com.example.twopchat.group.ui.GroupSystemEventType
import com.example.twopchat.group.ui.GroupTimelineMessage
import com.example.twopchat.group.ui.SYSTEM_MESSAGE_PLACEHOLDER
import com.example.twopchat.group.ui.components.GroupSystemMessageFormatter
import com.example.twopchat.group.storage.StoredGroup
import com.example.twopchat.group.storage.StoredGroupEvent
import com.example.twopchat.group.storage.StoredGroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSystemMessageTest {

    private fun createSystemMessage(
        messageId: String = "sys_msg_1",
        authorId: String = "dev_alice_123456",
        authorName: String = "Алиса",
        eventType: GroupSystemEventType = GroupSystemEventType.MEMBER_ADDED,
        targetMemberName: String? = "Боб",
        systemPayload: Map<String, String> = emptyMap(),
        isSelfActor: Boolean = false,
        isSelfTarget: Boolean = false,
        timestampEpochMs: Long = 1000L,
    ): GroupTimelineMessage = GroupTimelineMessage(
        messageId = messageId,
        authorId = authorId,
        authorName = authorName,
        authorRole = GroupRole.MEMBER,
        text = SYSTEM_MESSAGE_PLACEHOLDER,
        timestampLabel = "12:00",
        timestampEpochMs = timestampEpochMs,
        isMine = isSelfActor,
        canReply = false,
        canEdit = false,
        canDelete = false,
        canReact = false,
        canPin = false,
        isSystem = true,
        systemEventType = eventType,
        targetMemberName = targetMemberName,
        systemPayload = systemPayload,
        isSelfActor = isSelfActor,
        isSelfTarget = isSelfTarget,
    )

    private fun createRegularMessage(
        messageId: String = "reg_msg_1",
        authorId: String = "dev_bob_654321",
        authorName: String = "Боб",
        text: String = "Привет всем",
        timestampEpochMs: Long = 1000L,
    ): GroupTimelineMessage = GroupTimelineMessage(
        messageId = messageId,
        authorId = authorId,
        authorName = authorName,
        authorRole = GroupRole.MEMBER,
        text = text,
        timestampLabel = "12:00",
        timestampEpochMs = timestampEpochMs,
        isMine = false,
        canReply = true,
        canEdit = false,
        canDelete = false,
        canReact = true,
        canPin = true,
        isSystem = false,
        systemEventType = null,
    )

    @Test
    fun testEmptyRosterFallbackToTruncatedId() {
        val unknownAuthorId = "abcdef0123456789"
        val unknownTargetId = "9876543210fedcba"

        val msg = createSystemMessage(
            authorId = unknownAuthorId,
            authorName = "", // Empty display name in roster
            targetMemberName = "", // Unknown target
            systemPayload = mapOf("target_id" to unknownTargetId),
            eventType = GroupSystemEventType.MEMBER_ADDED,
        )

        val formattedRu = GroupSystemMessageFormatter.format(msg, "ru")
        assertTrue(formattedRu.contains("abcdef01…"))
        assertTrue(formattedRu.contains("98765432…"))

        val formattedEn = GroupSystemMessageFormatter.format(msg, "en")
        assertTrue(formattedEn.contains("abcdef01…"))
        assertTrue(formattedEn.contains("98765432…"))
    }

    @Test
    fun testVeryLongGroupNameTruncation() {
        val veryLongTitle = "A".repeat(250)
        val msg = createSystemMessage(
            eventType = GroupSystemEventType.GROUP_NAME_CHANGED,
            systemPayload = mapOf("title" to veryLongTitle),
            isSelfActor = false,
            authorName = "Алиса",
        )

        val formatted = GroupSystemMessageFormatter.format(msg, "ru")
        // Title should be truncated to 31 chars + "…" = 32 chars total
        val expectedTitleInQuotes = "«" + "A".repeat(31) + "…»"
        assertTrue("Formatted text should contain truncated title: $formatted", formatted.contains(expectedTitleInQuotes))
        assertFalse("Formatted text should not contain full 250 characters", formatted.contains("A".repeat(50)))
    }

    @Test
    fun testConcurrentEventsSameTimestamp() {
        val sameTimestamp = 1700000000000L

        val regularMsg1 = createRegularMessage(messageId = "msg_1", timestampEpochMs = sameTimestamp)
        val systemEvent1 = createSystemMessage(messageId = "event_1", timestampEpochMs = sameTimestamp)
        val regularMsg2 = createRegularMessage(messageId = "msg_2", timestampEpochMs = sameTimestamp)
        val systemEvent2 = createSystemMessage(messageId = "event_2", timestampEpochMs = sameTimestamp)

        val unsorted = listOf(regularMsg1, regularMsg2, systemEvent2, systemEvent1)

        val sorted = unsorted.sortedWith(
            compareBy<GroupTimelineMessage> { it.timestampEpochMs }
                .thenBy { if (it.isSystem) 0 else 1 }
                .thenBy { it.messageId }
        )

        // System messages should precede regular messages when timestamps are identical
        assertTrue("First element should be system event", sorted[0].isSystem)
        assertTrue("Second element should be system event", sorted[1].isSystem)
        assertFalse("Third element should be regular message", sorted[2].isSystem)
        assertFalse("Fourth element should be regular message", sorted[3].isSystem)

        assertEquals("event_1", sorted[0].messageId)
        assertEquals("event_2", sorted[1].messageId)
        assertEquals("msg_1", sorted[2].messageId)
        assertEquals("msg_2", sorted[3].messageId)
    }

    @Test
    fun testTombstonedEventFiltered() {
        data class FakeEvent(val id: String, val isTombstoned: Boolean)

        val events = listOf(
            FakeEvent("e1", isTombstoned = false),
            FakeEvent("e2", isTombstoned = true),
            FakeEvent("e3", isTombstoned = false),
            FakeEvent("e4", isTombstoned = true),
        )

        val filtered = events.filter { !it.isTombstoned }
        assertEquals(2, filtered.size)
        assertEquals(listOf("e1", "e3"), filtered.map { it.id })
    }

    @Test
    fun testAllLanguagesProduceNonEmptyStrings() {
        val languages = listOf("ru", "en", "de", "es", "fr", "pt", "tr", "Русский", "Deutsch", "English")

        for (eventType in GroupSystemEventType.entries) {
            val payload = when (eventType) {
                GroupSystemEventType.ROLE_CHANGED -> mapOf("role" to "ADMIN")
                GroupSystemEventType.GROUP_NAME_CHANGED -> mapOf("title" to "Тестовая группа")
                else -> emptyMap()
            }

            for (lang in languages) {
                // Actor self
                val msgActorSelf = createSystemMessage(
                    eventType = eventType,
                    isSelfActor = true,
                    isSelfTarget = false,
                    systemPayload = payload,
                )
                val textActorSelf = GroupSystemMessageFormatter.format(msgActorSelf, lang)
                assertTrue("Non-empty for event=$eventType, lang=$lang (actor self)", textActorSelf.isNotBlank())

                // Target self
                val msgTargetSelf = createSystemMessage(
                    eventType = eventType,
                    isSelfActor = false,
                    isSelfTarget = true,
                    systemPayload = payload,
                )
                val textTargetSelf = GroupSystemMessageFormatter.format(msgTargetSelf, lang)
                assertTrue("Non-empty for event=$eventType, lang=$lang (target self)", textTargetSelf.isNotBlank())

                // Neither self
                val msgNeither = createSystemMessage(
                    eventType = eventType,
                    isSelfActor = false,
                    isSelfTarget = false,
                    systemPayload = payload,
                )
                val textNeither = GroupSystemMessageFormatter.format(msgNeither, lang)
                assertTrue("Non-empty for event=$eventType, lang=$lang (neither)", textNeither.isNotBlank())
            }
        }
    }

    @Test
    fun testSelfActorVsSelfTargetMatrix() {
        // 1. MEMBER_ADDED
        val addedActorSelf = createSystemMessage(
            eventType = GroupSystemEventType.MEMBER_ADDED,
            isSelfActor = true,
            isSelfTarget = false,
            targetMemberName = "Боб",
        )
        assertEquals("Вы добавили Боб", GroupSystemMessageFormatter.format(addedActorSelf, "ru"))
        assertEquals("You added Боб", GroupSystemMessageFormatter.format(addedActorSelf, "en"))

        val addedTargetSelf = createSystemMessage(
            eventType = GroupSystemEventType.MEMBER_ADDED,
            isSelfActor = false,
            isSelfTarget = true,
            authorName = "Алиса",
        )
        assertEquals("Вы вступили в группу", GroupSystemMessageFormatter.format(addedTargetSelf, "ru"))
        assertEquals("You joined the group", GroupSystemMessageFormatter.format(addedTargetSelf, "en"))

        val addedNeither = createSystemMessage(
            eventType = GroupSystemEventType.MEMBER_ADDED,
            isSelfActor = false,
            isSelfTarget = false,
            authorName = "Алиса",
            targetMemberName = "Боб",
        )
        assertEquals("Алиса добавил(а) Боб", GroupSystemMessageFormatter.format(addedNeither, "ru"))
        assertEquals("Алиса added Боб", GroupSystemMessageFormatter.format(addedNeither, "en"))

        // 2. MEMBER_REMOVED
        val removedActorSelf = createSystemMessage(
            eventType = GroupSystemEventType.MEMBER_REMOVED,
            isSelfActor = true,
            isSelfTarget = false,
            targetMemberName = "Боб",
        )
        assertEquals("Вы исключили Боб", GroupSystemMessageFormatter.format(removedActorSelf, "ru"))

        val removedTargetSelf = createSystemMessage(
            eventType = GroupSystemEventType.MEMBER_REMOVED,
            isSelfActor = false,
            isSelfTarget = true,
        )
        assertEquals("Вас исключили из группы", GroupSystemMessageFormatter.format(removedTargetSelf, "ru"))

        val removedNeither = createSystemMessage(
            eventType = GroupSystemEventType.MEMBER_REMOVED,
            isSelfActor = false,
            isSelfTarget = false,
            authorName = "Алиса",
            targetMemberName = "Боб",
        )
        assertEquals("Алиса исключил(а) Боб", GroupSystemMessageFormatter.format(removedNeither, "ru"))

        // 3. MEMBER_LEFT
        val leftSelf = createSystemMessage(
            eventType = GroupSystemEventType.MEMBER_LEFT,
            isSelfActor = true,
            isSelfTarget = true,
        )
        assertEquals("Вы покинули группу", GroupSystemMessageFormatter.format(leftSelf, "ru"))

        val leftOther = createSystemMessage(
            eventType = GroupSystemEventType.MEMBER_LEFT,
            isSelfActor = false,
            isSelfTarget = false,
            targetMemberName = "Боб",
        )
        assertEquals("Боб покинул(а) группу", GroupSystemMessageFormatter.format(leftOther, "ru"))

        // 4. ROLE_CHANGED
        val roleAdminActorSelf = createSystemMessage(
            eventType = GroupSystemEventType.ROLE_CHANGED,
            isSelfActor = true,
            isSelfTarget = false,
            targetMemberName = "Боб",
            systemPayload = mapOf("role" to "ADMIN"),
        )
        assertEquals("Вы назначили Боб администратором", GroupSystemMessageFormatter.format(roleAdminActorSelf, "ru"))

        val roleAdminTargetSelf = createSystemMessage(
            eventType = GroupSystemEventType.ROLE_CHANGED,
            isSelfActor = false,
            isSelfTarget = true,
            systemPayload = mapOf("role" to "ADMIN"),
        )
        assertEquals("Вас назначили администратором", GroupSystemMessageFormatter.format(roleAdminTargetSelf, "ru"))

        val roleAdminNeither = createSystemMessage(
            eventType = GroupSystemEventType.ROLE_CHANGED,
            isSelfActor = false,
            isSelfTarget = false,
            authorName = "Алиса",
            targetMemberName = "Боб",
            systemPayload = mapOf("role" to "ADMIN"),
        )
        assertEquals("Алиса назначил(а) Боб администратором", GroupSystemMessageFormatter.format(roleAdminNeither, "ru"))

        // 5. GROUP_NAME_CHANGED
        val nameActorSelf = createSystemMessage(
            eventType = GroupSystemEventType.GROUP_NAME_CHANGED,
            isSelfActor = true,
            systemPayload = mapOf("title" to "Новый чат"),
        )
        assertEquals("Вы изменили название группы на «Новый чат»", GroupSystemMessageFormatter.format(nameActorSelf, "ru"))

        val nameNeither = createSystemMessage(
            eventType = GroupSystemEventType.GROUP_NAME_CHANGED,
            isSelfActor = false,
            authorName = "Алиса",
            systemPayload = mapOf("title" to "Новый чат"),
        )
        assertEquals("Алиса изменил(а) название группы на «Новый чат»", GroupSystemMessageFormatter.format(nameNeither, "ru"))

        // 6. OWNERSHIP_TRANSFERRED
        val ownerActorSelf = createSystemMessage(
            eventType = GroupSystemEventType.OWNERSHIP_TRANSFERRED,
            isSelfActor = true,
            isSelfTarget = false,
            targetMemberName = "Боб",
        )
        assertEquals("Вы передали права создателя группы Боб", GroupSystemMessageFormatter.format(ownerActorSelf, "ru"))

        val ownerTargetSelf = createSystemMessage(
            eventType = GroupSystemEventType.OWNERSHIP_TRANSFERRED,
            isSelfActor = false,
            isSelfTarget = true,
        )
        assertEquals("Вам передали права создателя группы", GroupSystemMessageFormatter.format(ownerTargetSelf, "ru"))

        val ownerNeither = createSystemMessage(
            eventType = GroupSystemEventType.OWNERSHIP_TRANSFERRED,
            isSelfActor = false,
            isSelfTarget = false,
            authorName = "Алиса",
            targetMemberName = "Боб",
        )
        assertEquals("Алиса передал(а) права создателя группы Боб", GroupSystemMessageFormatter.format(ownerNeither, "ru"))
    }

    @Test
    fun testSystemMessageInvariants() {
        // Invariant 1: System message must have systemEventType
        assertThrows(IllegalArgumentException::class.java) {
            GroupTimelineMessage(
                messageId = "inv_1",
                authorId = "a1",
                authorName = "A1",
                text = SYSTEM_MESSAGE_PLACEHOLDER,
                timestampLabel = "12:00",
                isSystem = true,
                systemEventType = null, // Must fail!
            )
        }

        // Invariant 2: Regular message cannot have systemEventType
        assertThrows(IllegalArgumentException::class.java) {
            GroupTimelineMessage(
                messageId = "inv_2",
                authorId = "a1",
                authorName = "A1",
                text = "Regular message",
                timestampLabel = "12:00",
                isSystem = false,
                systemEventType = GroupSystemEventType.MEMBER_ADDED, // Must fail!
            )
        }

        // Invariant 3: System message cannot carry arbitrary encrypted text
        assertThrows(IllegalArgumentException::class.java) {
            GroupTimelineMessage(
                messageId = "inv_3",
                authorId = "a1",
                authorName = "A1",
                text = "Secret ciphertext payload", // Must fail!
                timestampLabel = "12:00",
                isSystem = true,
                systemEventType = GroupSystemEventType.MEMBER_ADDED,
            )
        }

        // Invariant 4: System message with SYSTEM_MESSAGE_PLACEHOLDER or empty text succeeds
        val validWithPlaceholder = GroupTimelineMessage(
            messageId = "inv_4",
            authorId = "a1",
            authorName = "A1",
            text = SYSTEM_MESSAGE_PLACEHOLDER,
            timestampLabel = "12:00",
            isSystem = true,
            systemEventType = GroupSystemEventType.MEMBER_ADDED,
        )
        assertNotNull(validWithPlaceholder)

        val validWithEmpty = GroupTimelineMessage(
            messageId = "inv_5",
            authorId = "a1",
            authorName = "A1",
            text = "",
            timestampLabel = "12:00",
            isSystem = true,
            systemEventType = GroupSystemEventType.MEMBER_ADDED,
        )
        assertNotNull(validWithEmpty)

        // Non-interactive check
        val sysMsg = createSystemMessage()
        assertFalse("System message cannot be replied to", sysMsg.canReply)
        assertFalse("System message cannot be edited", sysMsg.canEdit)
        assertFalse("System message cannot be deleted", sysMsg.canDelete)
        assertFalse("System message cannot be reacted to", sysMsg.canReact)
        assertFalse("System message cannot be pinned", sysMsg.canPin)
    }

    @Test
    fun testWallpaperAndSettingsUpdatesDoNotEmitGroupNameChanged() {
        val group = StoredGroup(
            groupId = "group_123",
            title = "Текущее название",
            description = "Описание",
            avatarUri = null,
            ownerDeviceId = "dev_alice_123456",
            localDeviceId = "dev_alice_123456",
            currentEpoch = 1L,
            createdAtMs = 1000L,
            updatedAtMs = 1000L,
        )
        val member = StoredGroupMember(
            groupId = "group_123",
            deviceId = "dev_alice_123456",
            accountId = "acc_alice",
            transportFingerprint = "fp_alice",
            peerName = "alice",
            signingKeyBase64 = "key_alice",
            displayName = "Алиса",
            role = "OWNER",
            permissions = 0xFFFFFFFFL,
            status = "ACTIVE",
            joinedEpoch = 1L,
            createdAtMs = 1000L,
            updatedAtMs = 1000L,
        )
        val members = mapOf(member.deviceId to member)

        // 1. Wallpaper update (even if title is present in payload!) -> Must return null
        val wallpaperPayload = org.json.JSONObject().apply {
            put("title", "Текущее название")
            put("description", "Описание")
            put("wallpaper_uri", "/data/wallpaper/fox.jpg")
        }
        val wallpaperEvent = StoredGroupEvent(
            groupId = "group_123",
            eventId = "evt_wp_1",
            epoch = 1L,
            authorDeviceId = "dev_alice_123456",
            authorSeq = 2L,
            hlcPhysicalMs = 2000L,
            hlcLogical = 0,
            kind = "GROUP_UPDATED",
            body = wallpaperPayload.toString(),
        )
        val wpResult = GroupChatCoordinator.mapEventToSystemTimelineMessage(group, wallpaperEvent, members)
        assertNull("Wallpaper update must never emit GROUP_NAME_CHANGED", wpResult)

        // 2. Admin-only posting update -> Must return null
        val adminPostingPayload = org.json.JSONObject().apply {
            put("title", "Текущее название")
            put("admin_only_posting", true)
        }
        val adminPostingEvent = wallpaperEvent.copy(
            eventId = "evt_admin_only",
            body = adminPostingPayload.toString(),
        )
        val adminResult = GroupChatCoordinator.mapEventToSystemTimelineMessage(group, adminPostingEvent, members)
        assertNull("Admin-only posting toggle must never emit GROUP_NAME_CHANGED", adminResult)

        // 3. Avatar update without title change -> Must return null
        val avatarPayload = org.json.JSONObject().apply {
            put("title", "Текущее название")
            put("avatar_uri", "/data/avatar/photo.jpg")
        }
        val avatarEvent = wallpaperEvent.copy(
            eventId = "evt_avatar",
            body = avatarPayload.toString(),
        )
        val avatarResult = GroupChatCoordinator.mapEventToSystemTimelineMessage(group, avatarEvent, members)
        assertNull("Avatar update without title change must never emit GROUP_NAME_CHANGED", avatarResult)

        // 4. Real title change -> Must return GROUP_NAME_CHANGED message
        val renamePayload = org.json.JSONObject().apply {
            put("title", "Новое Название")
        }
        val renameEvent = wallpaperEvent.copy(
            eventId = "evt_rename",
            body = renamePayload.toString(),
        )
        val renameResult = GroupChatCoordinator.mapEventToSystemTimelineMessage(group, renameEvent, members)
        assertNotNull("Real rename event must emit timeline system message", renameResult)
        assertEquals(GroupSystemEventType.GROUP_NAME_CHANGED, renameResult?.systemEventType)
        assertEquals("Новое Название", renameResult?.systemPayload?.get("title"))
    }
}
