package com.example.twopchat.security

import android.content.Context
import com.example.twopchat.R
import com.example.twopchat.logging.SafeLog
import java.math.BigInteger
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Provides the official 2048-word BIP-39 English dictionary, real-time prefix
 * suggestions, word validation, and SHA-256 checksum verification.
 */
object Bip39Dictionary {
    private const val TAG = "Bip39Dictionary"
    private val lock = ReentrantLock()

    @Volatile
    private var wordsList: List<String> = emptyList()

    @Volatile
    private var wordsSet: Set<String> = emptySet()

    @Volatile
    private var wordToIndex: Map<String, Int> = emptyMap()

    /**
     * Initializes the dictionary from raw resources or fallback.
     */
    fun ensureLoaded(context: Context? = null) {
        if (wordsList.size == 2048) return

        lock.withLock {
            if (wordsList.size == 2048) return

            var lines: List<String>? = null

            // 1. Try loading from context raw resources
            if (context != null) {
                try {
                    context.resources.openRawResource(R.raw.bip39_english).bufferedReader().use { reader ->
                        lines = reader.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    }
                } catch (e: Throwable) {
                    SafeLog.w(TAG, "Failed to load bip39_english from raw resource: ${e.message}")
                }
            }

            // 2. Try loading from ClassLoader resource (useful in unit tests)
            if (lines == null || lines?.size != 2048) {
                try {
                    val stream = javaClass.classLoader?.getResourceAsStream("raw/bip39_english.txt")
                        ?: javaClass.classLoader?.getResourceAsStream("bip39_english.txt")
                    if (stream != null) {
                        stream.bufferedReader().use { reader ->
                            lines = reader.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // 3. Fallback to file directly in test environment
            if (lines == null || lines?.size != 2048) {
                try {
                    val testFile = java.io.File("src/main/res/raw/bip39_english.txt")
                    if (testFile.exists()) {
                        lines = testFile.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    }
                } catch (_: Throwable) {
                }
            }

            val finalLines = lines
            if (finalLines != null && finalLines.size == 2048) {
                loadFromLines(finalLines)
            } else {
                SafeLog.e(TAG, "BIP-39 wordlist could not be loaded (${finalLines?.size ?: 0} words)")
            }
        }
    }

    /**
     * Loads the word list directly from a list of strings (thread-safe).
     */
    fun loadFromLines(lines: List<String>) {
        lock.withLock {
            wordsList = lines.map { it.trim().lowercase() }
            wordsSet = wordsList.toHashSet()
            val map = HashMap<String, Int>(2048)
            for ((index, word) in wordsList.withIndex()) {
                map[word] = index
            }
            wordToIndex = map
        }
    }

    fun isLoaded(): Boolean = wordsList.size == 2048

    fun isValidWord(word: String): Boolean {
        val trimmed = word.trim().lowercase()
        if (trimmed.isEmpty()) return false
        return wordsSet.contains(trimmed)
    }

    fun getWordIndex(word: String): Int? {
        val trimmed = word.trim().lowercase()
        return wordToIndex[trimmed]
    }

    fun getWordAt(index: Int): String? {
        if (index in wordsList.indices) {
            return wordsList[index]
        }
        return null
    }

    /**
     * Returns autocomplete suggestions matching the given prefix.
     */
    fun suggestWords(prefix: String, limit: Int = 3): List<String> {
        val clean = prefix.trim().lowercase()
        if (clean.length < 2) return emptyList()

        val results = mutableListOf<String>()
        for (w in wordsList) {
            if (w.startsWith(clean)) {
                results.add(w)
                if (results.size >= limit) break
            }
        }
        return results
    }

    /**
     * Splits arbitrary input into words (handling whitespace, newlines, commas).
     */
    fun parseMnemonicWords(input: String): List<String> {
        return input.trim()
            .split(Regex("[\\s,;]+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Verifies the 8-bit SHA-256 checksum of a 24-word BIP-39 mnemonic phrase.
     */
    fun validateChecksum(words: List<String>): Boolean {
        if (words.size != 24) return false

        val indices = IntArray(24)
        for (i in 0 until 24) {
            val idx = getWordIndex(words[i]) ?: return false
            indices[i] = idx
        }

        // 24 words of 11 bits = 264 bits = 33 bytes (32 bytes entropy + 1 byte checksum)
        var accum = BigInteger.ZERO
        for (idx in indices) {
            accum = accum.shiftLeft(11).or(BigInteger.valueOf(idx.toLong()))
        }

        val rawBytes = accum.toByteArray()
        val combined = ByteArray(33)
        val copyLen = minOf(rawBytes.size, 33)
        System.arraycopy(rawBytes, rawBytes.size - copyLen, combined, 33 - copyLen, copyLen)

        val entropy = combined.copyOfRange(0, 32)
        val checksumByte = combined[32]
        val sha = MessageDigest.getInstance("SHA-256").digest(entropy)
        return sha[0] == checksumByte
    }
}
