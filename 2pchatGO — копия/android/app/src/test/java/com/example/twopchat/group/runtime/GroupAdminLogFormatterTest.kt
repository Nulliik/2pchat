package com.example.twopchat.group.runtime

import com.example.twopchat.group.storage.StoredGroupEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAdminLogFormatterTest {

    private fun createEvent(kind: String, body: String? = null): StoredGroupEvent =
        StoredGroupEvent(
            groupId = "group_123",
            eventId = "evt_123",
            epoch = 1L,
            authorDeviceId = "dev_author_1",
            authorSeq = 1L,
            hlcPhysicalMs = 1000L,
            hlcLogical = 0,
            kind = kind,
            body = body,
        )

    @Test
    fun testWallpaperChangeAndRemoval() {
        val setEvent = createEvent("GROUP_UPDATED")
        val setPayload = JSONObject().apply {
            put("wallpaper_uri", "/data/wallpapers/1.jpg")
        }
        val setRu = GroupChatCoordinator.formatAdminAction(
            setEvent, setPayload, "doggy", null, "ru"
        )
        assertEquals("изменил(а) обои чата", setRu)

        val setEn = GroupChatCoordinator.formatAdminAction(
            setEvent, setPayload, "doggy", null, "en"
        )
        assertEquals("changed chat wallpaper", setEn)

        val removePayload = JSONObject().apply {
            put("wallpaper_uri", "")
        }
        val removeRu = GroupChatCoordinator.formatAdminAction(
            setEvent, removePayload, "doggy", null, "ru"
        )
        assertEquals("удалил(а) обои чата", removeRu)

        val removeEn = GroupChatCoordinator.formatAdminAction(
            setEvent, removePayload, "doggy", null, "en"
        )
        assertEquals("removed chat wallpaper", removeEn)
    }

    @Test
    fun testAvatarChangeAndRemoval() {
        val event = createEvent("GROUP_UPDATED")
        val setPayload = JSONObject().apply {
            put("avatar_uri", "/data/avatars/group.jpg")
        }
        val setRu = GroupChatCoordinator.formatAdminAction(
            event, setPayload, "doggy", null, "ru"
        )
        assertEquals("изменил(а) фото группы", setRu)

        val removePayload = JSONObject().apply {
            put("avatar_uri", "")
        }
        val removeRu = GroupChatCoordinator.formatAdminAction(
            event, removePayload, "doggy", null, "ru"
        )
        assertEquals("удалил(а) фото группы", removeRu)
    }

    @Test
    fun testAdminOnlyPostingToggles() {
        val event = createEvent("GROUP_UPDATED")

        val enablePayload = JSONObject().apply {
            put("admin_only_posting", true)
        }
        val enableRu = GroupChatCoordinator.formatAdminAction(
            event, enablePayload, "doggy", null, "ru"
        )
        assertEquals("включил(а) режим «сообщения только от администраторов»", enableRu)

        val disablePayload = JSONObject().apply {
            put("admin_only_posting", false)
        }
        val disableRu = GroupChatCoordinator.formatAdminAction(
            event, disablePayload, "doggy", null, "ru"
        )
        assertEquals("разрешил(а) всем участникам отправлять сообщения", disableRu)
    }

    @Test
    fun testTitleAndDescriptionChanges() {
        val event = createEvent("GROUP_UPDATED")

        val titlePayload = JSONObject().apply {
            put("title", "Супер Чат 2026")
        }
        val titleRu = GroupChatCoordinator.formatAdminAction(
            event, titlePayload, "doggy", null, "ru"
        )
        assertEquals("изменил(а) название беседы на «Супер Чат 2026»", titleRu)

        val descPayload = JSONObject().apply {
            put("description", "Новые правила")
        }
        val descRu = GroupChatCoordinator.formatAdminAction(
            event, descPayload, "doggy", null, "ru"
        )
        assertEquals("изменил(а) описание беседы", descRu)
    }

    @Test
    fun testMemberBannedVsKickedVsLeft() {
        val event = createEvent("MEMBER_REMOVED")

        // 1. Ban
        val banPayload = JSONObject().apply {
            put("member_device_id", "dev_puppy")
            put("status", "BANNED")
        }
        val banRu = GroupChatCoordinator.formatAdminAction(
            event, banPayload, "doggy", "puppy", "ru"
        )
        assertEquals("заблокировал(а) пользователя puppy", banRu)

        // 2. Kick
        val kickPayload = JSONObject().apply {
            put("member_device_id", "dev_puppy")
            put("status", "LEFT")
        }
        val kickRu = GroupChatCoordinator.formatAdminAction(
            event, kickPayload, "doggy", "puppy", "ru"
        )
        assertEquals("исключил(а) пользователя puppy", kickRu)

        // 3. Voluntary leave
        val leaveEvent = createEvent("MEMBER_REMOVED").copy(authorDeviceId = "dev_puppy")
        val leavePayload = JSONObject().apply {
            put("member_device_id", "dev_puppy")
            put("status", "LEFT")
        }
        val leaveRu = GroupChatCoordinator.formatAdminAction(
            leaveEvent, leavePayload, "puppy", "puppy", "ru"
        )
        assertEquals("покинул(а) беседу", leaveRu)
    }

    @Test
    fun testMemberRestrictedAndRoleChanged() {
        val restrictEvent = createEvent("MEMBER_RESTRICTED")
        val restrictPayload = JSONObject().apply {
            put("member_device_id", "dev_puppy")
        }
        val restrictRu = GroupChatCoordinator.formatAdminAction(
            restrictEvent, restrictPayload, "doggy", "puppy", "ru"
        )
        assertEquals("ограничил(а) права пользователя puppy", restrictRu)

        val roleEvent = createEvent("ROLE_CHANGED")
        val rolePayload = JSONObject().apply {
            put("member_device_id", "dev_puppy")
            put("role", "ADMIN")
        }
        val roleRu = GroupChatCoordinator.formatAdminAction(
            roleEvent, rolePayload, "doggy", "puppy", "ru"
        )
        assertEquals("изменил(а) роль puppy на Администратор", roleRu)
    }

    @Test
    fun testOwnershipTransferred() {
        val event = createEvent("OWNERSHIP_TRANSFERRED")
        val payload = JSONObject().apply {
            put("member_device_id", "dev_puppy")
        }
        val ru = GroupChatCoordinator.formatAdminAction(
            event, payload, "doggy", "puppy", "ru"
        )
        assertEquals("передал(а) права создателя пользователю puppy", ru)
    }

    @Test
    fun testDeleteMessageFormatting() {
        val deleteEvent = createEvent("DELETE")

        // Own message
        val ownDeleteRu = GroupChatCoordinator.formatAdminAction(
            deleteEvent, JSONObject(), "doggy", null, "ru"
        )
        assertEquals("удалил(а) сообщение", ownDeleteRu)

        // Other user's message
        val otherDeletePayload = JSONObject().apply {
            put("target_author_device_id", "dev_puppy")
        }
        val otherDeleteRu = GroupChatCoordinator.formatAdminAction(
            deleteEvent, otherDeletePayload, "doggy", "puppy", "ru"
        )
        assertEquals("удалил(а) сообщение пользователя puppy", otherDeleteRu)
    }

    @Test
    fun testPinAndUnpin() {
        val pinEvent = createEvent("PIN")
        assertEquals("закрепил(а) сообщение", GroupChatCoordinator.formatAdminAction(pinEvent, null, "doggy", null, "ru"))

        val unpinEvent = createEvent("UNPIN")
        assertEquals("открепил(а) сообщение", GroupChatCoordinator.formatAdminAction(unpinEvent, null, "doggy", null, "ru"))
    }

    @Test
    fun testSevenLanguagesLocalization() {
        val event = createEvent("GROUP_UPDATED")
        val payload = JSONObject().apply {
            put("wallpaper_uri", "/path/wall.jpg")
        }

        val languages = listOf("ru", "en", "de", "es", "fr", "pt", "tr")
        val expected = mapOf(
            "ru" to "изменил(а) обои чата",
            "en" to "changed chat wallpaper",
            "de" to "hat den Chat-Hintergrund geändert",
            "es" to "cambió el fondo del chat",
            "fr" to "a changé le fond d'écran",
            "pt" to "alterou o papel de parede",
            "tr" to "sohbet duvar kağıdını değiştirdi",
        )

        for (lang in languages) {
            val result = GroupChatCoordinator.formatAdminAction(
                event, payload, "doggy", null, lang
            )
            assertEquals("Check for $lang", expected[lang], result)
        }
    }
}
