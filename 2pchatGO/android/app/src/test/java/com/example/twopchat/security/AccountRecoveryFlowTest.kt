package com.example.twopchat.security

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Validates the Account Recovery logic for Task 1B:
 * - Real-time BIP-39 phrase parsing and validation
 * - Checksum computation and corrupted phrase rejection
 * - Rate limiting exponential backoff and SharedPreferences persistence
 */
class AccountRecoveryFlowTest {

    private class MockSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        inner class MockEditor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (value != null) pending[key] = value else pending.remove(key)
                return this
            }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String): SharedPreferences.Editor {
                pending[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
                pending.clear()
            }
        }

        override fun getAll(): Map<String, *> = data
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = MockEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Before
    fun setUp() {
        val file = File("src/main/res/raw/bip39_english.txt")
        if (file.exists()) {
            Bip39Dictionary.loadFromLines(file.readLines())
        }
    }

    @Test
    fun testParseAndValidatePastedPhrase() {
        val rawInput = """
            abandon abandon abandon abandon abandon abandon
            abandon abandon abandon abandon abandon abandon
            abandon abandon abandon abandon abandon abandon
            abandon abandon abandon abandon abandon art
        """.trimIndent()

        val parsed = Bip39Dictionary.parseMnemonicWords(rawInput)
        assertEquals(24, parsed.size)
        assertTrue(parsed.all { Bip39Dictionary.isValidWord(it) })
        assertTrue(Bip39Dictionary.validateChecksum(parsed))
    }

    @Test
    fun testCorruptedChecksumRejection() {
        val rawInput = """
            abandon abandon abandon abandon abandon abandon
            abandon abandon abandon abandon abandon abandon
            abandon abandon abandon abandon abandon abandon
            abandon abandon abandon abandon abandon abandon
        """.trimIndent()

        val parsed = Bip39Dictionary.parseMnemonicWords(rawInput)
        assertEquals(24, parsed.size)
        // 24th word "abandon" does not match SHA-256 checksum of all-zeros entropy (which is "art")
        assertFalse(Bip39Dictionary.validateChecksum(parsed))
    }

    @Test
    fun testRestoreAttemptTrackerPersistenceAcrossRestarts() {
        val mockPrefs = MockSharedPreferences()
        val now = 200_000L

        // App instance 1: 4 failed attempts (1 free lockout exhausted, 1s lockout triggered)
        val tracker1 = RestoreAttemptTracker(mockPrefs)
        tracker1.recordFailure(now)
        tracker1.recordFailure(now)
        tracker1.recordFailure(now)
        assertFalse(tracker1.isLockedOut(now))

        tracker1.recordFailure(now)
        assertTrue(tracker1.isLockedOut(now))
        assertEquals(1000L, tracker1.getRemainingLockoutMs(now))

        // App closes, new app instance launched: state must persist!
        val tracker2 = RestoreAttemptTracker(mockPrefs)
        assertTrue("Lockout must persist across app restart", tracker2.isLockedOut(now))
        assertEquals(1000L, tracker2.getRemainingLockoutMs(now))
        assertEquals(4, tracker2.getFailedAttempts())

        // After 1000ms expires:
        assertFalse(tracker2.isLockedOut(now + 1000L))

        // 5th failed attempt: 2000ms lockout
        tracker2.recordFailure(now + 1000L)
        assertEquals(2000L, tracker2.getRemainingLockoutMs(now + 1000L))

        // Success resets both memory and persisted preferences
        tracker2.recordSuccess()
        assertEquals(0, tracker2.getFailedAttempts())
        assertFalse(tracker2.isLockedOut(now + 1000L))

        val tracker3 = RestoreAttemptTracker(mockPrefs)
        assertEquals(0, tracker3.getFailedAttempts())
        assertFalse(tracker3.isLockedOut(now + 1000L))
    }
}
