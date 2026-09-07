package com.example.twopchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class Bip39DictionaryTest {

    @Before
    fun setUp() {
        val file = File("src/main/res/raw/bip39_english.txt")
        if (file.exists()) {
            Bip39Dictionary.loadFromLines(file.readLines())
        } else {
            Bip39Dictionary.ensureLoaded(null)
        }
    }

    @Test
    fun testDictionarySize() {
        assertTrue("Dictionary must be loaded", Bip39Dictionary.isLoaded())
        assertEquals("Dictionary must contain exactly 2048 words", 2048, (0 until 2048).count { Bip39Dictionary.getWordAt(it) != null })
        assertEquals("abandon", Bip39Dictionary.getWordAt(0))
        assertEquals("zoo", Bip39Dictionary.getWordAt(2047))
    }

    @Test
    fun testWordValidation() {
        assertTrue(Bip39Dictionary.isValidWord("abandon"))
        assertTrue(Bip39Dictionary.isValidWord("ABANDON"))
        assertTrue(Bip39Dictionary.isValidWord("  zoo  "))
        assertTrue(Bip39Dictionary.isValidWord("yellow"))

        assertFalse(Bip39Dictionary.isValidWord("seduce")) // not in official BIP-39
        assertFalse(Bip39Dictionary.isValidWord("invalidword123"))
        assertFalse(Bip39Dictionary.isValidWord(""))
    }

    @Test
    fun testWordSuggestions() {
        val suggestions = Bip39Dictionary.suggestWords("aba", limit = 3)
        assertEquals(listOf("abandon"), suggestions)

        val abSuggestions = Bip39Dictionary.suggestWords("ab", limit = 3)
        assertEquals(listOf("abandon", "ability", "able"), abSuggestions)

        val shortSuggestions = Bip39Dictionary.suggestWords("a", limit = 3)
        assertTrue("Single character should return empty suggestions", shortSuggestions.isEmpty())
    }

    @Test
    fun testParseMnemonicWords() {
        val input = "abandon   ability\nable, about; above"
        val parsed = Bip39Dictionary.parseMnemonicWords(input)
        assertEquals(listOf("abandon", "ability", "able", "about", "above"), parsed)
    }

    @Test
    fun testChecksumValidationKnownVector() {
        // Standard 24-word vector of all zeros entropy (checksum is 'art')
        val allZerosPhrase = listOf(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "art"
        )
        assertTrue("All-zeros mnemonic must have valid checksum", Bip39Dictionary.validateChecksum(allZerosPhrase))

        // Corrupt last word
        val corrupted = allZerosPhrase.toMutableList()
        corrupted[23] = "zoo"
        assertFalse("Corrupted last word must fail checksum", Bip39Dictionary.validateChecksum(corrupted))

        // Incomplete phrase
        assertFalse("Phrase with < 24 words must fail", Bip39Dictionary.validateChecksum(allZerosPhrase.take(23)))
    }
}
