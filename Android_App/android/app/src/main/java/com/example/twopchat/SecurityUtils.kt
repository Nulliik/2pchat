package com.example.twopchat

import android.content.SharedPreferences
import java.security.MessageDigest

object SecurityUtils {
    /**
     * Compute SHA-256 hash of a string.
     */
    fun hashPasscode(pin: String): String {
        if (pin.isEmpty()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            pin
        }
    }

    /**
     * Verify an entered passcode against a stored value (which could be SHA-256 or plaintext).
     * If legacy plaintext is matched, it transparently upgrades it to SHA-256 in SharedPreferences.
     */
    fun verifyAndMigratePasscode(
        enteredPin: String,
        storedValue: String,
        sharedPrefs: SharedPreferences,
        prefKey: String
    ): Boolean {
        if (storedValue.isEmpty()) return false
        val hashedInput = hashPasscode(enteredPin)
        
        // Match SHA-256 hash
        if (hashedInput == storedValue) {
            return true
        }
        
        // Fallback to legacy plaintext match
        if (enteredPin == storedValue) {
            // Upgrade to SHA-256 hash in storage
            sharedPrefs.edit().putString(prefKey, hashedInput).apply()
            return true
        }
        
        return false
    }
}
