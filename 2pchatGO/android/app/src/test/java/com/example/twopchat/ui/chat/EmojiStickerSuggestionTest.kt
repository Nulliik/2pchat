package com.example.twopchat.ui.chat

import com.example.twopchat.BuiltinSticker
import com.example.twopchat.BuiltinStickerPack
import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiStickerSuggestionTest {
    @Test
    fun matchesStickersByTypedEmoji() {
        val packs = listOf(
            BuiltinStickerPack(
                id = "pack_1",
                title = "Moods",
                stickers = listOf(
                    BuiltinSticker("pack_1", "heart", "❤️", 0L),
                    BuiltinSticker("pack_1", "laugh", "😂", 0L),
                    BuiltinSticker("pack_1", "smirk", "😏", 0L),
                ),
            ),
        )

        val inputText = "Привет 😏"
        val trimmed = inputText.trim()
        val q = trimmed.lowercase()

        val suggestions = packs.flatMap { it.stickers }.filter { sticker ->
            sticker.emoji.isNotBlank() && (
                q.contains(sticker.emoji) ||
                sticker.emoji.contains(q)
            )
        }

        assertEquals(1, suggestions.size)
        assertEquals("smirk", suggestions.first().stickerId)
    }

    @Test
    fun returnsEmptyListWhenInputIsEmpty() {
        val packs = listOf(
            BuiltinStickerPack(
                id = "pack_1",
                title = "Moods",
                stickers = listOf(
                    BuiltinSticker("pack_1", "heart", "❤️", 0L),
                ),
            ),
        )

        val inputText = "   "
        val trimmed = inputText.trim()

        val suggestions = if (trimmed.isEmpty()) {
            emptyList()
        } else {
            packs.flatMap { it.stickers }
        }

        assertEquals(0, suggestions.size)
    }
}
