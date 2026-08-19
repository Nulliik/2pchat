package com.example.twopchat.ui.chat

import com.example.twopchat.BuiltinSticker
import com.example.twopchat.BuiltinStickerPack
import org.junit.Assert.assertEquals
import org.junit.Test

class StickerPickerSearchTest {
    @Test
    fun filtersStickersByEmoji() {
        val pack = BuiltinStickerPack(
            id = "test_pack",
            title = "Test Pack",
            stickers = listOf(
                BuiltinSticker("test_pack", "love", "❤️", 0L),
                BuiltinSticker("test_pack", "laugh", "😂", 0L),
                BuiltinSticker("test_pack", "fire", "🔥", 0L),
            ),
        )

        val query = "🔥"
        val filtered = pack.stickers.filter { sticker ->
            sticker.emoji.contains(query, ignoreCase = true) ||
                sticker.stickerId.lowercase().contains(query)
        }

        assertEquals(1, filtered.size)
        assertEquals("fire", filtered.first().stickerId)
    }

    @Test
    fun filtersStickersByKeyword() {
        val pack = BuiltinStickerPack(
            id = "test_pack",
            title = "Test Pack",
            stickers = listOf(
                BuiltinSticker("test_pack", "cool_cat", "😎", 0L),
                BuiltinSticker("test_pack", "party_dog", "🥳", 0L),
            ),
        )

        val query = "cat"
        val filtered = pack.stickers.filter { sticker ->
            sticker.emoji.contains(query, ignoreCase = true) ||
                sticker.stickerId.lowercase().contains(query)
        }

        assertEquals(1, filtered.size)
        assertEquals("cool_cat", filtered.first().stickerId)
    }
}
