package com.example.twopchat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small envelope-encryption primitive backed by a non-exportable Android Keystore key. */
object SecureStorage {
    private const val KEY_ALIAS = "2pchat_local_storage_v1"
    private const val PREFIX = "enc:v1:"

    @Volatile
    private var cachedKey: SecretKey? = null

    private fun createCipher(): Cipher {
        return Cipher.getInstance("AES/GCM/NoPadding")
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                cachedKey = existing
                return existing
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            var key: SecretKey? = null

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    val strongBoxSpec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    ).setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setIsStrongBoxBacked(true)
                        .build()
                    keyGenerator.init(strongBoxSpec)
                    key = keyGenerator.generateKey()
                } catch (_: Exception) {
                    // StrongBox is not present on device hardware; fallback to TEE KeyStore
                    key = null
                }
            }

            if (key == null) {
                val teeSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(teeSpec)
                key = keyGenerator.generateKey()
            }

            cachedKey = key
            key
        }
    }

    internal class StringCipher internal constructor(
        private val secretKey: SecretKey,
        private val cipher: Cipher,
    ) {
        fun encrypt(value: String): String {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            val cipherBytes = cipher.doFinal(valueBytes)
            val packed = cipher.iv + cipherBytes
            val result = PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
            SecurityUtils.zeroize(valueBytes)
            SecurityUtils.zeroize(cipherBytes)
            SecurityUtils.zeroize(packed)
            return result
        }

        fun decrypt(value: String?): String? {
            if (value == null || !value.startsWith(PREFIX)) return value
            return try {
                val packed = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
                if (packed.size <= 12) return value
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(128, packed, 0, 12),
                )
                val plainBytes = cipher.doFinal(packed, 12, packed.size - 12)
                val result = String(plainBytes, Charsets.UTF_8)
                SecurityUtils.zeroize(packed)
                SecurityUtils.zeroize(plainBytes)
                result
            } catch (e: Exception) {
                Log.w("SecureStorage", "Failed to decrypt string: ${e.message}")
                value
            }
        }
    }

    internal fun newStringCipher(): StringCipher = StringCipher(key(), createCipher())

    fun encrypt(value: String): String {
        return newStringCipher().encrypt(value)
    }

    /** Returns legacy plaintext unchanged, enabling non-destructive migration. */
    fun decrypt(value: String?): String? {
        if (value == null || !value.startsWith(PREFIX)) return value
        return newStringCipher().decrypt(value)
    }

    fun isEncrypted(value: String?) = value?.startsWith(PREFIX) == true

    /** Binary envelope used for private media which must not be left as plaintext files. */
    fun encryptBytes(value: ByteArray): ByteArray {
        val cipher = createCipher()
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(BINARY_VERSION) + cipher.iv + cipher.doFinal(value)
    }

    fun decryptBytes(value: ByteArray): ByteArray {
        require(value.size > 13 && value[0] == BINARY_VERSION) { "Invalid encrypted binary value" }
        val cipher = createCipher()
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value, 1, 12))
        return cipher.doFinal(value, 13, value.size - 13)
    }

    @Synchronized
    fun getOrGenerateDbPassphrase(context: android.content.Context): ByteArray {
        val sharedPrefs = P2PPreferences.prefs(context)
        val enc = sharedPrefs.getString("db_passphrase_enc", null)
        if (enc != null) {
            if (enc.startsWith(PREFIX)) {
                val b64Ciphertext = enc.removePrefix(PREFIX)
                val packed = Base64.decode(b64Ciphertext, Base64.NO_WRAP)
                if (packed.size > 12) {
                    val cipher = createCipher()
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, packed, 0, 12)
                    )
                    val plainBytes = cipher.doFinal(packed, 12, packed.size - 12)
                    SecurityUtils.zeroize(packed)
                    return plainBytes
                }
            } else {
                return enc.toByteArray(Charsets.UTF_8)
            }
        }

        val rawRandom = ByteArray(32)
        java.security.SecureRandom().nextBytes(rawRandom)
        val b64Passphrase = Base64.encode(rawRandom, Base64.NO_WRAP)
        SecurityUtils.zeroize(rawRandom)

        val cipher = createCipher()
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val cipherBytes = cipher.doFinal(b64Passphrase)
        val packed = cipher.iv + cipherBytes
        val encString = PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        SecurityUtils.zeroize(cipherBytes)
        SecurityUtils.zeroize(packed)

        sharedPrefs.edit().putString("db_passphrase_enc", encString).commit()
        return b64Passphrase
    }

    /** Helper for fallback attempting decoded binary key if legacy database was created during raw byte window. */
    @Synchronized
    fun getRawDecodedDbPassphraseFallback(context: android.content.Context): ByteArray? {
        val sharedPrefs = P2PPreferences.prefs(context)
        val enc = sharedPrefs.getString("db_passphrase_enc", null) ?: return null
        if (enc.startsWith(PREFIX)) {
            val b64Ciphertext = enc.removePrefix(PREFIX)
            val packed = Base64.decode(b64Ciphertext, Base64.NO_WRAP)
            if (packed.size <= 12) return null
            val cipher = createCipher()
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed, 0, 12))
            val plainBytes = cipher.doFinal(packed, 12, packed.size - 12)
            SecurityUtils.zeroize(packed)
            return try {
                val decoded = Base64.decode(plainBytes, Base64.NO_WRAP)
                SecurityUtils.zeroize(plainBytes)
                decoded
            } catch (_: Exception) {
                SecurityUtils.zeroize(plainBytes)
                null
            }
        }
        return try {
            Base64.decode(enc, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearDbPassphrase() {
        synchronized(this) {
            cachedKey = null
        }
        com.example.twopchat.data.ChatDatabaseHelper.closeAllConnections()
    }

    @Synchronized
    fun deleteKey() {
        synchronized(this) {
            cachedKey = null
        }
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private const val BINARY_VERSION: Byte = 1
}
