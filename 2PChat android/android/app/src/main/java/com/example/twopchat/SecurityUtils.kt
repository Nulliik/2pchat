package com.example.twopchat

import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SecurityUtils {
    private const val ITERATIONS = 200000
    private const val KEY_LENGTH = 256 // bits

    /**
     * Compute PBKDF2-HMAC-SHA256 hash of a string with a secure random salt.
     * Output format: pbkdf2_sha256$iterations$saltHex$hashHex
     */
    fun hashPasscode(pin: String): String {
        if (pin.isEmpty()) return ""
        return try {
            val random = SecureRandom()
            val salt = ByteArray(16)
            random.nextBytes(salt)
            val saltHex = salt.joinToString("") { "%02x".format(it) }

            val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val hash = skf.generateSecret(spec).encoded
            val hashHex = hash.joinToString("") { "%02x".format(it) }

            "pbkdf2_sha256\$$ITERATIONS\$$saltHex\$$hashHex"
        } catch (e: Exception) {
            throw RuntimeException("Could not hash passcode securely", e)
        }
    }

    /**
     * Compute legacy SHA-256 hash of a string (only for migration verification).
     */
    private fun hashLegacySha256(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Helper to convert a hex string to a ByteArray.
     */
    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    /**
     * Verify a PBKDF2-HMAC-SHA256 hash against entered PIN.
     */
    private fun verifyPbkdf2(enteredPin: String, storedValue: String): Boolean {
        return try {
            val parts = storedValue.split("$")
            if (parts.size == 4) {
                val iterations = parts[1].toIntOrNull() ?: return false
                val saltHex = parts[2]
                val hashHex = parts[3]

                val salt = hexToBytes(saltHex)
                val spec = PBEKeySpec(enteredPin.toCharArray(), salt, iterations, KEY_LENGTH)
                val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val hash = skf.generateSecret(spec).encoded
                val expected = hexToBytes(hashHex)
                MessageDigest.isEqual(hash, expected)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verify an entered passcode against a stored value (which could be PBKDF2, SHA-256, or legacy plaintext).
     * If legacy SHA-256 or legacy plaintext is matched, it transparently upgrades it to PBKDF2 in SharedPreferences.
     */
    fun verifyAndMigratePasscode(
        enteredPin: String,
        storedValue: String,
        sharedPrefs: SharedPreferences,
        prefKey: String
    ): Boolean {
        if (storedValue.isEmpty()) return false

        // 1. Match modern PBKDF2 hash
        if (storedValue.startsWith("pbkdf2_sha256$")) {
            return verifyPbkdf2(enteredPin, storedValue)
        }

        // 2. Fallback to legacy SHA-256 match
        val legacyHashedInput = try {
            hashLegacySha256(enteredPin)
        } catch (e: Exception) {
            ""
        }
        if (legacyHashedInput.isNotEmpty() && MessageDigest.isEqual(
                legacyHashedInput.toByteArray(Charsets.US_ASCII),
                storedValue.toByteArray(Charsets.US_ASCII)
            )) {
            // Upgrade to PBKDF2
            try {
                sharedPrefs.edit().putString(prefKey, hashPasscode(enteredPin)).apply()
            } catch (e: Exception) {
                // Ignore upgrade write errors on verify success
            }
            return true
        }

        // 3. Fallback to legacy plaintext match
        if (MessageDigest.isEqual(
                enteredPin.toByteArray(Charsets.UTF_8),
                storedValue.toByteArray(Charsets.UTF_8)
            )) {
            // Upgrade to PBKDF2
            try {
                sharedPrefs.edit().putString(prefKey, hashPasscode(enteredPin)).apply()
            } catch (e: Exception) {
                // Ignore upgrade write errors on verify success
            }
            return true
        }

        return false
    }
}

