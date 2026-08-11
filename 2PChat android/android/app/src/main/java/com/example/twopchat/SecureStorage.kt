package com.example.twopchat

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
            val key = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    ).setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generateKey()
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
            val packed = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            require(packed.size > 12) { "Invalid encrypted value" }
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(128, packed, 0, 12),
            )
            val plainBytes = cipher.doFinal(packed, 12, packed.size - 12)
            val result = String(plainBytes, Charsets.UTF_8)
            SecurityUtils.zeroize(packed)
            SecurityUtils.zeroize(plainBytes)
            return result
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
            val dec = decrypt(enc)
            if (dec != null) {
                return Base64.decode(dec, Base64.NO_WRAP)
            }
        }
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val b64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val encrypted = encrypt(b64Str)
        sharedPrefs.edit().putString("db_passphrase_enc", encrypted).commit()
        return bytes
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
