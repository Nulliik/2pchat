package com.example.twopchat.security

import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SecurityUtils {
    private const val ITERATIONS = 200000
    private const val KEY_LENGTH = 256 // bits

    /** Zeroize byte array in RAM to prevent memory inspection attacks. */
    fun zeroize(bytes: ByteArray?) {
        bytes?.fill(0.toByte())
    }

    /** Zeroize char array in RAM. */
    fun zeroize(chars: CharArray?) {
        chars?.fill('\u0000')
    }

    /**
     * Compute PBKDF2-HMAC-SHA256 hash of a string with a secure random salt.
     * Output format: pbkdf2_sha256$iterations$saltHex$hashHex
     */
    fun hashPasscode(pin: String): String {
        if (pin.isEmpty()) return ""
        var salt: ByteArray? = null
        var pinChars: CharArray? = null
        var hash: ByteArray? = null
        return try {
            val random = SecureRandom()
            salt = ByteArray(16)
            random.nextBytes(salt)
            val saltHex = salt.joinToString("") { "%02x".format(it) }

            pinChars = pin.toCharArray()
            val spec = PBEKeySpec(pinChars, salt, ITERATIONS, KEY_LENGTH)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            hash = skf.generateSecret(spec).encoded
            val hashHex = hash.joinToString("") { "%02x".format(it) }
            spec.clearPassword()

            "pbkdf2_sha256\$$ITERATIONS\$$saltHex\$$hashHex"
        } catch (e: Exception) {
            throw RuntimeException("Could not hash passcode securely", e)
        } finally {
            zeroize(salt)
            zeroize(pinChars)
            zeroize(hash)
        }
    }

    /** Hash the PIN and wrap the verifier with a non-exportable Android Keystore key. */
    fun protectPasscode(pin: String): String = SecureStorage.encrypt(hashPasscode(pin))

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
        var salt: ByteArray? = null
        var pinChars: CharArray? = null
        var hash: ByteArray? = null
        var expected: ByteArray? = null
        return try {
            val parts = storedValue.split("$")
            if (parts.size == 4) {
                val iterations = parts[1].toIntOrNull() ?: return false
                val saltHex = parts[2]
                val hashHex = parts[3]

                salt = hexToBytes(saltHex)
                pinChars = enteredPin.toCharArray()
                val spec = PBEKeySpec(pinChars, salt, iterations, KEY_LENGTH)
                val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                hash = skf.generateSecret(spec).encoded
                expected = hexToBytes(hashHex)
                spec.clearPassword()
                MessageDigest.isEqual(hash, expected)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        } finally {
            zeroize(salt)
            zeroize(pinChars)
            zeroize(hash)
            zeroize(expected)
        }
    }

    fun verifyPasscode(enteredPin: String, storedValue: String): Boolean {
        if (storedValue.isEmpty()) return false
        val verifier = try {
            SecureStorage.decrypt(storedValue)
        } catch (_: Exception) {
            null
        } ?: return false
        return verifier.startsWith("pbkdf2_sha256$") && verifyPbkdf2(enteredPin, verifier)
    }

    /** Verify PBKDF2 and envelope legacy unencrypted PBKDF2 values after a successful unlock. */
    fun verifyAndMigratePasscode(
        enteredPin: String,
        storedValue: String,
        sharedPrefs: SharedPreferences,
        prefKey: String
    ): Boolean {
        if (!verifyPasscode(enteredPin, storedValue)) return false
        if (!SecureStorage.isEncrypted(storedValue)) {
            try {
                sharedPrefs.edit().putString(prefKey, SecureStorage.encrypt(storedValue)).apply()
            } catch (_: Exception) {
                // Verification remains valid if a best-effort migration write fails.
            }
        }
        return true
    }

    /** Derive a 256-bit Key Encryption Key (KEK) from PIN and salt via PBKDF2-HMAC-SHA256 (200k iterations). */
    fun deriveKek(pinChars: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        var spec: PBEKeySpec? = null
        return try {
            spec = PBEKeySpec(pinChars, salt, iterations, KEY_LENGTH)
            val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            skf.generateSecret(spec).encoded
        } finally {
            spec?.clearPassword()
        }
    }

    /** Encrypt raw payload with KEK using AES-256-GCM. Result format: [12-byte IV][ciphertext + 16-byte tag] */
    fun wrapKeyWithKek(kekBytes: ByteArray, rawKeyPayload: ByteArray): ByteArray? {
        if (kekBytes.size != 32 || rawKeyPayload.isEmpty()) return null
        return try {
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val secretKey = javax.crypto.spec.SecretKeySpec(kekBytes, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
            val cipherText = cipher.doFinal(rawKeyPayload)
            iv + cipherText
        } catch (_: Exception) {
            null
        }
    }

    /** Decrypt payload with KEK using AES-256-GCM. Returns null if tag verification fails or wrong KEK is provided. */
    fun unwrapKeyWithKek(kekBytes: ByteArray, wrappedPayload: ByteArray): ByteArray? {
        if (kekBytes.size != 32 || wrappedPayload.size <= 12 + 16) return null
        return try {
            val secretKey = javax.crypto.spec.SecretKeySpec(kekBytes, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                secretKey,
                javax.crypto.spec.GCMParameterSpec(128, wrappedPayload, 0, 12)
            )
            cipher.doFinal(wrappedPayload, 12, wrappedPayload.size - 12)
        } catch (_: Exception) {
            null
        }
    }
}

